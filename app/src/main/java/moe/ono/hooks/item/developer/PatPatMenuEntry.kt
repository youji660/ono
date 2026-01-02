package moe.ono.hooks.item.developer

import android.view.View
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import moe.ono.creator.JsonViewerDialog
import moe.ono.hooks.base.util.Toasts
import moe.ono.hooks.dispatcher.OnMenuBuilder
import moe.ono.ui.CommonContextWrapper
import moe.ono.util.ContextUtils
import moe.ono.util.Logger
import moe.ono.util.SyncUtils
import org.json.JSONObject
import java.lang.reflect.Proxy

class PatPatMenuEntry : OnMenuBuilder {

    override val targetTypes: Array<String>
        get() = super.targetTypes

    override fun onGetMenu(aioMsgItem: Any, targetType: String, param: XC_MethodHook.MethodHookParam) {
        val menuList = (param.result as? MutableList<Any>) ?: return
        if (menuList.isEmpty()) return

        // 避免重复
        if (menuList.any { safeGetTitle(it)?.contains("拍一拍") == true }) return

        val ctxAct = ContextUtils.getCurrentActivity() ?: return
        val itemClass = menuList[0].javaClass

        val newItem = runCatching { createMenuItemLike(itemClass, menuList[0]) }.getOrNull()
            ?: run {
                Logger.e("[PatPatMenuEntry] cannot create menu item, class=${itemClass.name}")
                return
            }

        runCatching { setTitle(newItem, "查看拍一拍缓存") }
            .onFailure { Logger.e("[PatPatMenuEntry] setTitle fail", it) }

        runCatching { setId(newItem, 0x6F504154 /* oPAT */) }.onFailure { }

        val click = {
            val cache = moe.ono.hooks.base.api.QQMsgRespHandler.PatPatCache
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
            return
        }

        menuList.add(newItem)
    }

    private fun createMenuItemLike(itemClz: Class<*>, template: Any): Any {
        itemClz.declaredConstructors.forEach { c ->
            runCatching {
                c.isAccessible = true
                val p = c.parameterTypes
                return when {
                    p.isEmpty() -> c.newInstance()
                    p.size == 1 && CharSequence::class.java.isAssignableFrom(p[0]) -> c.newInstance("")
                    p.size == 1 && p[0] == String::class.java -> c.newInstance("")
                    p.size == 2 && p[0] == Int::class.javaPrimitiveType && CharSequence::class.java.isAssignableFrom(p[1]) ->
                        c.newInstance(0, "")
                    p.size == 2 && p[0] == Int::class.javaPrimitiveType && p[1] == String::class.java ->
                        c.newInstance(0, "")
                    else -> null
                } ?: throw IllegalStateException("ctor not match")
            }.onSuccess { return it }
        }

        runCatching {
            val m = itemClz.methods.firstOrNull { it.name == "clone" && it.parameterTypes.isEmpty() }
            if (m != null) {
                m.isAccessible = true
                return m.invoke(template)
            }
        }

        return XposedHelpers.newInstance(itemClz)
    }

    private fun safeGetTitle(item: Any): String? {
        runCatching {
            val m = item.javaClass.methods.firstOrNull { it.name.equals("getTitle", true) && it.parameterTypes.isEmpty() }
            return m?.invoke(item)?.toString()
        }
        runCatching {
            val f = item.javaClass.declaredFields.firstOrNull { it.name.contains("title", true) || it.name.contains("text", true) }
            if (f != null) {
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

        val f = item.javaClass.declaredFields.firstOrNull {
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

        val f = item.javaClass.declaredFields.firstOrNull {
            it.name.equals("id", true) || it.name.contains("itemId", true)
        } ?: return

        f.isAccessible = true
        if (f.type == Int::class.javaPrimitiveType || f.type == Int::class.java) {
            f.set(item, id)
        }
    }

    private fun bindClick(item: Any, click: () -> Unit): Boolean {
        item.javaClass.methods.firstOrNull {
            it.name.contains("setOnClick", true) &&
                it.parameterTypes.size == 1 &&
                View.OnClickListener::class.java.isAssignableFrom(it.parameterTypes[0])
        }?.let { m ->
            m.isAccessible = true
            m.invoke(item, View.OnClickListener { click.invoke() })
            return true
        }

        item.javaClass.declaredFields.firstOrNull {
            View.OnClickListener::class.java.isAssignableFrom(it.type)
        }?.let { f ->
            f.isAccessible = true
            f.set(item, View.OnClickListener { click.invoke() })
            return true
        }

        val cbField = item.javaClass.declaredFields.firstOrNull {
            it.name.contains("callback", true) || it.name.contains("action", true) || it.name.contains("onClick", true)
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
}