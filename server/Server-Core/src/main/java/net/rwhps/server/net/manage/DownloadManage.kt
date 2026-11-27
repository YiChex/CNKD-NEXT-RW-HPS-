/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.net.manage

import net.rwhps.server.data.global.Data
import net.rwhps.server.util.algorithms.digest.DigestAlgorithm
import net.rwhps.server.util.algorithms.digest.Digester
import net.rwhps.server.util.file.FileUtils
import net.rwhps.server.util.io.IoRead
import net.rwhps.server.util.log.Log
import net.rwhps.server.util.log.ex.ProgressBar
import net.rwhps.server.util.log.exp.NetException
import okhttp3.*
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection

/**
 *
 *
 * @date 2024/2/14 10:21
 * @author Dr (dr@der.kim)
 */
object DownloadManage {
    private val downLoad = OkHttpClient()

    @JvmStatic
    fun addDownloadTask(downloadData: DownloadData): Boolean {
        return downUrl(downloadData)
    }

    private fun downUrl(downloadData: DownloadData): Boolean {
        var flagStatus = true

        val request: Request = Request.Builder()
            .url(downloadData.fileURL)
            .addHeader("User-Agent", Data.userAgent)
            .build()

        try {
            downLoad.newCall(request).enqueue(object: Callback {
                override fun onFailure(call: Call, e: IOException) {
                    throw e
                }

                @Throws(IOException::class)
                override fun onResponse(call: Call, response: Response) {
                    try {
                        //判断连接是否成功
                        if (response.code == HttpURLConnection.HTTP_OK) {
                            val headers = response.headers

                            if (!response.isSuccessful) {
                                throw FileNotFoundException()
                            }

                            if (response.body == null) {
                                throw NetException.DownException("[Down File] Response.body NPE")
                            }

                            var progress: ProgressBar? = null
                            //获取文件大小
                            if (headers["Content-Length"] != null && downloadData.progressFlag) {
                                progress = ProgressBar(0, headers["Content-Length"]!!.toInt())
                                Log.clog("Start Down File : ${downloadData.fileName}")
                            }
                            downloadData.fileOutputStream.use {
                                IoRead.copyInputStream(response.body!!.byteStream(), it) { len ->
                                    progress?.use { progress ->
                                        progress.progress(len)
                                    }
                                }
                            }

                        }
                    } finally {
                        flagStatus = false
                    }
                }
            })

            while (flagStatus) {
                Thread.sleep(100)
            }

            if (downloadData.digest.isNotBlank() && downloadData.fileUtils != null) {
                val digest = Digester(downloadData.digestType).digestHex(downloadData.fileUtils.file)
                if (!digest.equals(downloadData.digest, true)) {
                    throw NetException.DownException("[Down File] File Digester Error: need [${downloadData.digest}]:[$digest]")
                }
            }

            return true
        } catch (e: Exception) {
            Log.error(e)
        }
        return false
    }

    class DownloadData {
        val fileURL: String
        val fileName: String
        val fileUtils: FileUtils?
        val fileOutputStream: OutputStream

        val digestType: DigestAlgorithm
        val digest: String

        val progressFlag: Boolean

        constructor(
            fileURL: String, fileUtils: FileUtils,
            digestType: DigestAlgorithm = DigestAlgorithm.MD5, digest: String = "",
            progressFlag: Boolean = false
        ) : this(
                fileURL, fileUtils.name, fileUtils, fileUtils.writeByteOutputStream(),
                digestType, digest,
                progressFlag
        )

        constructor(
            fileURL: String, fileName: String, fileOutputStream: OutputStream,
            digestType: DigestAlgorithm = DigestAlgorithm.MD5, digest: String = "",
            progressFlag: Boolean = false
        ) : this(
                fileURL, fileName, null, fileOutputStream,
                digestType, digest,
                progressFlag
        )

        private constructor(
            fileURL: String, fileName: String, fileUtils: FileUtils?, fileOutputStream: OutputStream,
            digestType: DigestAlgorithm = DigestAlgorithm.MD5, digest: String = "",
            progressFlag: Boolean = false
        ) {
            this.fileURL = fileURL
            this.fileName = fileName
            this.fileUtils = fileUtils
            this.fileOutputStream = fileOutputStream

            this.digestType = digestType
            this.digest = digest

            this.progressFlag = progressFlag

            init()
        }

        fun init() {
            require(fileURL.isNotBlank()) {
                "[DownLoad] URL NULL"
            }
            require(fileName.isNotBlank()) {
                "[DownLoad] Name NULL"
            }
        }
    }
}