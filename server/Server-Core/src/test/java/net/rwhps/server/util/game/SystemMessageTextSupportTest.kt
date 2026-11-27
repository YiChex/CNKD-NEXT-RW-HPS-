package net.rwhps.server.util.game

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class SystemMessageTextSupportTest {
    @Test
    fun preservesPunctuationInDefaultSystemMessages() {
        assertEquals("团队投降进度：已申请 1/2 人，还差 1 人", SystemMessageTextSupport.normalize("团队投降进度：已申请 1/2 人，还差 1 人"))
        assertEquals("服务器已启动！", SystemMessageTextSupport.normalize("服务器已启动！"))
    }

    @Test
    fun helpKeepsCommandPunctuationAndLineBreaks() {
        val help = ".help\n   /map <MapNumber...> - Change map.\n   /kick <position>"
        assertEquals(help, SystemMessageTextSupport.normalize(help, SystemMessageTextMode.HELP))
    }

    @Test
    fun winnerResultKeepsSquareBrackets() {
        assertEquals("[Alice, Bob]", SystemMessageTextSupport.normalize("[Alice, Bob]", SystemMessageTextMode.WINNER_RESULT))
    }

    @Test
    fun configuredNoticeKeepsPlayerFacingPunctuation() {
        assertEquals(
            "服务器已就绪！ @CNKD",
            SystemMessageTextSupport.normalize("服务器已就绪！ @CNKD", SystemMessageTextMode.CONFIG_NOTICE)
        )
    }
}
