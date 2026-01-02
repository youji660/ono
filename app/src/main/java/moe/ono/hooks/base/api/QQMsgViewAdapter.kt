package moe.ono.hooks.base.api

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.XC_MethodHook
import moe.ono.BuildConfig
import moe.ono.config.ConfigManager.cGetInt
import moe.ono.config.ConfigManager.cPutInt
import moe.ono.hooks._base.ApiHookItem
import moe.ono.hooks._core.annotation.HookItem
import moe.ono.reflex.ClassUtils
import moe.ono.reflex.FieldUtils
import moe.ono.reflex.MethodUtils
import moe.ono.util.HostInfo
import moe.ono.util.Logger
import java.lang.reflect.Method

@HookItem(path = "API/适配QQMsg内容ViewID")
class QQMsgViewAdapter : ApiHookItem() {

    companion object {
        private var contentViewId = 0

        @JvmStatic
        fun getContentView(msgItemView: View): View {
            return msgItemView.findViewById(contentViewId)
        }

        @JvmStatic
        fun getContentViewId(): Int = contentViewId

        @JvmStatic
        fun hasContentMessage(messageRootView: ViewGroup): Boolean {
            return messageRootView.childCount >= 5
        }
    }

    private var unhook: XC_MethodHook.Unhook? = null

    private fun findContentViewId(): Int {
        return cGetInt(
            "contentViewId${HostInfo.getVersionName()}:${BuildConfig.VERSION_NAME}",
            -1
        )
    }

    private fun putContentViewId(id: Int) {
        cPutInt(
            "contentViewId${HostInfo.getVersionName()}:${BuildConfig.VERSION_NAME}",
            id
        )
    }

    // 兼容：找到 AIOBubbleMsgItemVB 里“更新气泡View”的方法（void + 4参 + 含 Bundle/List/int）
    private fun findCompatOnMsgViewUpdate(loader: ClassLoader): Method? {
        val clz = runCatching {
            // 你项目的 ClassUtils 只有 findClass(String)，不支持传 loader，所以这里兜底 loader.loadClass
            runCatching { ClassUtils.findClass("com.tencent.mobileqq.aio.msglist.holder.AIOBubbleMsgItemVB") }.getOrNull()
                ?: loader.loadClass("com.tencent.mobileqq.aio.msglist.holder.AIOBubbleMsgItemVB")
        }.getOrNull() ?: run {
            Logger.e("[QQMsgViewAdapter] class not found: AIOBubbleMsgItemVB")
            return null
        }

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

        val best = candidates.firstOrNull { m ->
            val ps = m.parameterTypes
            hasInt(ps) && hasBundle(ps) && hasList(ps)
        } ?: candidates.firstOrNull { m ->
            val ps = m.parameterTypes
            hasBundle(ps) && hasList(ps)
        }

        if (best == null) {
            Logger.e("[QQMsgViewAdapter] compat method not found on ${clz.name}, candidates=${candidates.size}")
            candidates.take(8).forEach { m ->
                Logger.e("[QQMsgViewAdapter] cand: ${m.name}(${m.parameterTypes.joinToString { it.name }})")
            }
            return null
        }

        best.isAccessible = true
        Logger.i("[QQMsgViewAdapter] use method: ${best.name}(${best.parameterTypes.joinToString { it.simpleName }})")
        return best
    }

    override fun entry(loader: ClassLoader) {
        // 已缓存过 id 就不再 hook
        findContentViewId().takeIf { it > 0 }?.let {
            contentViewId = it
            return
        }

        val onMsgViewUpdate = findCompatOnMsgViewUpdate(loader) ?: run {
            Logger.e("[QQMsgViewAdapter] skip hook (method not found)")
            return
        }

        unhook = hookAfter(onMsgViewUpdate) { param ->
            val thisObject = param.thisObject ?: return@hookAfter

            val msgView = runCatching {
                FieldUtils.create(thisObject)
                    .fieldType(View::class.java)
                    .firstValue<View>(thisObject)
            }.getOrNull() as? ViewGroup ?: return@hookAfter

            val aioMsgItem = runCatching {
                FieldUtils.create(thisObject)
                    .fieldType(ClassUtils.findClass("com.tencent.mobileqq.aio.msg.AIOMsgItem"))
                    .firstValue<Any>(thisObject)
            }.getOrNull() ?: return@hookAfter

            val msgRecord = runCatching {
                MethodUtils.create(aioMsgItem.javaClass)
                    .methodName("getMsgRecord")
                    .callFirst(aioMsgItem)
            }.getOrNull() ?: return@hookAfter

            // elements 字段不保证一定叫 elements：这里容错
            val elements: List<Any> = runCatching {
                @Suppress("UNCHECKED_CAST")
                FieldUtils.getField(msgRecord, "elements", ArrayList::class.java) as ArrayList<Any>
            }.getOrNull() ?: run {
                // 如果没有 elements，你也可以先直接尝试找 BubbleLayoutCompatPress（多数版本可行）
                findContentView(msgView)
                return@hookAfter
            }

            for (msgElement in elements) {
                val type: Int = runCatching {
                    FieldUtils.getField(msgElement, "elementType", Int::class.javaPrimitiveType)
                }.getOrNull() ?: continue

                // 文本/图片(<=2) 才解析，其他类型可能没有目标 view
                if (type <= 2) {
                    findContentView(msgView)
                    break
                }
            }
        }
    }

    private fun findContentView(itemView: ViewGroup) {
        for (i in 0 until itemView.childCount) {
            val child = itemView.getChildAt(i)
            if (child.javaClass.name == "com.tencent.qqnt.aio.holder.template.BubbleLayoutCompatPress") {
                contentViewId = child.id
                putContentViewId(child.id)
                unhook?.unhook() // 找到就解 hook
                Logger.i("[QQMsgViewAdapter] contentViewId=$contentViewId (cached + unhook)")
                break
            }
        }
    }
}