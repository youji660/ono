package moe.ono.hooks._base;

import static de.robv.android.xposed.XposedHelpers.findClass;
import static de.robv.android.xposed.XposedHelpers.findConstructorExact;
import static moe.ono.constants.Constants.PrekCfgXXX;

import androidx.annotation.NonNull;

import java.lang.reflect.Constructor;
import java.lang.reflect.Member;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import moe.ono.config.ConfigManager;
import moe.ono.hooks._core.factory.ExceptionFactory;
import moe.ono.startup.HybridClassLoader;

/**
 * 所有hook功能的基础类,都应该要继承这个类
 */
public abstract class BaseHookItem {

    /**
     * 功能名称
     */
    private String path;
    /**
     * 功能描述
     */
    private String desc;

    /**
     * 是否加载
     */
    private boolean isLoad = false;

    public String getSimpleName() {
        return this.getClass().getSimpleName();
    }

    public String getPath() {
        return path;
    }


    public void setPath(String path) {
        this.path = path;
    }

    public String getDesc() {
        return desc;
    }


    public void setDesc(String desc) {
        this.desc = desc;
    }

    public String getItemName() {
        int index = path.lastIndexOf("/");
        if (index == -1) {
            return path;
        }
        return path.substring(index + 1);
    }

    public boolean isLoad() {
        return isLoad;
    }

    public final void startLoad() {
        if (isLoad) {
            return;
        }
        try {
            isLoad = true;
            // 这里原来就这样写的，我没改逻辑，保持两次调用 initOnce()
            initOnce();
            if (initOnce()) {
                entry(HybridClassLoader.getHostClassLoader());
            }
        } catch (Throwable e) {
            XposedBridge.log(e);
            ExceptionFactory.add(this, e);
        }
    }

    /**
     * 在loadHook前执行一次 返回true表示继续执行loadHook
     * 如果返回false 表示由initOnce自行处理loadHook事件
     */
    public boolean initOnce() {
        return true;
    }

    public abstract void entry(@NonNull ClassLoader classLoader) throws Throwable;

    /**
     * 标准hook方法执行前
     */
    protected XC_MethodHook.Unhook hookBefore(Member method, HookAction action) {
        // ★ 防止 method 为空时崩溃：Only methods and constructors can be hooked: null
        if (method == null) {
            XposedBridge.log("[ONO-HOOK] hookBefore: method is null, skip. item=" + getSimpleName());
            return null;
        }

        return XposedBridge.hookMethod(method, new XC_MethodHook(
                ConfigManager.dGetInt(PrekCfgXXX + "hook_priority", 50)) {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                tryExecute(param, action);
            }
        });
    }

    protected XC_MethodHook.Unhook hookBefore(Class<?> clazz, HookAction action, Object... parameterTypesAndCallback) {
        Constructor<?> m = findConstructorExact(clazz, getParameterClasses(clazz.getClassLoader(), parameterTypesAndCallback));

        return XposedBridge.hookMethod(m, new XC_MethodHook(
                ConfigManager.dGetInt(PrekCfgXXX + "hook_priority", 50)) {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                tryExecute(param, action);
            }
        });
    }


    /**
     * 标准hook方法执行后
     */
    protected XC_MethodHook.Unhook hookAfter(Member method, HookAction action) {
        // ★ 防止 method 为空
        if (method == null) {
            XposedBridge.log("[ONO-HOOK] hookAfter: method is null, skip. item=" + getSimpleName());
            return null;
        }

        return XposedBridge.hookMethod(method, new XC_MethodHook(
                ConfigManager.dGetInt(PrekCfgXXX + "hook_priority", 50)) {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                tryExecute(param, action);
            }
        });
    }

    protected XC_MethodHook.Unhook hookAfter(Class<?> clazz, HookAction action, Object... parameterTypesAndCallback) {
        Constructor<?> m = findConstructorExact(clazz, getParameterClasses(clazz.getClassLoader(), parameterTypesAndCallback));

        return XposedBridge.hookMethod(m, new XC_MethodHook(
                ConfigManager.dGetInt(PrekCfgXXX + "hook_priority", 50)) {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                tryExecute(param, action);
            }
        });
    }

    /**
     * 带执行优先级的 hook
     *
     * @param priority 越高 执行优先级越高 默认50
     */
    protected XC_MethodHook.Unhook hookAfter(Member method, HookAction action, int priority) {
        // ★ 防止 method 为空
        if (method == null) {
            XposedBridge.log("[ONO-HOOK] hookAfter(p): method is null, skip. item=" + getSimpleName());
            return null;
        }

        return XposedBridge.hookMethod(method, new XC_MethodHook(priority) {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                tryExecute(param, action);
            }
        });
    }

    /**
     * 跟上面那个一样
     */
    protected XC_MethodHook.Unhook hookBefore(Member method, HookAction action, int priority) {
        // ★ 防止 method 为空
        if (method == null) {
            XposedBridge.log("[ONO-HOOK] hookBefore(p): method is null, skip. item=" + getSimpleName());
            return null;
        }

        return XposedBridge.hookMethod(method, new XC_MethodHook(priority) {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                tryExecute(param, action);
            }
        });
    }

    /**
     * 真正执行接口方法的地方 ，这么写可以很便捷的捕获异常和子类重写
     */
    protected void tryExecute(XC_MethodHook.MethodHookParam param, HookAction hookAction) {
        try {
            hookAction.call(param);
        } catch (Throwable throwable) {
            ExceptionFactory.add(this, throwable);
        }
    }

    private static Class<?>[] getParameterClasses(ClassLoader classLoader, Object[] parameterTypesAndCallback) {
        Class<?>[] parameterClasses = null;
        for (int i = parameterTypesAndCallback.length - 1; i >= 0; i--) {
            Object type = parameterTypesAndCallback[i];
            if (type == null)
                throw new NullPointerException("parameter type must not be null");

            // ignore trailing callback
            if (type instanceof XC_MethodHook)
                continue;

            if (parameterClasses == null)
                parameterClasses = new Class<?>[i + 1];

            if (type instanceof Class)
                parameterClasses[i] = (Class<?>) type;
            else if (type instanceof String)
                parameterClasses[i] = findClass((String) type, classLoader);
            else
                throw new IllegalArgumentException("parameter type must either be specified as Class or String", null);
        }

        // if there are no arguments for the method
        if (parameterClasses == null)
            parameterClasses = new Class<?>[0];

        return parameterClasses;
    }


    /**
     * hook 动作 指代
     * new XC_MethodHook() {
     * protected void beforeHookedMethod(MethodHookParam param) {
     * action.call(param);
     * }
     * }
     */
    protected interface HookAction {
        void call(XC_MethodHook.MethodHookParam param) throws Throwable;
    }

}