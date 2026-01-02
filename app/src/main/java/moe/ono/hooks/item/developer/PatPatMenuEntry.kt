package moe.ono.hooks.item.developer

import android.view.View
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import moe.ono.creator.JsonViewerDialog
import moe.ono.hooks.base.api.QQMsgRespHandler
import moe.ono.hooks.base.util.Toasts
import moe.ono.hooks.dispatcher.OnMenuBuilder
import moe.ono.ui.CommonContextWrapper
import moe.ono.util.ContextUtils
import moe.ono.util.Logger
import moe.ono.util.SyncUtils
import org.json.JSONObject
import java.lang.reflect.Proxy

/**
 * 在消息长按菜单里增加：
 *  - “查看拍一拍缓存”
 *
 * 依赖：QQMsgRespHandler.Companion.PatPatCache
 */
class PatPatMenuEntry : OnMenuBuilder {

    override val targetTypes: Array<String>
        get() = super.targetTypes

    override fun onGetMenu(aioMsgItem: Any, targetType: String, param: XC_MethodHook.MethodHookParam) {
        val menuList = (param.result as? MutableList<Any>) ?: return
        if (menuList.isEmpty()) return

        // 避免重复添加
        if (menuList.any {
                safeGetTitle(it)?.contains("查看拍一拍缓存") == true ||
                    safeGetTitle(it)?.contains("拍一拍") == true
            }
        ) return

        val ctxAct = ContextUtils.getCurrentActivity() ?: return

        val template = menuList[0]
        var itemClz: Class<*> = template.javaClass

        // ByteBuddy 动态类不能直接 new，退回到真实父类
        if (itemClz.name.contains("\$ByteBuddy\$", ignoreCase = true) || itemClz.name.contains("ByteBuddy", ignoreCase = true)) {
            val superClz = itemClz.superclass
            if (superClz != null && superClz != Any::class.java) {
                Logger.w("[PatPatMenuEntry] template is ByteBuddy: ${itemClz.name}, use superclass: ${superClz.name}")
                itemClz = superClz
            }
        }

        val newItem = runCatching { createMenuItemLike(itemClz, template) }.getOrNull()
            ?: run {
                Logger.e("[PatPatMenuEntry] cannot create menu item, templateClz=${template.javaClass.name}, useClz=${itemClz.name}")
                dumpConstructors(itemClz)
                return
            }

        runCatching { setTitle(newItem, "查看拍一拍缓存") }
            .onFailure { Logger.e("[PatPatMenuEntry] setTitle fail", it) }

        runCatching { setId(newItem, 0x6F504154 /* oPAT */) }.onFailure { /* ignore */ }

        val click = {
            // ✅ 关键：嵌套 object 在 companion 里，必须走 Companion
            val cache = QQMsgRespHandler.Companion.PatPatCache
            val json: JSONObject? = cache.lastJson
            val text: String? = cache.lastText

            SyncUtils.runOnUiThread {
                if (json != null) {
                    JsonViewerDialog.createView(
                        CommonContextWrapper.createAppCompatContext(ctxAct),
                        json
                    )
                    if (!text.isNullOrBlank()) Toasts.success(ctxAct, text)
                } else {
                    Toasts.error(ctxAct, "暂无拍一拍缓存（先触发一次拍一拍）")
                }
            }
        }

        if (!bindClick(newItem, click)) {
            Logger.e("[PatPatMenuEntry] bindClick fail: ${newItem.javaClass.name}")
            dumpFields(newItem.javaClass)
            return
        }

        menuList.add(newItem)
        Logger.i("[PatPatMenuEntry] added menu item ok, itemClz=${newItem.javaClass.name}")
    }

    // -------------------------
    // 反射适配层
    // -------------------------

    private fun createMenuItemLike(itemClz: Class<*>, template: Any): Any {
        // 1) 常见构造器
        itemClz.declaredConstructors.forEach { c ->
            runCatching {
                c.isAccessible = true
                val p = c.parameterTypes
                return when {
                    p.isEmpty() -> c.newInstance()
                    p.size == 1 && CharSequence::class.java.isAssignableFrom(p[0]) -> c.newInstance("")
                    p.size == 1 && p[0] == String::class.java -> c.newInstance("")
                    p.size == 2 && p[0] == Int::class.javaPrimitiveType &&
                        CharSequence::class.java.isAssignableFrom(p[1]) -> c.newInstance(0, "")
                    p.size == 2 && p[0] == Int::class.javaPrimitiveType && p[1] == String::class.java -> c.newInstance(0, "")
                    else -> null
                } ?: throw IllegalStateException("ctor not match")
            }.onSuccess { return it }
        }

        // 2) clone
        runCatching {
            val m = itemClz.methods.firstOrNull { it.name == "clone" && it.parameterTypes.isEmpty() }
            if (m != null) {
                m.isAccessible = true
                return m.invoke(template)
            }
        }

        // 3) Xposed newInstance 兜底
        return XposedHelpers.newInstance(itemClz)
    }

    private fun safeGetTitle(item: Any): String? {
        runCatching {
            val m = item.javaClass.methods.firstOrNull {
                it.name.equals("getTitle", true) && it.parameterTypes.isEmpty()
            }
            if (m != null) return m.invoke(item)?.toString()
        }

        findFieldRecursive(item.javaClass) { f ->
            f.name.contains("title", true) || f.name.contains("text", true)
        }?.let { f ->
            runCatching {
                f.isAccessible = true
                return f.get(item)?.toString()
            }
        }
        return null
    }

    private fun setTitle(item: Any, title: String) {
        item.javaClass.methods.firstOrNull {
            it.name.equals("setTitle", true) && it.parameterTypes.size == 1
        }?.let {
            it.isAccessible = true
            it.invoke(item, title)
            return
        }

        val f = findFieldRecursive(item.javaClass) {
            it.name.contains("title", true) || it.name.contains("text", true)
        } ?: return
        f.isAccessible = true
        f.set(item, title)
    }

    private fun setId(item: Any, id: Int) {
        item.javaClass.methods.firstOrNull {
            it.name.equals("setId", true) &&
                it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType
        }?.let {
            it.isAccessible = true
            it.invoke(item, id)
            return
        }

        val f = findFieldRecursive(item.javaClass) {
            it.name.equals("id", true) || it.name.contains("itemId", true)
        } ?: return
        f.isAccessible = true
        if (f.type == Int::class.javaPrimitiveType || f.type == Int::class.java) {
            f.set(item, id)
        }
    }

    private fun bindClick(item: Any, click: () -> Unit): Boolean {
        // 1) setOnClickListener(View.OnClickListener)
        item.javaClass.methods.firstOrNull {
            it.name.contains("setOnClick", true) &&
                it.parameterTypes.size == 1 &&
                View.OnClickListener::class.java.isAssignableFrom(it.parameterTypes[0])
        }?.let { m ->
            m.isAccessible = true
            m.invoke(item, View.OnClickListener { click.invoke() })
            return true
        }

        // 2) 字段里有 OnClickListener（含父类）
        findFieldRecursive(item.javaClass) {
            View.OnClickListener::class.java.isAssignableFrom(it.type)
        }?.let { f ->
            f.isAccessible = true
            f.set(item, View.OnClickListener { click.invoke() })
            return true
        }

        // 3) callback/action/onClick 字段（含父类）
        val cbField = findFieldRecursive(item.javaClass) {
            it.name.contains("callback", true) ||
                it.name.contains("action", true) ||
                it.name.contains("onClick", true)
        } ?: return false

        val t = cbField.type
        if (!t.isInterface) return false

        val proxy = Proxy.newProxyInstance(t.classLoader, arrayOf(t)) { _, _, _ ->
            click.invoke()
            null
        }

        cbField.isAccessible = true
        cbField.set(item, proxy)
        return true
    }

    private fun findFieldRecursive(clz: Class<*>, pred: (java.lang.reflect.Field) -> Boolean): java.lang.reflect.Field? {
        var c: Class<*>? = clz
        while (c != null && c != Any::class.java) {
            c.declaredFields.firstOrNull(pred)?.let { return it }
            c = c.superclass
        }
        return null
    }

    // -------------------------
    // debug
    // -------------------------

    private fun dumpConstructors(clz: Class<*>) {
        runCatching {
            Logger.w("[PatPatMenuEntry] ctors of ${clz.name}:")
            clz.declaredConstructors.forEach { c ->
                Logger.w("  ctor: (${c.parameterTypes.joinToString { it.name }})")
            }
        }
    }

    private fun dumpFields(clz: Class<*>) {
        runCatching {
            Logger.w("[PatPatMenuEntry] fields of ${clz.name} (recursive):")
            var c: Class<*>? = clz
            while (c != null && c != Any::class.java) {
                c.declaredFields.forEach { f ->
                    Logger.w("  ${c.name}#${f.name}: ${f.type.name}")
                }
                c = c.superclass
            }
        }
    }
}