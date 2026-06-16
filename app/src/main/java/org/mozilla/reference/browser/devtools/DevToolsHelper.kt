/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.devtools

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.webextension.MessageHandler
import mozilla.components.concept.engine.webextension.Port
import mozilla.components.concept.engine.webextension.WebExtensionRuntime
import mozilla.components.support.webextensions.BuiltInWebExtensionController
import org.json.JSONObject

object DevToolsHelper {
    private const val EXTENSION_ID = "devtools-injector@browserhelper.local"
    private const val EXTENSION_URL = "resource://android/assets/extensions/devtools_injector/"
    private const val CONTENT_PORT = "devtools_inject"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val controller = BuiltInWebExtensionController(EXTENSION_ID, EXTENSION_URL, CONTENT_PORT)

    private var store: BrowserStore? = null
    private var appContext: Context? = null

    // Track which EngineSession we last registered a content handler for,
    // so we can re-register when the selected tab changes.
    private var registeredSession: EngineSession? = null

    fun install(runtime: WebExtensionRuntime, store: BrowserStore, context: Context) {
        this.store = store
        this.appContext = context.applicationContext
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
        val engineSession = store?.state?.selectedTab?.engineState?.engineSession
        if (engineSession == null) {
            Toast.makeText(context, "没有活动的页面", Toast.LENGTH_SHORT).show()
            return
        }
        // Register content handler for this session if not already done.
        ensureContentHandlerRegistered(engineSession)

        if (!controller.portConnected(engineSession, CONTENT_PORT)) {
            Toast.makeText(context, "DevTools 通道未就绪，请稍候再试", Toast.LENGTH_SHORT).show()
            return
        }
        controller.sendContentMessage(
            JSONObject().put("action", "toggle"),
            engineSession,
            CONTENT_PORT,
        )
    }

    private fun ensureContentHandlerRegistered(engineSession: EngineSession) {
        if (registeredSession === engineSession) return
        registeredSession = engineSession
        controller.registerContentMessageHandler(
            engineSession,
            object : MessageHandler {
                override fun onPortConnected(port: Port) {}
                override fun onPortDisconnected(port: Port) {}
                override fun onPortMessage(message: Any, port: Port) {}
            },
            CONTENT_PORT,
        )
    }
}
