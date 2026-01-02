package moe.ono.util

import com.tencent.qqnt.kernel.nativeinterface.MsgRecord
import java.lang.reflect.Method
import java.util.IdentityHashMap
import java.util.LinkedHashMap

object ElemDump {

    @Volatile
    var enabled = true

    /** 仅输出拍一拍解析结果；想看全量 element dump 再打开 */
    @Volatile
    var dumpAllElements = false

    data class PatInfo(
        val elemClass: String,
        val text: String?,
        val fromUin: String?,
        val toUin: String?,
        val hitReason: String,
        val extra: Map<String, String>
    )

    fun logMsgRecordElements(msgRecord: MsgRecord) {
        if (!enabled) return

        val elements = getElements(msgRecord)
        if (elements == null) {
            Logger.w("[ElemDump] cannot locate element list from MsgRecord: ${msgRecord.javaClass.name}")
            return
        }

        if (dumpAllElements) {
            Logger.i("========== [ElemDump] START ==========")
            Logger.i("MsgRecord class = ${msgRecord.javaClass.name}")
            Logger.i("ElemDump: elements size = ${elements.size}")
            dumpList(elements)
            Logger.i("========== [ElemDump] END ==========")
        }

        val pat = findPatInfo(elements)
        if (pat != null) {
            Logger.i("========== [ElemDump] PAT DETECTED ==========")
            Logger.i("PatInfo:")
            Logger.i("  elemClass = ${pat.elemClass}")
            Logger.i("  text      = ${pat.text}")
            Logger.i("  fromUin   = ${pat.fromUin}")
            Logger.i("  toUin     = ${pat.toUin}")
            Logger.i("  reason    = ${pat.hitReason}")
            if (pat.extra.isNotEmpty()) {
                pat.extra.forEach { (k, v) -> Logger.i("  $k = $v") }
            }
            Logger.i("========== [ElemDump] PAT END ==========")
        }
    }

    /**
     * 从 MsgRecord 里取 elements List：
     * 1) 优先找无参返回 List 且名字含 elem/element 的方法
     * 2) 其次找无参返回 List 的方法
     * 3) 最后扫字段 List
     */
    private fun getElements(msgRecord: MsgRecord): List<Any?>? {
        val methods = msgRecord.javaClass.declaredMethods
            .filter { it.parameterTypes.isEmpty() && java.util.List::class.java.isAssignableFrom(it.returnType) }

        val sorted = methods.sortedWith(
            compareByDescending<Method> { m ->
                val n = m.name.lowercase()
                when {
                    "element" in n || "elem" in n -> 100
                    "list" in n -> 50
                    else -> 0
                }
            }.thenBy { it.name }
        )

        for (m in sorted) {
            val list = runCatching {
                m.isAccessible = true
                m.invoke(msgRecord) as? List<*>
            }.getOrNull()

            if (list != null) return list as List<Any?>
        }

        // fields fallback
        var c: Class<*>? = msgRecord.javaClass
        while (c != null && c != Any::class.java) {
            for (f in c.declaredFields) {
                val list = runCatching {
                    f.isAccessible = true
                    f.get(msgRecord) as? List<*>
                }.getOrNull()
                if (list != null) return list as List<Any?>
            }
            c = c.superclass
        }
        return null
    }

    /**
     * 找拍一拍/戳一戳 element，并解析：
     * - 先灰字结构：25.1.28.2（你贴的“灰字”）
     * - 再旧版 element：类名/toString 命中 pat/poke/拍/戳
     * - from/to：灰字优先取 25.1.20.*；否则走 element 的字段兜底
     */
    private fun findPatInfo(elements: List<Any?>): PatInfo? {
        for (e in elements) {
            if (e == null) continue

            val clsName = e.javaClass.name
            val s = runCatching { e.toString() }.getOrNull().orEmpty()

            // 字段拍平（含浅递归）
            val flat = flattenAny(e, maxDepth = 2, maxFields = 350)

            // 1) 强特征：灰字拍一拍（25.1.28.2）
            tryParseGrayPat(flat)?.let { return it }

            // 2) 弱特征：旧版 element（类名/toString）
            val reason = hitReason(clsName, s) ?: continue

            val text = pickFirst(
                flat,
                "text", "content", "brief", "desc", "wording", "tip",
                "msg", "summary", "display", "show", "hint"
            )?.takeIf { it.isNotBlank() && it != "null" }
                ?: s.takeIf { it.isNotBlank() }

            // from / to：字段直接命中
            var from = pickFirst(flat, "fromuin", "senderuin", "srcuin", "operatoruin", "actionuin", "opuin", "uin")
            var to = pickFirst(flat, "touin", "targetuin", "dstuin", "receiveruin", "peeruin", "dstUin")

            // 补一刀：operator/target 下钻
            if (from.isNullOrBlank() || from == "null") {
                from = pickFirst(flat, "operator.uin", "operatoruin.uin", "op.uin", "opuin.uin", "sender.uin", "from.uin")
            }
            if (to.isNullOrBlank() || to == "null") {
                to = pickFirst(flat, "target.uin", "targetuin.uin", "peer.uin", "to.uin", "dst.uin")
            }

            // extra：只保留关键字段，避免刷屏
            val keep = LinkedHashMap<String, String>()
            for ((k, v) in flat) {
                val kk = k.lowercase()
                if (
                    kk.contains("uin") ||
                    kk.contains("text") || kk.contains("content") || kk.contains("word") || kk.contains("desc") || kk.contains("tip") ||
                    kk.contains("type") || kk.contains("action") || kk.contains("op") || kk.contains("target") ||
                    kk.contains("id") || kk.contains("time") ||
                    kk.startsWith("25.") // 灰字相关也保留，便于对比
                ) {
                    keep[k] = v
                }
            }

            return PatInfo(
                elemClass = clsName,
                text = text,
                fromUin = from,
                toUin = to,
                hitReason = reason,
                extra = keep
            )
        }
        return null
    }

    /**
     * 新版灰字结构解析：
     * - 文案：25.1.28.2
     * - uin：优先 25.1.20.2 / 25.1.20.3 / 25.1.20.4（你后续可再校验含义）
     */
    private fun tryParseGrayPat(flat: Map<String, String>): PatInfo? {
        val grayText = flat["25.1.28.2"]
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it != "null" }
            ?: return null

        val fromUin = flat["25.1.20.2"] ?: flat["25.1.20.3"] ?: flat["25.1.20.1"]
        val toUin = flat["25.1.20.4"] ?: flat["25.1.28.3"]

        val extra = LinkedHashMap<String, String>()
        val keysToKeep = arrayOf(
            "25.1.1.1", "25.1.1.5", "25.1.1.14", "25.1.1.30", "25.1.1.50",
            "25.1.20.2", "25.1.20.3", "25.1.20.4",
            "25.1.28.1", "25.1.28.2", "25.1.28.3",
            "25.1.30", "25.1.14"
        )
        for (k in keysToKeep) {
            val v = flat[k]
            if (!v.isNullOrBlank() && v != "null") extra[k] = v
        }

        return PatInfo(
            elemClass = "GRAY_TIPS(25.1.28)",
            text = grayText,
            fromUin = fromUin,
            toUin = toUin,
            hitReason = "grayTips(25.1.28.2)",
            extra = extra
        )
    }

    /**
     * 收紧命中条件：只用类名/toString
     */
    private fun hitReason(clsName: String, toStr: String): String? {
        val c = clsName.lowercase()
        if (c.contains("poke") || c.contains("pat") || c.contains("nudge")) return "className($clsName)"

        // 文案命中（中文关键字）
        if (toStr.contains("拍了拍") || toStr.contains("戳了戳") || toStr.contains("拍一拍")) return "toString(hit)"

        return null
    }

    private fun dumpList(list: List<Any?>) {
        list.forEachIndexed { index, elem ->
            if (elem == null) return@forEachIndexed
            Logger.i("  [$index] ${elem.javaClass.name}")
            dumpObject(elem)
        }
    }

    private fun dumpObject(obj: Any) {
        var c: Class<*>? = obj.javaClass
        while (c != null && c != Any::class.java) {
            for (f in c.declaredFields) {
                runCatching {
                    f.isAccessible = true
                    val v = f.get(obj)
                    Logger.i("      field ${f.name} = ${valueToString(v)}")
                }
            }
            c = c.superclass
        }
        runCatching { Logger.i("      toString = $obj") }
    }

    /**
     * 更通用的拍平：
     * - 支持对象字段、List/Array/Map
     * - path 用点号/下标，如: a.b[0].c
     * - 深度/数量限制防刷屏
     */
    private fun flattenAny(
        root: Any,
        maxDepth: Int,
        maxFields: Int
    ): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val seen = IdentityHashMap<Any, Boolean>()

        fun put(prefix: String, v: Any?) {
            if (prefix.isNotEmpty() && out.size < maxFields) out[prefix] = valueToString(v)
        }

        fun walk(o: Any?, prefix: String, depth: Int) {
            if (out.size >= maxFields) return

            if (o == null) {
                put(prefix, null)
                return
            }

            if (isLeafAny(o)) {
                put(prefix, o)
                return
            }

            if (depth > maxDepth) {
                put(prefix, o)
                return
            }

            if (seen.containsKey(o)) return
            seen[o] = true

            // List
            if (o is List<*>) {
                for (i in 0 until o.size) {
                    if (out.size >= maxFields) return
                    walk(o[i], "$prefix[$i]", depth + 1)
                }
                return
            }

            // Array (Object[])
            if (o.javaClass.isArray) {
                val len = java.lang.reflect.Array.getLength(o)
                for (i in 0 until len) {
                    if (out.size >= maxFields) return
                    val v = java.lang.reflect.Array.get(o, i)
                    walk(v, "$prefix[$i]", depth + 1)
                }
                return
            }

            // Map
            if (o is Map<*, *>) {
                o.entries.forEach { (k, v) ->
                    if (out.size >= maxFields) return
                    val kk = runCatching { k.toString() }.getOrNull() ?: "<key>"
                    val key = if (prefix.isEmpty()) kk else "$prefix.$kk"
                    walk(v, key, depth + 1)
                }
                return
            }

            // 普通对象字段
            var c: Class<*>? = o.javaClass
            while (c != null && c != Any::class.java) {
                for (f in c.declaredFields) {
                    if (out.size >= maxFields) return
                    val v = runCatching {
                        f.isAccessible = true
                        f.get(o)
                    }.getOrNull()

                    val key = if (prefix.isEmpty()) f.name else "$prefix.${f.name}"
                    if (v == null || isLeafAny(v)) {
                        out[key] = valueToString(v)
                    } else {
                        walk(v, key, depth + 1)
                    }
                }
                c = c.superclass
            }
        }

        walk(root, "", 0)
        return out
    }

    private fun isLeafAny(v: Any): Boolean {
        return v is String ||
            v is Number ||
            v is Boolean ||
            v is Char ||
            v is ByteArray || v is IntArray || v is LongArray ||
            v.javaClass.isEnum ||
            v.javaClass.name.startsWith("java.") ||
            v.javaClass.name.startsWith("kotlin.")
    }

    private fun pickFirst(map: Map<String, String>, vararg keys: String): String? {
        // 直接 key 命中（支持 path）
        for (k in keys) {
            val v = map[k] ?: map.entries.firstOrNull { it.key.equals(k, ignoreCase = true) }?.value
            if (!v.isNullOrBlank() && v != "null") return v
        }
        // 再按“包含”兜底
        val lower = map.entries.associate { it.key.lowercase() to it.value }
        for (k in keys) {
            val kk = k.lowercase()
            val hit = lower.entries.firstOrNull { (key, _) -> key.endsWith(kk) || key.contains(kk) }?.value
            if (!hit.isNullOrBlank() && hit != "null") return hit
        }
        return null
    }

    private fun valueToString(v: Any?): String =
        when (v) {
            null -> "null"
            is ByteArray -> "byte[${v.size}]"
            is IntArray -> "int[${v.size}]"
            is LongArray -> "long[${v.size}]"
            else -> runCatching { v.toString() }.getOrElse { "<toString error>" }
        }
}