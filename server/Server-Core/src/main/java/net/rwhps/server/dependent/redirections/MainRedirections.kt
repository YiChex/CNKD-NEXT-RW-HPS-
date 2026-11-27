package net.rwhps.server.dependent.redirections

import net.rwhps.asm.api.listener.RedirectionListener
import net.rwhps.asm.api.replace.RedirectionReplace
import net.rwhps.asm.api.wrap.RedirectionWrapper
import net.rwhps.asm.data.MethodTypeInfoValue
import net.rwhps.asm.data.manager.ListenerRedirectionsDataManager
import net.rwhps.asm.data.manager.RemoveRedirectionsDataManager
import net.rwhps.asm.data.manager.ReplaceRedirectionsDataManager
import net.rwhps.asm.data.manager.WrapRedirectionsDataManager
import net.rwhps.asm.func.Find
import net.rwhps.asm.redirections.replace.def.BasicDataRedirections

/**
 * 用来实现各个 ASM Redirections 服务
 * 进行统一调用
 *
 * @author Dr (dr@der.kim)
 */
interface MainRedirections {
    // 强制实现
    fun register()

    fun addAllReplace(classPath: String) {
        ReplaceRedirectionsDataManager.addAllMethodReplace(classPath)
    }

    fun addAllReplace(classPath: String, removeSync: Boolean) {
        ReplaceRedirectionsDataManager.addAllMethodReplace(classPath, removeSync)
    }

    fun addAllReplace(classFind: Find<String, Boolean>) {
        ReplaceRedirectionsDataManager.addAllMethodReplace(classFind)
    }

    fun redirectR(methodTypeInfoValue: MethodTypeInfoValue) {
        ReplaceRedirectionsDataManager.addPartialMethodReplace(methodTypeInfoValue, BasicDataRedirections.NULL)
    }

    fun redirectR(methodTypeInfoValue: MethodTypeInfoValue, redirection: RedirectionReplace) {
        ReplaceRedirectionsDataManager.addPartialMethodReplace(methodTypeInfoValue, redirection)
    }

    fun redirectL(methodTypeInfoValue: MethodTypeInfoValue) {
        ListenerRedirectionsDataManager.addPartialMethodListener(methodTypeInfoValue, null)
    }

    fun redirectL(methodTypeInfoValue: MethodTypeInfoValue, redirection: RedirectionListener?) {
        ListenerRedirectionsDataManager.addPartialMethodListener(methodTypeInfoValue, redirection)
    }

    /**
     * FIX-LOOP-GUARD: 把 [methodTypeInfoValue] 指向的方法**整个方法体**包进
     * `try { <原指令> } catch (Throwable t) { <wrapper>; <默认返回> }`。
     *
     * 与 [redirectL] **可同时注册**（两者语义不同：redirectL 在首/尾插入调用，
     * redirectWrap 包住整个方法体，因此能捕获方法体自身抛出的异常）。
     */
    fun redirectWrap(methodTypeInfoValue: MethodTypeInfoValue, wrapper: Class<out RedirectionWrapper>) {
        WrapRedirectionsDataManager.addPartialMethodWrap(methodTypeInfoValue, wrapper)
    }

    fun redirectRemove(methodTypeInfoValue: MethodTypeInfoValue) {
        RemoveRedirectionsDataManager.addPartialMethodRemove(methodTypeInfoValue)
    }
}
// 我修改了你的梦