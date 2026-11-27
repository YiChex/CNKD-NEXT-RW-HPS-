package net.rwhps.server.util.compression

import net.rwhps.server.util.annotations.mark.TestMark
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * @date  2023/6/28 16:47
 * @author Dr (dr@der.kim)
 */
@TestMark.NoTestingIsRequired("在 CompressionEncoderUtils 中已经完成测试")
class CompressionDecoderUtilsTest {

    @Test
    fun zip() {
    }

    @Test
    fun extractEntriesUnderPrefixFiltersSupportFilesSafely() {
        val zipBytes = ByteArrayOutputStream().use { bytes ->
            ZipOutputStream(bytes).use { zip ->
                fun add(name: String, content: String = "test") {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(content.toByteArray())
                    zip.closeEntry()
                }

                add("mod/maps/example.tmx")
                add("mod/maps/example_map.png")
                add("mod/maps/units.tsx")
                add("mod/maps/readme.txt")
                add("mod/maps/../escape.png")
                add("mod/maps/sub/../../escape2.tsx")
            }
            bytes.toByteArray()
        }

        val target = Files.createTempDirectory("rw-hps-map-support").toFile()
        try {
            val extracted = CompressionDecoderUtils.zip(zipBytes).use { decoder ->
                decoder.extractEntriesUnderPrefix(
                    "mod/maps/",
                    target,
                    excludeEntries = setOf("mod/maps/example.tmx"),
                    allowedSuffixes = setOf(".png", ".tsx")
                )
            }

            assertEquals(2, extracted)
            assertTrue(target.resolve("example_map.png").exists())
            assertTrue(target.resolve("units.tsx").exists())
            assertFalse(target.resolve("example.tmx").exists())
            assertFalse(target.resolve("readme.txt").exists())
            assertFalse(target.parentFile.resolve("escape.png").exists())
            assertFalse(target.parentFile.resolve("escape2.tsx").exists())
        } finally {
            target.deleteRecursively()
        }
    }

    @Test
    fun testZip() {
    }

    @Test
    fun zipAllReadStream() {
    }

    @Test
    fun sevenZip() {
    }

    @Test
    fun testSevenZip() {
    }

    @Test
    fun sevenAllReadStream() {
    }
}
