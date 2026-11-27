package net.rwhps.server.data.json

import net.rwhps.server.util.file.json.Json
import net.rwhps.server.util.file.json.JsonArray
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class JsonTest {
    private val baseTest = Json(
            """ {
          "String": "String-Test",
          "Int": ${Int.MAX_VALUE},
          "Long": ${Long.MAX_VALUE},
          "Boolean": true
        }
    """.trimIndent()
    )

    private val baseTestX = Json(
            """ {
          "Array": [String-Test, ${Int.MAX_VALUE}, ${Long.MAX_VALUE}, true],
          "ArrayX": [
          {
              "String": "String-Test",
              "Int": ${Int.MAX_VALUE},
              "Long": ${Long.MAX_VALUE},
              "Boolean": true
          },
          {
              "String": "String-Test",
              "Int": ${Int.MAX_VALUE},
              "Long": ${Long.MAX_VALUE},
              "Boolean": true
          },
          ]
        }
    """.trimIndent()
    )

    @Test
    fun getString() {
        assertEquals(baseTest.getString("String"), "String-Test") { "[Json] getString Error" }
        assertEquals(baseTestX.getArrayData("ArrayX").getJson(0).getString("String"), "String-Test") { "[Json] getStringX Error" }
        assertEquals(baseTestX.getArrayData("ArrayX").getJson(1).getString("String"), "String-Test") { "[Json] getStringX Error" }
    }

    @Test
    fun getInt() {
        assertEquals(baseTest.getInt("Int"), Int.MAX_VALUE) { "[Json] getInt Error" }
        assertEquals(baseTestX.getArrayData("ArrayX").getJson(0).getInt("Int"), Int.MAX_VALUE) { "[Json] getIntX Error" }
        assertEquals(baseTestX.getArrayData("ArrayX").getJson(1).getInt("Int"), Int.MAX_VALUE) { "[Json] getIntX Error" }
    }

    @Test
    fun getLong() {
        assertEquals(baseTest.getLong("Long"), Long.MAX_VALUE) { "[Json] getLong Error" }
        assertEquals(baseTestX.getArrayData("ArrayX").getJson(0).getLong("Long"), Long.MAX_VALUE) { "[Json] getLongX Error" }
        assertEquals(baseTestX.getArrayData("ArrayX").getJson(1).getLong("Long"), Long.MAX_VALUE) { "[Json] getLongX Error" }
    }

    @Test
    fun getBoolean() {
        assertEquals(baseTest.getBoolean("Boolean"), true) { "[Json] getBoolean Error" }
        assertEquals(baseTestX.getArrayData("ArrayX").getJson(0).getBoolean("Boolean"), true) { "[Json] getBooleanX Error" }
        assertEquals(baseTestX.getArrayData("ArrayX").getJson(1).getBoolean("Boolean"), true) { "[Json] getBooleanX Error" }
    }

    @Test
    fun getInnerMap() {
        val map = baseTest.getInnerMap()
        assertEquals(map["String"], "String-Test") { "[Json] getInnerMap-String Error" }
        assertEquals(map["Int"], Int.MAX_VALUE) { "[Json] getInnerMap-Int Error" }
        assertEquals(map["Long"], Long.MAX_VALUE) { "[Json] getInnerMap-Long Error" }
        assertEquals(map["Boolean"], true) { "[Json] getInnerMap-Boolean Error" }

        val mapX = baseTestX.getInnerMap()["Array"] as JsonArray
        assertEquals(mapX.getString(0), "String-Test") { "[Json] getInnerMap-String Error" }
        assertEquals(mapX.getInt(1), Int.MAX_VALUE) { "[Json] getInnerMap-Int Error" }
        assertEquals(mapX.getLong(2), Long.MAX_VALUE) { "[Json] getInnerMap-Long Error" }
        assertEquals(mapX.getBoolean(3), true) { "[Json] getInnerMap-Boolean Error" }
    }

    @Test
    fun getArrayData() {
        val mapX = baseTestX.getArrayData("Array")
        assertEquals(mapX.getString(0), "String-Test") { "[Json] getInnerMap-String Error" }
        assertEquals(mapX.getInt(1), Int.MAX_VALUE) { "[Json] getInnerMap-Int Error" }
        assertEquals(mapX.getLong(2), Long.MAX_VALUE) { "[Json] getInnerMap-Long Error" }
        assertEquals(mapX.getBoolean(3), true) { "[Json] getInnerMap-Boolean Error" }
    }

    @Test
    fun getArraySeqData() {
        val mapX = baseTestX.getArraySeqData("ArrayX")
        mapX.eachAll {
            assertEquals(it.getString("String"), "String-Test") { "[Json] getInnerMap-String Error" }
            assertEquals(it.getInt("Int"), Int.MAX_VALUE) { "[Json] getInnerMap-Int Error" }
            assertEquals(it.getLong("Long"), Long.MAX_VALUE) { "[Json] getInnerMap-Long Error" }
            assertEquals(it.getBoolean("Boolean"), true) { "[Json] getInnerMap-Boolean Error" }
        }
    }
}