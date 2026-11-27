package net.rwhps.server.struct.list

import net.rwhps.server.util.inline.toPrintLog
import org.junit.jupiter.api.Test

/**
 *
 *
 * @date 2024/1/18 19:27
 * @author Dr (dr@der.kim)
 */
class IntSeqTest {

    @Test
    fun elements() {
        IntSeq().let {
            it.add(1)
            it.add(4)
            it.add(7)
            it.elements().toPrintLog()
        }
    }
}