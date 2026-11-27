/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.util.algorithms

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.SecretKeySpec

/**
 * @author Dr (dr@der.kim)
 */
object Aes {
    /**
     * AES加密
     * @param content 待加密的内容
     * @param encryptKey 加密密钥
     * @return 加密后的byte[]
     * @throws Exception Exception
     */
    @JvmStatic
    @Throws(Exception::class)
    fun aesEncryptToBytes(content: ByteArray, encryptKey: String): ByteArray {
        val keyGenerator = KeyGenerator.getInstance("AES")
        val random = SecureRandom.getInstance("SHA1PRNG")
        random.setSeed(encryptKey.toByteArray())
        keyGenerator.init(128, random)
        val cipher = Cipher.getInstance("AES")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyGenerator.generateKey().encoded, "AES"))
        return cipher.doFinal(content)
    }

    /**
     * AES解密
     * @param encryptBytes 待解密的byte[]
     * @param decryptKey 解密密钥
     * @return 解密后的String
     * @throws Exception Exception
     */
    @JvmStatic
    @Throws(Exception::class)
    fun aesDecryptByBytes(encryptBytes: ByteArray, decryptKey: String): ByteArray {
        val keyGenerator = KeyGenerator.getInstance("AES")
        val random = SecureRandom.getInstance("SHA1PRNG")
        random.setSeed(decryptKey.toByteArray())
        keyGenerator.init(128, random)
        val cipher = Cipher.getInstance("AES")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyGenerator.generateKey().encoded, "AES"))
        return cipher.doFinal(encryptBytes)
    }
}