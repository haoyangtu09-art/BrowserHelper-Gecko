/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.devtools

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import mozilla.components.concept.engine.webextension.MessageHandler
import mozilla.components.concept.engine.webextension.Port
import mozilla.components.concept.engine.webextension.WebExtensionRuntime
import mozilla.components.support.webextensions.BuiltInWebExtensionController
import org.json.JSONObject

object DevToolsHelper {
    private const val EXTENSION_ID = "devtools-injector@browserhelper.local"
    private const val EXTENSION_URL = "resource://android/assets/extensions/devtools_injector/"
    private const val NATIVE_APP = "devtools_inject"

    private val mainHandler = Handler(Looper.getMainLooper())
    private var port: Port? = null
    private var registered = false
    private var appContext: Context? = null
    private var pendingToggleContext: Context? = null

    private val controller = BuiltInWebExtensionController(EXTENSION_ID, EXTENSION_URL, NATIVE_APP)

    fun install(runtime: WebExtensionRuntime, context: Context) {
        appContext = context.applicationContext
        registerHandler()
        controller.install(
            runtime,
            onSuccess = {},
            onError = { throwable ->
                mainHandler.post {
                    appContext?.let {
                        Toast.makeText(it, "DevTools 扩展安装失败: ${throwable.message}", Toast.LENGTH_LONG).show()
                    }
                }
            },
        )
    }

    fun toggle(context: Context) {
        val activePort = port
        if (activePort != null) {
            sendToggle(activePort)
        } else {
            pendingToggleContext = context
            Toast.makeText(context, "正在连接 DevTools 通道…", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendToggle(activePort: Port) {
        activePort.postMessage(
            JSONObject()
                .put("id", System.currentTimeMillis().toString())
                .put("action", "toggle"),
        )
    }

    private fun registerHandler() {
        if (registered) return
        registered = true
        controller.registerBackgroundMessageHandler(
            object : MessageHandler {
                override fun onPortConnected(port: Port) {
                    this@DevToolsHelper.port = port
                    pendingToggleContext?.let { ctx ->
                        pendingToggleContext = null
                        sendToggle(port)
                        Toast.makeText(ctx, "DevTools 已开启", Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onPortDisconnected(port: Port) {
                    if (this@DevToolsHelper.port == port) {
                        this@DevToolsHelper.port = null
                    }
                }

                override fun onPortMessage(message: Any, port: Port) {
                    val data = message as? JSONObject ?: return
                    val error = data.optString("error")
                    if (error.isNotBlank()) {
                        mainHandler.post {
                            appContext?.let {
                                Toast.makeText(it, "DevTools 错误: $error", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
            },
            NATIVE_APP,
        )
    }
}
