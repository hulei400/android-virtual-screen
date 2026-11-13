package io.github.lejw0925.virtualscreen.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import io.github.lejw0925.virtualscreen.VirtualScreenApplication
import io.github.lejw0925.virtualscreen.backend.BackendOperation
import io.github.lejw0925.virtualscreen.backend.LiveGeometry
import io.github.lejw0925.virtualscreen.core.ScreenAccessibilityService
import io.github.lejw0925.virtualscreen.core.SessionState
import io.github.lejw0925.virtualscreen.core.failure
import io.github.lejw0925.virtualscreen.core.runtime
import io.github.lejw0925.virtualscreen.mcp.ToolActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Edge-collapsing floating viewer for the virtual display.
 *
 * Idea borrowed from Oo0520/Eta-por (VirtualScreenFloatWindow: triggerBar, sinkToEdge,
 * collapsed state); this is an original implementation against this app's LiveDisplayView
 * and preview channel — no Eta code is copied.
 *
 * No buttons on the window at all (the close and takeover buttons were
 * removed — they got in the way of watching): the window always pops up
 * view-only at the very top-right corner, single-finger drag moves it,
 * dragging the bottom edge resizes it, dragging to a screen edge collapses
 * it to a narrow trigger bar (tap the bar, or drag it outward, to
 * expand back). Double-tap the top-left corner — where the takeover button
 * used to be — toggles control mode: content touches then go straight into
 * the app, same as the fullscreen viewer, and the phone vibrates once to
 * confirm the switch (one buzz for control on, two for view-only). When a
 * text field is focused in control mode, the phone keyboard pops up outside
 * the window and typing is committed to the field (keyboard bridge, below).
 *
 * The live SurfaceFlinger mirror serves one viewer at a time: while the float window is
 * shown it holds the mirror and the fullscreen viewer falls back to JPEG frames, then
 * re-attaches when the float window hides. Hiding never ends the display or the lease.
 */
internal class FloatViewer(private val app: VirtualScreenApplication) {
    companion object {
        fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)
        fun overlayPermissionIntent(context: Context): Intent =
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
        private const val SIDE_LEFT = 0
        private const val SIDE_RIGHT = 1
        /** #88: compact-menu item ids (long-press 收起). 接管 was removed
         *  in #88 fix 6 (user 2026-11-13) — the shrink circle's colour now
         *  shows takeover state instead of a separate button. */
        private const val MENU_APPS = 2
        private const val MENU_PIP = 3
        private const val MENU_SETTINGS = 4
        private const val MENU_END = 5
        /** #88 fix 5: toggle for showing/hiding the step status in the top bar. */
        private const val MENU_STEP = 6
        /** #88: double-tap window to expand (a single tap triggered too easily). */
        private const val DOUBLE_TAP_TIMEOUT_MS = 300L
    }
    private val windowManager = app.getSystemService(WindowManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val prefs = app.getSharedPreferences("float_viewer", Context.MODE_PRIVATE)
    private val density get() = app.resources.displayMetrics.density
    private val screenW get() = windowManager.currentWindowMetrics.bounds.width()
    private val screenH get() = windowManager.currentWindowMetrics.bounds.height()
    private val touchSlopPx get() = ViewConfiguration.get(app).scaledTouchSlop
    private val minWidthPx get() = (200f * density).roundToInt()
    private val maxWidthPx get() = (screenW - (16f * density).roundToInt()).coerceAtLeast(minWidthPx)
    private val maxHeightPx get() = (screenH - (32f * density).roundToInt()).coerceAtLeast(1)
    /** Bottom edge resize strip height: the frame below the content. A drag
     *  starting on this strip resizes the window; it never overlaps the app. */
    private val resizeEdgePx get() = (20f * density).roundToInt()
    private val edgePx get() = (8f * density).roundToInt()
    private val cornerPx get() = 16f * density
    private var root: FrameLayout? = null
    private var content: FrameLayout? = null
    private var liveView: LiveDisplayView? = null
    private var triggerBar: TextView? = null
    /** True while the user is directly operating the float window's content. */
    private var controlMode = false
    /** Published so the foreground service's notification can show the matching
     *  Fluid Cloud action (接管 / 只看) and rebuild when the mode flips. */
    private val _controlModeFlow = MutableStateFlow(false)
    val controlModeFlow: StateFlow<Boolean> = _controlModeFlow.asStateFlow()
    private var contentTouch = false
    /** Keyboard bridge (see [openImeBridge]): hidden sink the phone keyboard
     *  types into; its text is committed to the virtual display's focused
     *  field. */
    private var imeSink: EditText? = null
    private var imeBridgeOn = false
    private var imeShowJob: Job? = null
    private var imeCommitJob: Job? = null
    private var imeCheckJob: Job? = null
    // True while the sink is being set programmatically (pre-fill on open,
    // clear on close): the TextWatcher must not treat that as user typing
    // and commit it back to the field. Set/cleared synchronously around
    // setText, which fires the watcher on the same thread.
    private var suppressImeCommit = false
    private var params: WindowManager.LayoutParams? = null
    private var chromeTouch = false
    private var stateJob: Job? = null
    private var previewJob: Job? = null
    private var activityJob: Job? = null
    /** #88 fix 6: watches the top app; shrinks the fullscreen viewer back
     *  to the small window when the virtual display returns to the desktop
     *  (home/launcher). */
    private var homeJob: Job? = null
    /** #88 fix 7: the phone's own Home key/gesture never changes the
     *  virtual display's top app, so homeJob can't see it. This receiver
     *  listens for the system's home/recent-apps broadcast instead, so a
     *  fullscreen viewer shrinks back to the small window when the user
     *  leaves for the phone's own desktop (or Recents). Registered in
     *  show(), unregistered in hide(). */
    private var homeReceiver: BroadcastReceiver? = null
    private var live = false
    private var collapsed = false
    /** #87: fullscreen (expanded) state. The float window morphs into a
     *  fullscreen viewer with a top bar (step + purpose + shrink circle),
     *  like the reference video: double-tap the small window to expand
     *  (#88: a single tap triggered too easily), tap the shrink circle to
     *  shrink back, long-press it for a compact menu (#88). The bar has no
     *  background (#88 fix 6) and the circle's colour shows takeover state
     *  (light blue = in control). While expanded, drag/resize/collapse are
     *  off; the top bar is the only chrome. */
    private var expanded = false
    private var fsSavedX = 0; private var fsSavedY = 0; private var fsSavedW = 0; private var fsSavedH = 0
    private var topBar: LinearLayout? = null
    private var stepText: TextView? = null
    private var purposeText: TextView? = null
    /** #88 fix 6: the shrink circle (replaces the old 接管 button as the
     *  takeover indicator — light blue while in control mode). */
    private var shrinkCircle: TextView? = null
    private var expandAnimator: ValueAnimator? = null
    /** Current corner radius, animated 16dp -> 0 on expand and back on
     *  shrink. The root background drawable and the outline both read it.
     *  Set to cornerPx in buildViews. */
    private var cornerRadiusPx = 0f
    private var rootBackground: GradientDrawable? = null
    private var collapsedSide = SIDE_LEFT
    private var collapsedDuringDrag = false
    /** #88: uptime of the last tap that did not expand (first tap of a double-tap). */
    private var lastTapUpTime = 0L
    private var savedX = 0; private var savedY = 0; private var savedW = 0; private var savedH = 0
    private var downRawX = 0f; private var downRawY = 0f; private var downWinX = 0; private var downWinY = 0
    private var downWinH = 0
    private var moved = false; private var resizing = false
    private var triggerDownX = 0f; private var triggerDownY = 0f; private var triggerDownWinY = 0
    private var triggerMoved = false; private var triggerDragging = false
    private val configCallbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            val p = params ?: return
            p.x = p.x.coerceIn(0, (screenW - p.width).coerceAtLeast(0))
            p.y = p.y.coerceIn(0, (screenH - p.height).coerceAtLeast(0))
            updateLayout()
        }
        override fun onLowMemory() {}
    }
    private val sinkRunnable = Runnable {
        if (!collapsed) return@Runnable
        val p = params ?: return@Runnable
        val thin = (12f * density).roundToInt(); val tall = (72f * density).roundToInt()
        p.width = thin; p.height = tall
        p.x = if (collapsedSide == SIDE_LEFT) 0 else screenW - thin
        p.y = p.y.coerceIn(0, (screenH - tall).coerceAtLeast(0))
        updateLayout()
    }
    // The root intercepts every touch except ones starting on the trigger
    // bar, so window gestures always win and content touches never inject —
    // unless control mode is on, in which case content touches go to the app
    // and only chrome touches and bottom-edge resize drags still reach the
    // window.
    private fun isOnChrome(event: MotionEvent): Boolean {
        // #87: when expanded, the fullscreen top bar is chrome too — its
        // buttons (takeover / shrink) must receive touches, not the window
        // drag or the content.
        val targets = if (collapsed) listOfNotNull(triggerBar) else if (expanded) listOfNotNull(topBar) else emptyList<View>()
        return targets.any { view ->
            if (view.visibility != View.VISIBLE) return@any false
            val loc = IntArray(2); view.getLocationOnScreen(loc)
            event.rawX >= loc[0] && event.rawX < loc[0] + view.width &&
                event.rawY >= loc[1] && event.rawY < loc[1] + view.height
        }
    }
    private fun onWindowTouch(event: MotionEvent): Boolean {
        // #87: while expanded (fullscreen) the window does not move, resize
        // or collapse — the top bar's buttons are the only controls.
        if (expanded) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX; downRawY = event.rawY
                downWinX = params?.x ?: 0; downWinY = params?.y ?: 0
                downWinH = params?.height ?: 0
                moved = false
                resizing = onResizeEdge(event)
            }
            MotionEvent.ACTION_MOVE -> {
                if (resizing) {
                    // Bottom-edge drag resizes: height follows the finger,
                    // width follows the live frame aspect so no black bars
                    // appear. The top edge stays; the bottom edge follows
                    // the finger.
                    val p = params ?: return true
                    val aspect = currentAspect()
                    val dy = (event.rawY - downRawY).roundToInt()
                    var h = (downWinH + dy).coerceIn(1, maxHeightPx)
                    var w = (h * aspect).roundToInt()
                    if (w > maxWidthPx) { w = maxWidthPx; h = (w / aspect).roundToInt() }
                    if (w < minWidthPx) { w = minWidthPx; h = (w / aspect).roundToInt() }
                    // The height grows down from a fixed y: without this,
                    // growing the window pushes its bottom edge below the
                    // screen, where it can't be grabbed anymore.
                    val y = p.y.coerceIn(0, (screenH - h).coerceAtLeast(0))
                    if (w != p.width || h != p.height || y != p.y) { p.width = w; p.height = h; p.y = y; moved = true; updateLayout() }
                } else if (event.pointerCount == 1 && !collapsedDuringDrag) {
                    val dx = (event.rawX - downRawX).roundToInt(); val dy = (event.rawY - downRawY).roundToInt()
                    if (abs(dx) > touchSlopPx || abs(dy) > touchSlopPx) moved = true
                    if (moved) {
                        val p = params ?: return true
                        p.x = (downWinX + dx).coerceIn(0, (screenW - p.width).coerceAtLeast(0))
                        p.y = (downWinY + dy).coerceIn(0, (screenH - p.height).coerceAtLeast(0))
                        updateLayout()
                        // Only collapse mid-drag when the drag is pushing
                        // the window into the edge horizontally. A vertical
                        // drag along the edge used to collapse on the first
                        // move: the window already sits at p.x == 0 there,
                        // so the bare position check fired even though the
                        // user was only moving it up/down.
                        if (abs(dx) > abs(dy)) {
                            if (collapseIfAtEdge()) collapsedDuringDrag = true
                        }
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                val snapped = collapsedDuringDrag; collapsedDuringDrag = false
                if (resizing) {
                    resizing = false
                    saveSize()
                } else if (!moved) {
                    // #88: double-tap the small window to expand it to
                    // fullscreen. A single tap triggered too easily (a tap
                    // meant to focus the window, or a stray touch, blew it
                    // up to fullscreen). The first tap only arms the
                    // double-tap; the second tap within 300ms expands.
                    val now = SystemClock.uptimeMillis()
                    if (now - lastTapUpTime < DOUBLE_TAP_TIMEOUT_MS) {
                        lastTapUpTime = 0L
                        expandToFullscreen()
                    } else {
                        lastTapUpTime = now
                    }
                } else if (snapped) {
                    // Collapsed mid-drag: glue the bar to the edge at once. It
                    // used to keep following the finger after collapsing, so it
                    // sat a few dp off the edge until the 2s sink timer fired.
                    snapToEdge()
                } else {
                    val p = params ?: return true
                    // Only snap into the bar on release when the drag
                    // actually moved the window toward that edge. A window
                    // already sitting at the edge used to collapse on any
                    // release within 8dp — including a vertical drag along
                    // the edge, or a slight nudge inward.
                    val dx = (event.rawX - downRawX).roundToInt()
                    if (p.x <= edgePx && dx < -touchSlopPx) collapse(SIDE_LEFT)
                    else if (p.x + p.width >= screenW - edgePx && dx > touchSlopPx) collapse(SIDE_RIGHT)
                    else saveSize()
                }
            }
            MotionEvent.ACTION_CANCEL -> { collapsedDuringDrag = false; resizing = false }
        }
        return true
    }
    /** Bottom edge resize strip: a drag starting on the window's bottom edge
     *  resizes it. The strip is the frame below the content (see the bottom
     *  margin in [buildViews]), so it never overlaps the app. */
    private fun onResizeEdge(event: MotionEvent): Boolean {
        if (collapsed) return false
        val p = params ?: return false
        return event.rawY >= p.y + p.height - resizeEdgePx
    }
    private val triggerTouch = View.OnTouchListener { _, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                triggerDownX = event.rawX; triggerDownY = event.rawY
                triggerDownWinY = params?.y ?: 0
                triggerMoved = false; triggerDragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - triggerDownX
                val dy = event.rawY - triggerDownY
                val outward = if (collapsedSide == SIDE_LEFT) dx else -dx
                if (triggerDragging) {
                    // Already in vertical-drag mode: keep following the finger.
                    moveBarTo(triggerDownWinY + dy.roundToInt())
                } else if (!triggerMoved) {
                    if (outward > 24f * density) { expand(); triggerMoved = true }
                    else if (abs(dy) > touchSlopPx && abs(dy) > abs(dx)) {
                        // Vertical drag dominates: reposition the bar along
                        // the edge instead of expanding.
                        triggerDragging = true; triggerMoved = true
                        moveBarTo(triggerDownWinY + dy.roundToInt())
                    }
                }
            }
            MotionEvent.ACTION_UP -> { if (!triggerMoved) expand(); triggerDragging = false }
            MotionEvent.ACTION_CANCEL -> { triggerDragging = false }
        }
        true
    }
    /** Move the collapsed trigger bar vertically along its edge. The bar
     *  stays pinned to the edge (x unchanged); only y follows the finger.
     *  Also keeps savedY centred on the bar, so expanding restores the
     *  window at the bar's current height instead of jumping back to where
     *  it collapsed. */
    private fun moveBarTo(y: Int) {
        val p = params ?: return
        if (!collapsed) return
        p.y = y.coerceIn(0, (screenH - p.height).coerceAtLeast(0))
        savedY = (p.y + p.height / 2 - savedH / 2).coerceIn(0, (screenH - savedH).coerceAtLeast(0))
        updateLayout()
    }
    /** Set when the user deliberately closed the float window or went
     *  fullscreen: the auto-show must not pop it back. Cleared when a new
     *  display appears, or when the user leaves the viewer for Home. */
    var autoShowSuppressed = false
        private set
    fun suppressAutoShow() { autoShowSuppressed = true }
    fun clearAutoShowSuppression() { autoShowSuppressed = false }
    val isShowing: Boolean get() = root != null
    fun toggle() { if (root != null) hide() else show() }
    fun show(): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) { mainHandler.post { show() }; return true }
        if (root != null) return true
        if (autoShowSuppressed) return false
        if (!canDrawOverlays(app)) return false
        val state = app.sessions.state.value
        if (state.displayId == null) return false
        return try {
            buildViews(state)
            app.registerComponentCallbacks(configCallbacks)
            startLoops()
            // #88 fix 7: listen for the phone's Home / Recents. The
            // virtual display's top app doesn't change when the user
            // presses the phone's Home key, so homeJob alone never fires
            // for it — without this the fullscreen viewer keeps covering
            // the phone's desktop. Only shrinks while expanded; the small
            // window and the collapsed side bar stay as they are.
            homeReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action != Intent.ACTION_CLOSE_SYSTEM_DIALOGS) return
                    when (intent.getStringExtra("reason")) {
                        "homekey", "recentapps" -> if (expanded) shrinkToFloat()
                    }
                }
            }
            runCatching {
                app.registerReceiver(
                    homeReceiver,
                    IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS),
                    Context.RECEIVER_NOT_EXPORTED,
                )
            }
            true
        } catch (e: Exception) {
            app.activityLog.failure("float_viewer", e)
            hide()
            false
        }
    }
    fun hide() {
        if (Looper.myLooper() != Looper.getMainLooper()) { mainHandler.post { hide() }; return }
        closeImeBridge()
        setControlMode(false)
        expandAnimator?.cancel(); expandAnimator = null
        expanded = false
        stateJob?.cancel(); stateJob = null
        previewJob?.cancel(); previewJob = null
        activityJob?.cancel(); activityJob = null
        homeJob?.cancel(); homeJob = null
        homeReceiver?.let { runCatching { app.unregisterReceiver(it) } }
        homeReceiver = null
        imeCheckJob?.cancel(); imeCheckJob = null
        root?.removeCallbacks(sinkRunnable)
        runCatching { app.unregisterComponentCallbacks(configCallbacks) }
        liveView?.setResumed(false)
        liveView?.dispose()
        liveView = null; imeSink = null
        root?.let { runCatching { windowManager.removeView(it) } }
        root = null; content = null; triggerBar = null; topBar = null; stepText = null; purposeText = null; shrinkCircle = null; rootBackground = null; params = null
        collapsed = false; collapsedDuringDrag = false
    }

    // Control mode: the float window's content becomes directly operable
    // (taps and scrolls inject into the virtual display, same as the
    // fullscreen viewer). Off by default — the window always pops up
    // view-only. Turning it off cancels any in-flight gesture and closes
    // the keyboard bridge.
    private fun setControlMode(on: Boolean) {
        if (controlMode == on) return
        controlMode = on
        _controlModeFlow.value = on
        if (!on) { liveView?.cancelGesture(); closeImeBridge() }
        updateShrinkCircle()
        app.activityLog.runtime("float_viewer", "control_mode", if (on) "on" else "off")
    }
    /** Set control mode from outside the window (Fluid Cloud notification
     *  action): the only remaining switch for control mode, with vibration
     *  confirmation and keyboard-bridge check. */
    fun setControlModeExternal(on: Boolean) {
        if (controlMode == on) return
        setControlMode(on)
        buzz(on)
        if (on) checkImeBridge()
    }
    private fun buzz(controlOn: Boolean) {
        val vibrator = app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (!vibrator.hasVibrator()) return
        val effect = if (controlOn) {
            VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE)
        } else {
            VibrationEffect.createWaveform(longArrayOf(0, 40, 80, 40), -1)
        }
        runCatching { vibrator.vibrate(effect) }
    }
    private fun buildViews(state: SessionState) {
        val d = density
        cornerRadiusPx = cornerPx
        val (initW, initH) = restoredSize(state)
        val (initX, initY) = restoredPosition(initW, initH)
        val p = WindowManager.LayoutParams(initW, initH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = initX; y = initY }
        val rootView = object : FrameLayout(app) {
            override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    chromeTouch = isOnChrome(event)
                    // Control mode: content touches go to the app; only chrome
                    // touches and bottom-edge resize drags still reach the
                    // window. The old takeover zone is gone, so the top-left
                    // corner is ordinary content now.
                    contentTouch = controlMode && !chromeTouch && !onResizeEdge(event)
                }
                return !chromeTouch && !contentTouch
            }
            override fun onTouchEvent(event: MotionEvent): Boolean = onWindowTouch(event)
        }.apply {
            // #87: keep the drawable so the expand/shrink animation can
            // drive the corner radius (16dp -> 0 fullscreen and back).
            rootBackground = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; setColor(0xFF000000.toInt()); cornerRadius = cornerRadiusPx }
            background = rootBackground
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) { outline.setRoundRect(0, 0, view.width, view.height, cornerRadiusPx) }
            }
            clipToOutline = true
        }
        // The live SurfaceView cannot be view-clipped, so it sits 4dp inside the
        // rounded frame instead of filling it edge to edge.
        val inset = (4f * d).roundToInt()
        val contentView = FrameLayout(app).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT).apply {
                // The bottom margin is the resize edge strip: the frame below
                // the content, where a drag resizes the window.
                setMargins(inset, inset, inset, resizeEdgePx)
            }
        }
        val viewer = LiveDisplayView(app).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            onLiveChanged = { live = it }
            // View-only by default: content touches never inject. In control
            // mode the user operates the app directly, same as the fullscreen
            // viewer (beginPhoneGesture takes the lease for the gesture).
            onManualDown = { if (controlMode) app.sessions.beginPhoneGesture().token else null }
            onManualComplete = { token ->
                app.sessions.finishPhoneControl(token)
                checkImeBridge()
            }
            onCompatibilityInput = { op -> scope.launch { runCatching { app.sessions.phoneTakeover(); phoneInput(app, op) }.onFailure { app.activityLog.runtime("float_viewer", "control_input", "failed") } } }
            onError = { code -> app.activityLog.runtime("float_viewer", "error", code) }
        }
        contentView.addView(viewer)
        // Hidden input sink for the keyboard bridge: the system keyboard
        // never appears for the virtual display's fields on its own (the
        // LOCAL and FALLBACK display IME policies both failed — the policy
        // is set, the keyboard just never shows), so typing goes through
        // this 1x1 sink on the phone display and is committed to the
        // virtual display's focused field (see [openImeBridge]).
        val sink = EditText(app).apply {
            layoutParams = FrameLayout.LayoutParams(1, 1)
            setBackgroundColor(0x00000000)
            setTextColor(0x00000000)
            setCursorVisible(false)
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_DONE
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (suppressImeCommit) return
                    val text = s?.toString() ?: return
                    imeCommitJob?.cancel()
                    imeCommitJob = scope.launch {
                        delay(200)
                        if (!imeBridgeOn) return@launch
                        runCatching { app.sessions.phoneTakeover(); phoneInput(app, BackendOperation.Text(text, replace = true)) }
                            .onFailure { app.activityLog.runtime("float_viewer", "ime_commit", "failed") }
                    }
                }
            })
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) { commitImeBridge(); true } else false
            }
        }
        rootView.addView(sink)
        imeSink = sink
        val trigger = TextView(app).apply {
            text = "›"; textSize = 26f; gravity = Gravity.CENTER; setTextColor(0xFFFFFFFF.toInt())
            background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; setColor(0xCC1F1F1F.toInt()); cornerRadius = 14f * d }
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            visibility = View.GONE
            setOnTouchListener(triggerTouch)
        }
        rootView.addView(contentView)
        rootView.addView(trigger)
        // #87: fullscreen top bar (step + purpose + takeover + shrink),
        // hidden until the window expands. Added last so it sits above the
        // live content.
        val bar = buildTopBar()
        rootView.addView(bar)
        topBar = bar
        windowManager.addView(rootView, p)
        root = rootView; content = contentView; liveView = viewer; triggerBar = trigger; params = p
        viewer.setResumed(true)
    }
    private fun startLoops() {
        stateJob = scope.launch {
            app.sessions.state.collect { s ->
                if (s.displayId == null) { hide(); return@collect }
                val frame = s.frame
                liveView?.setTarget(s.sessionId, app.sessions.liveChannel,
                    LiveGeometry(frame?.width ?: s.config.width, frame?.height ?: s.config.height, s.frameRotation), frame)
                fitToFrameAspect()
            }
        }
        previewJob = scope.launch {
            while (isActive) {
                withContext(Dispatchers.IO) { runCatching { if (app.sessions.state.value.displayId != null) app.sessions.preview() } }
                delay(if (collapsed) 2000 else if (live) 1000 else 350)
            }
        }
        // #87: keep the fullscreen top bar's step + purpose live. mcp is
        // created in onCreate, before the viewer can show; runCatching
        // guards the (unreachable in practice) uninitialized case so it
        // can never kill the other loops.
        activityJob = scope.launch {
            runCatching { app.mcp.activity.collect { updateTopBar(it) } }
        }
        // #88 fix 6: return to the desktop (home/launcher on top) auto-
        // shrinks the fullscreen viewer back to the small window. Only
        // fires when the top app actually changes to home, and only while
        // expanded — expanding by hand while already on home stays put.
        homeJob = scope.launch {
            app.sessions.currentAppFlow.collect { current ->
                val pkg = current?.packageName ?: return@collect
                if (expanded && isHomeApp(pkg)) shrinkToFloat()
            }
        }
    }
    /** #88 fix 6: is this package the phone's home/launcher? Resolves the
     *  HOME intent (the default desktop) and compares package names. */
    private fun isHomeApp(packageName: String): Boolean {
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val homePkg = runCatching {
            app.packageManager.resolveActivity(homeIntent, 0)?.activityInfo?.packageName
        }.getOrNull()
        return homePkg != null && homePkg == packageName
    }
    private fun collapseIfAtEdge(): Boolean {
        val p = params ?: return false
        if (p.x <= 0) { collapse(SIDE_LEFT); return true }
        if (p.x + p.width >= screenW) { collapse(SIDE_RIGHT); return true }
        return false
    }
    /** Pin the collapsed trigger bar flush to its edge right now. After a
     *  mid-drag collapse the bar used to keep following the finger, so it sat
     *  a few dp off the edge until the 2s sink timer glued it back. */
    private fun snapToEdge() {
        val p = params ?: return
        if (!collapsed) return
        p.x = if (collapsedSide == SIDE_LEFT) 0 else screenW - p.width
        p.y = p.y.coerceIn(0, (screenH - p.height).coerceAtLeast(0))
        updateLayout()
    }
    private fun collapse(side: Int) {
        val p = params ?: return
        collapsed = true; collapsedSide = side
        setControlMode(false)
        savedX = p.x; savedY = p.y; savedW = p.width; savedH = p.height
        content?.visibility = View.GONE
        triggerBar?.text = if (side == SIDE_LEFT) "›" else "‹"
        triggerBar?.visibility = View.VISIBLE
        val barW = (28f * density).roundToInt(); val barH = (112f * density).roundToInt()
        p.y = (p.y + (p.height - barH) / 2).coerceIn(0, (screenH - barH).coerceAtLeast(0))
        p.width = barW; p.height = barH
        p.x = if (side == SIDE_LEFT) 0 else screenW - barW
        updateLayout()
        root?.removeCallbacks(sinkRunnable)
        root?.postDelayed(sinkRunnable, 2000)
    }
    private fun expand() {
        val p = params ?: return
        root?.removeCallbacks(sinkRunnable)
        collapsed = false
        triggerBar?.visibility = View.GONE
        content?.visibility = View.VISIBLE
        p.width = savedW; p.height = savedH
        p.x = savedX.coerceIn(0, (screenW - p.width).coerceAtLeast(0))
        p.y = savedY.coerceIn(0, (screenH - p.height).coerceAtLeast(0))
        updateLayout()
    }
    // #87: fullscreen top bar + smooth expand/shrink, mirroring the
    // reference video: the small window morphs into a fullscreen viewer
    // with a step bar (第 N 步 + current purpose) and a shrink circle.
    // The bar is chrome (see isOnChrome), so its buttons work even in
    // control mode.
    // #88 fix 6: the bar has NO background any more (user 2026-11-13) —
    // the black strip is gone, so the step text and the shrink circle just
    // float over the app. The text carries a shadow so white stays readable
    // over light content. The 接管 button is gone too; the shrink circle's
    // colour now shows takeover state (light blue = in control).
    private fun buildTopBar(): LinearLayout {
        val d = density
        val l = systemLanguage(app)
        val bar = LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((12f * d).roundToInt(), (8f * d).roundToInt(), (12f * d).roundToInt(), (8f * d).roundToInt())
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.TOP }
            visibility = View.GONE
        }
        val step = TextView(app).apply {
            textSize = 15f
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            // No bar background any more: a soft shadow keeps the white
            // text readable over light app content.
            setShadowLayer(4f, 0f, 2f, 0xAA000000.toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val purpose = TextView(app).apply {
            textSize = 14f
            setTextColor(0xFFE0E0E0.toInt())
            setShadowLayer(4f, 0f, 2f, 0xAA000000.toInt())
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins((10f * d).roundToInt(), 0, (10f * d).roundToInt(), 0)
            }
        }
        val shrink = shrinkIconButton()
        // #88 fix 4: the top bar is draggable — drag it and the whole bar
        // (step status + 收起) moves together, so the user can slide it out
        // of the way of whatever they are looking at. Dragging works on the
        // bar's text/empty area, even when the step status is hidden.
        makeBarDraggable(bar)
        bar.addView(step)
        bar.addView(purpose)
        bar.addView(shrink)
        stepText = step; purposeText = purpose; shrinkCircle = shrink
        applyStepVisibility()
        updateShrinkCircle()
        return bar
    }
    /** #88 fix 4: make the fullscreen top bar draggable. Dragging it moves
     *  the whole bar (step status, 接管， 收起） together, vertically, and
     *  it stays where the user drops it — so it can be slid out of the way
     *  of the content underneath. The listener sits on the bar itself: the
     *  接管/收起 buttons consume their own touches first, so their taps
     *  still work, and dragging keeps working even when the step status is
     *  hidden (#88 fix 5) because the bar itself is always there. */
    private fun makeBarDraggable(bar: LinearLayout) {
        val d = density
        val slop = (8f * d).roundToInt()
        var downY = 0f
        var startTy = 0f
        var dragging = false
        bar.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = event.rawY
                    startTy = bar.translationY
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = event.rawY - downY
                    if (!dragging && kotlin.math.abs(dy) > slop) dragging = true
                    if (dragging) {
                        val maxTy = (screenH - bar.height).coerceAtLeast(0).toFloat()
                        bar.translationY = (startTy + dy).coerceIn(0f, maxTy)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    true
                }
                else -> false
            }
        }
    }
    /** #88 fix 5: show or hide the step status (第 N 步 + purpose) in the
     *  top bar, from the showStepStatus setting. When hidden, the bar is
     *  just the 接管 button and the 收起 circle — minimal, barely blocking
     *  the view. The bar stays draggable either way (see makeBarDraggable). */
    private fun applyStepVisibility() {
        val show = app.settings.flow.value.showStepStatus
        stepText?.visibility = if (show) View.VISIBLE else View.GONE
        purposeText?.visibility = if (show) View.VISIBLE else View.GONE
    }
    /** #88 fix 3 + fix 6: the 收起 button is a plain frosted-glass circle,
     *  no text and no symbol (user 2026-11-13 — the ⌄ is gone). Single tap
     *  shrinks back to the small window; long-press opens the compact menu.
     *  Its colour expresses takeover state (fix 6): light blue while in
     *  control mode, translucent white while view-only — see
     *  [updateShrinkCircle]. */
    private fun shrinkIconButton(): TextView {
        val d = density
        val size = (40f * d).roundToInt()
        return TextView(app).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                // Frosted glass: translucent white, with a faint white rim
                // so the circle reads against any content.
                setColor(0x55FFFFFF)
                setStroke((1f * d).roundToInt(), 0x88FFFFFF.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                setMargins((4f * d).roundToInt(), 0, 0, 0)
            }
            setOnClickListener { shrinkToFloat() }
            setOnLongClickListener { showCompactMenu(this); true }
        }
    }
    /** #88 fix 6: the shrink circle's colour expresses takeover state, now
     *  that the separate 接管 button is gone. Light blue = the user is in
     *  control (takeover); translucent white = view-only. Called whenever
     *  control mode flips (setControlMode) and when the top bar is built. */
    private fun updateShrinkCircle() {
        val circle = shrinkCircle ?: return
        val d = density
        circle.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            if (controlMode) {
                // In control (takeover): light blue.
                setColor(0xFF90CAF9.toInt())
                setStroke((1f * d).roundToInt(), 0xFFFFFFFF.toInt())
            } else {
                // View-only: frosted translucent white.
                setColor(0x55FFFFFF)
                setStroke((1f * d).roundToInt(), 0x88FFFFFF.toInt())
            }
        }
    }
    /** Refresh the top bar from the latest MCP activity. No-op while the
     *  window is small — the bar is hidden there. */
    private fun updateTopBar(activity: ToolActivity?) {
        if (!expanded) return
        val l = systemLanguage(app)
        val step = activity?.step ?: 0
        stepText?.text = if (step > 0) l.text("第 $step 步", "Step $step", "第 $step 步") else l.text("待命", "Idle", "待命")
        val purpose = activity?.purpose?.takeIf { it.isNotBlank() }
        purposeText?.text = purpose ?: l.text("MCP 已就绪", "MCP ready", "MCP 已就緒")
    }
    /** Double-tap the small window: morph it into the fullscreen viewer. */
    private fun expandToFullscreen() {
        val p = params ?: return
        if (expanded || collapsed) return
        fsSavedX = p.x; fsSavedY = p.y; fsSavedW = p.width; fsSavedH = p.height
        expanded = true
        topBar?.visibility = View.VISIBLE
        updateTopBar(runCatching { app.mcp.activity.value }.getOrNull())
        updateShrinkCircle()
        val from = android.graphics.Rect(p.x, p.y, p.x + p.width, p.y + p.height)
        val to = android.graphics.Rect(0, 0, screenW, screenH)
        animateWindowRect(from, to, cornerPx, 0f)
        app.activityLog.runtime("float_viewer", "fullscreen", "expanded")
    }
    /** 收起: morph the fullscreen viewer back to the small window. */
    private fun shrinkToFloat() {
        val p = params ?: return
        if (!expanded) return
        setControlMode(false)
        val from = android.graphics.Rect(p.x, p.y, p.x + p.width, p.y + p.height)
        val to = android.graphics.Rect(fsSavedX, fsSavedY, fsSavedX + fsSavedW, fsSavedY + fsSavedH)
        animateWindowRect(from, to, 0f, cornerPx) {
            expanded = false
            topBar?.visibility = View.GONE
        }
        app.activityLog.runtime("float_viewer", "fullscreen", "shrunk")
    }
    /** #88: compact menu (long-press 收起) with the functions from the
     *  fullscreen viewer's round-button card, so they are reachable from
     *  the fullscreen float window without going back to the main viewer.
     *  Single-tap 收起 still just shrinks. 画中画 only appears when PiP
     *  is enabled in settings.
     *
     *  #88 fix 2: built as a plain PopupWindow, not PopupMenu — PopupMenu
     *  dims the content behind it with a light gray mask, which the user
     *  found blocking the view. This one has a transparent window
     *  background and no dim, so the menu floats over the content.
     *  The items are beautified (user 2026-11-13): white rounded card,
     *  comfortable touch targets, a light press highlight, thin dividers.
     *
     *  Menu order: 隐藏步骤/显示步骤， 应用， 设置， 结束. 接管 was removed
     *  in #88 fix 6 (user 2026-11-13) — the shrink circle's colour now
     *  shows takeover state instead of a separate button. */
    private fun showCompactMenu(anchor: View) {
        val l = systemLanguage(app)
        val d = density
        val container = LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(0xFFFFFFFF.toInt())
                cornerRadius = 16f * d
            }
            // #88 fix 6: shorter card (user 2026-11-13) — tighter padding
            // so the menu does not stretch so tall.
            setPadding((4f * d).roundToInt(), (4f * d).roundToInt(), (4f * d).roundToInt(), (4f * d).roundToInt())
        }
        // Pressed-state background for a menu item: light gray rounded
        // highlight, transparent when idle.
        fun itemBackground(): android.graphics.drawable.StateListDrawable {
            val pressed = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(0xFFF0F0F0.toInt())
                cornerRadius = 10f * d
            }
            val normal = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(0x00000000)
                cornerRadius = 10f * d
            }
            return android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), pressed)
                addState(intArrayOf(), normal)
            }
        }
        val items = mutableListOf<Pair<Int, Pair<String, Int>>>()
        // #88 fix 5: step-status toggle — hides/shows the 第 N 步 + purpose
        // in the top bar. Label shows the action it will take.
        val stepLabel = if (app.settings.flow.value.showStepStatus) l.text("隐藏步骤", "Hide step", "隱藏步驟") else l.text("显示步骤", "Show step", "顯示步驟")
        items.add(MENU_STEP to (stepLabel to 0xFF1F1F1F.toInt()))
        items.add(MENU_APPS to (l.text("应用", "Apps", "應用") to 0xFF1F1F1F.toInt()))
        if (app.settings.flow.value.pipEnabled) {
            items.add(MENU_PIP to (l.text("画中画", "PiP", "子母畫面") to 0xFF1F1F1F.toInt()))
        }
        items.add(MENU_SETTINGS to (l.text("设置", "Settings", "設定") to 0xFF1F1F1F.toInt()))
        items.add(MENU_END to (l.text("结束", "End", "結束") to 0xFF1F1F1F.toInt()))
        var popup: PopupWindow? = null
        for ((index, entry) in items.withIndex()) {
            val (id, textAndColor) = entry
            val (text, color) = textAndColor
            val tv = TextView(app).apply {
                this.text = text
                textSize = 16f
                setTextColor(color)
                gravity = Gravity.CENTER_VERTICAL
                // #88 fix 6: shorter card — 40dp rows instead of 48dp, and
                // less vertical padding, so the menu is more compact.
                minHeight = (40f * d).roundToInt()
                setPadding((18f * d).roundToInt(), (8f * d).roundToInt(), (18f * d).roundToInt(), (8f * d).roundToInt())
                background = itemBackground()
                isClickable = true
                setOnClickListener {
                    popup?.dismiss()
                    when (id) {
                        MENU_STEP -> {
                            // #88 fix 5: flip the step-status visibility and
                            // persist it, so the choice survives restarts.
                            app.settings.update { it.copy(showStepStatus = !it.showStepStatus) }
                            applyStepVisibility()
                        }
                        MENU_APPS -> showAppPicker()
                        MENU_PIP -> enterPip()
                        MENU_SETTINGS -> openSettings()
                        MENU_END -> endSession()
                    }
                }
            }
            container.addView(tv)
            // Thin divider between items, not after the last one.
            if (index < items.size - 1) {
                val divider = View(app).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (1f * d).roundToInt()).apply {
                        leftMargin = (18f * d).roundToInt()
                        rightMargin = (18f * d).roundToInt()
                    }
                    setBackgroundColor(0xFFE8E8E8.toInt())
                }
                container.addView(divider)
            }
        }
        popup = PopupWindow(container, WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, true).apply {
            // Transparent window background: no dim/mask over the content,
            // and outside touches still dismiss the menu.
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = 8f * d
        }
        popup.showAsDropDown(anchor)
        app.activityLog.runtime("float_viewer", "menu", "shown")
    }
    /** #88 fix: show the app picker directly in the float window (an
     *  overlay dialog), instead of opening MainActivity. Opening
     *  MainActivity hid the float window (its onResume hides it) and the
     *  picker never stayed usable — tapping 应用 just dropped straight
     *  back to the small window. Here the picker stays in the float view;
     *  picking an app launches it on the virtual display.
     *  #88 fix 6: a search field on top filters the list by app name or
     *  package (user 2026-11-13), so a long app list is quick to narrow. */
    private fun showAppPicker() {
        scope.launch {
            val apps = withContext(Dispatchers.IO) {
                runCatching {
                    app.sessions.discoverApplications(includeIcons = false)
                        .filter { it.exported && it.packageName != app.packageName }
                }.getOrDefault(emptyList())
            }
            if (apps.isEmpty()) return@launch
            val l = systemLanguage(app)
            val d = density
            val layout = LinearLayout(app).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((16f * d).roundToInt(), (8f * d).roundToInt(), (16f * d).roundToInt(), 0)
            }
            val search = EditText(app).apply {
                hint = l.text("搜索应用", "Search apps", "搜尋應用")
                textSize = 16f
                setSingleLine()
                setPadding((12f * d).roundToInt(), (10f * d).roundToInt(), (12f * d).roundToInt(), (10f * d).roundToInt())
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(0xFFF0F0F0.toInt())
                    cornerRadius = 10f * d
                }
            }
            layout.addView(search, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            val listView = android.widget.ListView(app).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (320f * d).roundToInt())
            }
            layout.addView(listView)
            var filtered = apps
            val adapter = android.widget.ArrayAdapter<String>(app, android.R.layout.simple_list_item_1, filtered.map { it.label })
            listView.adapter = adapter
            search.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    val q = s?.toString()?.trim().orEmpty()
                    filtered = if (q.isEmpty()) apps else apps.filter { it.label.contains(q, ignoreCase = true) || it.packageName.contains(q, ignoreCase = true) }
                    adapter.clear()
                    adapter.addAll(filtered.map { it.label })
                    adapter.notifyDataSetChanged()
                }
            })
            val dialog = android.app.AlertDialog.Builder(app)
                .setTitle(l.text("选择应用", "Choose app", "選擇應用"))
                .setView(layout)
                .setNegativeButton(l.text("取消", "Cancel", "取消"), null)
                .create()
            listView.setOnItemClickListener { _, _, position, _ ->
                val item = filtered.getOrNull(position) ?: return@setOnItemClickListener
                dialog.dismiss()
                scope.launch {
                    runCatching {
                        phoneControl(app) { lease ->
                            val result = app.sessions.launch(lease.owner, lease.token, item.component)
                            check(result.ok) { result.code ?: "LAUNCH_FAILED" }
                            app.settings.update { it.copy(lastComponent = item.component) }
                        }
                    }
                }
            }
            // Showing from the Application context, so the dialog must be
            // an overlay window or it cannot appear above the float window.
            dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            dialog.show()
            app.activityLog.runtime("float_viewer", "menu", "app_picker_shown")
        }
    }
    /** Enter PiP: hide the float window, let MainActivity take it from there. */
    private fun enterPip() {
        hide()
        app.startActivity(Intent(app, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("pip", true)
        })
    }
    /** #88 fix: shrink back to the small window first. Opening the
     *  Settings page while the float window is fullscreen leaves the
     *  fullscreen window covering the Settings page, and the user has to
     *  shrink it by hand. Shrinking first keeps the small window in a
     *  corner instead, out of the way. */
    private fun openSettings() {
        shrinkToFloat()
        app.startActivity(Intent(app, SettingsActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
    /** End the virtual-screen session (same as the card's 结束), then hide. */
    private fun endSession() {
        val sessionId = app.sessions.state.value.sessionId
        if (sessionId == null) { hide(); return }
        scope.launch {
            runCatching { app.sessions.closeFromPhone(sessionId) }
            hide()
        }
    }
    /** Animate the window rect and corner radius together (~300ms,
     *  decelerating). Cancels any in-flight expand/shrink first. */
    private fun animateWindowRect(from: android.graphics.Rect, to: android.graphics.Rect, fromRadius: Float, toRadius: Float, onEnd: (() -> Unit)? = null) {
        val p = params ?: return
        expandAnimator?.cancel()
        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 300
            interpolator = DecelerateInterpolator()
            addUpdateListener { v ->
                val f = v.animatedValue as Float
                p.x = (from.left + (to.left - from.left) * f).roundToInt()
                p.y = (from.top + (to.top - from.top) * f).roundToInt()
                p.width = (from.width() + (to.width() - from.width()) * f).roundToInt()
                p.height = (from.height() + (to.height() - from.height()) * f).roundToInt()
                cornerRadiusPx = fromRadius + (toRadius - fromRadius) * f
                rootBackground?.cornerRadius = cornerRadiusPx
                root?.invalidateOutline()
                updateLayout()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { onEnd?.invoke() }
            })
        }
        expandAnimator = anim
        anim.start()
    }
    // Keyboard bridge (idea borrowed from Eta 3.4.0-beta.30, re-implemented
    // from the observed behaviour, no Eta code copied). The system keyboard
    // never appears for the virtual display's fields on its own: the display
    // IME policy is set (LOCAL in #14/#15, FALLBACK in #17/#19, ime_policy
    // true in both cases) and the keyboard still never shows, so the bridge
    // does it instead — a hidden sink EditText on the phone display summons
    // the phone keyboard, and the typed text is committed to the virtual
    // display's focused field. The bridge opens automatically when a text
    // field is focused in control mode (see [checkImeBridge]); there is no
    // button for it anymore.
    /** After a gesture in control mode, look for a focused editable field
     *  on the virtual display; pop the phone keyboard if there is one,
     *  close it if not. */
    private fun checkImeBridge() {
        if (!controlMode || collapsed) { closeImeBridge(); return }
        imeCheckJob?.cancel()
        imeCheckJob = scope.launch {
            delay(300)
            if (!controlMode || collapsed) { closeImeBridge(); return@launch }
            val displayId = app.sessions.state.value.displayId ?: run { closeImeBridge(); return@launch }
            val target = runCatching {
                ScreenAccessibilityService.current
                    ?.snapshot(displayId, UUID.randomUUID().toString())
                    ?.nodes?.firstOrNull { it.editable && it.focused }
            }.getOrNull()
            if (target != null) {
                // Coolapk puts the hot search word in the field's *text* as
                // a grey placeholder, with the same word as the hint (seen
                // live: text "选机中心k" after typing "k", hint "选机中心").
                // If the text is exactly the hint it is not real input:
                // pre-fill with "" so the first keystroke replaces it
                // instead of appending after it.
                val rawText = target.text ?: ""
                val initialText = if (rawText.isNotEmpty() && rawText == target.hintText) "" else rawText
                openImeBridge(initialText)
            } else closeImeBridge()
        }
    }
    private fun openImeBridge(initialText: String) {
        val sink = imeSink ?: return
        val p = params ?: return
        // Pre-fill with the field's current text so typing appends instead
        // of wiping it; only move the cursor when the text actually changed,
        // so a mid-typing refresh does not yank it. The pre-fill is
        // programmatic, so suppress the commit it would otherwise trigger
        // (it must not write the field's own text back into the field —
        // that is what turned Coolapk's hot-word placeholder into real
        // input on every tap).
        if (sink.text.toString() != initialText) {
            suppressImeCommit = true
            sink.setText(initialText)
            suppressImeCommit = false
            sink.setSelection(initialText.length)
        }
        if (imeBridgeOn) return
        imeBridgeOn = true
        // Let the overlay take input focus so the sink can hold it.
        p.flags = p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        updateLayout()
        sink.requestFocus()
        val imm = app.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        imeShowJob?.cancel()
        imeShowJob = scope.launch {
            for (delayMs in listOf(120L, 480L, 900L, 1400L)) {
                delay(delayMs)
                if (!imeBridgeOn) return@launch
                runCatching { imm.showSoftInput(sink, 0) }
            }
        }
    }
    private fun closeImeBridge() {
        if (!imeBridgeOn) return
        imeBridgeOn = false
        imeShowJob?.cancel(); imeShowJob = null
        imeCommitJob?.cancel(); imeCommitJob = null
        val sink = imeSink
        val imm = app.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        if (sink != null && imm != null) runCatching { imm.hideSoftInputFromWindow(sink.windowToken, 0) }
        sink?.clearFocus()
        // Programmatic clear: suppress the commit like the pre-fill, so no
        // empty write is scheduled as the bridge closes.
        suppressImeCommit = true
        sink?.setText("")
        suppressImeCommit = false
        val p = params
        if (p != null) {
            p.flags = p.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            updateLayout()
        }
    }
    /** Commit the sink text now (keyboard Done key) and close the bridge. */
    private fun commitImeBridge() {
        val text = imeSink?.text?.toString() ?: ""
        imeCommitJob?.cancel(); imeCommitJob = null
        if (text.isEmpty()) { closeImeBridge(); return }
        scope.launch {
            runCatching { app.sessions.phoneTakeover(); phoneInput(app, BackendOperation.Text(text, replace = true)) }
                .onFailure { app.activityLog.runtime("float_viewer", "ime_commit", "failed") }
            closeImeBridge()
        }
    }
    /** Keep the window glued to the live frame aspect: a stale or resized size
     *  otherwise leaves black bars on the sides (or top/bottom). Runs on every
     *  frame but is a no-op once the aspect matches (2% deadband). */
    private fun fitToFrameAspect() {
        if (collapsed) return
        // #87: while expanded the window is fullscreen by design — the
        // aspect fit must not shrink it back mid-animation or after.
        if (expanded) return
        val p = params ?: return
        if (p.width <= 0 || p.height <= 0) return
        // Keep the whole window on-screen first: a window resized past the
        // bottom edge used to stay there, because the aspect check below
        // returned early when the aspect matched — leaving the resize grips
        // off-screen and ungrabbable. This also pulls a stuck window back
        // on the next frame after the fix is installed.
        val nx = p.x.coerceIn(0, (screenW - p.width).coerceAtLeast(0))
        val ny = p.y.coerceIn(0, (screenH - p.height).coerceAtLeast(0))
        if (nx != p.x || ny != p.y) { p.x = nx; p.y = ny; updateLayout() }
        val aspect = currentAspect()
        if (aspect <= 0f || !aspect.isFinite()) return
        val winAspect = p.width.toFloat() / p.height
        if (abs(winAspect - aspect) / aspect < 0.02f) return
        var h = (p.width / aspect).roundToInt()
        var w = p.width
        if (h > maxHeightPx) { h = maxHeightPx; w = (h * aspect).roundToInt() }
        if (w == p.width && h == p.height) return
        p.width = w; p.height = h
        p.x = p.x.coerceIn(0, (screenW - p.width).coerceAtLeast(0))
        p.y = p.y.coerceIn(0, (screenH - p.height).coerceAtLeast(0))
        updateLayout()
    }
    private fun currentAspect(): Float {
        val s = app.sessions.state.value
        val w = s.frame?.width ?: s.config.width; val h = s.frame?.height ?: s.config.height
        return if (w > 0 && h > 0) w.toFloat() / h else 0.5f
    }
    private fun defaultSize(state: SessionState): Pair<Int, Int> {
        // Live frame aspect (falls back to the display config): the window must
        // match it, or the fitted frame letterboxes with black bars on the sides.
        val aspect = currentAspect()
        var w = (320f * density).roundToInt().coerceIn(minWidthPx, maxWidthPx)
        var h = (w / aspect).roundToInt()
        if (h > maxHeightPx) { h = maxHeightPx; w = (h * aspect).roundToInt() }
        return w to h
    }
    private fun restoredSize(state: SessionState): Pair<Int, Int> {
        val aspect = currentAspect()
        val w = prefs.getInt("width", 0); val h = prefs.getInt("height", 0)
        if (w <= 0 || h <= 0) return defaultSize(state)
        // A saved height may predate the current frame: re-derive it from the
        // saved width and the live frame aspect instead of trusting it.
        var rw = w.coerceIn(minWidthPx, maxWidthPx)
        var rh = (rw / aspect).roundToInt()
        if (rh > maxHeightPx) { rh = maxHeightPx; rw = (rh * aspect).roundToInt() }
        return rw to rh
    }
    private fun restoredPosition(w: Int, h: Int): Pair<Int, Int> {
        // No position memory: always dock flush to the very top-right
        // corner, so the window never pops up in the middle of the screen.
        val x = (screenW - w - (16f * density).roundToInt()).coerceAtLeast(0)
        return x to 0
    }
    /** Only the size is remembered; the position is not. The window always
     *  pops up docked to the top-right corner (see [restoredPosition]), so it
     *  never blocks the middle of the screen. */
    private fun saveSize() {
        val p = params ?: return
        if (collapsed) return
        prefs.edit().putInt("width", p.width).putInt("height", p.height).apply()
    }
    private fun updateLayout() {
        val view = root ?: return; val p = params ?: return
        runCatching { windowManager.updateViewLayout(view, p) }
    }
}
