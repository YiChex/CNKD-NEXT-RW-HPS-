package net.rwhps.server.plugin.tool.io;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

public final class AtomicFiles {
    private AtomicFiles() {
    }

    public static void writeUtf8(Path target, String content, boolean replace, boolean backup) throws IOException {
        Path normalized = target.toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        if (parent == null) throw new IOException("output has no parent directory: " + target);
        Files.createDirectories(parent);
        if (Files.isSymbolicLink(normalized)) throw new IOException("output cannot be a symbolic link: " + normalized);
        if (Files.exists(normalized) && !replace) throw new IOException("output already exists; use --force: " + normalized);
        Path temporary = parent.resolve("." + normalized.getFileName() + "." + UUID.randomUUID().toString().replace("-", "") + ".tmp");
        byte[] bytes = (content.endsWith("\n") ? content : content + "\n").getBytes(StandardCharsets.UTF_8);
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                channel.write(ByteBuffer.wrap(bytes));
                channel.force(true);
            }
            if (backup && Files.exists(normalized)) {
                Path backupPath = normalized.resolveSibling(normalized.getFileName() + ".bak");
                if (Files.isSymbolicLink(backupPath)) throw new IOException("backup output cannot be a symbolic link: " + backupPath);
                Files.copy(normalized, backupPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            }
            try {
                Files.move(temporary, normalized, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, normalized, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
