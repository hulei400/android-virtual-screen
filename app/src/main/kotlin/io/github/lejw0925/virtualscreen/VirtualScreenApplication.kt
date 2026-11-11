package io.github.lejw0925.virtualscreen

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import io.github.lejw0925.virtualscreen.core.code
import io.github.lejw0925.virtualscreen.core.ActivityLog
import io.github.lejw0925.virtualscreen.core.ActivityLogEntry
import io.github.lejw0925.virtualscreen.core.RuntimeDiagnostics
import io.github.lejw0925.virtualscreen.core.failure
import io.github.lejw0925.virtualscreen.core.runtime
import io.github.lejw0925.virtualscreen.core.VirtualScreenSessionManager
import io.github.lejw0925.virtualscreen.mcp.McpServerController
import io.github.lejw0925.virtualscreen.tunnel.TunnelManager
import io.github.lejw0925.virtualscreen.tunnel.TunnelMode
import io.github.lejw0925.virtualscreen.ui.FloatViewer
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class VirtualScreenApplication : Application() {
    private val runtimeScope = CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val runtimeMutex = Mutex()
    @Volatile private var shutdownJob: Job? = null
    private var startupJob: Job? = null
    private val mutableMcpChanging = MutableStateFlow(false)
    val mcpChanging = mutableMcpChanging.asStateFlow()
    private val mutableRuntimeTasks = MutableStateFlow(0)
    val runtimeTasks = mutableRuntimeTasks.asStateFlow()
    suspend fun <T> runtimeWork(block: suspend () -> T): T {
        mutableRuntimeTasks.update { it + 1 }
        try { return block() }
        catch (failure: Exception) { activityLog.failure("runtime_task", failure); throw failure }
        finally { mutableRuntimeTasks.update { it - 1 } }
    }
    val mcpWanted: Boolean get() = getSharedPreferences("runtime",MODE_PRIVATE).getBoolean("mcpEnabled",false)
    /** Tunnel mode the user last connected with; restored whenever MCP starts. Stops initiated by MCP shutdown keep it. */
    var tunnelWanted: TunnelMode
        get() = getSharedPreferences("runtime",MODE_PRIVATE).getString("tunnelMode",null)?.let { runCatching { TunnelMode.valueOf(it) }.getOrNull() } ?: TunnelMode.OFF
        set(mode) { getSharedPreferences("runtime",MODE_PRIVATE).edit().putString("tunnelMode",mode.name).apply() }
    private fun rememberMcp(enabled: Boolean) { getSharedPreferences("runtime",MODE_PRIVATE).edit().putBoolean("mcpEnabled",enabled).commit() }
    fun setMcpEnabled(enabled: Boolean) {
        activityLog.runtime("mcp_switch", if (enabled) "enable_requested" else "disable_requested")
        rememberMcp(enabled)
        if(enabled) {
            startForegroundService(Intent(this,VirtualScreenForegroundService::class.java))
            restoreMcp()
        } else {
            mutableMcpChanging.value = true
            runtimeScope.launch {
                try { runtimeMutex.withLock { tunnels.stop(); mcp.stop() } }
                finally { mutableMcpChanging.value = false }
            }
        }
    }
    /** Runs in the application scope so navigating away cannot cancel server startup. */
    @Synchronized fun restoreMcp() {
        if(!mcpWanted || startupJob?.isActive == true) return
        mutableMcpChanging.value = true
        startupJob = runtimeScope.launch {
            activityLog.runtime("mcp_restore", "starting", "backend=${settings.flow.value.backend}")
            try {
                awaitShutdown()
                runtimeMutex.withLock {
                    if(!mcpWanted) return@withLock
                    sessions.initializeBackend(settings.flow.value.backend)
                    val capabilities = sessions.ensureAccessibility()
                    check(capabilities.displayCreation != io.github.lejw0925.virtualscreen.backend.CapabilityStatus.REQUIRES_AUTHORIZATION) {
                        if(settings.flow.value.backend == io.github.lejw0925.virtualscreen.backend.BackendKind.ROOT) "ROOT_PERMISSION_REQUIRED" else "SHIZUKU_PERMISSION_REQUIRED"
                    }
                    mcp.start(settings.flow.value.lan).getOrThrow()
                    sessions.clearInactiveDisplayError()
                    val wantedTunnel = tunnelWanted
                    if(wantedTunnel != TunnelMode.OFF && !tunnels.state().running) {
                        val (frpc, cloudflare) = tunnels.loadProfile()
                        runCatching { tunnels.start(wantedTunnel, frpc, cloudflare) }
                            .onFailure { activityLog.failure("tunnel_restore", it) }
                    }
                }
            } catch(c: CancellationException) { throw c }
              catch(e: Exception) { activityLog.failure("mcp_restore", e); rememberMcp(false); mcp.reportStartupError(e.code()) }
            finally { mutableMcpChanging.value = false }
        }
    }
    suspend fun awaitShutdown() { shutdownJob?.join() }
    @Synchronized fun stopRuntime(preserveMcp: Boolean = false) {
        if(!preserveMcp) rememberMcp(false)
        if(shutdownJob?.isActive == true) return
        shutdownJob = runtimeScope.launch { runtimeMutex.withLock {
            activityLog.runtime("runtime", "stopping", "preserve_mcp=$preserveMcp")
            runCatching { tunnels.stop() }.onFailure { activityLog.failure("tunnel_stop", it) }
            runCatching { mcp.stop() }.onFailure { activityLog.failure("mcp_stop", it) }
            runCatching { sessions.shutdown() }.onFailure { activityLog.failure("screen_shutdown", it) }
            activityLog.runtime("runtime", "stopped")
        } }
    }
    lateinit var activityLog: ActivityLog
        private set
    lateinit var sessions: VirtualScreenSessionManager
        private set
    lateinit var mcp: McpServerController
        private set
    lateinit var tunnels: TunnelManager
        private set

    lateinit var settings: io.github.lejw0925.virtualscreen.core.AppSettings
        private set
    internal val floatViewer by lazy { io.github.lejw0925.virtualscreen.ui.FloatViewer(this) }

    /** The activity currently in front, if any — used to send the fullscreen
     *  viewer to the background when the float window auto-shows on connect. */
    @Volatile private var resumedActivity: Activity? = null
    /** When the current display appeared (epoch ms). MainActivity uses it to
     *  tell an auto-launched viewer (display just created) apart from the
     *  user deliberately opening the app: only the latter hides the float
     *  window. Without this, the auto-launched viewer's onResume hides the
     *  float window the auto-show just popped, every single time. */
    @Volatile var displayAppearedAt: Long = 0L
        private set
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        activityLog = ActivityLog(this)
        RuntimeDiagnostics(this, activityLog).install()
        org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("L")
        settings = io.github.lejw0925.virtualscreen.core.AppSettings(this)
        sessions = VirtualScreenSessionManager(this,configuredDisplay={ settings.configForViewer() }, log=activityLog,
            autoApproveRestart={ settings.flow.value.autoApproveRestart })
        mcp = McpServerController(this, sessions, log=activityLog)
        tunnels = TunnelManager(this, log=activityLog)
        rikka.shizuku.Shizuku.addRequestPermissionResultListener { requestCode, result ->
            if (requestCode == 8732) activityLog.runtime("shizuku_permission",
                if (result == PackageManager.PERMISSION_GRANTED) "granted" else "denied")
            if(requestCode == 8732 && result == PackageManager.PERMISSION_GRANTED) runtimeScope.launch {
                runCatching { sessions.ensureAccessibility() }.onFailure { activityLog.failure("accessibility_enable", it) }
            }
        }
        // A connection creates the display: pop the float window by default
        // (PiP is off) so the user can watch without opening the app. If the
        // fullscreen viewer happens to be in front, send it to the background —
        // the float window is the default view on connect, not the viewer.
        // Skipped when the overlay permission has not been granted yet.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) { resumedActivity = activity }
            override fun onActivityPaused(activity: Activity) { if (resumedActivity === activity) resumedActivity = null }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
        runtimeScope.launch {
            var lastDisplayId: Int? = null
            var lastHeadless: Boolean? = null
            var retryJob: Job? = null
            sessions.state.collect { s ->
                val id = s.displayId
                val headless = s.config.headless
                val newDisplay = id != null && id != lastDisplayId
                // A headless session turned visible (launching an app clears
                // headless): show the float window for the live display.
                val becameVisible = id != null && lastHeadless == true && !headless
                if (newDisplay || becameVisible) {
                    if (headless) {
                        // Headless display: command-only work, do not pop up
                        // the float window. Suppress auto-show so a later
                        // state emission doesn't bring it up.
                        retryJob?.cancel()
                        floatViewer.suppressAutoShow()
                    } else {
                        // New display: the float window is the default view.
                        displayAppearedAt = System.currentTimeMillis()
                        // A single show() can be lost (a racing hide from the
                        // viewer resuming, a main-thread timing slip), so retry
                        // for a few seconds. The retry stops as soon as the
                        // window is up, or as soon as the user deliberately goes
                        // fullscreen (that sets the suppression flag, so the loop
                        // never fights the user's choice).
                        floatViewer.clearAutoShowSuppression()
                        retryJob?.cancel()
                        retryJob = launch {
                            repeat(6) {
                                if (floatViewer.isShowing || floatViewer.autoShowSuppressed) {
                                    maybeMoveViewerToBackground()
                                    return@launch
                                }
                                if (sessions.state.value.displayId == null) return@launch
                                if (FloatViewer.canDrawOverlays(this@VirtualScreenApplication)) {
                                    runCatching { floatViewer.show() }
                                }
                                delay(700)
                            }
                            if (floatViewer.isShowing) maybeMoveViewerToBackground()
                        }
                    }
                }
                lastDisplayId = id
                lastHeadless = headless
            }
        }
    }

    /** Send the fullscreen viewer to the background, but only when the float
     *  window is actually showing — never yank the user out of a viewer they
     *  opened deliberately. */
    private fun maybeMoveViewerToBackground() {
        val activity = resumedActivity
        if (activity is io.github.lejw0925.virtualscreen.ui.MainActivity && floatViewer.isShowing) {
            mainHandler.post { runCatching { activity.moveTaskToBack(true) } }
        }
    }

    fun diagnosticReport(entries: List<ActivityLogEntry>): String = buildString {
        val screen = sessions.state.value
        val server = mcp.state()
        val tunnel = tunnels.state()
        appendLine("Virtual Screen diagnostic report · ${java.time.Instant.now()}")
        appendLine(RuntimeDiagnostics.environment(this@VirtualScreenApplication))
        appendLine("backend=${screen.backend}; state=${screen.status}; display=${screen.displayId}; error=${screen.errorCode}")
        appendLine("display_config=${screen.config.width}x${screen.config.height}@${screen.config.density}; preview=${screen.previewStatus}")
        appendLine("capabilities: display=${screen.capabilities.displayCreation}; capture=${screen.capabilities.frameCapture}; input=${screen.capabilities.inputInjection}; nodes=${screen.capabilities.accessibilityNodes}")
        appendLine("mcp_wanted=$mcpWanted; mcp_running=${server.running}; mcp_error=${server.lastError}; clients=${server.sessionCount}")
        appendLine("tunnel=${tunnel.mode}; phase=${tunnel.phase}; tunnel_error=${tunnel.errorCode}")
        appendLine("uptime_ms=${android.os.SystemClock.uptimeMillis()}; log_storage_error=${activityLog.writeError.value}")
        appendLine()
        entries.forEach { appendLine(it.text()) }
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        activityLog.runtime("memory", if (level == TRIM_MEMORY_UI_HIDDEN) "background" else "warning", "level=$level")
    }
}
