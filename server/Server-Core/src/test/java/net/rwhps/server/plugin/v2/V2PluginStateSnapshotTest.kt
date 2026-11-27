package net.rwhps.server.plugin.v2

import net.rwhps.server.data.bean.internal.BeanPluginInfo
import net.rwhps.server.game.GameMaps
import net.rwhps.server.plugin.V2PluginCapabilities
import net.rwhps.server.plugin.api.MapKind
import net.rwhps.server.plugin.api.MapSnapshot
import net.rwhps.server.plugin.api.ModSnapshot
import net.rwhps.server.plugin.api.PlayerSnapshot
import net.rwhps.server.plugin.api.PluginServer
import net.rwhps.server.plugin.api.RoomPhase
import net.rwhps.server.plugin.api.RoomSnapshot
import net.rwhps.server.plugin.api.ServerConfigSnapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.Collections

class V2PluginStateSnapshotTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `read state capability returns provider owned immutable snapshots`() {
        val provider = FakeStateProvider()
        val scope = PluginResourceScope("state.reader")
        val context = context(scope, setOf(V2PluginCapabilities.READ_STATE), provider)

        assertEquals(RoomPhase.LOBBY, context.server().roomSnapshot().phase())
        assertEquals("Crossing Large (10p)", context.server().mapSnapshot().displayName())
        assertEquals("Example Mod", context.server().modSnapshots().single().name())
        assertEquals(1000, context.server().configSnapshot().maxUnits())
        assertThrows(UnsupportedOperationException::class.java) {
            (context.server().modSnapshots() as MutableList<ModSnapshot>).add(ModSnapshot("late", true))
        }
        scope.close()
    }

    @Test
    fun `state snapshots reject plugins without the declared capability`() {
        val scope = PluginResourceScope("state.denied")
        val context = context(scope, emptySet(), FakeStateProvider())

        assertThrows(IllegalStateException::class.java) { context.server().roomSnapshot() }
        assertThrows(IllegalStateException::class.java) { context.server().mapSnapshot() }
        assertThrows(IllegalStateException::class.java) { context.server().modSnapshots() }
        assertThrows(IllegalStateException::class.java) { context.server().configSnapshot() }
        scope.close()
    }

    @Test
    fun `live snapshot classification is deterministic and does not expose source paths`() {
        assertEquals(RoomPhase.LOBBY, LivePluginStateSnapshotProvider.roomPhase(false, false, false))
        assertEquals(RoomPhase.PLAYING, LivePluginStateSnapshotProvider.roomPhase(true, false, false))
        assertEquals(RoomPhase.RETURNING, LivePluginStateSnapshotProvider.roomPhase(true, true, false))
        assertEquals(RoomPhase.RESETTING, LivePluginStateSnapshotProvider.roomPhase(true, true, true))
        assertEquals(MapKind.BUILT_IN, LivePluginStateSnapshotProvider.mapKind(GameMaps.MapType.DefaultMap))
        assertEquals(MapKind.CUSTOM, LivePluginStateSnapshotProvider.mapKind(GameMaps.MapType.CustomMap))
        assertEquals(MapKind.SAVED_GAME, LivePluginStateSnapshotProvider.mapKind(GameMaps.MapType.SavedGames))
        assertTrue(LivePluginStateSnapshotProvider.isModProvidedRuntimeName("cnkd_mod_maps_pack_map.tmx"))
        assertFalse(LivePluginStateSnapshotProvider.isModProvidedRuntimeName("ordinary-map.tmx"))
    }

    @Test
    fun `allowlisted config snapshot has no credential or network target fields`() {
        val fieldNames = ServerConfigSnapshot::class.java.declaredFields.map { it.name.lowercase() }
        val forbiddenParts = listOf("password", "passwd", "token", "license", "secret", "targethost", "targetport", "path")

        assertTrue(fieldNames.none { name -> forbiddenParts.any(name::contains) })
    }

    @Test
    fun `api minor addition keeps legacy plugin server implementations loadable`() {
        val legacyStub = object: PluginServer {
            override fun productName() = "legacy"
            override fun apiVersion() = "1.1.0"
            override fun gameStarted() = false
            override fun players(): List<PlayerSnapshot> = emptyList()
            override fun sendSystemMessage(playerId: String, message: String) = false
            override fun broadcastSystemMessage(message: String) = Unit
        }

        assertThrows(UnsupportedOperationException::class.java) { legacyStub.roomSnapshot() }
        assertThrows(UnsupportedOperationException::class.java) { legacyStub.mapSnapshot() }
        assertThrows(UnsupportedOperationException::class.java) { legacyStub.modSnapshots() }
        assertThrows(UnsupportedOperationException::class.java) { legacyStub.configSnapshot() }
    }

    private fun context(
        scope: PluginResourceScope,
        capabilities: Set<String>,
        provider: PluginStateSnapshotProvider
    ): V2PluginContext {
        return V2PluginContext(
            descriptor(capabilities),
            tempDir,
            scope,
            stateSnapshotProvider = provider
        )
    }

    private fun descriptor(capabilities: Set<String>) = BeanPluginInfo(
        name = "State Test",
        internalName = "state.test",
        author = "test",
        main = "example.StatePlugin",
        description = "fixture",
        version = "1.0.0",
        supportedVersions = ">= 1.2.0",
        apiVersion = 2,
        capabilities = capabilities.toList()
    )

    private class FakeStateProvider: PluginStateSnapshotProvider {
        private val mods = Collections.unmodifiableList(listOf(ModSnapshot("Example Mod", true)))

        override fun roomSnapshot() = RoomSnapshot(
            true,
            RoomPhase.LOBBY,
            false,
            false,
            false,
            2,
            2,
            0,
            0,
            100
        )

        override fun mapSnapshot() = MapSnapshot(
            true,
            "Crossing Large (10p)",
            MapKind.BUILT_IN,
            false,
            false,
            ""
        )

        override fun modSnapshots(): List<ModSnapshot> = mods

        override fun configSnapshot() = ServerConfigSnapshot(
            "CNKD NEXT",
            100,
            1000,
            100,
            false,
            true,
            -1,
            5,
            false,
            false,
            false,
            true,
            4000,
            false,
            "lineOfSight"
        )
    }
}
