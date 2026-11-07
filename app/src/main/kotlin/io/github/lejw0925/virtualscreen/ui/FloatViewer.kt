package io.github.lejw0925.virtualscreen.ui

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
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import io.github.lejw0925.virtualscreen.VirtualScreenApplication
import io.github.lejw0925.virtualscreen.backend.BackendOperation
import io.github.lejw0925.virtualscreen.backend.LiveGeometry
import io.github.lejw0925.virtualscreen.core.ScreenAccessibilityService
import io.github.lejw0925.virtualscreen.core.SessionState
import io.github.lejw0925.virtualscreen.core.failure
import io.github.lejw0925.virtualscreen.core.runtime
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
 * dragging either bottom corner grip resizes it, dragging to a screen edge
 * collapses it to a narrow trigger bar (tap the bar, or drag it outward, to
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
    private val edgePx get() = (8f * density).roundToInt()
    private val cornerPx get() = 16f * density
    private var root: FrameLayout? = null
    private var content: FrameLayout? = null
    private var liveView: LiveDisplayView? = null
    private var triggerBar: TextView? = null
    private var resizeGrip: View? = null
    private var resizeGripLeft: View? = null
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
    private var params: WindowManager.LayoutParams? = null
    private var chromeTouch = false
    private var stateJob: Job? = null
    private var previewJob: Job? = null
    private var live = false
    private var collapsed = false
    private var collapsedSide = SIDE_LEFT
    private var collapsedDuringDrag = false
    private var savedX = 0; private var savedY = 0; private var savedW = 0; private var savedH = 0
    private var downRawX = 0f; private var downRawY = 0f; private var downWinX = 0; private var downWinY = 0
    private var downWinW = 0; private var downWinH = 0
    private var moved = false; private var resizing = false; private var resizeLeft = false
    private var lastTapTime = 0L; private var lastTapX = 0f; private var lastTapY = 0f
    private var triggerDownX = 0f; private var triggerMoved = false
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
    // and only the resize grips still move/resize the window.
    private fun isOnChrome(event: MotionEvent): Boolean {
        val targets = if (collapsed) listOfNotNull(triggerBar) else emptyList<TextView>()
        return targets.any { view ->
            if (view.visibility != View.VISIBLE) return@any false
            val loc = IntArray(2); view.getLocationOnScreen(loc)
            event.rawX >= loc[0] && event.rawX < loc[0] + view.width &&
                event.rawY >= loc[1] && event.rawY < loc[1] + view.height
        }
    }
    /** The old takeover button's spot (top-left corner, 32dp plus slack):
     *  double-tap here toggles control mode. Touches here never reach the
     *  app, so the toggle works in both directions — turning control on
     *  from view-only, and back off from control mode. */
    private fun isInTakeoverZone(event: MotionEvent): Boolean {
        val zone = 48f * density
        return event.x <= zone && event.y <= zone
    }
    private fun onWindowTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX; downRawY = event.rawY
                downWinX = params?.x ?: 0; downWinY = params?.y ?: 0
                downWinW = params?.width ?: 0; downWinH = params?.height ?: 0
                moved = false
                val corner = resizeCornerAt(event)
                resizing = corner != 0; resizeLeft = corner == 2
            }
            MotionEvent.ACTION_MOVE -> {
                if (resizing) {
                    // Corner drag resizes: width follows the finger, height
                    // follows the live frame aspect so no black bars appear.
                    // Right corner: the left edge stays; left corner: the
                    // right edge stays (width shrinks as the finger moves right).
                    val p = params ?: return true
                    val aspect = currentAspect()
                    val dx = (event.rawX - downRawX).roundToInt()
                    var w = (if (resizeLeft) downWinW - dx else downWinW + dx).coerceIn(minWidthPx, maxWidthPx)
                    var h = (w / aspect).roundToInt()
                    if (h > maxHeightPx) { h = maxHeightPx; w = (h * aspect).roundToInt() }
                    val x = if (resizeLeft) (downWinX + downWinW - w).coerceIn(0, (screenW - w).coerceAtLeast(0)) else p.x
                    if (w != p.width || h != p.height || x != p.x) { p.width = w; p.height = h; p.x = x; moved = true; updateLayout() }
                } else if (event.pointerCount == 1 && !collapsedDuringDrag) {
                    val dx = (event.rawX - downRawX).roundToInt(); val dy = (event.rawY - downRawY).roundToInt()
                    if (abs(dx) > touchSlopPx || abs(dy) > touchSlopPx) moved = true
                    if (moved) {
                        val p = params ?: return true
                        p.x = (downWinX + dx).coerceIn(0, (screenW - p.width).coerceAtLeast(0))
                        p.y = (downWinY + dy).coerceIn(0, (screenH - p.height).coerceAtLeast(0))
                        updateLayout()
                        if (collapseIfAtEdge()) collapsedDuringDrag = true
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                val snapped = collapsedDuringDrag; collapsedDuringDrag = false
                if (resizing) {
                    resizing = false
                    saveSize()
                } else if (!moved) {
                    if (isDoubleTap(event) && isInTakeoverZone(event)) toggleControlMode()
                    else { lastTapTime = SystemClock.uptimeMillis(); lastTapX = event.x; lastTapY = event.y }
                } else if (snapped) {
                    // Collapsed mid-drag: glue the bar to the edge at once. It
                    // used to keep following the finger after collapsing, so it
                    // sat a few dp off the edge until the 2s sink timer fired.
                    snapToEdge()
                } else {
                    val p = params ?: return true
                    if (p.x <= edgePx) collapse(SIDE_LEFT)
                    else if (p.x + p.width >= screenW - edgePx) collapse(SIDE_RIGHT)
                    else saveSize()
                }
            }
            MotionEvent.ACTION_CANCEL -> { collapsedDuringDrag = false; resizing = false }
        }
        return true
    }
    /** Bottom corner grip zones (about 40dp): a drag starting in either one
     *  resizes the window instead of moving it. Returns 0 (none), 1 (right),
     *  or 2 (left). Pinch zoom is gone; this replaces it. */
    private fun resizeCornerAt(event: MotionEvent): Int {
        if (collapsed) return 0
        val p = params ?: return 0
        val zone = 40f * density
        if (event.rawY < p.y + p.height - zone) return 0
        if (event.rawX >= p.x + p.width - zone) return 1
        if (event.rawX <= p.x + zone) return 2
        return 0
    }
    private val triggerTouch = View.OnTouchListener { _, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { triggerDownX = event.rawX; triggerMoved = false }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - triggerDownX
                val outward = if (collapsedSide == SIDE_LEFT) dx else -dx
                if (outward > 24f * density) { expand(); triggerMoved = true }
            }
            MotionEvent.ACTION_UP -> { if (!triggerMoved) expand() }
            MotionEvent.ACTION_CANCEL -> {}
        }
        true
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
        stateJob?.cancel(); stateJob = null
        previewJob?.cancel(); previewJob = null
        imeCheckJob?.cancel(); imeCheckJob = null
        root?.removeCallbacks(sinkRunnable)
        runCatching { app.unregisterComponentCallbacks(configCallbacks) }
        liveView?.setResumed(false)
        liveView?.dispose()
        liveView = null; imeSink = null; resizeGrip = null; resizeGripLeft = null
        root?.let { runCatching { windowManager.removeView(it) } }
        root = null; content = null; triggerBar = null; params = null
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
        app.activityLog.runtime("float_viewer", "control_mode", if (on) "on" else "off")
    }
    /** Set control mode from outside the window (Fluid Cloud notification
     *  action): same switch as the double-tap, with the same vibration
     *  confirmation and keyboard-bridge check. */
    fun setControlModeExternal(on: Boolean) {
        if (controlMode == on) return
        setControlMode(on)
        buzz(on)
        if (on) checkImeBridge()
    }
    /** Double-tap toggles control mode; the phone vibrates to confirm the
     *  switch, since there is no visible button left to show the mode. */
    private fun toggleControlMode() {
        val on = !controlMode
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
    /** A 36dp corner grip: three diagonal lines. `mirror` flips it for the
     *  bottom-left corner. Purely visual — the resize zone is detected from
     *  raw touch coordinates in [resizeCornerAt]. */
    private fun makeGrip(mirror: Boolean): View {
        val d = density
        return object : View(app) {
            private val gripPaint = android.graphics.Paint().apply {
                color = 0xBFFFFFFF.toInt(); strokeWidth = 2f * d
                strokeCap = android.graphics.Paint.Cap.ROUND
            }
            override fun onDraw(canvas: android.graphics.Canvas) {
                super.onDraw(canvas)
                val w = width.toFloat(); val h = height.toFloat()
                val mx = { f: Float -> if (mirror) 1f - f else f }
                canvas.drawLine(w * mx(0.85f), h * 0.30f, w * mx(0.30f), h * 0.85f, gripPaint)
                canvas.drawLine(w * mx(0.95f), h * 0.55f, w * mx(0.55f), h * 0.95f, gripPaint)
                canvas.drawLine(w * mx(0.70f), h * 0.10f, w * mx(0.10f), h * 0.70f, gripPaint)
            }
        }.apply {
            val gravity = Gravity.BOTTOM or if (mirror) Gravity.START else Gravity.END
            layoutParams = FrameLayout.LayoutParams((36f * d).roundToInt(), (36f * d).roundToInt(), gravity).apply {
                setMargins((2f * d).roundToInt(), 0, (2f * d).roundToInt(), (2f * d).roundToInt())
            }
        }
    }
    private fun buildViews(state: SessionState) {
        val d = density
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
                    // Control mode: content touches go to the app; only chrome,
                    // the resize grips, and the takeover zone still reach the
                    // window (move/resize, or toggle control mode).
                    contentTouch = controlMode && !chromeTouch && resizeCornerAt(event) == 0 && !isInTakeoverZone(event)
                }
                return !chromeTouch && !contentTouch
            }
            override fun onTouchEvent(event: MotionEvent): Boolean = onWindowTouch(event)
        }.apply {
            background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; setColor(0xFF000000.toInt()); cornerRadius = cornerPx }
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) { outline.setRoundRect(0, 0, view.width, view.height, cornerPx) }
            }
            clipToOutline = true
        }
        // The live SurfaceView cannot be view-clipped, so it sits 4dp inside the
        // rounded frame instead of filling it edge to edge.
        val inset = (4f * d).roundToInt()
        val contentView = FrameLayout(app).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT).apply {
                setMargins(inset, inset, inset, inset)
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
        // Bottom corner grips: three diagonal lines marking the drag-to-resize zones.
        val grip = makeGrip(false)
        rootView.addView(grip)
        resizeGrip = grip
        val gripLeft = makeGrip(true)
        rootView.addView(gripLeft)
        resizeGripLeft = gripLeft
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
        resizeGrip?.visibility = View.GONE
        resizeGripLeft?.visibility = View.GONE
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
        resizeGrip?.visibility = View.VISIBLE
        resizeGripLeft?.visibility = View.VISIBLE
        p.width = savedW; p.height = savedH
        p.x = savedX.coerceIn(0, (screenW - p.width).coerceAtLeast(0))
        p.y = savedY.coerceIn(0, (screenH - p.height).coerceAtLeast(0))
        updateLayout()
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
            if (target != null) openImeBridge(target.text ?: "") else closeImeBridge()
        }
    }
    private fun openImeBridge(initialText: String) {
        val sink = imeSink ?: return
        val p = params ?: return
        // Pre-fill with the field's current text so typing appends instead
        // of wiping it; only move the cursor when the text actually changed,
        // so a mid-typing refresh does not yank it.
        if (sink.text.toString() != initialText) {
            sink.setText(initialText)
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
        sink?.setText("")
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
    private fun isDoubleTap(event: MotionEvent): Boolean {
        val now = SystemClock.uptimeMillis()
        val dx = event.x - lastTapX; val dy = event.y - lastTapY
        val distDp = kotlin.math.hypot(dx.toDouble(), dy.toDouble()) / density
        val isDouble = now - lastTapTime < 300L && distDp < 20f
        if (isDouble) lastTapTime = 0L
        return isDouble
    }
    /** Keep the window glued to the live frame aspect: a stale or resized size
     *  otherwise leaves black bars on the sides (or top/bottom). Runs on every
     *  frame but is a no-op once the aspect matches (2% deadband). */
    private fun fitToFrameAspect() {
        if (collapsed) return
        val p = params ?: return
        if (p.width <= 0 || p.height <= 0) return
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
