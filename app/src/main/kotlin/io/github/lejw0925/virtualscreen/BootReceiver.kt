package io.github.lejw0925.virtualscreen

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Starts the foreground service on boot so the MCP server (and its tunnel)
 * come back up after a phone restart without the user having to open the
 * app first. The service's onCreate calls restoreMcp(), which only starts
 * the MCP server when the user had it enabled (mcpWanted), so a boot start
 * never turns MCP on for someone who had it off.
 *
 * Note: ColorOS may still require the app to be allowed to auto-start in
 * system settings (Settings > Apps > Virtual Screen > Auto-start); the
 * receiver alone cannot override that.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        try {
            context.startForegroundService(
                Intent(context, VirtualScreenForegroundService::class.java),
            )
        } catch (t: Throwable) {
            // A missed boot start just means the user opens the app once,
            // the same as before this receiver existed. Never crash here.
        }
    }
}
