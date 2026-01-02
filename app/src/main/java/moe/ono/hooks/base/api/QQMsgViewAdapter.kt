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
import java.util.ArrayList

@HookItem(path = "API/适配QQMsg内容ViewID")
class QQMsgViewAdapter : ApiHookItem() {

    companion object {
        private var contentViewId = 0

        @JvmStatic
        fun getContentView(msgItemView: View): View = msgItemView.findViewById(contentViewId)

        @JvmStatic
        fun getContentViewId(): Int = contentViewId

        @JvmStatic
        fun hasContentMessage(messageRootView: ViewGroup): Boolean = messageRootView.childCount >= 5
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
        val clz: Class<*> = runCatching {
            runCatching { ClassUtils.findClass("com.tencent.mobileqq.aio.msglist.holder.AIOBubbleMsgItemVB") }.getOrNull()
                ?: loader.loadClass("com.tencent.mobileqq.aio.msglist.holder.AIOBubbleMsgItemVB")
        }.getOrNull() ?: run {
            Logger.e("[QQMsgViewAdapter] class not found: AIOBubbleMsgItemVB")
            return null
        }

        val candidates: List<Method> = clz.declaredMethods
            .asSequence()
            .filter { it.returnType == Void.TYPE }
            .filter { it.parameterTypes.size == 4 }
            .toList()

        fun hasInt(ps: Array<Class<*>>): Boolean =
            ps.any { it == Int::class.javaPrimitiveType || it == Int::class.java }

        fun hasBundle(ps: Array<Class<*>>): Boolean =
            ps.any { it == Bundle::class.java }

        fun hasList(ps: Array<Class<*>>): Boolean =
            ps.any { java.util.List::class.java.isAssignableFrom(it) }

        val best: Method? =
            candidates.firstOrNull { m ->
                val ps = m.parameterTypes
                hasInt(ps) && hasBundle(ps) && hasList(ps)
            } ?: candidates.firstOrNull { m ->
                val ps = m.parameterTypes
                hasBundle(ps) && hasList(ps)
            }

        if (best == null) {
            Logger.e("[QQMsgViewAdapter] compat method not found on ${clz.name}, candidates=${candidates.size}")
            candidates.take(8).forEach { m ->
                Logger.e("[QQMsgViewAdapter] cand: ${m.name}(${m.parameterTypes.joinToString { p -> p.name }})")
            }
            return null
        }

        best.isAccessible = true
        Logger.i("[QQMsgViewAdapter] use method: ${best.name}(${best.parameterTypes.joinToString { p -> p.simpleName }})")
        return best
    }

    override fun entry(loader: ClassLoader) {
        val cachedId: Int = findContentViewId()
        if (cachedId > 0) {
            contentViewId = cachedId
            return
        }

        val onMsgViewUpdate: Method = findCompatOnMsgViewUpdate(loader) ?: run {
            Logger.e("[QQMsgViewAdapter] skip hook (method not found)")
            return
        }

        // 关键：把 hookAfter 的 param 类型写死，避免 “Cannot infer type for this parameter (R/T)”
        unhook = hookAfter(onMsgViewUpdate) { param: XC_MethodHook.MethodHookParam ->
            val thisObject: Any = param.thisObject ?: return@hookAfter

            // 明确 msgView 的类型
            val msgViewAny: Any? = runCatching {
                FieldUtils.create(thisObject)
                    .fieldType(View::class.java)
                    .firstValue<View>(thisObject)
            }.getOrNull()

            val msgView: ViewGroup = (msgViewAny as? ViewGroup) ?: return@hookAfter

            // 明确 aioMsgItem 的类型
            val aioMsgItem: Any = runCatching {
                FieldUtils.create(thisObject)
                    .fieldType(ClassUtils.findClass("com.tencent.mobileqq.aio.msg.AIOMsgItem"))
                    .firstValue<Any>(thisObject)
            }.getOrNull() ?: return@hookAfter

            // 明确 msgRecord 的类型为 Any（不要让 Kotlin 去推断泛型）
            val msgRecord: Any = runCatching {
                MethodUtils.create(aioMsgItem.javaClass)
                    .methodName("getMsgRecord")
                    .callFirst<Any>(aioMsgItem)
            }.getOrNull() ?: return@hookAfter

            // 明确 elements 的类型（这里最容易触发 Not enough info for 'T'）
            val elements: ArrayList<Any>? = runCatching {
                @Suppress("UNCHECKED_CAST")
                FieldUtils.getField(
                    msgRecord,
                    "elements",
                    ArrayList::class.java
                ) as ArrayList<Any>
            }.getOrNull()

            if (elements == null) {
                // elements 字段名变了也不至于直接失败：直接找 BubbleLayoutCompatPress
                findContentView(msgView)
                return@hookAfter
            }

            for (msgElement in elements) {
                val type: Int = runCatching {
                    // primitiveType 可能是 null，强制 !!
                    FieldUtils.getField(
                        msgElement,
                        "elementType",
                        Int::class.javaPrimitiveType!!
                    ) as Int
                }.getOrNull() ?: continue

                // 文本/图片优先
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
                unhook?.unhook()
                Logger.i("[QQMsgViewAdapter] contentViewId=$contentViewId (cached + unhook)")
                break
            }
        }
    }
}