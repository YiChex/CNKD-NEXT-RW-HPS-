/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.util.log

import net.rwhps.server.data.global.Data
import net.rwhps.server.struct.map.OrderedMap
import net.rwhps.server.util.file.FileUtils

/**
 * 保存 Log 部分
 * 只保存十次, 在每次启动时保存
 * @author Dr (dr@der.kim)
 */
class SaveLog(
    archiveName: String,
    private val archiveCount: Int
) {
    private val archiveLog = FileUtils.getFolder(Data.ServerLogPath).toFile(archiveName)
    private val dataList: OrderedMap<String, ByteArray>

    init {
        if (archiveLog.exists()) {
            archiveLog.zipDecoder.apply {
                dataList = getZipAllBytes()
                close()
            }
        } else {
            dataList = OrderedMap()
        }
    }

    fun add(fileUtil: FileUtils) {
        add(fileUtil.name, fileUtil.readFileByte())
    }

    fun add(name: String, data: ByteArray) {
        if (dataList.size >= archiveCount) {
            dataList.remove(dataList.keys.first())
        }
        dataList[name] = data
    }
}