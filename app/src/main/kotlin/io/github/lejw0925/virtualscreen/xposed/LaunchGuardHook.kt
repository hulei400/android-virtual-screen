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
            // Find startActivity by scanning declared methods for one that
            // takes an Intent, instead of pinning the exact 11-arg signature.
            // The exact form needs hidden parameter types resolved by name
            // (IApplicationThread, ProfilerInfo), and ProfilerInfo does not
            // resolve on ColorOS 16 (ClassNotFoundException:
            // android$os$ProfilerInfo — that was the first live-test failure:
            // the hook never installed). Prefer the overload with the most
            // parameters (the full AIDL implementation); fall back to any
            // overload carrying an Intent.
            var target: java.lang.reflect.Method? = null
            var intentIndex = -1
            for (m in atmsClass.declaredMethods) {
                if (m.name != "startActivity") continue
                val params = m.parameterTypes
                var idx = -1
                for (i in params.indices) {
                    if (params[i] == Intent::class.java) {
                        idx = i
                        break
                    }
                }
                if (idx < 0) continue
                if (target == null || params.size > target.parameterTypes.size) {
                    target = m
                    intentIndex = idx
                }
            }
            if (target == null || intentIndex < 0) {
                XposedBridge.log(
                    "[LaunchGuard] ATMS.startActivity with Intent not found",
                )
                return
            }
            XposedBridge.hookMethod(target, LaunchGuardCallback(intentIndex))
            XposedBridge.log(
                "[LaunchGuard] hooked ATMS.startActivity " +
                    "(intent arg $intentIndex, ${target.parameterTypes.size} params)",
            )
        } catch (t: Throwable) {
            // A missed hook means the feature is silently off, never a crash.
            XposedBridge.log("[LaunchGuard] hook install failed")
            XposedBridge.log(t)
        }
    }

    private class LaunchGuardCallback(private val intentIndex: Int) : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            try {
                val intent = param.args[intentIndex] as? Intent ?: return
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
         *
         * Fast path: when no non-primary display exists at all (the usual
         * case when Muse is not connected and no virtual display is up),
         * return false immediately without enumerating tasks. Enumerating
         * every running task on every launcher tap is what made desktop
         * launches stutter when nothing virtual was even running.
         */
        private fun hasTaskOnOtherDisplay(targetPackage: String): Boolean {
            if (!hasNonPrimaryDisplay()) return false
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
                    if (base.packageName != targetPackage) continue
                    val displayId = getDisplayId(task) ?: continue
                    if (displayId != Display.DEFAULT_DISPLAY) {
                        return true
                    }
                }
                false
            } catch (t: Throwable) {
                XposedBridge.log(t)
                false
            }
        }

        /**
         * True when any display other than the primary (display 0) exists.
         * Uses DisplayManagerGlobal (a cheap in-process call in
         * system_server) instead of enumerating tasks. Any failure returns
         * true (fail open to the full task check, never skip the guard on
         * a lookup error).
         *
         * Note: #80 tried ServiceManager.getService("display") +
         * BinderService.getDisplayIds, and #81 tried
         * IDisplayManager.Stub.asInterface + IDisplayManager.getDisplayIds
         * — both throw NoSuchMethodException on ColorOS 16 (the methods
         * simply do not exist there), so the fast path never ran and every
         * launcher tap fell back to the expensive task enumeration.
         * DisplayManagerGlobal.getDisplayIds is the only one of the three
         * that exists on this system. Its cache can briefly list a
         * just-destroyed virtual display (the first launch after closing
         * the screen may still stutter once), which is far better than
         * stuttering on every launch.
         */
        private fun hasNonPrimaryDisplay(): Boolean {
            return try {
                val dmgClass = Class.forName("android.hardware.display.DisplayManagerGlobal")
                val dmg = dmgClass.getMethod("getInstance").invoke(null) ?: return true
                val ids = dmgClass.getMethod("getDisplayIds").invoke(dmg) as? IntArray ?: return true
                // Only our own virtual display counts. Other apps keep
                // their own virtual displays alive permanently (verified
                // 2026-11-12: Muse's headless browser holds displays
                // 6/7/8, "local-browser-headless"); counting them would
                // run the expensive task scan on every launcher tap.
                // Our display is created with the name
                // "android-virtual-screen".
                val getDisplayInfo = dmgClass.getMethod(
                    "getDisplayInfo",
                    Int::class.javaPrimitiveType,
                )
                for (id in ids) {
                    if (id == Display.DEFAULT_DISPLAY) continue
                    val info = getDisplayInfo.invoke(dmg, id) ?: continue
                    val name = info.javaClass.getField("name").get(info) as? String ?: continue
                    if (name.contains("android-virtual-screen")) return true
                }
                false
            } catch (t: Throwable) {
                XposedBridge.log(t)
                true
            }
        }

        /**
         * Task display id via reflection: TaskInfo.getDisplayId() is a
         * hidden framework API, so it does not resolve against the SDK at
         * compile time (that was the #50 CI failure). The method is public
         * in the framework, so reflection finds it at runtime. Returns null
         * on any failure (fail open).
         */
        private fun getDisplayId(task: ActivityManager.RunningTaskInfo): Int? {
            return try {
                val method = displayIdMethod ?: return null
                method.invoke(task) as? Int
            } catch (t: Throwable) {
                XposedBridge.log(t)
                null
            }
        }

        /** Cached reflective lookup of TaskInfo.getDisplayId(). */
        private val displayIdMethod: java.lang.reflect.Method? by lazy {
            try {
                ActivityManager.RunningTaskInfo::class.java
                    .getMethod("getDisplayId")
            } catch (t: Throwable) {
                XposedBridge.log(t)
                null
            }
        }
    }
}
