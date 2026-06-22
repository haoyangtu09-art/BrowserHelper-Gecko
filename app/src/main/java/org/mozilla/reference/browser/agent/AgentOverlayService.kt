/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.agent

import android.animation.ValueAnimator
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.MediaStore
import android.view.Gravity
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import java.io.File
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import org.mozilla.reference.browser.agent.ui.OverlayRoot
import kotlin.math.roundToInt

// Outer padding around the panel composable (8dp each side → 16dp total).
private const val PANEL_PADDING_DP = 16f

// Base (scale = 1f) panel content size in dp; must match OverlayRoot's PANEL_BASE_*_DP.
// The panel is laid out at this size and uniformly scaled, so one factor resizes the
// whole UI. Resize is clamped between MIN/MAX scale and the screen bounds.
private const val PANEL_BASE_W_DP = 320f
private const val PANEL_BASE_H_DP = 520f
private const val MIN_PANEL_SCALE = 0.75f
private const val MAX_PANEL_SCALE = 1.6f

// Ball composable total width: 49dp disc + 6dp outer padding on each side.
private const val BALL_TOTAL_DP = 61f

// Idle delay before a parked ball dims and tucks half off the edge.
private const val IDLE_DELAY_MS = 2500L

/**
 * Hosts the floating Agent UI in a single WindowManager overlay window.
 *
 * The window wraps its Compose content, so it is small in the collapsed (white ball)
 * state and grows to the chat panel when expanded. Two flag sets matter:
 *  - collapsed: FLAG_NOT_FOCUSABLE so the ball never steals focus / the back button.
 *  - expanded: focusable (flag cleared) so the panel's text field can receive IME —
 *    this is the whole reason the chat UI is native and not a Gecko shadow-DOM input.
 */
class AgentOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var root: FrameLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var lifecycleOwner: OverlayLifecycleOwner? = null

    private val expandedState = mutableStateOf(false)
    private val anchorRightState = mutableStateOf(false)
    private val dimmedState = mutableStateOf(false)
    private val panelScaleState = mutableStateOf(1f)
    private var snapAnimator: ValueAnimator? = null
    private var idleHidden = false
    private val idleRunnable = Runnable { enterIdle() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        installCrashDump()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        addOverlay()
    }

    // Diagnostic: capture the real crash stack to Download/agent_crash.txt so it can be
    // read without root/logcat. Chains to the previous handler so the normal crash flow
    // is preserved. Remove once the overlay is stable.
    private fun installCrashDump() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, ex ->
            try { dumpCrash(ex) } catch (_: Throwable) {}
            prev?.uncaughtException(thread, ex)
        }
    }

    private fun dumpCrash(ex: Throwable) {
        val text = "AgentOverlay crash @${System.currentTimeMillis()}\n" + ex.stackTraceToString()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = contentResolver
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            resolver.query(
                collection,
                arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.DISPLAY_NAME}=?",
                arrayOf("agent_crash.txt"),
                null,
            )?.use { c ->
                val idIdx = c.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                while (c.moveToNext()) {
                    resolver.delete(
                        android.content.ContentUris.withAppendedId(collection, c.getLong(idIdx)),
                        null,
                        null,
                    )
                }
            }
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "agent_crash.txt")
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            }
            val uri = resolver.insert(collection, values) ?: return
            resolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
        } else {
            val dir = getExternalFilesDir(null) ?: filesDir
            File(dir, "agent_crash.txt").outputStream().use { it.write(text.toByteArray()) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    private fun addOverlay() {
        if (root != null) return
        val owner = OverlayLifecycleOwner().also { it.onCreate() }
        lifecycleOwner = owner

        val compose = ComposeView(this).apply {
            setContent {
                OverlayRoot(
                    expanded = expandedState.value,
                    anchorRight = anchorRightState.value,
                    dimmed = dimmedState.value,
                    panelScale = panelScaleState.value,
                    onExpand = { setExpanded(true) },
                    onCollapse = { setExpanded(false) },
                    onWake = { wake() },
                    onBallDrag = { dx, dy -> moveBy(dx, dy) },
                    onBallDragEnd = { snapToEdge() },
                    onPanelDrag = { dx, dy -> movePanelBy(dx, dy) },
                    onPanelResize = { dx, dy -> resizePanelBy(dx, dy) },
                )
            }
        }
        // The ViewTree owners must live on the window's ROOT view: Compose builds its
        // window recomposer by walking UP from the root to find a ViewTreeLifecycleOwner.
        // Setting them only on the child ComposeView made that lookup start at the
        // FrameLayout root and find nothing → "ViewTreeLifecycleOwner not found" crash.
        val container = FrameLayout(this).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            addView(compose)
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            collapsedFlags(),
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = (resources.displayMetrics.heightPixels * 0.30f).roundToInt()
        }
        params = lp
        root = container
        try {
            windowManager.addView(container, lp)
            scheduleIdle()
        } catch (_: Throwable) {
            root = null
            stopSelf()
        }
    }

    private fun collapsedFlags(): Int =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

    private fun expandedFlags(): Int =
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

    private fun setExpanded(expanded: Boolean) {
        val lp = params ?: return
        if (expanded) {
            cancelSnap()
            cancelIdle()
            idleHidden = false
            dimmedState.value = false
            val screenW = resources.displayMetrics.widthPixels
            val ballCenter = lp.x + (root?.width ?: 0) / 2
            val toRight = ballCenter >= screenW / 2
            anchorRightState.value = toRight
            expandedState.value = true
            lp.flags = expandedFlags()
            // Open on the side the ball sits on, anchored near the top so it stays fully on screen.
            val panelW = ((PANEL_BASE_W_DP * panelScaleState.value + PANEL_PADDING_DP) * resources.displayMetrics.density).roundToInt()
            val panelH = ((PANEL_BASE_H_DP * panelScaleState.value + PANEL_PADDING_DP) * resources.displayMetrics.density).roundToInt()
            lp.x = if (toRight) (screenW - panelW).coerceAtLeast(0) else 0
            val targetY = (resources.displayMetrics.heightPixels * 0.15f).roundToInt()
            lp.y = targetY.coerceAtMost((resources.displayMetrics.heightPixels - panelH).coerceAtLeast(0))
            safeUpdate()
        } else {
            expandedState.value = false
            lp.flags = collapsedFlags()
            // The window shrinks back to the ball immediately (SizeTransform snaps), so
            // place its x on the anchored edge right now. A right-anchored panel otherwise
            // left the ball stranded mid-screen (window x was the panel's left), then it
            // visibly slid to the edge. Setting x synchronously lets the panel collapse
            // straight into the correct top corner — symmetric with the left side.
            val toRight = anchorRightState.value
            val screenW = resources.displayMetrics.widthPixels
            val ballW = (BALL_TOTAL_DP * resources.displayMetrics.density).roundToInt()
            lp.x = if (toRight) (screenW - ballW).coerceAtLeast(0) else 0
            safeUpdate()
            // Once the collapse animation settles, correct x to the measured ball width
            // (estimate may be a few px off) and re-arm the idle dim/tuck timer.
            root?.postDelayed({
                if (expandedState.value) return@postDelayed
                val w = resources.displayMetrics.widthPixels
                val measured = root?.width ?: ballW
                params?.x = if (anchorRightState.value) (w - measured).coerceAtLeast(0) else 0
                safeUpdate()
                scheduleIdle()
            }, 340L)
        }
    }

    private fun moveBy(dx: Float, dy: Float) {
        if (expandedState.value) return
        cancelSnap()
        cancelIdle()
        moveWindowBy(dx, dy)
    }

    /** Wake a parked ball: cancel the idle timer, un-dim, and slide back fully on-screen. */
    private fun wake() {
        if (expandedState.value) return
        cancelIdle()
        dimmedState.value = false
        if (!idleHidden) return
        idleHidden = false
        val lp = params ?: return
        val screenW = resources.displayMetrics.widthPixels
        val viewW = root?.width ?: 0
        val onLeft = lp.x + viewW / 2 < screenW / 2
        val target = if (onLeft) 0 else (screenW - viewW).coerceAtLeast(0)
        animateBallX(lp.x, target)
    }

    private fun scheduleIdle() {
        val container = root ?: return
        container.removeCallbacks(idleRunnable)
        if (expandedState.value) return
        container.postDelayed(idleRunnable, IDLE_DELAY_MS)
    }

    private fun cancelIdle() {
        root?.removeCallbacks(idleRunnable)
    }

    /** After resting at the edge, tuck half the ball off-screen and dim it so it stops
     *  competing for attention. Any touch (onWake) brings it back. */
    private fun enterIdle() {
        if (expandedState.value) return
        val lp = params ?: return
        val screenW = resources.displayMetrics.widthPixels
        val viewW = root?.width ?: 0
        if (viewW == 0) return
        val onLeft = lp.x + viewW / 2 < screenW / 2
        val target = if (onLeft) -(viewW / 2) else screenW - viewW / 2
        idleHidden = true
        dimmedState.value = true
        animateBallX(lp.x, target)
    }

    /** Drag the expanded panel freely (no edge snapping). */
    private fun movePanelBy(dx: Float, dy: Float) {
        if (!expandedState.value) return
        moveWindowBy(dx, dy)
    }

    /**
     * Resize the expanded panel from its bottom-right grip with a single uniform scale
     * factor, so the whole UI (floating buttons, text, input) grows/shrinks together.
     * The diagonal drag amount drives the scale; it is clamped to MIN/MAX and the screen
     * bounds. For a right-anchored panel the window's left edge is shifted so the right
     * edge stays pinned to where the panel opened.
     */
    private fun resizePanelBy(dx: Float, dy: Float) {
        if (!expandedState.value) return
        val density = resources.displayMetrics.density
        val screenW = resources.displayMetrics.widthPixels / density
        val screenH = resources.displayMetrics.heightPixels / density
        val maxScaleW = (screenW - PANEL_PADDING_DP) / PANEL_BASE_W_DP
        val maxScaleH = (screenH - PANEL_PADDING_DP) / PANEL_BASE_H_DP
        val maxScale = minOf(MAX_PANEL_SCALE, maxScaleW, maxScaleH).coerceAtLeast(MIN_PANEL_SCALE)
        val oldScale = panelScaleState.value
        // Use the dominant drag axis (average) over the base width to derive a scale delta.
        val deltaScale = ((dx + dy) / 2f / density) / PANEL_BASE_W_DP
        val newScale = (oldScale + deltaScale).coerceIn(MIN_PANEL_SCALE, maxScale)
        if (newScale == oldScale) return
        panelScaleState.value = newScale
        if (anchorRightState.value) {
            val deltaPx = (PANEL_BASE_W_DP * (newScale - oldScale) * density).roundToInt()
            val lp = params ?: return
            lp.x = (lp.x - deltaPx).coerceAtLeast(0)
        }
        root?.requestLayout()
        safeUpdate()
    }

    private fun moveWindowBy(dx: Float, dy: Float) {
        val lp = params ?: return
        lp.x += dx.roundToInt()
        lp.y += dy.roundToInt()
        val maxX = (resources.displayMetrics.widthPixels - (root?.width ?: 0)).coerceAtLeast(0)
        val maxY = (resources.displayMetrics.heightPixels - (root?.height ?: 0)).coerceAtLeast(0)
        lp.x = lp.x.coerceIn(0, maxX)
        lp.y = lp.y.coerceIn(0, maxY)
        safeUpdate()
    }

    private fun snapToEdge() {
        if (expandedState.value) return
        val lp = params ?: return
        val screenW = resources.displayMetrics.widthPixels
        val viewW = root?.width ?: 0
        val target = if (lp.x + viewW / 2 < screenW / 2) 0 else (screenW - viewW).coerceAtLeast(0)
        animateBallX(lp.x, target)
        // Re-arm the idle dim/tuck after the snap settles (idle delay >> snap duration).
        scheduleIdle()
    }

    /** Animate the collapsed ball window's x to the snapped edge. */
    private fun animateBallX(from: Int, to: Int) {
        cancelSnap()
        if (from == to) return
        val anim = ValueAnimator.ofInt(from, to).apply {
            duration = 380
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener { a ->
                if (expandedState.value) {
                    cancel()
                    return@addUpdateListener
                }
                val lp = params ?: return@addUpdateListener
                lp.x = a.animatedValue as Int
                safeUpdate()
            }
        }
        snapAnimator = anim
        anim.start()
    }

    private fun cancelSnap() {
        snapAnimator?.cancel()
        snapAnimator = null
    }

    private fun safeUpdate() {
        val container = root ?: return
        val lp = params ?: return
        try {
            windowManager.updateViewLayout(container, lp)
        } catch (_: Throwable) {
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelSnap()
        cancelIdle()
        root?.let { container ->
            try {
                windowManager.removeView(container)
            } catch (_: Throwable) {
            }
        }
        root = null
        lifecycleOwner?.onDestroy()
        lifecycleOwner = null
    }
}
