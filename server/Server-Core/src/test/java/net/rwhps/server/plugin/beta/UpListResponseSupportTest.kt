/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin.beta

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class UpListResponseSupportTest {
    @Test
    fun acceptsExplicitGameUpdatedResponse() {
        assertTrue(UpListResponseSupport.isUpdateAccepted("CORRODINGGAMES[1.0]\nGAME UPDATED"))
    }

    @Test
    fun rejectsFailedProtocolHeader() {
        assertFalse(UpListResponseSupport.isUpdateAccepted("CORRODINGGAMES[1.0][FAILED]"))
    }

    @Test
    fun rejectsEmptyAndErrorResponses() {
        assertFalse(UpListResponseSupport.isUpdateAccepted(""))
        assertFalse(UpListResponseSupport.isUpdateAccepted("ERROR_TOO_MANY"))
    }

    @Test
    fun explicitFailureOverridesSuccessText() {
        assertFalse(UpListResponseSupport.isUpdateAccepted("CORRODINGGAMES[1.0][FAILED]\nGAME UPDATED"))
        assertFalse(UpListResponseSupport.isUpdateAccepted("DENIED: GAME UPDATED"))
        assertFalse(UpListResponseSupport.isUpdateAccepted("REJECTED\nGAME UPDATED"))
    }

    @Test
    fun acceptsOnlyAnExplicitSuccessfulPortProbe() {
        assertTrue(UpListResponseSupport.isPortOpenResponse("true\n"))
        assertTrue(UpListResponseSupport.isPortOpenResponse("TRUE"))
        assertFalse(UpListResponseSupport.isPortOpenResponse("false"))
        assertFalse(UpListResponseSupport.isPortOpenResponse("error: true"))
    }

    /**
     * 线上真实响应格式：第二行是 `<ip>,<bool>`，判定依据是**最后一个逗号字段**。
     *
     * 原实现要求整行等于 "true"，因此对真实响应永远返回 false ⇒ portOpen 恒 false ⇒
     * 每次都误报「端口未开放」。这些用例锁定真实格式，防止回归。
     *
     * 实测样本（2026-10-21 对 gs1 `action=self_info` 的只读探测，端口确实在监听）：
     *   "CORRODINGGAMES[1.0]\n101.33.68.226,false"
     */
    @Test
    fun readsTheVerdictFromTheLastCommaFieldOfTheRealSelfInfoResponse() {
        // 真实格式：端口开放
        assertTrue(
            UpListResponseSupport.isPortOpenResponse("CORRODINGGAMES[1.0]\n61.184.8.252,true"),
            "the verdict is the last comma field, not the whole line"
        )
        // 真实格式：端口关闭
        assertFalse(
            UpListResponseSupport.isPortOpenResponse("CORRODINGGAMES[1.0]\n101.33.68.226,false")
        )
        // 大小写与空白
        assertTrue(UpListResponseSupport.isPortOpenResponse("CORRODINGGAMES[1.0]\n1.2.3.4, TRUE "))
        // 头部行不含逗号，不能被误判
        assertFalse(UpListResponseSupport.isPortOpenResponse("CORRODINGGAMES[1.0]"))
        // 多行里任意一行命中即可
        assertTrue(
            UpListResponseSupport.isPortOpenResponse("CORRODINGGAMES[1.0]\n1.2.3.4,false\n1.2.3.4,true")
        )
    }

    /** 无逗号的行必须保持历史整行比较，避免把散文/诊断文本当成端口开放。 */
    @Test
    fun doesNotTreatProseContainingTrueAsAnOpenPort() {
        assertFalse(UpListResponseSupport.isPortOpenResponse("error: true"))
        assertFalse(UpListResponseSupport.isPortOpenResponse("isPortOpen=true"))
        assertFalse(UpListResponseSupport.isPortOpenResponse("CORRODINGGAMES[1.0]\nnot,really,trueish"))
    }

    @Test
    fun rejectsAnAddResponseThatEchoesTheIdInsideAnError() {
        assertTrue(UpListResponseSupport.isAddAccepted("GAME ADDED u_test", "u_test"))
        assertFalse(UpListResponseSupport.isAddAccepted("ERROR_DUPLICATE u_test", "u_test"))
        assertTrue(UpListResponseSupport.isAddAccepted("u_test", "u_test"))
    }

    @Test
    fun recognizesProtocolNotFoundWithoutTreatingHtmlAsAnExplicitMiss() {
        assertTrue(UpListResponseSupport.isExplicitNotFound("GAME NOT FOUND"))
        assertTrue(UpListResponseSupport.isExplicitNotFound("ERROR_GAME_NOT_FOUND: u_test"))
        assertTrue(UpListResponseSupport.isExplicitNotFound("HTTP_404: missing"))
        assertFalse(UpListResponseSupport.isExplicitNotFound("<html><title>Not Found</title></html>"))
    }
}
