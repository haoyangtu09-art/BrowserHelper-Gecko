/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.devtools

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Native confirmation gate for L3 (sensitive) agent tools.
 *
 * IRON RULE (bhcodex architecture): a sensitive tool may be treated as
 * user-approved ONLY if this native dialog returns true. The model / Termux side
 * can never pass a "confirmed" flag — approval originates solely from a real human
 * tap here, defending against web prompt-injection that claims "the user already
 * agreed".
 *
 * Fail-closed: no foreground activity, timeout, or deny → false.
 *
 * Called from the BrowserBridge connection thread; it blocks that thread (not the
 * UI thread) until the user responds. The dialog is shown on the tracked
 * foreground Activity, set via [setTopActivity] from the app's lifecycle callbacks.
 */
object AgentConfirm {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var top: WeakReference<Activity>? = null

    fun setTopActivity(a: Activity?) {
        top = if (a == null) null else WeakReference(a)
    }

    /**
     * Block the calling thread until the user responds to a native dialog, or until
     * [timeoutMs] elapses. Returns true ONLY on an explicit "allow" tap.
     */
    fun requireConfirm(title: String, summary: String, timeoutMs: Long = 60_000): Boolean {
        val act = top?.get()
        if (act == null || act.isFinishing) return false
        val latch = CountDownLatch(1)
        val allowed = AtomicBoolean(false)
        main.post {
            try {
                AlertDialog.Builder(act)
                    .setTitle(title)
                    .setMessage(summary)
                    .setCancelable(false)
                    .setPositiveButton("允许") { _, _ -> allowed.set(true); latch.countDown() }
                    .setNegativeButton("拒绝") { _, _ -> allowed.set(false); latch.countDown() }
                    .show()
            } catch (_: Throwable) {
                latch.countDown() // fail-closed: allowed stays false
            }
        }
        val responded = try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            false
        }
        return responded && allowed.get()
    }
}
