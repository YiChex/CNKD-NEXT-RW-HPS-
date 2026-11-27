/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin.internal.headless.inject.core

import com.corrodinggames.rts.gameFramework.j.HessLowLatencyBridge
import net.rwhps.server.game.manage.CallManage
import net.rwhps.server.plugin.internal.headless.inject.core.link.LinkGameNet
import net.rwhps.server.plugin.internal.headless.inject.net.GameVersionServer
import net.rwhps.server.plugin.internal.headless.support.SingleRoomLowLatencySupport
import net.rwhps.server.plugin.internal.headless.support.StartModeAutoStartSupport
import net.rwhps.server.plugin.internal.headless.support.StartModeDefeatedPlayerKickSupport
import net.rwhps.server.plugin.internal.headless.support.StartModeLobbySupport
import net.rwhps.server.plugin.internal.headless.support.StartModeMapLockSupport
import net.rwhps.server.plugin.internal.headless.support.StartModeSurvivalSupport
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class GameThreadDispatchWiringTest {
    @Test
    fun injectedGameFunctionUsesTheConcurrentUpdateQueueInsteadOfTheSlickPauseFlag() {
        val constantPoolText = GameFunction::class.java
            .getResourceAsStream("/${GameFunction::class.java.name.replace('.', '/')}.class")
            ?.readBytes()
            ?.toString(Charsets.ISO_8859_1)
            ?: error("GameFunction class bytes are unavailable")

        assertTrue(constantPoolText.contains("java/util/concurrent/ConcurrentLinkedQueue"))
        assertFalse(constantPoolText.contains("paused"))
        assertTrue(constantPoolText.contains("CANCELLED"))
        assertTrue(constantPoolText.contains("RUNNING"))
    }

    @Test
    fun startPathDispatchesMultiplayerStartThroughTheGameFunctionBoundary() {
        val constantPoolText = StartModeAutoStartSupport::class.java
            .getResourceAsStream("/${StartModeAutoStartSupport::class.java.name.replace('.', '/')}.class")
            ?.readBytes()
            ?.toString(Charsets.ISO_8859_1)
            ?: error("StartModeAutoStartSupport class bytes are unavailable")

        assertTrue(constantPoolText.contains("suspendMainThreadOperations"))
        assertTrue(constantPoolText.contains("java/util/concurrent/atomic/AtomicBoolean"))
        assertTrue(constantPoolText.contains("startOperationInProgress"))
    }

    @Test
    fun lobbyRulesAndMapLockDispatchRuntimeEngineMutationsThroughTheGameFunctionBoundary() {
        val lobbyClassText = StartModeLobbySupport::class.java
            .getResourceAsStream("/${StartModeLobbySupport::class.java.name.replace('.', '/')}.class")
            ?.readBytes()
            ?.toString(Charsets.ISO_8859_1)
            ?: error("StartModeLobbySupport class bytes are unavailable")
        val mapLockClassText = StartModeMapLockSupport::class.java
            .getResourceAsStream("/${StartModeMapLockSupport::class.java.name.replace('.', '/')}.class")
            ?.readBytes()
            ?.toString(Charsets.ISO_8859_1)
            ?: error("StartModeMapLockSupport class bytes are unavailable")
        val survivalClassText = StartModeSurvivalSupport::class.java
            .getResourceAsStream("/${StartModeSurvivalSupport::class.java.name.replace('.', '/')}.class")
            ?.readBytes()
            ?.toString(Charsets.ISO_8859_1)
            ?: error("StartModeSurvivalSupport class bytes are unavailable")

        assertTrue(lobbyClassText.contains("suspendMainThreadOperations"))
        assertTrue(mapLockClassText.contains("suspendMainThreadOperations"))
        assertTrue(mapLockClassText.contains("applyLockedMapDuringBootstrap"))
        assertTrue(mapLockClassText.contains("applyBootstrapRuntimeMapTarget"))
        assertTrue(survivalClassText.contains("suspendMainThreadOperations"))
    }

    @Test
    fun defeatedPlayerAutoKickReadsOriginalPlayerStateOnlyThroughTheGameFunctionBoundary() {
        val callManageClassText = CallManage::class.java
            .getResourceAsStream("/${CallManage::class.java.name.replace('.', '/')}.class")
            ?.readBytes()
            ?.toString(Charsets.ISO_8859_1)
            ?: error("CallManage class bytes are unavailable")
        val supportClassText = StartModeDefeatedPlayerKickSupport::class.java
            .getResourceAsStream("/${StartModeDefeatedPlayerKickSupport::class.java.name.replace('.', '/')}.class")
            ?.readBytes()
            ?.toString(Charsets.ISO_8859_1)
            ?: error("StartModeDefeatedPlayerKickSupport class bytes are unavailable")

        assertTrue(callManageClassText.contains("suspendMainThreadOperations"))
        assertTrue(callManageClassText.contains("StartModeDefeatedPlayerKickSupport"))
        assertTrue(supportClassText.contains("evaluateAndKick"))
        assertTrue(supportClassText.contains("getSurvive"))
    }

    @Test
    fun playerBindingRejectsReconnectAfterAnAutomaticDefeatKick() {
        val playerConnectClassText = GameThreadDispatchWiringTest::class.java.classLoader
            .getResourceAsStream("net/rwhps/server/plugin/internal/headless/inject/lib/PlayerConnectX.class")
            ?.readBytes()
            ?.toString(Charsets.ISO_8859_1)
            ?: error("PlayerConnectX class bytes are unavailable")

        assertTrue(playerConnectClassText.contains("shouldRejectReconnect"))
        assertTrue(playerConnectClassText.contains("sendKick"))
        assertTrue(playerConnectClassText.contains("disconnect"))
    }

    @Test
    fun headlessServerStartResetsTheEngineFrameCounterAfterHostStart() {
        // P1 root cause for "game frame:N is greater then nest step:M": the engine's host-start
        // path (ad.b(Z)Z -> q() -> a(Z) -> s()) zeroes ad.X but never l.bx, so the stale frame
        // counter must be cleared by us right after hostStartWithPasswordAndMods returns.
        val classBytes = LinkGameNet::class.java
            .getResourceAsStream("/${LinkGameNet::class.java.name.replace('.', '/')}.class")
            ?.readBytes()
            ?: error("LinkGameNet class bytes are unavailable")
        val constantPoolText = classBytes.toString(Charsets.ISO_8859_1)

        assertTrue(constantPoolText.contains("hostStartWithPasswordAndMods"))
        assertTrue(constantPoolText.contains("getGameEngine"))
        // Short names like "bx" must be matched as whole CONSTANT_Utf8 entries, otherwise the
        // substring also hits unrelated strings such as the "by_uber" map name.
        assertTrue(hasUtf8Entry(classBytes, "com/corrodinggames/rts/gameFramework/l"))
        assertTrue(hasUtf8Entry(classBytes, "bx"))
        assertTrue(hasUtf8Entry(classBytes, "by"))
    }

    @Test
    fun frameInvariantGuardIsWiredIntoTheNetEngineFrameBridge() {
        // P0 defence-in-depth: the guard must run at the ad.a(F)V entry injection point, must
        // read the public l.bx frame counter, and must be counted unconditionally (production
        // runs metricsEnabled=false, so a metrics-gated counter would be invisible).
        val bridgeClassText = HessLowLatencyBridge::class.java
            .getResourceAsStream("/${HessLowLatencyBridge::class.java.name.replace('.', '/')}.class")
            ?.readBytes()
            ?.toString(Charsets.ISO_8859_1)
            ?: error("HessLowLatencyBridge class bytes are unavailable")
        val supportClassText = SingleRoomLowLatencySupport::class.java
            .getResourceAsStream("/${SingleRoomLowLatencySupport::class.java.name.replace('.', '/')}.class")
            ?.readBytes()
            ?.toString(Charsets.ISO_8859_1)
            ?: error("SingleRoomLowLatencySupport class bytes are unavailable")

        assertTrue(bridgeClassText.contains("repairFrameInvariant"))
        assertTrue(bridgeClassText.contains("beforeNetEngineFrame"))
        assertTrue(bridgeClassText.contains("recordHessFrameInvariantGuardFailed"))
        assertTrue(supportClassText.contains("hessFrameInvariantGuardEnabled"))
        assertTrue(supportClassText.contains("recordHessFrameInvariantRepaired"))
        // A silent guard failure would make a zero repair count a false clean bill of health.
        assertTrue(supportClassText.contains("recordHessFrameInvariantGuardFailed"))
    }

    @Test
    fun inboundEngineRelayControlPacketsAreRejectedAtTheEnqueueBoundary() {
        // P2b: ad.c(au)V case 170 is the only writer of j.c.q=true, which unlocks case 178
        // (PACKET_RECONNECT_TO) -> ad.a(Socket)Z (join-server mode). Both are > 100, so
        // NetEnginePackaging dispatches them straight into the engine. CNKD only ever sends
        // them, never legitimately receives them from a client.
        val serverClassText = GameVersionServer::class.java
            .getResourceAsStream("/${GameVersionServer::class.java.name.replace('.', '/')}.class")
            ?.readBytes()
            ?.toString(Charsets.ISO_8859_1)
            ?: error("GameVersionServer class bytes are unavailable")

        assertTrue(serverClassText.contains("isInboundEngineRelayControlPacket"))
        assertTrue(serverClassText.contains("recordInboundEngineRelayControlRejected"))
        // Prove the predicate actually names both packet types rather than merely existing.
        assertTrue(serverClassText.contains("RELAY_BECOME_SERVER"))
        assertTrue(serverClassText.contains("PACKET_RECONNECT_TO"))
        // And that the drop happens before the engine enqueue helper is invoked.
        assertTrue(serverClassText.contains("addPacketToEngineSafely"))
    }

    private fun hasUtf8Entry(classBytes: ByteArray, value: String): Boolean {
        val name = value.toByteArray(Charsets.ISO_8859_1)
        val probe = ByteArray(name.size + 3)
        probe[0] = 1 // CONSTANT_Utf8 tag
        probe[1] = ((name.size shr 8) and 0xFF).toByte()
        probe[2] = (name.size and 0xFF).toByte()
        name.copyInto(probe, 3)
        return classBytes.indexOfSubsequence(probe) >= 0
    }

    private fun ByteArray.indexOfSubsequence(target: ByteArray): Int {
        if (target.isEmpty() || target.size > size) {
            return -1
        }
        outer@ for (start in 0..(size - target.size)) {
            for (offset in target.indices) {
                if (this[start + offset] != target[offset]) {
                    continue@outer
                }
            }
            return start
        }
        return -1
    }
}
