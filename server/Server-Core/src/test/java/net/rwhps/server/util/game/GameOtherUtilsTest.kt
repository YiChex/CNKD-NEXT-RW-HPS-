package net.rwhps.server.util.game

import net.rwhps.server.util.inline.toPrintLog
import org.junit.jupiter.api.Test

/**
 * @date 2023/7/21 9:35
 * @author Dr (dr@der.kim)
 */
class GameOtherUtilsTest {

    @Test
    fun mapProcessing() {
        GameOtherUtils
            .mapProcessing("MOD|1B16891712A941BEFBBFD57AD9F5388EAC58B99DB01D020C4E883834E64A2E27//maps/[杂乱（10P）大型太空（无固定资源点）]")
            .toPrintLog()
        GameOtherUtils.mapProcessing("NEW_PATH|maps2/10p幸运环湖3").toPrintLog()
    }

    @Test
    fun getPoint() {
    }
}