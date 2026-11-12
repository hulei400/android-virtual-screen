package io.github.lejw0925.virtualscreen.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.ComponentCallbacks
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
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
    private var live = false
    private var collapsed = false
    /** #87: fullscreen (expanded) state. The float window morphs into a
     *  fullscreen viewer with a top bar (step + purpose + takeover +
     *  shrink), like the reference video: tap the small window to expand,
     *  tap 收起 to shrink back. While expanded, drag/resize/collapse are
     *  off; the top bar's buttons are the only chrome. */
    private var expanded = false
    private var fsSavedX = 0; private var fsSavedY = 0; private var fsSavedW = 0; private var fsSavedH = 0
    private var topBar: LinearLayout? = null
    private var stepText: TextView? = null
    private var purposeText: TextView? = null
    private var takeoverBtn: Button? = null
    private var expandAnimator: ValueAnimator? = null
    /** Current corner radius, animated 16dp -> 0 on expand and back on
     *  shrink. The root background drawable and the outline both read it.
     *  Set to cornerPx in buildViews. */
    private var cornerRadiusPx = 0f
    private var rootBackground: GradientDrawable? = null
    private var collapsedSide = SIDE_LEFT
    private var collapsedDuringDrag = false
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
                    // #87: a tap on the small window expands it to
                    // fullscreen (with the step bar), like the reference
                    // video. In control mode taps go to the content instead
                    // and never reach here, so this only fires view-only.
                    expandToFullscreen()
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
        imeCheckJob?.cancel(); imeCheckJob = null
        root?.removeCallbacks(sinkRunnable)
        runCatching { app.unregisterComponentCallbacks(configCallbacks) }
        liveView?.setResumed(false)
        liveView?.dispose()
        liveView = null; imeSink = null
        root?.let { runCatching { windowManager.removeView(it) } }
        root = null; content = null; triggerBar = null; topBar = null; stepText = null; purposeText = null; takeoverBtn = null; rootBackground = null; params = null
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
        updateTakeoverButton()
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
    // with a step bar (第 N 步 + current purpose), a takeover toggle and
    // a shrink button. The bar is chrome (see isOnChrome), so its buttons
    // work even in control mode.
    private fun buildTopBar(): LinearLayout {
        val d = density
        val l = systemLanguage(app)
        val bar = LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xCC1F1F1F.toInt())
            setPadding((12f * d).roundToInt(), (8f * d).roundToInt(), (12f * d).roundToInt(), (8f * d).roundToInt())
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.TOP }
            visibility = View.GONE
        }
        val step = TextView(app).apply {
            textSize = 15f
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val purpose = TextView(app).apply {
            textSize = 14f
            setTextColor(0xFFE0E0E0.toInt())
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins((10f * d).roundToInt(), 0, (10f * d).roundToInt(), 0)
            }
        }
        val takeover = barButton(l.text("接管", "Take over", "接管")) { setControlModeExternal(!controlMode) }
        val shrink = barButton(l.text("收起", "Shrink", "收起")) { shrinkToFloat() }
        bar.addView(step)
        bar.addView(purpose)
        bar.addView(takeover)
        bar.addView(shrink)
        stepText = step; purposeText = purpose; takeoverBtn = takeover
        return bar
    }
    private fun barButton(text: String, onClick: () -> Unit): Button {
        val d = density
        return Button(app).apply {
            this.text = text
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(0xFF3C4043.toInt())
                cornerRadius = 18f * d
            }
            setPadding((14f * d).roundToInt(), (6f * d).roundToInt(), (14f * d).roundToInt(), (6f * d).roundToInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins((4f * d).roundToInt(), 0, 0, 0)
            }
            setOnClickListener { onClick() }
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
    /** Takeover button label + colour follow control mode: 接管 (accent)
     *  while view-only, 只看 (neutral) while the user is operating. */
    private fun updateTakeoverButton() {
        val btn = takeoverBtn ?: return
        val d = density
        val l = systemLanguage(app)
        if (controlMode) {
            btn.text = l.text("只看", "View only", "僅觀看")
            btn.background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; setColor(0xFF3C4043.toInt()); cornerRadius = 18f * d }
        } else {
            btn.text = l.text("接管", "Take over", "接管")
            btn.background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; setColor(0xFF1A73E8.toInt()); cornerRadius = 18f * d }
        }
    }
    /** Tap the small window: morph it into the fullscreen viewer. */
    private fun expandToFullscreen() {
        val p = params ?: return
        if (expanded || collapsed) return
        fsSavedX = p.x; fsSavedY = p.y; fsSavedW = p.width; fsSavedH = p.height
        expanded = true
        topBar?.visibility = View.VISIBLE
        updateTopBar(runCatching { app.mcp.activity.value }.getOrNull())
        updateTakeoverButton()
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
