package net.rwhps.server.util.plugin

import net.rwhps.server.util.file.FakeFileSystem
import net.rwhps.server.util.log.Log
import org.junit.jupiter.api.Test

/**
 * @date 2023/7/19 11:31
 * @author Dr (dr@der.kim)
 */
class FakeFileSystemTest {
    private val fakeFileSystem = FakeFileSystem()

    init {
        Log.set("DEBUG")
    }

    private fun path(first: String, vararg more: String?) = fakeFileSystem.getPath(first, *more)

    @Test
    fun testToString() {
        assert(fakeFileSystem.getRoot().toString() == "/") {
            "根目录解析为：${fakeFileSystem.getRoot()}"
        }
        assert(fakeFileSystem.getPath("/a/b/c").toString() == "/a/b/c") {
            "目录'/a/b/c'解析为：${fakeFileSystem.getPath("/a/b/c")}"
        }
        assert(fakeFileSystem.getPath("/a/b/c/").toString() == "/a/b/c") {
            "目录'/a/b/c/'解析为：${fakeFileSystem.getPath("/a/b/c/")}"
        }
        assert(fakeFileSystem.getPath("a/b").toString() == "a/b") {
            "目录'a/b'解析为：${fakeFileSystem.getPath("a/b")}"
        }
        assert(fakeFileSystem.getPath("").toString() == "") {
            "目录''解析为：${fakeFileSystem.getPath("")}"
        }
    }

    @Test
    fun testResolve() {
        assert(path("a").resolve(path("b")).toString() == "a/b") {
            "目录'a'下'b 解析为：${path("a").resolve(path("b"))}"
        }
    }
}