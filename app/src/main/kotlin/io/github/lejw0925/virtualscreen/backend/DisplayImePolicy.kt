package io.github.lejw0925.virtualscreen.backend

import android.os.IBinder
import android.os.Process
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * IME bridge, part 1 (idea borrowed from Eta 3.4.0-beta.30, re-implemented
 * from the observed behaviour, no Eta code copied): let the system keyboard
 * work with the virtual display.
 *
 * The keyboard is set to FALLBACK (0): when a field on the virtual display
 * is focused, the IME shows on the phone's own display instead of inside
 * the virtual display's mirror. LOCAL (1) was tried first (#14/#15) but the
 * keyboard never appeared in the mirror — the mirror does not show the IME
 * window — so the keyboard now pops up outside the small window, full size,
 * where the user can actually type on it.
 *
 * Runs in a tiny helper process via app_process — the same route
 * ShellTextInput already uses — because `setDisplayImePolicy` is a hidden
 * IWindowManager API restricted to privileged callers. The helper runs as
 * root (uid 0), not shell (uid 2000): root is the proven su path on this
 * phone (the whole Root backend runs on it), while `su 2000` is unproven
 * here — the first build (#14) used shell and the keyboard still did not
 * appear after a takeover, so the policy call most likely never ran.
 */
internal object DisplayImePolicy {
    private const val TAG = "DisplayImePolicy"
    private const val MARKER = "IME_POLICY:"
    private const val POLICY_FALLBACK = 0

    /** Best-effort: retries once (the display may not be ready yet), returns false instead of throwing. */
    fun apply(displayId: Int, sourceApk: String): Boolean {
        require(displayId > 0) { "INVALID_DISPLAY" }
        repeat(2) { attempt ->
            runCatching { applyOnce(displayId, sourceApk) }.onSuccess { ok ->
                if (ok) return true
            }.onFailure { error ->
                Log.w(TAG, "apply failed (attempt ${attempt + 1}): ${error.message}")
            }
            if (attempt == 0) Thread.sleep(300)
        }
        return false
    }

    private fun applyOnce(displayId: Int, sourceApk: String): Boolean {
        val quotedApk = "'" + sourceApk.replace("'", "'\\''") + "'"
        val mode = detectSuCommandMode(uid = "0", timeoutMillis = 5000)
        val process = ProcessBuilder(*mode.arguments(
            "CLASSPATH=$quotedApk app_process /system/bin ${DisplayImePolicy::class.java.name}", uid = "0"))
            .redirectErrorStream(true).start()
        try {
            process.outputStream.bufferedWriter().use { input ->
                input.write(JSONObject().put("displayId", displayId).put("policy", POLICY_FALLBACK).toString())
            }
            var result: JSONObject? = null
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line -> if (line.startsWith(MARKER)) result = JSONObject(line.removePrefix(MARKER)) }
            }
            check(process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0) { "IME_POLICY_UNAVAILABLE" }
            return result?.optBoolean("ok") == true
        } finally {
            process.destroy()
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val result = try {
            // Root (0) is the path the caller uses; shell (2000) is accepted
            // too so the same helper also works from Eta-style callers.
            check(Process.myUid() == 0 || Process.myUid() == 2000) { "PRIVILEGED_IDENTITY_REQUIRED" }
            val bytes = System.`in`.readNBytes(4097)
            val request = JSONObject(bytes.toString(Charsets.UTF_8))
            val displayId = request.getInt("displayId")
            require(displayId > 0) { "INVALID_DISPLAY" }
            val policy = request.optInt("policy", POLICY_FALLBACK)
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
