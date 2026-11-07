package io.github.lejw0925.virtualscreen.backend

import android.os.IBinder
import android.os.Process
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * IME bridge, part 1 (idea borrowed from Eta 3.4.0-beta.30, re-implemented
 * from the observed behaviour, no Eta code copied): let the system keyboard
 * work with the virtual display.
 *
 * Android only shows a soft keyboard on a display whose IME policy allows
 * it, and the default for a freshly created virtual display is not reliable
 * across ROMs. So set it explicitly to LOCAL (1) once, right after the
 * display is created. `setDisplayImePolicy` is a hidden IWindowManager API
 * restricted to system/shell callers, so it runs in a tiny helper process as
 * the shell user (uid 2000) via app_process — the same route ShellTextInput
 * already uses for clipboard paste.
 */
internal object DisplayImePolicy {
    private const val MARKER = "IME_POLICY:"
    private const val POLICY_LOCAL = 1

    /** Best-effort: returns false instead of throwing when the policy cannot be set. */
    fun apply(displayId: Int, sourceApk: String): Boolean {
        require(displayId > 0) { "INVALID_DISPLAY" }
        return runCatching {
            val quotedApk = "'" + sourceApk.replace("'", "'\\''") + "'"
            val mode = detectSuCommandMode(uid = "2000", timeoutMillis = 5000)
            val process = ProcessBuilder(*mode.arguments(
                "CLASSPATH=$quotedApk app_process /system/bin ${DisplayImePolicy::class.java.name}", uid = "2000"))
                .redirectErrorStream(true).start()
            try {
                process.outputStream.bufferedWriter().use { input ->
                    input.write(JSONObject().put("displayId", displayId).put("policy", POLICY_LOCAL).toString())
                }
                var result: JSONObject? = null
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line -> if (line.startsWith(MARKER)) result = JSONObject(line.removePrefix(MARKER)) }
                }
                check(process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0) { "IME_POLICY_UNAVAILABLE" }
                result?.optBoolean("ok") == true
            } finally {
                process.destroy()
            }
        }.getOrDefault(false)
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val result = try {
            check(Process.myUid() == 2000) { "SHELL_IDENTITY_REQUIRED" }
            val bytes = System.`in`.readNBytes(4097)
            val request = JSONObject(bytes.toString(Charsets.UTF_8))
            val displayId = request.getInt("displayId")
            require(displayId > 0) { "INVALID_DISPLAY" }
            val policy = request.optInt("policy", POLICY_LOCAL)
            val windowService = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java).invoke(null, "window") as IBinder
            val iwm = Class.forName("android.view.IWindowManager\$Stub")
                .getMethod("asInterface", IBinder::class.java).invoke(null, windowService)
            iwm.javaClass.getMethod("setDisplayImePolicy", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(iwm, displayId, policy)
            JSONObject().put("ok", true).put("display_id", displayId).put("policy", policy)
        } catch (error: Exception) {
            JSONObject().put("ok", false).put("code", "IME_POLICY_UNAVAILABLE")
                .put("error_type", (error.cause ?: error).javaClass.simpleName)
        }
        System.out.println(MARKER + result)
        System.out.flush()
        kotlin.system.exitProcess(0)
    }
}
