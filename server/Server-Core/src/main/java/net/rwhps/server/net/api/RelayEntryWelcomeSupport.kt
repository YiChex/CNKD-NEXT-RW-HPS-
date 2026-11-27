package net.rwhps.server.net.api

import net.rwhps.server.data.global.Data
import net.rwhps.server.util.file.FileUtils

internal object RelayEntryWelcomeSupport {
    private const val fileName = "RelayEntryWelcome.txt"

    private val defaultContent = """
        // 每行对应欢迎页的一行文案，支持占位符：
        // {displayName} {shortName} {region} {operatorName} {qqGroup} {listOpenText}
        // {joinBaseUrl} {serverVersion} {shareRoomPrefix} {shareCodeExample}
        // 修改后约 5 秒内自动热重载，无需重启
        // 如需停用某一行，请在行首加 //
        欢迎加入 {displayName}
        {listOpenText}
        优先分享 {shareRoomPrefix} 系列房号给好友
        房号无法加入时，可使用备用入口：{joinBaseUrl}/{shareCodeExample}
        联机QQ群：{qqGroup}
        版本：{serverVersion}
    """.trimIndent()

    @Volatile
    private var cachedLines: List<String> = emptyList()

    @Volatile
    private var cachedLastModified: Long = Long.MIN_VALUE

    private fun fileUtils(): FileUtils {
        return FileUtils.getFolder(Data.ServerDataPath).toFile(fileName)
    }

    private fun ensureFile() {
        val file = fileUtils()
        if (!file.exists() || file.length() == 0L) {
            file.writeFile(defaultContent)
        }
    }

    private fun loadLines(): List<String> {
        return fileUtils()
            .readFileStringData()
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .split('\n')
            .map { it.trimEnd() }
            .filter { line ->
                val trimmed = line.trim()
                trimmed.isNotEmpty() && !trimmed.startsWith("//")
            }
    }

    fun ensureInitialized() {
        ensureFile()
        reloadIfNeeded(force = true)
    }

    fun reloadIfNeeded(force: Boolean = false) {
        ensureFile()
        val file = fileUtils().file
        val lastModified = if (file.exists()) file.lastModified() else Long.MIN_VALUE
        if (!force && lastModified == cachedLastModified) {
            return
        }
        cachedLines = loadLines()
        cachedLastModified = lastModified
    }

    fun lines(): List<String> {
        if (cachedLastModified == Long.MIN_VALUE) {
            ensureInitialized()
        }
        return cachedLines
    }
}
