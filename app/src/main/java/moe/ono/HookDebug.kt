package moe.ono

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

class HookDebug : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 只处理 QQ
        if (lpparam.packageName != "com.tencent.mobileqq") return

        XposedBridge.log("HookDebug: QQ loaded, start debug...")

        // 用 RefUtil 打印 NT 的 MsgRecord 类结构
        try {
            RefUtil.logClassInfo(
                "com.tencent.qqnt.kernel.nativeinterface.MsgRecord",
                lpparam.classLoader
            )
        } catch (t: Throwable) {
            XposedBridge.log("HookDebug: logClassInfo error: $t")
        }
    }
}