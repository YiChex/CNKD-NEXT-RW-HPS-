package net.rwhps.server.net.api

import net.rwhps.server.data.global.Data
import net.rwhps.server.util.file.FileUtils

internal object RelayFriendlyShareSupport {
    private const val fileName = "RelayFriendlyShare.txt"

    private val defaultContent = """
        // 每行对应一条友情分享文案，后续欢迎提示会按行显示
        // 修改后无需重启，新的欢迎提示与房间提示会自动读取最新内容
        // 服务端会原样下发文本，不会改写你的颜色、符号或格式标签
        // 如需临时停用某行，请在行首加 //
        // 示例：欢迎加入当前服务器交流群
        // 示例：优先分享 RK 系列房号，无法直连时可使用完整域名房号
        // 示例：如需彩色友链，请将本行替换为你客户端已验证可用的颜色标签格式
    """.trimIndent()

    private fun fileUtils(): FileUtils {
        return FileUtils.getFolder(Data.ServerDataPath).toFile(fileName)
    }

    private fun ensureFile() {
        val file = fileUtils()
        if (!file.exists() || file.length() == 0L) {
            file.writeFile(defaultContent)
        }
    }

    fun ensureInitialized() {
        ensureFile()
    }

    fun lines(): List<String> {
        ensureFile()
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
}
