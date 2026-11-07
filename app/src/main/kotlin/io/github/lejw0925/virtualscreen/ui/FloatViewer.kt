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
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import io.github.lejw0925.virtualscreen.VirtualScreenApplication
import io.github.lejw0925.virtualscreen.backend.LiveGeometry
import io.github.lejw0925.virtualscreen.core.SessionState
import io.github.lejw0925.virtualscreen.core.failure
import io.github.lejw0925.virtualscreen.core.runtime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Edge-collapsing floating viewer for the virtual display.
 *
 * Idea borrowed from Oo0520/Eta-por (VirtualScreenFloatWindow: triggerBar, sinkToEdge,
 * collapsed state); this is an original implementation against this app's LiveDisplayView
 * and preview channel — no Eta code is copied.
 *
 * Monitor-only, like Eta's observe mode: content touches never inject from the float
 * window. Single-finger drag moves it, dragging either bottom corner grip resizes it,
 * double-tap goes fullscreen, dragging
 * to a screen edge collapses it to a narrow trigger bar (tap the bar, or drag it outward,
 * to expand back). Take over from the fullscreen viewer instead.
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
    private var closeButton: ImageButton? = null
    private var takeoverButton: TextView? = null
    /** True while the user is directly operating the float window's content. */
    private var controlMode = false
    private var contentTouch = false
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
    // The root intercepts every touch except ones starting on a chrome control
    // (close button, takeover button) or the trigger bar, so window gestures
    // always win and content touches never inject — unless control mode is on,
    // in which case content touches go to the app and only chrome and the
    // resize grips still move/resize the window.
    private fun isOnChrome(event: MotionEvent): Boolean {
        val targets = if (collapsed) listOfNotNull(triggerBar) else listOfNotNull(closeButton, takeoverButton)
        return targets.any { view ->
            if (view.visibility != View.VISIBLE) return@any false
            val loc = IntArray(2); view.getLocationOnScreen(loc)
            event.rawX >= loc[0] && event.rawX < loc[0] + view.width &&
                event.rawY >= loc[1] && event.rawY < loc[1] + view.height
        }
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
                    savePosition()
                } else if (!moved) {
                    if (isDoubleTap(event)) goFullscreen()
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
                    else savePosition()
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
        if (root != null) savePosition()
        setControlMode(false)
        stateJob?.cancel(); stateJob = null
        previewJob?.cancel(); previewJob = null
        root?.removeCallbacks(sinkRunnable)
        runCatching { app.unregisterComponentCallbacks(configCallbacks) }
        liveView?.setResumed(false)
        liveView?.dispose()
        liveView = null; closeButton = null; takeoverButton = null; resizeGrip = null; resizeGripLeft = null
        root?.let { runCatching { windowManager.removeView(it) } }
        root = null; content = null; triggerBar = null; params = null
        collapsed = false; collapsedDuringDrag = false
    }

    // Control mode: the float window's content becomes directly operable
    // (taps and scrolls inject into the virtual display, same as the
    // fullscreen viewer). Off by default — the window always pops up
    // view-only. Turning it off cancels any in-flight gesture.
    private fun setControlMode(on: Boolean) {
        if (controlMode == on) return
        controlMode = on
        if (!on) liveView?.cancelGesture()
        takeoverButton?.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0x00000000)
            setStroke((1.5f * density).roundToInt(), if (on) 0x992E9E44.toInt() else 0x33FFFFFF)
        }
        app.activityLog.runtime("float_viewer", "control_mode", if (on) "on" else "off")
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
                    // Control mode: content touches go to the app; only chrome
                    // and the resize grips still move/resize the window.
                    contentTouch = controlMode && !chromeTouch && resizeCornerAt(event) == 0
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
            onManualComplete = { token -> app.sessions.finishPhoneControl(token) }
            onCompatibilityInput = { op -> scope.launch { runCatching { app.sessions.phoneTakeover(); phoneInput(app, op) }.onFailure { app.activityLog.runtime("float_viewer", "control_input", "failed") } } }
            onError = { code -> app.activityLog.runtime("float_viewer", "error", code) }
        }
        contentView.addView(viewer)
        val close = ImageButton(app).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x80000000.toInt()) }
            layoutParams = FrameLayout.LayoutParams((32f * d).roundToInt(), (32f * d).roundToInt(), Gravity.TOP or Gravity.END).apply {
                setMargins(0, (6f * d).roundToInt(), (6f * d).roundToInt(), 0)
            }
            setOnClickListener { hide(); suppressAutoShow() }
        }
        contentView.addView(close)
        closeButton = close
        // Takeover toggle (replaces the #14 keyboard bridge): the float window
        // still pops up view-only by default; tapping this makes its content
        // directly operable — taps and scrolls go into the app, and tapping a
        // text field pops the keyboard up (display IME policy, #15). Tapping
        // again returns to view-only.
        // Takeover toggle: as unobtrusive as possible — no text, transparent
        // fill, only a faint outline ring so it can still be found and tapped.
        // The ring turns green in control mode so the mode is visible.
        val takeover = TextView(app).apply {
            text = ""
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x00000000)
                setStroke((1.5f * d).roundToInt(), 0x33FFFFFF)
            }
            layoutParams = FrameLayout.LayoutParams((32f * d).roundToInt(), (32f * d).roundToInt(), Gravity.TOP or Gravity.START).apply {
                setMargins((6f * d).roundToInt(), (6f * d).roundToInt(), 0, 0)
            }
            setOnClickListener { setControlMode(!controlMode) }
        }
        contentView.addView(takeover)
        takeoverButton = takeover
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
        savePosition()
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
    private fun goFullscreen() {
        suppressAutoShow()
        hide()
        app.startActivity(Intent(app, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
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
        val x = prefs.getInt("pos_x", -1); val y = prefs.getInt("pos_y", -1)
        if (x < 0 || y < 0) return (screenW - w - (16f * density).roundToInt()).coerceAtLeast(0) to (screenH / 4).coerceAtLeast(0)
        return x.coerceIn(0, (screenW - w).coerceAtLeast(0)) to y.coerceIn(0, (screenH - h).coerceAtLeast(0))
    }
    private fun savePosition() {
        if (collapsed) {
            // Collapsed into the edge bar: persist the pre-collapse geometry,
            // not the bar's own position — the bar is not a place to restore to.
            if (savedW > 0 && savedH > 0) {
                prefs.edit().putInt("pos_x", savedX).putInt("pos_y", savedY).putInt("width", savedW).putInt("height", savedH).apply()
            }
            return
        }
        val p = params ?: return
        prefs.edit().putInt("pos_x", p.x).putInt("pos_y", p.y).putInt("width", p.width).putInt("height", p.height).apply()
    }
    private fun updateLayout() {
        val view = root ?: return; val p = params ?: return
        runCatching { windowManager.updateViewLayout(view, p) }
    }
}
