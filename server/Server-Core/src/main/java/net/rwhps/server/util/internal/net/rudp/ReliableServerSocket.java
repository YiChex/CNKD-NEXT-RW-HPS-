/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.util.internal.net.rudp;

import net.rwhps.server.struct.map.ObjectMap;
import net.rwhps.server.util.internal.net.rudp.impl.SYNSegment;
import net.rwhps.server.util.internal.net.rudp.impl.Segment;
import net.rwhps.server.util.log.Log;

import java.io.IOException;
import java.net.*;
import java.util.ArrayList;

/**
 * This class implements server sockets that use
 * the Simple Reliable UDP (Reliable UDP) protocol.
 *
 * @author Adrian Granados
 * @author Dr (dr@der.kim)
 * @see java.net.ServerSocket
 */
public class ReliableServerSocket extends ServerSocket {
    public final RUDPHeadProcessor selector = new RUDPHeadProcessor();

    /**
     * Creates an unbound Reliable UDP server socket.
     *
     * @throws IOException if an I/O error occurs when opening
     *                     the underlying UDP socket.
     * @see java.net.ServerSocket#ServerSocket()
     */
    public ReliableServerSocket() throws IOException {
        this(0, 0, null);
    }

    /**
     * Creates a Reliable UDP server socket, bound to the specified port. A port
     * of <code>0</code> creates a socket on any free port.
     * </p>
     * The maximum queue length for incoming connection indications (a
     * request to connect) is set to <code>50</code>. If a connection
     * indication arrives when the queue is full, the connection is refused.
     *
     * @param port the port number, or <code>0</code> to use any free port.
     * @throws IOException if an I/O error occurs when opening
     *                     the underlying UDP socket.
     * @see java.net.ServerSocket#ServerSocket(int)
     */
    public ReliableServerSocket(int port) throws IOException {
        this(port, 0, null);
    }

    /**
     * Creates a Reliable UDP server socket and binds it to the specified local port, with
     * the specified backlog. A port of <code>0</code> creates a socket on any
     * free port.
     *
     * @param port    the port number, or <code>0</code> to use any free port.
     * @param backlog the listen backlog.
     * @throws IOException if an I/O error occurs when opening
     *                     the underlying UDP socket.
     * @see java.net.ServerSocket#ServerSocket(int, int)
     */
    public ReliableServerSocket(int port, int backlog) throws IOException {
        this(port, backlog, null);
    }

    /**
     * Creates a Reliable UDP server socket and binds it to the specified local port and
     * IP address, with the specified backlog. The <i>bindAddr</i> argument
     * can be used on a multi-homed host for a ReliableServerSocket that
     * will only accept connect requests to one of its addresses.
     * If <i>bindAddr</i> is null, it will default accepting
     * connections on any/all local addresses.
     * A port of <code>0</code> creates a socket on any free port.
     *
     * @param port     the port number, or <code>0</code> to use any free port.
     * @param backlog  the listen backlog.
     * @param bindAddr the local InetAddress the server will bind to.
     * @throws IOException if an I/O error occurs when opening
     *                     the underlying UDP socket.
     * @see java.net.ServerSocket#ServerSocket(int, int, InetAddress)
     */
    public ReliableServerSocket(int port, int backlog, InetAddress bindAddr) throws IOException {
        this(new DatagramSocket(new InetSocketAddress(bindAddr, port)), backlog);
    }

    /**
     * Creates a Reliable UDP server socket attached to the specified UDP socket, with
     * the specified backlog.
     *
     * @param sock    the underlying UDP socket.
     * @param backlog the listen backlog.
     * @throws IOException if an I/O error occurs.
     */
    public ReliableServerSocket(DatagramSocket sock, int backlog) throws IOException {
        if (sock == null) {
            throw new NullPointerException("sock");
        }

        serverSocket = sock;
        int backlogSize = (backlog <= 0) ? DEFAULT_BACKLOG_SIZE : backlog;
        _backlog = new ArrayList<>(backlogSize);
        clientSockTable = new ObjectMap<>();
        _stateListener = new StateListener();
        timeout = 0;
        closed = false;

        new ReceiverThread().start();
    }

    @Override
    public Socket accept() throws IOException {
        if (isClosed()) {
            throw new SocketException("Socket is closed");
        }

        synchronized (_backlog) {
            while (_backlog.isEmpty()) {
                try {
                    if (timeout == 0) {
                        _backlog.wait();
                    } else {
                        long startTime = System.currentTimeMillis();
                        _backlog.wait(timeout);
                        if (System.currentTimeMillis() - startTime >= timeout) {
                            throw new SocketTimeoutException();
                        }
                    }

                } catch (InterruptedException xcp) {
                    Log.error("[Reliable UDP]", xcp);
                }

                if (isClosed()) {
                    throw new IOException();
                }
            }

            return _backlog.remove(0);
        }
    }

    @Override
    public synchronized void bind(SocketAddress endpoint) throws IOException {
        bind(endpoint, 0);
    }

    @Override
    public synchronized void bind(SocketAddress endpoint, int backlog) throws IOException {
        if (isClosed()) {
            throw new SocketException("Socket is closed");
        }

        serverSocket.bind(endpoint);
    }

    @Override
    public synchronized void close() {
        if (isClosed()) {
            return;
        }

        closed = true;
        synchronized (_backlog) {
            _backlog.clear();
            _backlog.notify();
        }

        if (clientSockTable.isEmpty()) {
            serverSocket.close();
        }
    }

    @Override
    public InetAddress getInetAddress() {
        return serverSocket.getInetAddress();
    }

    @Override
    public int getLocalPort() {
        return serverSocket.getLocalPort();
    }

    @Override
    public SocketAddress getLocalSocketAddress() {
        return serverSocket.getLocalSocketAddress();
    }

    @Override
    public boolean isBound() {
        return serverSocket.isBound();
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void setSoTimeout(int timeout) {
        if (timeout < 0) {
            throw new IllegalArgumentException("timeout < 0");
        }

        this.timeout = timeout;
    }

    @Override
    public int getSoTimeout() {
        return timeout;
    }

    /**
     * Registers a new client socket with the specified endpoint address.
     *
     * @param endpoint the new socket.
     * @return the registered socket.
     */
    private ReliableClientSocket addClientSocket(SocketAddress endpoint) {
        synchronized (clientSockTable) {
            ReliableClientSocket sock = clientSockTable.get(endpoint);

            if (sock == null) {
                sock = new ReliableClientSocket(selector, serverSocket, endpoint);
                sock.addStateListener(_stateListener);
                clientSockTable.put(endpoint, sock);
            }

            return sock;
        }
    }

    /**
     * Deregisters a client socket with the specified endpoint address.
     *
     * @param endpoint the socket.
     * @return the deregistered socket.
     */
    private ReliableClientSocket removeClientSocket(SocketAddress endpoint) {
        synchronized (clientSockTable) {
            ReliableClientSocket sock = clientSockTable.remove(endpoint);

            if (clientSockTable.isEmpty()) {
                if (isClosed()) {
                    serverSocket.close();
                }
            }

            return sock;
        }
    }

    private final DatagramSocket serverSocket;
    private int timeout;
    private boolean closed;

    /*
     * The listen backlog queue.
     */
    private final ArrayList<ReliableSocket> _backlog;

    /*
     * A table of active opened client sockets.
     */
    private final ObjectMap<SocketAddress, ReliableClientSocket> clientSockTable;

    private final ReliableSocketStateListener _stateListener;

    private static final int DEFAULT_BACKLOG_SIZE = 50;

    private class ReceiverThread extends Thread {
        public ReceiverThread() {
            super("ReliableServerSocket");
            setDaemon(true);
        }

        @Override
        public void run() {
            byte[] buffer = new byte[65535];

            while (true) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                ReliableClientSocket sock = null;

                try {
                    serverSocket.receive(packet);
                    SocketAddress endpoint = packet.getSocketAddress();
                    Segment s = Segment.parse(packet.getData(), 0, packet.getLength());

                    synchronized (clientSockTable) {

                        if (!isClosed()) {
                            if (s instanceof SYNSegment) {
                                if (!clientSockTable.containsKey(endpoint)) {
                                    sock = addClientSocket(endpoint);
                                }
                            }
                        }

                        if (sock == null) {
                            sock = clientSockTable.get(endpoint);
                        }
                    }

                    if (sock != null) {
                        sock.segmentReceived(s);
                    }
                } catch (IOException xcp) {
                    if (isClosed()) {
                        break;
                    }
                    Log.error("[Reliable UDP]", xcp);
                } catch (RuntimeException xcp) {
                    /*
                     * D-05: Segment.parse() throws IllegalArgumentException for malformed
                     * datagrams (unknown flags, length < RUDP_HEADER_LEN). Only IOException
                     * used to be caught here, so a single forged UDP packet killed this
                     * receive thread and the socket silently stopped receiving.
                     * Drop the bad datagram and keep serving.
                     */
                    if (isClosed()) {
                        break;
                    }
                    Log.warn("[Reliable UDP] Discarded malformed datagram: " + xcp);
                }
            }
        }
    }

    private class StateListener implements ReliableSocketStateListener {
        public void connectionOpened(ReliableSocket sock) {
            if (sock instanceof ReliableClientSocket) {
                synchronized (_backlog) {
                    while (_backlog.size() > DEFAULT_BACKLOG_SIZE) {
                        try {
                            _backlog.wait();
                        } catch (InterruptedException xcp) {
                            Log.error("[Reliable UDP]", xcp);
                        }
                    }

                    _backlog.add(sock);
                    _backlog.notify();
                }
            }
        }

        public void connectionRefused(ReliableSocket sock) {
            // do nothing.
        }

        public void connectionClosed(ReliableSocket sock) {
            // Remove client socket from the table of active connections.
            if (sock instanceof ReliableClientSocket) {
                removeClientSocket(sock.getRemoteSocketAddress());
            }
        }

        public void connectionFailure(ReliableSocket sock) {
            // Remove client socket from the table of active connections.
            if (sock instanceof ReliableClientSocket) {
                removeClientSocket(sock.getRemoteSocketAddress());
            }
        }

        public void connectionReset(ReliableSocket sock) {
            // do nothing.
        }
    }
}
