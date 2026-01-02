package moe.ono.hooks.base.api

import android.os.Bundle
import android.view.View
import com.tencent.qqnt.kernel.nativeinterface.MsgRecord
import moe.ono.config.ONOConf
import moe.ono.hooks._base.ApiHookItem
import moe.ono.hooks._base.BaseSwitchFunctionHookItem
import moe.ono.hooks._core.annotation.HookItem
import moe.ono.hooks._core.factory.ExceptionFactory
import moe.ono.reflex.ClassUtils
import moe.ono.reflex.FieldUtils
import moe.ono.reflex.MethodUtils
import moe.ono.util.Logger
import moe.ono.util.QAppUtils
import java.lang.reflect.Method

@HookItem(path = "API/监听QQMsgView更新")
class QQMessageViewListener : ApiHookItem() {

    companion object {
        private val ON_AIO_CHAT_VIEW_UPDATE_LISTENER_MAP:
            HashMap<BaseSwitchFunctionHookItem, OnChatViewUpdateListener> = HashMap()

        /**
         * 添加消息监听器 责任链模式
         */
        @JvmStatic
        fun addMessageViewUpdateListener(
            hookItem: BaseSwitchFunctionHookItem,
            onMsgViewUpdateListener: OnChatViewUpdateListener
        ) {
            ON_AIO_CHAT_VIEW_UPDATE_LISTENER_MAP[hookItem] = onMsgViewUpdateListener
        }

        /**
         * 兼容查找：AIOBubbleMsgItemVB 中“更新气泡View”的方法
         * 特征：
         * - 返回 void
         * - 参数数量 = 4
         * - 包含：int / Bundle / List（Ignore 在不同版本会变，不能硬绑）
         */
        private fun findCompatOnMsgViewUpdate(loader: ClassLoader): Method? {
            val clz = runCatching {
                ClassUtils.findClass("com.tencent.mobileqq.aio.msglist.holder.AIOBubbleMsgItemVB", loader)
            }.getOrNull() ?: run {
                Logger.e("[QQMessageViewListener] class not found: AIOBubbleMsgItemVB")
                return null
            }

            // 先走你们 MethodUtils 的“宽松扫描”方式：只限定返回 void + 4参
            val candidates = clz.declaredMethods
                .asSequence()
                .filter { it.returnType == Void.TYPE }
                .filter { it.parameterTypes.size == 4 }
                .toList()

            fun hasInt(ps: Array<Class<*>>) =
                ps.any { it == Int::class.javaPrimitiveType || it == Int::class.java }

            fun hasBundle(ps: Array<Class<*>>) =
                ps.any { it == Bundle::class.java }

            fun hasList(ps: Array<Class<*>>) =
                ps.any { java.util.List::class.java.isAssignableFrom(it) }

            // 最优：同时具备 int + Bundle + List
            val best = candidates.firstOrNull { m ->
                val ps = m.parameterTypes
                hasInt(ps) && hasBundle(ps) && hasList(ps)
            } ?: run {
                // 次优：具备 Bundle + List（某些版本没有 int 或 int 被包装/换位）
                candidates.firstOrNull { m ->
                    val ps = m.parameterTypes
                    hasBundle(ps) && hasList(ps)
                }
            }

            if (best == null) {
                Logger.e(
                    "[QQMessageViewListener] compat method not found on ${clz.name}, " +
                        "candidates=${candidates.size}"
                )
                // 可选：输出候选签名便于你定位
                candidates.take(8).forEach { m ->
                    Logger.e("[QQMessageViewListener] cand: ${m.name}(${m.parameterTypes.joinToString { it.name }})")
                }
                return null
            }

            best.isAccessible = true
            Logger.i("[QQMessageViewListener] use method: ${best.name}(${best.parameterTypes.joinToString { it.simpleName }})")
            return best
        }
    }

    override fun entry(loader: ClassLoader) {
        val onMsgViewUpdate = findCompatOnMsgViewUpdate(loader) ?: run {
            // 找不到就跳过，避免整模块炸
            Logger.e("[QQMessageViewListener] skip hook (method not found)")
            return
        }

        hookAfter(onMsgViewUpdate) { param ->
            val thisObject = param.thisObject

            val msgView = FieldUtils.create(thisObject)
                .fieldType(View::class.java)
                .firstValue<View>(thisObject)

            val aioMsgItem = FieldUtils.create(thisObject)
                .fieldType(ClassUtils.findClass("com.tencent.mobileqq.aio.msg.AIOMsgItem", loader))
                .firstValue<Any>(thisObject)

            onViewUpdate(aioMsgItem, msgView)
        }
    }

    private fun onViewUpdate(aioMsgItem: Any, msgView: View) {
        val msgRecord: MsgRecord = MethodUtils.create(aioMsgItem.javaClass)
            .methodName("getMsgRecord")
            .callFirst(aioMsgItem)

        val peerUid = msgRecord.peerUid
        val msgSeq = msgRecord.msgSeq

        for ((switchFunctionHookItem, listener) in ON_AIO_CHAT_VIEW_UPDATE_LISTENER_MAP.entries) {
            if (switchFunctionHookItem.isEnabled) {
                try {
                    listener.onViewUpdateAfter(msgView, msgRecord)
                } catch (e: Throwable) {
                    ExceptionFactory.add(switchFunctionHookItem, e)
                }
            }
        }

        ONOConf.setInt("ChatScrollMemory", peerUid, msgSeq.toInt())
    }

    interface OnChatViewUpdateListener {
        fun onViewUpdateAfter(msgItemView: View, msgRecord: Any)
    }
}