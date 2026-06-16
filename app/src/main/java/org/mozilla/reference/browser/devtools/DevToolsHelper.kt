/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.devtools

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.webextension.MessageHandler
import mozilla.components.concept.engine.webextension.Port
import mozilla.components.concept.engine.webextension.WebExtensionRuntime
import mozilla.components.lib.state.ext.flow
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
    private var scope: CoroutineScope? = null
    private var lastRegisteredSession: EngineSession? = null

    fun install(runtime: WebExtensionRuntime, store: BrowserStore, context: Context) {
        this.store = store
        this.appContext = context.applicationContext

        controller.install(
            runtime,
            onSuccess = {
                // Register handler for the current tab immediately after install,
                // before the content script's connectNative() call is processed.
                mainHandler.post {
                    registerForCurrentTab()
                    observeTabChanges()
                }
            },
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
        if (!controller.portConnected(engineSession, CONTENT_PORT)) {
            // Re-register in case the session changed without us noticing, then retry once.
            registerForSession(engineSession)
            Toast.makeText(context, "DevTools 通道未就绪，请稍候再试", Toast.LENGTH_SHORT).show()
            return
        }
        controller.sendContentMessage(
            JSONObject().put("action", "toggle"),
            engineSession,
            CONTENT_PORT,
        )
    }

    private fun registerForCurrentTab() {
        val engineSession = store?.state?.selectedTab?.engineState?.engineSession ?: return
        registerForSession(engineSession)
    }

    private fun registerForSession(engineSession: EngineSession) {
        if (lastRegisteredSession === engineSession) return
        lastRegisteredSession = engineSession
        controller.registerContentMessageHandler(
            engineSession,
            object : MessageHandler {
                override fun onPortConnected(port: Port) {}
                override fun onPortDisconnected(port: Port) {
                    // Port disconnected (e.g. page navigation); clear so next
                    // toggle re-registers for the new session.
                    if (lastRegisteredSession === engineSession) {
                        lastRegisteredSession = null
                    }
                }
                override fun onPortMessage(message: Any, port: Port) {}
            },
            CONTENT_PORT,
        )
    }

    private fun observeTabChanges() {
        scope?.cancel()
        val currentStore = store ?: return
        scope = CoroutineScope(Dispatchers.Main)
        scope!!.launch {
            currentStore.flow()
                .map { state -> state.selectedTab?.engineState?.engineSession }
                .distinctUntilChanged()
                .collect { engineSession ->
                    if (engineSession != null) {
                        registerForSession(engineSession)
                    }
                }
        }
    }
}
