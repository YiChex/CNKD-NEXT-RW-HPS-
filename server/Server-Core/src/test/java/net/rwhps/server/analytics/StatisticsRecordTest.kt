package net.rwhps.server.analytics
import net.rwhps.server.plugin.api.StatisticsRecord
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class StatisticsRecordTest {
    @Test fun `nested snapshot cannot retain native mutable lists`() {
        val list=mutableListOf<Any>(1,2)
        val record=StatisticsRecord(1,"match","FRAME",mapOf("rows" to list))
        list.add(3)
        assertEquals(listOf(1,2),record.data()["rows"])
        assertThrows(UnsupportedOperationException::class.java){(record.data()["rows"] as MutableList<Any>).add(4)}
        assertThrows(IllegalArgumentException::class.java){StatisticsRecord(2,"m","FRAME",mapOf("native" to Any()))}
    }
    @Test fun `exclusive ownership rejects unobserved acknowledgments and disables on close`() {
        val stream=StatisticsBridge.open("test.collector")
        try {
            assertThrows(IllegalStateException::class.java){StatisticsBridge.open("other.collector")}
            assertThrows(IllegalArgumentException::class.java){stream.acknowledge(1)}
            assertTrue(stream.read(5).isEmpty())
            stream.enabled(true);assertEquals(true,stream.status()["enabled"])
        }finally{stream.close()}
        assertThrows(IllegalStateException::class.java){stream.read(1)}
        StatisticsBridge.open("next.collector").close()
    }
}
