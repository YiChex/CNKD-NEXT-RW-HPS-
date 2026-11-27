/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */
package net.rwhps.server.util.algorithms

import net.rwhps.server.data.global.Data
import net.rwhps.server.util.Time.concurrentMillis
import java.lang.reflect.UndeclaredThrowableException
import java.math.BigInteger
import java.security.GeneralSecurityException
import java.util.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * @author [Internet]
 * @Data 2020/5/8 9:26
 */
object Topt {
    /**
     * 依赖 Hmac.java
     */
    /**
     * 时间步长 单位:秒 作为口令变化的时间周期
     * 10S存活
     * 来保证OPT即时性 若User -> Server-Web后时间过期 则可发二次码
     */
    private const val STEP: Long = 10

    /**
     * 转码位数 [1-8]
     * 12345678
     */
    private const val CODE_DIGITS = 8

    /**
     * 初始化时间
     */
    private const val INITIAL_TIME: Long = 0

    /**
     * 时间回溯
     * 5S User -> Ngrok/Frp -> Server-Web
     * or
     * 5S User -> Server-Web
     * 防止在前一个到期时发送旧码 即 发送后五秒内依然可验证
     */
    private const val FLEXIBILIT_TIME: Long = 5

    /**
     * 数子量级
     */
    private val DIGITS_POWER = intArrayOf(1, 10, 100, 1000, 10000, 100000, 1000000, 10000000, 100000000)

    /**
     * 生成一次性密码
     * @param pass 密码
     * @return String
     */
    fun newTotp(pass: String): String {
        val now = concurrentMillis()
        val time = java.lang.Long.toHexString(timeFactor(now)).uppercase(Locale.getDefault())
        return generateTotp(pass + Data.TOPT_KEY, time)
    }

    /**
     * 刚性口令验证
     * @param pass 密码
     * @param totp 待验证的口令
     * @return boolean
     */
    fun verifyTotpRigidity(pass: String, totp: String): Boolean {
        val now = concurrentMillis()
        val time = java.lang.Long.toHexString(timeFactor(now)).uppercase(Locale.getDefault())
        return generateTotp(pass + Data.TOPT_KEY, time) == totp
    }

    /**
     * 柔性口令验证
     * @param pass 密码
     * @param totp 待验证的口令
     * @return boolean
     */
    fun verifyTotpFlexibility(pass: String, totp: String): Boolean {
        val now = concurrentMillis()
        val time = java.lang.Long.toHexString(timeFactor(now)).uppercase(Locale.getDefault())
        val tempTotp = generateTotp(pass + Data.TOPT_KEY, time)
        if (tempTotp == totp) {
            return true
        }
        val time2 = java.lang.Long.toHexString(timeFactor(now - FLEXIBILIT_TIME)).uppercase(Locale.getDefault())
        val tempTotp2 = generateTotp(pass + Data.TOPT_KEY, time2)
        return tempTotp2 == totp
    }

    /**
     * 获取动态因子
     * @param targetTime 指定时间
     * @return long
     */
    private fun timeFactor(targetTime: Long): Long {
        return (targetTime - INITIAL_TIME) / STEP
    }

    /**
     * 哈希加密
     * @param crypto   加密算法
     * @param keyBytes 密钥数组
     * @param text     加密内容
     * @return byte[]
     */
    private fun hmacSha(crypto: String, keyBytes: ByteArray, text: ByteArray): ByteArray {
        return try {
            val hmac: Mac = Mac.getInstance(crypto)
            val macKey = SecretKeySpec(keyBytes, "AES")
            hmac.init(macKey)
            hmac.doFinal(text)
        } catch (gse: GeneralSecurityException) {
            throw UndeclaredThrowableException(gse)
        }
    }

    private fun hexStr2Bytes(hex: String): ByteArray {
        val bArray = BigInteger("10$hex", 16).toByteArray()
        val ret = ByteArray(bArray.size - 1)
        System.arraycopy(bArray, 1, ret, 0, ret.size)
        return ret
    }

    private fun generateTotp256(key: String, time: String): String {
        return generateTotp(key, time, "HmacSHA256")
    }

    private fun generateTotp512(key: String, time: String): String {
        return generateTotp(key, time, "HmacSHA512")
    }

    private fun generateTotp(key: String, timeIn: String, crypto: String = "HmacSHA1"): String {
        var time: String? = timeIn
        val timeBuilder = StringBuilder(time)
        val len = 16
        while (timeBuilder.length < len) {
            timeBuilder.insert(0, "0")
        }
        time = timeBuilder.toString()
        val msg = hexStr2Bytes(time)
        val k = key.toByteArray()
        val hash = hmacSha(crypto, k, msg)
        return truncate(hash)
    }

    /**
     * 截断函数
     * @param target 20字节的字符串
     * @return String
     */
    private fun truncate(target: ByteArray): String {
        val result: StringBuilder
        val offset = target[target.size - 1].toInt() and 0xf
        val binary = (target[offset].toInt() and 0x7f shl 24 or (target[offset + 1].toInt() and 0xff shl 16) or (target[offset + 2].toInt() and 0xff shl 8) or (target[offset + 3].toInt() and 0xff))
        val otp = binary % DIGITS_POWER[CODE_DIGITS]
        result = StringBuilder(otp.toString())
        while (result.length < CODE_DIGITS) {
            result.insert(0, "0")
        }
        return result.toString()
    }
}