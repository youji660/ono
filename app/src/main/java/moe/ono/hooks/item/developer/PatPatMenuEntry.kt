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
import java.lang.reflect.Proxy

/**
 * 在消息长按菜单里增加：
 *  - “查看拍一拍缓存”
 *
 * 依赖：QQMsgRespHandler.PatPatCache（你已经加在 QQMsgRespHandler companion object 里了）
 */
class PatPatMenuEntry : OnMenuBuilder {

    /**
     * 复用接口默认 targetTypes（全类型都能点开）
     *
     * 注意：这里必须写 super<OnMenuBuilder> 才能编译通过
     */
    override val targetTypes: Array<String>
        get() = super<OnMenuBuilder>.targetTypes

    override fun onGetMenu(aioMsgItem: Any, targetType: String, param: XC_MethodHook.MethodHookParam) {
        val menuList = (param.result as? MutableList<Any>) ?: return
        if (menuList.isEmpty()) return

        // 避免重复加
        if (menuList.any { safeGetTitle(it) == "查看拍一拍缓存" }) return

        val ctxAct = ContextUtils.getCurrentActivity() ?: return

        val template = menuList[0]
        val itemClass = template.javaClass

        val newItem = runCatching {
            createMenuItemLike(itemClass, template)
        }.getOrNull() ?: run {
            Logger.e("[PatPatMenuEntry] cannot create menu item, class=${itemClass.name}")
            return
        }

        // 设置标题 / id（id 不一定有用，但尽量写）
        runCatching { setTitle(newItem, "查看拍一拍缓存") }
            .onFailure { Logger.e("[PatPatMenuEntry] setTitle fail", it) }
        runCatching { setId(newItem, 0x6F50_4154 /* 'oPAT' */) }
            .onFailure { /* ignore */ }

        // 点击回调：弹出缓存内容
        val click = {
            val cache = QQMsgRespHandler.PatPatCache
            val json = cache.lastJson
            val text = cache.lastText

            SyncUtils.runOnUiThread {
                if (json != null) {
                    JsonViewerDialog.createView(
                        CommonContextWrapper.createAppCompatContext(ctxAct),
                        json
                    )
                    if (!text.isNullOrBlank()) {
                        Toasts.success(ctxAct, text)
                    }
                } else {
                    Toasts.error(ctxAct, "暂无拍一拍缓存（先触发一次拍一拍）")
                }
            }
        }

        // 绑定点击（不同 QQ 版本菜单实现不同，这里做多路兼容）
        if (!bindClick(newItem, click)) {
            Logger.e("[PatPatMenuEntry] bindClick fail: ${newItem.javaClass.name}")
            // 你也可以选择仍然插入，但点了没反应；这里选择不插入
            return
        }

        // 插入位置：尽量不影响系统“最后几项”
        val insertAt = (menuList.size - 1).coerceAtLeast(0)
        menuList.add(insertAt, newItem)
    }

    // ---------------------------------------------------------
    // 反射适配层（尽量兼容不同版本的菜单项类型）
    // ---------------------------------------------------------

    private fun createMenuItemLike(itemClz: Class<*>, template: Any): Any {
        // 0) 优先 clone（字段最全，最稳）
        runCatching {
            val m = itemClz.methods.firstOrNull { it.name == "clone" && it.parameterTypes.isEmpty() }
            if (m != null) {
                m.isAccessible = true
                val cloned = m.invoke(template)
                if (cloned != null) return cloned
            }
        }

        // 1) 常见构造器尝试
        // (Int, CharSequence) / (Int, String) / (CharSequence) / (String) / ()
        itemClz.declaredConstructors.forEach { c ->
            runCatching {
                c.isAccessible = true
                val p = c.parameterTypes
                val inst: Any? = when {
                    p.isEmpty() -> c.newInstance()
                    p.size == 1 && CharSequence::class.java.isAssignableFrom(p[0]) -> c.newInstance("")
                    p.size == 1 && p[0] == String::class.java -> c.newInstance("")
                    p.size == 2 && p[0] == Int::class.javaPrimitiveType && CharSequence::class.java.isAssignableFrom(p[1]) ->
                        c.newInstance(0, "")
                    p.size == 2 && p[0] == Int::class.javaPrimitiveType && p[1] == String::class.java ->
                        c.newInstance(0, "")
                    else -> null
                }
                if (inst != null) return inst
            }
        }

        // 2) 兜底：无参 newInstance（XposedHelpers 会尝试走 hidden ctor）
        return XposedHelpers.newInstance(itemClz)
    }

    private fun safeGetTitle(item: Any): String? {
        // 常见方法：getTitle()
        runCatching {
            val m = item.javaClass.methods.firstOrNull { it.name.equals("getTitle", true) && it.parameterTypes.isEmpty() }
            val v = m?.invoke(item)
            if (v != null) return v.toString()
        }

        // 常见字段：title / text
        runCatching {
            val f = item.javaClass.declaredFields.firstOrNull {
                it.name.contains("title", true) || it.name.contains("text", true)
            }
            if (f != null) {
                f.isAccessible = true
                return f.get(item)?.toString()
            }
        }
        return null
    }

    private fun setTitle(item: Any, title: String) {
        // 1) setTitle(xxx)
        item.javaClass.methods.firstOrNull {
            it.name.equals("setTitle", true) && it.parameterTypes.size == 1
        }?.let {
            it.isAccessible = true
            val t = it.parameterTypes[0]
            it.invoke(item, if (t == CharSequence::class.java) title as CharSequence else title)
            return
        }

        // 2) 写字段 title/text
        val f = item.javaClass.declaredFields.firstOrNull {
            it.name.contains("title", true) || it.name.contains("text", true)
        } ?: return
        f.isAccessible = true
        f.set(item, title)
    }

    private fun setId(item: Any, id: Int) {
        // setId(int)
        item.javaClass.methods.firstOrNull {
            it.name.equals("setId", true) && it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType
        }?.let {
            it.isAccessible = true
            it.invoke(item, id)
            return
        }

        // 字段：id / itemId
        val f = item.javaClass.declaredFields.firstOrNull {
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

        // 2) 字段类型 View.OnClickListener
        item.javaClass.declaredFields.firstOrNull {
            View.OnClickListener::class.java.isAssignableFrom(it.type)
        }?.let { f ->
            f.isAccessible = true
            f.set(item, View.OnClickListener { click.invoke() })
            return true
        }

        // 3) 字段里有 Runnable
        item.javaClass.declaredFields.firstOrNull {
            Runnable::class.java.isAssignableFrom(it.type)
        }?.let { f ->
            f.isAccessible = true
            f.set(item, Runnable { click.invoke() })
            return true
        }

        // 4) Kotlin Function0 / Function1（有些版本会用）
        item.javaClass.declaredFields.firstOrNull {
            it.type.name == "kotlin.jvm.functions.Function0" || it.type.name == "kotlin.jvm.functions.Function1"
        }?.let { f ->
            f.isAccessible = true
            val t = f.type
            if (t.isInterface) {
                val proxy = Proxy.newProxyInstance(
                    t.classLoader,
                    arrayOf(t)
                ) { _, _, _ ->
                    click.invoke()
                    null
                }
                f.set(item, proxy)
                return true
            }
        }

        // 5) 最后兜底：找“像回调”的 interface 字段（callback/action/onClick）
        val cbField = item.javaClass.declaredFields.firstOrNull {
            it.name.contains("callback", true) ||
                it.name.contains("action", true) ||
                it.name.contains("onclick", true) ||
                it.name.contains("listener", true)
        } ?: return false

        val t = cbField.type
        if (!t.isInterface) return false

        val proxy = Proxy.newProxyInstance(
            t.classLoader,
            arrayOf(t)
        ) { _, _, _ ->
            click.invoke()
            null
        }

        cbField.isAccessible = true
        cbField.set(item, proxy)
        return true
    }
}