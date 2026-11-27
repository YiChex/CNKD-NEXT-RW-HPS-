package net.rwhps.server.plugin.internal.headless.support

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 守住"投降输入面"不被后续排位剥离改窄。
 *
 * 背景：命令层只注册了 7 个别名（`surrender` / `ff` / `forfeit` / `giveup` / `resign` / `投降` / `放弃`），
 * 但聊天层 [StartModeSurrenderSupport.isSurrenderInput] 接受的**更多**。排位剥离时把投降从
 * `RankedShardAdapterSupport` 提取到本对象，任何别名丢失都会让玩家"打不出投降"却没有任何编译错误，
 * 因此在此逐条钉死。这些字面量必须与提取前的原实现逐字一致。
 */
class StartModeSurrenderSupportTest {
    @Test
    fun `prefixed aliases keep working`() {
        val prefixed = listOf(
            "/surrender", ".surrender", "-surrender", "_surrender",
            "/ff", ".ff",
            "/forfeit", "/giveup", "/give_up", "/resign",
            "/投降", "/认输", "/放弃"
        )
        prefixed.forEach { raw ->
            assertTrue(StartModeSurrenderSupport.isSurrenderInput(raw)) { "prefixed alias lost: $raw" }
        }
    }

    @Test
    fun `bare aliases keep working`() {
        val bare = listOf("投降", "认输", "放弃", "我要投降", "我要认输", "我要放弃", "ff")
        bare.forEach { raw ->
            assertTrue(StartModeSurrenderSupport.isSurrenderInput(raw)) { "bare alias lost: $raw" }
        }
    }

    @Test
    fun `aliases are case insensitive and tolerate surrounding whitespace`() {
        listOf("  /SURRENDER  ", "FF", " /Ff ", "\t投降\t").forEach { raw ->
            assertTrue(StartModeSurrenderSupport.isSurrenderInput(raw)) { "case/space handling lost: '$raw'" }
        }
    }

    @Test
    fun `prefixed alias with trailing argument still counts`() {
        // 原实现取前缀后、首个空格之前的片段，因此 "/surrender now" 仍应被识别。
        assertTrue(StartModeSurrenderSupport.isSurrenderInput("/surrender now"))
        assertTrue(StartModeSurrenderSupport.isSurrenderInput(".ff please"))
    }

    @Test
    fun `unrelated chat is not treated as surrender`() {
        listOf(
            "", "   ", "hello", "surrender later", "ffs", "投降吧",
            "/surrenderx", "/start", "放弃治疗", "我要赢"
        ).forEach { raw ->
            assertFalse(StartModeSurrenderSupport.isSurrenderInput(raw)) { "false positive: '$raw'" }
        }
    }

    @Test
    fun `original surrender action delegates to the generic policy`() {
        assertTrue(StartModeSurrenderSupport.isOriginalSurrenderAction(isSystemAction = true, systemAction = 100))
        assertFalse(StartModeSurrenderSupport.isOriginalSurrenderAction(isSystemAction = false, systemAction = 100))
        assertFalse(StartModeSurrenderSupport.isOriginalSurrenderAction(isSystemAction = true, systemAction = 99))
    }
}
