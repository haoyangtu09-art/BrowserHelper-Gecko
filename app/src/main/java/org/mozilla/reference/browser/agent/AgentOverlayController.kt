/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.agent

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast

/**
 * Start/stop gate for the floating Agent overlay, driven by the "web-agent" DevTools
 * plugin toggle (content script → DevToolsHelper.onPortMessage → here).
 *
 * Enabling requires the SYSTEM_ALERT_WINDOW ("draw over other apps") permission. If it
 * is missing we toast + jump straight to the system grant screen and remember the
 * intent in [pendingStart]; [onActivityResumed] (wired from BrowserApplication's
 * lifecycle callbacks) then auto-starts the overlay the moment the user returns with
 * the permission granted.
 */
object AgentOverlayController {

    @Volatile private var pendingStart = false

    fun enable(context: Context) {
        val app = context.applicationContext
        if (Settings.canDrawOverlays(app)) {
            pendingStart = false
            startOverlay(app)
        } else {
            pendingStart = true
            Toast.makeText(app, "网页 Agent 需要悬浮窗权限，请在接下来的页面授予后返回", Toast.LENGTH_LONG).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + app.packageName),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                app.startActivity(intent)
            } catch (_: Throwable) {
            }
        }
    }

    fun disable(context: Context) {
        pendingStart = false
        val app = context.applicationContext
        try {
            app.stopService(Intent(app, AgentOverlayService::class.java))
        } catch (_: Throwable) {
        }
    }

    /** Called from BrowserApplication.onActivityResumed: finish a deferred start once granted. */
    fun onActivityResumed(activity: Activity) {
        if (pendingStart && Settings.canDrawOverlays(activity)) {
            pendingStart = false
            startOverlay(activity.applicationContext)
        }
    }

    private fun startOverlay(app: Context) {
        try {
            app.startService(Intent(app, AgentOverlayService::class.java))
        } catch (_: Throwable) {
        }
    }
}
