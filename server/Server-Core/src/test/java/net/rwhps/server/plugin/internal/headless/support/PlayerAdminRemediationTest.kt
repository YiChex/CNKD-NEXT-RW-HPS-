package net.rwhps.server.plugin.internal.headless.support

import net.rwhps.server.data.bean.RuntimeConfigCandidate
import net.rwhps.server.data.bean.BeanServerConfigFile
import net.rwhps.server.game.headless.core.AbstractGameModule
import net.rwhps.server.game.headless.core.AbstractGameFunction
import net.rwhps.server.game.headless.core.link.AbstractLinkPlayerData
import net.rwhps.server.game.player.PlayerHess
import net.rwhps.server.game.room.ServerRoom
import net.rwhps.server.util.file.load.I18NBundle
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger

class PlayerAdminRemediationTest {
    @Test
    fun temporaryHostRevocationPreservesOnlyIndependentAuthority() {
        val ordinary = StartModeHostAuthorityPolicy.releaseTemporaryHost(true, true, false, false)
        assertFalse(ordinary.isAdmin)
        assertFalse(ordinary.autoAdmin)
        val permanent = StartModeHostAuthorityPolicy.releaseTemporaryHost(true, true, false, true)
        assertTrue(permanent.isAdmin)
        assertFalse(permanent.autoAdmin)
        assertTrue(StartModeHostAuthorityPolicy.releaseTemporaryHost(true, true, true, false).superAdmin)
        assertTrue(StartModeHostAuthorityPolicy.releaseTemporaryHost(true, false, false, false).isAdmin)
    }

    @Test
    fun subnetUnbanResolvesTheSameKeyAsTheOriginalBlacklist() {
        val expected = net.rwhps.server.util.IpUtils.ipToLong24("203.0.113.45", false)
        assertEquals(expected, net.rwhps.server.net.BanIdentitySupport.subnetKey("203.0.113"))
        assertEquals(expected, net.rwhps.server.net.BanIdentitySupport.subnetKey("203.0.113.0/24"))
        assertEquals(expected, net.rwhps.server.net.BanIdentitySupport.subnetKey(expected))
        assertNull(net.rwhps.server.net.BanIdentitySupport.subnetKey("203.0.113.0/16"))
        assertNull(net.rwhps.server.net.BanIdentitySupport.subnetKey("invalid"))
    }

    @Test
    fun malformedOnlineConfigurationCannotFallBackToDefaults() {
        for (raw in listOf("", "null", "[]", "{bad json", "{\"gameplay\":null}")) {
            assertThrows(Exception::class.java) { RuntimeConfigCandidate.main(raw) }
        }
        val candidate = RuntimeConfigCandidate.main("{\"gameplay\":{\"defIncome\":2,\"maxUnit\":777}}")
        assertEquals(2f, candidate.toServerConfig().defIncome)
        assertEquals(777, candidate.toServerConfig().maxUnit)
    }

    @Test
    fun rejectedMoneyDispatchNeverReportsSuccessOrChangesCredits() {
        var credits = 4000
        val writes = AtomicInteger()
        val link = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(AbstractLinkPlayerData::class.java)) { _, method, args ->
            when (method.name) {
                "getIndex" -> 0
                "getTeam" -> 0
                "getCredits" -> credits
                "setCredits" -> { writes.incrementAndGet(); credits = args!![0] as Int; null }
                "getName" -> "test"
                "getConnectHexID" -> "money-rejection-fixture"
                else -> if (method.returnType == Boolean::class.javaPrimitiveType) false else 0
            }
        } as AbstractLinkPlayerData
        lateinit var room: ServerRoom
        val function = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(AbstractGameFunction::class.java)) { _, method, _ ->
            if (method.name == "suspendMainThreadOperations") false else intArrayOf()
        } as AbstractGameFunction
        val module = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(AbstractGameModule::class.java)) { _, method, _ ->
            when (method.name) { "getRoom" -> room; "getGameFunction" -> function; else -> null }
        } as AbstractGameModule
        room = ServerRoom(module)
        room.isStartGame = true
        room.playerManage.playerAll.add(PlayerHess(null, I18NBundle("".byteInputStream()), link))
        val result = StartModeMoneyCommandSupport.addMoney(module, "1", "100")
        assertFalse(result.success)
        assertEquals(4000, credits)
        assertEquals(0, writes.get())
    }
}
