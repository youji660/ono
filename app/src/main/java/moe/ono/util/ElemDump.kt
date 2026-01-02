package moe.ono.util

import com.tencent.qqnt.kernel.nativeinterface.MsgRecord

object ElemDump {

    @Volatile
    var enabled = true   // 不想刷屏就关掉

    fun logMsgRecordElements(msgRecord: MsgRecord) {
        if (!enabled) return

        Logger.i("========== [ElemDump] START ==========")
        Logger.i("MsgRecord class = ${msgRecord.javaClass.name}")

        // 1️⃣ dump 所有 method（返回 java.util.List）
        for (m in msgRecord.javaClass.declaredMethods) {
            if (m.parameterTypes.isNotEmpty()) continue
            if (!java.util.List::class.java.isAssignableFrom(m.returnType)) continue

            try {
                m.isAccessible = true
                val ret = m.invoke(msgRecord)
                if (ret is java.util.List<*>) {
                    Logger.i(">> METHOD ${m.name}() -> List(size=${ret.size})")
                    dumpList(ret)
                }
            } catch (_: Throwable) {}
        }

        // 2️⃣ dump 所有 field（java.util.List）
        var c: Class<*>? = msgRecord.javaClass
        while (c != null && c != Any::class.java) {
            for (f in c.declaredFields) {
                try {
                    f.isAccessible = true
                    val v = f.get(msgRecord)
                    if (v is java.util.List<*>) {
                        Logger.i(">> FIELD ${f.name} -> List(size=${v.size})")
                        dumpList(v)
                    }
                } catch (_: Throwable) {}
            }
            c = c.superclass
        }

        Logger.i("========== [ElemDump] END ==========")
    }

    private fun dumpList(list: java.util.List<*>) {
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
                try {
                    f.isAccessible = true
                    val v = f.get(obj)
                    Logger.i("      field ${f.name} = ${valueToString(v)}")
                } catch (_: Throwable) {}
            }
            c = c.superclass
        }

        try {
            Logger.i("      toString = $obj")
        } catch (_: Throwable) {}
    }

    private fun valueToString(v: Any?): String =
        when (v) {
            null -> "null"
            is ByteArray -> "byte[${v.size}]"
            is IntArray -> "int[${v.size}]"
            is LongArray -> "long[${v.size}]"
            else -> v.toString()
        }
}