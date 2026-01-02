package moe.ono.reflex;

import static moe.ono.util.Initiator.loadClass;

import java.lang.reflect.Array;
import java.util.HashMap;
import java.util.Map;

import moe.ono.reflex.exception.ReflectException;

public class ClassUtils {
    private static final Object[][] baseTypes = {
            {"int", int.class},
            {"boolean", boolean.class},
            {"byte", byte.class},
            {"long", long.class},
            {"char", char.class},
            {"double", double.class},
            {"float", float.class},
            {"short", short.class},
            {"void", void.class}
    };

    /**
     * 获取基本类型
     */
    private static Class<?> getBaseTypeClass(String baseTypeName) {
        if (baseTypeName.length() == 1) return findSimpleType(baseTypeName.charAt(0));
        for (Object[] baseType : baseTypes) {
            if (baseTypeName.equals(baseType[0])) {
                return (Class<?>) baseType[1];
            }
        }
        throw new ReflectException(baseTypeName + " <-不是基本的数据类型");
    }

    /**
     * conversion base type
     *
     * @param simpleType Smali Base Type V,Z,B,I...
     */
    private static Class<?> findSimpleType(char simpleType) {
        return switch (simpleType) {
            case 'V' -> void.class;
            case 'Z' -> boolean.class;
            case 'B' -> byte.class;
            case 'S' -> short.class;
            case 'C' -> char.class;
            case 'I' -> int.class;
            case 'J' -> long.class;
            case 'F' -> float.class;
            case 'D' -> double.class;
            default -> throw new RuntimeException("Not an underlying type");
        };
    }

    /**
     * 排除常用类
     */
    public static boolean isCommonlyUsedClass(String name) {
        return name.startsWith("androidx.")
                || name.startsWith("android.")
                || name.startsWith("kotlin.")
                || name.startsWith("kotlinx.")
                || name.startsWith("com.tencent.mmkv.")
                || name.startsWith("com.android.tools.r8.")
                || name.startsWith("com.google.android.")
                || name.startsWith("com.google.gson.")
                || name.startsWith("com.google.common.")
                || name.startsWith("com.microsoft.appcenter.")
                || name.startsWith("org.intellij.lang.annotations.")
                || name.startsWith("org.jetbrains.annotations.");
    }

    /**
     * 获取类（默认走 Initiator.loadClass）
     */
    public static Class<?> findClass(String className) {
        try {
            return loadClass(className);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 获取类（指定 ClassLoader，适合 Xposed/LSPosed 多 ClassLoader 场景）
     * - 优先用指定 loader 加载
     * - 失败再兜底到 Initiator.loadClass
     */
    public static Class<?> findClass(String className, ClassLoader loader) {
        if (loader == null) {
            return findClass(className);
        }
        try {
            return getCacheLoader(loader).loadClass(className);
        } catch (ClassNotFoundException e) {
            // 兜底：再走默认逻辑
            try {
                return loadClass(className);
            } catch (ClassNotFoundException ex) {
                throw new RuntimeException(ex);
            }
        }
    }

    // 按父 ClassLoader 缓存 CacheClassLoader，避免频繁 new
    private static final Map<ClassLoader, CacheClassLoader> LOADER_CACHE = new HashMap<>();

    private static CacheClassLoader getCacheLoader(ClassLoader parent) {
        synchronized (LOADER_CACHE) {
            CacheClassLoader ccl = LOADER_CACHE.get(parent);
            if (ccl == null) {
                ccl = new CacheClassLoader(parent);
                LOADER_CACHE.put(parent, ccl);
            }
            return ccl;
        }
    }

    private static class CacheClassLoader extends ClassLoader {
        private static final Map<String, Class<?>> CLASS_CACHE = new HashMap<>();

        public CacheClassLoader(ClassLoader classLoader) {
            super(classLoader);
        }

        @Override
        public Class<?> loadClass(String className) throws ClassNotFoundException {
            Class<?> clazz = CLASS_CACHE.get(className);
            if (clazz != null) {
                return clazz;
            }

            String originName = className;

            if (className.endsWith(";") || className.contains("/")) {
                className = className.replace('/', '.');
                if (className.endsWith(";")) {
                    if (className.charAt(0) == 'L') {
                        className = className.substring(1, className.length() - 1);
                    } else {
                        className = className.substring(0, className.length() - 1);
                    }
                }
            }

            // 可能是数组类型
            if (className.startsWith("[")) {
                int index = className.lastIndexOf('[');
                // 获取原类型
                try {
                    clazz = getBaseTypeClass(className.substring(index + 1));
                } catch (Exception e) {
                    clazz = super.loadClass(className.substring(index + 1));
                }
                // 转换数组类型
                for (int i = 0; i < className.length(); i++) {
                    char ch = className.charAt(i);
                    if (ch == '[') {
                        clazz = Array.newInstance(clazz, 0).getClass();
                    } else {
                        break;
                    }
                }
                CLASS_CACHE.put(originName, clazz);
                return clazz;
            }

            // 可能是基础类型
            try {
                clazz = getBaseTypeClass(className);
            } catch (Exception e) {
                // 因为默认的 ClassLoader.load() 不能加载 "int" 这种类型
                clazz = super.loadClass(className);
            }

            CLASS_CACHE.put(originName, clazz);
            return clazz;
        }
    }
}