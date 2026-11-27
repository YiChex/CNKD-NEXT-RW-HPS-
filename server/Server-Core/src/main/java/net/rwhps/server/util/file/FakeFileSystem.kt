/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.util.file

import net.rwhps.server.struct.list.Seq
import java.net.URI
import java.nio.file.*
import java.nio.file.attribute.UserPrincipalLookupService
import java.nio.file.spi.FileSystemProvider

/**
 * 创建一个虚假的文件系统
 *
 * @property isOpen 是否打开
 * @property rootDir 文件系统
 * @property files 额外文件
 *
 * @author RW-HPS/ZeroDegress
 */
class FakeFileSystem: FileSystem() {
    private var isOpen = true
    private val rootDir = PluginFileSystemPath(this, "/")
    private val files = Seq<Path>()

    fun getRoot(): Path = rootDir
    override fun close() {
        isOpen = false
    }

    override fun provider(): FileSystemProvider {
        // D-08 follow-up: an in-memory filesystem has no provider. The JDK contract for an
        // unsupported operation is UnsupportedOperationException; the previous TODO()
        // threw NotImplementedError (an Error) and killed the calling thread.
        throw UnsupportedOperationException("FakeFileSystem has no FileSystemProvider")
    }

    override fun isOpen(): Boolean = isOpen

    override fun isReadOnly(): Boolean = true

    override fun getSeparator(): String = "/"

    override fun getRootDirectories(): MutableIterable<Path> = mutableListOf(rootDir)

    override fun getFileStores(): MutableIterable<FileStore> = mutableListOf()

    override fun supportedFileAttributeViews(): MutableSet<String> = mutableSetOf("basic")

    override fun getPath(first: String, vararg more: String?): Path {
        val fragments = Seq<String>()
        val abs = first.startsWith("/")
        if (abs) {
            fragments.add("/")
        }
        first.split("/").forEach { str ->
            val str1 = str.trim().removePrefix("/").removeSuffix("/")
            if (str1.isNotEmpty()) {
                fragments.add(str1)
            }
        }
        more.forEach { str ->
            str?.split("/")?.forEach { str1 ->
                val str2 = str1.trim().removePrefix("/").removeSuffix("/")
                if (str2.isNotEmpty()) {
                    fragments.add(str2)
                }
            }
        }
        return PluginFileSystemPath(this, *fragments.toArray(String::class.java))
    }

    override fun getPathMatcher(syntaxAndPattern: String?): PathMatcher {
        throw UnsupportedOperationException("FakeFileSystem does not support path matchers")
    }

    override fun getUserPrincipalLookupService(): UserPrincipalLookupService {
        throw UnsupportedOperationException("FakeFileSystem has no user principal lookup service")
    }

    override fun newWatchService(): WatchService {
        throw UnsupportedOperationException("FakeFileSystem does not support watch services")
    }

    fun addFile(filePath: Path) {
        files.add(filePath)
    }

    private class PluginFileSystemPath(
        private val fileSystem: FakeFileSystem, vararg val fragments: String
    ): Path {

        override fun compareTo(other: Path): Int {
            return this.toString().compareTo(other.toString())
        }

        override fun register(
            watcher: WatchService, events: Array<out WatchEvent.Kind<*>>, vararg modifiers: WatchEvent.Modifier?
        ): WatchKey {
            throw UnsupportedOperationException("FakeFileSystem does not support watch services")
        }

        override fun getFileSystem(): FileSystem {
            return fileSystem
        }

        override fun isAbsolute(): Boolean = if (this.fragments.isNotEmpty()) {
            this.fragments[0] == "/"
        } else {
            false
        }

        override fun getRoot(): Path = fileSystem.getRoot()

        override fun getFileName(): Path = if (this.fragments.isNotEmpty()) {
            this.fileSystem.getPath(this.fragments.last())
        } else {
            this.fileSystem.getPath("/")
        }

        override fun getParent(): Path = if (this.fragments.isEmpty() || this.fragments.last() == "/") {
            this.fileSystem.getPath("/")
        } else {
            this.fileSystem.getPath(
                    this.fragments.slice(0 until this.fragments.size - 1).joinToString("/")
            )
        }

        override fun getNameCount(): Int = this.fragments.size

        override fun getName(index: Int): Path = this.fileSystem.getPath(this.fragments[index])

        override fun subpath(beginIndex: Int, endIndex: Int): Path = this.fileSystem.getPath(
                this.fragments.slice(beginIndex .. endIndex).joinToString("/")
        )

        override fun startsWith(other: Path): Boolean = this.toString().startsWith(other.toString())

        override fun endsWith(other: Path): Boolean = this.toString().endsWith(other.toString())

        override fun normalize(): Path {
            val nFragments = Seq<String>()
            this.fragments.forEach { str ->
                when (str) {
                    "." -> {}
                    ".." -> {
                        if (nFragments.isEmpty() || (nFragments.isNotEmpty() && nFragments.last() == "/")) {
                            nFragments.add(str)
                        } else {
                            nFragments.pop()
                        }
                    }
                    else -> {
                        nFragments.add(str)
                    }
                }
            }
            return this.fileSystem.getPath("", *nFragments.toArray(String::class.java))
        }

        override fun resolve(other: Path): Path = if (other.isAbsolute) {
            other
        } else {
            if (fileSystem.files.find { o -> o.toString() == this.toString() } != null) {
                this.parent.resolve(other)
            } else {
                this.fileSystem.getPath(this.toString(), other.toString())
            }
        }

        override fun relativize(other: Path): Path = this.fileSystem.getPath(other.toString().removePrefix(this.toString()))

        override fun toUri(): URI = URI("ram:///${this.normalize()}")

        override fun toAbsolutePath(): Path = this.fileSystem.getRoot().resolve(this)

        override fun toRealPath(vararg options: LinkOption?): Path = this.toAbsolutePath()

        override fun toString(): String = if (this.fragments.isNotEmpty() && this.fragments.first() == "/") {
            "/" + this.fragments.slice(1 until fragments.size).joinToString("/")
        } else {
            this.fragments.joinToString("/")
        }
    }
}