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

// Panel composable total width: 320dp content + 8dp outer padding on each side.
private const val PANEL_TOTAL_DP = 336f

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
    private var snapAnimator: ValueAnimator? = null

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
                    onExpand = { setExpanded(true) },
                    onCollapse = { setExpanded(false) },
                    onBallDrag = { dx, dy -> moveBy(dx, dy) },
                    onBallDragEnd = { snapToEdge() },
                    onPanelDrag = { dx, dy -> movePanelBy(dx, dy) },
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
            val screenW = resources.displayMetrics.widthPixels
            val ballCenter = lp.x + (root?.width ?: 0) / 2
            val toRight = ballCenter >= screenW / 2
            anchorRightState.value = toRight
            expandedState.value = true
            lp.flags = expandedFlags()
            // Open on the side the ball sits on, anchored near the top so it stays fully on screen.
            val panelW = (PANEL_TOTAL_DP * resources.displayMetrics.density).roundToInt()
            lp.x = if (toRight) (screenW - panelW).coerceAtLeast(0) else 0
            lp.y = (resources.displayMetrics.heightPixels * 0.12f).roundToInt()
            safeUpdate()
        } else {
            expandedState.value = false
            lp.flags = collapsedFlags()
            safeUpdate()
            // Let the collapse animation finish (so the window has shrunk back to the
            // ball) before sliding the ball onto the edge it was anchored to. Snapping by
            // measured center would pick the wrong edge for a right-anchored panel, so use
            // the stored anchor side directly.
            val toRight = anchorRightState.value
            root?.postDelayed({
                if (expandedState.value) return@postDelayed
                val screenW = resources.displayMetrics.widthPixels
                val ballW = root?.width ?: 0
                val target = if (toRight) (screenW - ballW).coerceAtLeast(0) else 0
                animateBallX(params?.x ?: 0, target)
            }, 320L)
        }
    }

    private fun moveBy(dx: Float, dy: Float) {
        if (expandedState.value) return
        cancelSnap()
        moveWindowBy(dx, dy)
    }

    /** Drag the expanded panel freely (no edge snapping). */
    private fun movePanelBy(dx: Float, dy: Float) {
        if (!expandedState.value) return
        moveWindowBy(dx, dy)
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
