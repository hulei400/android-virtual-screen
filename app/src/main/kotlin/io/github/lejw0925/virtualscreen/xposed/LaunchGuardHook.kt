package io.github.lejw0925.virtualscreen.xposed

import android.app.ActivityManager
import android.content.Intent
import android.view.Display
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * LSPosed launch guard, loaded into system_server only.
 *
 * When the user taps an app icon on the phone while that app already has a
 * task on the virtual display, a plain launcher launch resets the virtual
 * instance (it refreshes back to its home page and loses its state). This
 * hook rewrites such a launch to force a brand-new task on the primary
 * display instead, the same behaviour as the app's own "launch on phone"
 * path, which was verified live not to disturb the virtual instance.
 *
 * The hook runs inside system_server, so every line is defensive: any doubt
 * fails open (the launch proceeds exactly as stock) and nothing ever
 * throws out of a hook callback.
 */
class LaunchGuardHook : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            // system_server's package name is literally "android".
            // The manager scope is also set to System Framework only; this
            // guard is defence-in-depth in case the scope is widened.
            if (lpparam.packageName != "android") return
            installHook(lpparam)
        } catch (t: Throwable) {
            XposedBridge.log(t)
        }
    }

    private fun installHook(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val classLoader = lpparam.classLoader
            val atmsClass = XposedHelpers.findClass(
                "com.android.server.wm.ActivityTaskManagerService",
                classLoader,
            )
            // IApplicationThread and ProfilerInfo are hidden framework classes;
            // resolve them by name instead of importing them.
            val iApplicationThread = XposedHelpers.findClass(
                "android.app.IApplicationThread",
                classLoader,
            )
            val profilerInfo = XposedHelpers.findClass(
                "android.os.ProfilerInfo",
                classLoader,
            )
            XposedHelpers.findAndHookMethod(
                atmsClass,
                "startActivity",
                iApplicationThread,
                String::class.java, // callingPackage
                String::class.java, // callingFeatureId
                Intent::class.java, // intent (args[3])
                String::class.java, // resolvedType
                android.os.IBinder::class.java, // resultTo
                String::class.java, // resultWho
                Int::class.javaPrimitiveType, // requestCode
                Int::class.javaPrimitiveType, // startFlags
                profilerInfo,
                android.os.Bundle::class.java, // bOptions
                LaunchGuardCallback(),
            )
            XposedBridge.log("[LaunchGuard] hooked ATMS.startActivity")
        } catch (t: Throwable) {
            // A missed hook means the feature is silently off, never a crash.
            XposedBridge.log("[LaunchGuard] hook install failed")
            XposedBridge.log(t)
        }
    }

    private class LaunchGuardCallback : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            try {
                val intent = param.args[3] as? Intent ?: return
                // Only guard launcher taps. The agent's own launches use
                // `am start --display <virtualId> -n <component>` with no
                // LAUNCHER category, so they are never rewritten here.
                if (intent.action != Intent.ACTION_MAIN) return
                if (!intent.hasCategory(Intent.CATEGORY_LAUNCHER)) return
                val targetPackage = intent.component?.packageName ?: return
                if (!hasTaskOnOtherDisplay(targetPackage)) return
                intent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_MULTIPLE_TASK,
                )
                XposedBridge.log(
                    "[LaunchGuard] redirected $targetPackage " +
                        "to a new task on the primary display",
                )
            } catch (t: Throwable) {
                XposedBridge.log(t)
            }
        }
    }

    companion object {
        /**
         * True when [targetPackage] has a task on any display other than the
         * primary display (display 0). Called from the ATMS.startActivity
         * before-hook, before ActivityStarter acquires its global lock, so
         * the Binder round-trip cannot deadlock. Any failure returns false
         * (fail open: the launch proceeds as stock).
         */
        private fun hasTaskOnOtherDisplay(targetPackage: String): Boolean {
            return try {
                // ActivityTaskManager is a hidden framework API; reach it
                // by reflection so the code still compiles against the SDK.
                val atmClass = Class.forName("android.app.ActivityTaskManager")
                val getInstance = atmClass.getMethod("getInstance")
                val atm = getInstance.invoke(null) ?: return false
                val getTasks = atmClass.getMethod(
                    "getTasks",
                    Int::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                )
                @Suppress("UNCHECKED_CAST")
                val tasks = getTasks.invoke(
                    atm,
                    Int.MAX_VALUE,
                    false,
                    false,
                    Display.INVALID_DISPLAY,
                ) as? List<ActivityManager.RunningTaskInfo> ?: return false
                for (task in tasks) {
                    val base = task.baseActivity ?: continue
                    if (base.packageName == targetPackage &&
                        task.displayId != Display.DEFAULT_DISPLAY
                    ) {
                        return true
                    }
                }
                false
            } catch (t: Throwable) {
                XposedBridge.log(t)
                false
            }
        }
    }
}
