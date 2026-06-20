/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.agent

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import org.mozilla.reference.browser.agent.ui.OverlayRoot
import kotlin.math.roundToInt

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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        addOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun addOverlay() {
        if (root != null) return
        val owner = OverlayLifecycleOwner().also { it.onCreate() }
        lifecycleOwner = owner

        val compose = ComposeView(this).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent {
                OverlayRoot(
                    expanded = expandedState.value,
                    onExpand = { setExpanded(true) },
                    onCollapse = { setExpanded(false) },
                    onDrag = { dx, dy -> moveBy(dx, dy) },
                    onDragEnd = { snapToEdge() },
                    onClose = { stopSelf() },
                )
            }
        }
        val container = FrameLayout(this).apply { addView(compose) }

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
        expandedState.value = expanded
        val lp = params ?: return
        lp.flags = if (expanded) expandedFlags() else collapsedFlags()
        if (expanded) {
            // Anchor the panel near the top so it stays fully on screen.
            lp.x = 0
            lp.y = (resources.displayMetrics.heightPixels * 0.12f).roundToInt()
        }
        safeUpdate()
    }

    private fun moveBy(dx: Float, dy: Float) {
        if (expandedState.value) return
        val lp = params ?: return
        lp.x += dx.roundToInt()
        lp.y += dy.roundToInt()
        val maxX = resources.displayMetrics.widthPixels
        val maxY = resources.displayMetrics.heightPixels
        lp.x = lp.x.coerceIn(0, maxX)
        lp.y = lp.y.coerceIn(0, maxY)
        safeUpdate()
    }

    private fun snapToEdge() {
        if (expandedState.value) return
        val lp = params ?: return
        val screenW = resources.displayMetrics.widthPixels
        val viewW = root?.width ?: 0
        lp.x = if (lp.x + viewW / 2 < screenW / 2) 0 else (screenW - viewW)
        safeUpdate()
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
