package moe.ono;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import de.robv.android.xposed.XposedBridge;

public class RefUtil {

    // ========= 找类 =========
    public static Class<?> findClass(String className, ClassLoader cl) {
        try {
            Class<?> c = Class.forName(className, false, cl);
            XposedBridge.log("[RefUtil] findClass OK: " + className);
            return c;
        } catch (Throwable t) {
            XposedBridge.log("[RefUtil] findClass FAIL: " + className + " : " + t);
            return null;
        }
    }

    // ========= 打印类信息（字段 + 方法签名） =========
    public static void logClassInfo(String className, ClassLoader cl) {
        Class<?> c = findClass(className, cl);
        if (c == null) return;

        XposedBridge.log("========== [RefUtil] Class Info ==========");
        XposedBridge.log("Class: " + c.getName());

        // 父类链
        Class<?> p = c.getSuperclass();
        while (p != null) {
            XposedBridge.log("  extends: " + p.getName());
            p = p.getSuperclass();
        }

        // 接口
        Class<?>[] ifs = c.getInterfaces();
        if (ifs != null && ifs.length > 0) {
            XposedBridge.log("  implements:");
            for (Class<?> i : ifs) {
                XposedBridge.log("    - " + i.getName());
            }
        }

        // 字段
        XposedBridge.log("---- Fields ----");
        Field[] fields = c.getDeclaredFields();
        for (Field f : fields) {
            String mod = Modifier.toString(f.getModifiers());
            XposedBridge.log("  " + mod + " "
                    + f.getType().getName() + " "
                    + f.getName());
        }

        // 方法
        XposedBridge.log("---- Methods ----");
        Method[] ms = c.getDeclaredMethods();
        for (Method m : ms) {
            StringBuilder sb = new StringBuilder();
            sb.append("  ")
              .append(Modifier.toString(m.getModifiers()))
              .append(" ")
              .append(m.getReturnType().getName())
              .append(" ")
              .append(m.getName())
              .append("(");
            Class<?>[] ps = m.getParameterTypes();
            for (int i = 0; i < ps.length; i++) {
                sb.append(ps[i].getName());
                if (i < ps.length - 1) sb.append(", ");
            }
            sb.append(")");
            XposedBridge.log(sb.toString());
        }

        XposedBridge.log("========== [RefUtil] End Class Info ==========");
    }

    // ========= 打印对象字段当前值 =========
    public static void logObjectFields(Object obj) {
        if (obj == null) {
            XposedBridge.log("[RefUtil] logObjectFields: obj == null");
            return;
        }

        Class<?> c = obj.getClass();
        XposedBridge.log("========== [RefUtil] Object Fields ==========");
        XposedBridge.log("Class: " + c.getName());

        while (c != null) {
            XposedBridge.log("-- Declared in: " + c.getName());
            Field[] fs = c.getDeclaredFields();
            for (Field f : fs) {
                try {
                    f.setAccessible(true);
                    Object v = f.get(obj);
                    XposedBridge.log("  " + f.getName() + " = " + safeToString(v));
                } catch (Throwable t) {
                    XposedBridge.log("  " + f.getName() + " <cannot read> : " + t);
                }
            }
            c = c.getSuperclass();
        }

        XposedBridge.log("========== [RefUtil] End Object Fields ==========");
    }

    private static String safeToString(Object v) {
        if (v == null) return "null";
        try {
            return v.toString();
        } catch (Throwable t) {
            return "<toString error: " + t + ">";
        }
    }
}