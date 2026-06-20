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
import mozilla.components.feature.session.SessionUseCases
import mozilla.components.lib.state.ext.flow
import mozilla.components.support.webextensions.BuiltInWebExtensionController
import org.json.JSONObject

object DevToolsHelper {
    private const val EXTENSION_ID = "netdebug@browserhelper.local"
    private const val EXTENSION_URL = "resource://android/assets/extensions/devtools_injector/"
    private const val CONTENT_PORT = "devtools_inject"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val controller = BuiltInWebExtensionController(EXTENSION_ID, EXTENSION_URL, CONTENT_PORT)

    private var store: BrowserStore? = null
    private var sessionUseCases: SessionUseCases? = null
    private var appContext: Context? = null
    private var scope: CoroutineScope? = null

    // Sessions that already have a handler registered.
    private val registeredSessions = mutableSetOf<EngineSession>()

    // If toggle was requested but port wasn't ready yet (page was reloading),
    // fire it as soon as the port connects.
    private var pendingToggle = false

    fun install(
        runtime: WebExtensionRuntime,
        store: BrowserStore,
        sessionUseCases: SessionUseCases,
        context: Context,
    ) {
        this.store = store
        this.sessionUseCases = sessionUseCases
        this.appContext = context.applicationContext

        // Forward decrypted-flow metadata from the MITM proxy to the active tab's
        // DevTools panel (display only).
        ProxyProbe.setChannel { obj -> emitToPanel(obj) }

        // Start the local MCP bridge so the Termux-side agent (bhcodex) can reach
        // BrowserHelper's tools over localhost (token-gated). Localhost-only +
        // bearer token; Phase 1 exposes read-only network tools.
        try { BrowserBridge.start(context.applicationContext) } catch (_: Throwable) {}

        controller.install(
            runtime,
            onSuccess = {
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
        val connected = controller.portConnected(engineSession, CONTENT_PORT)
        val registered = registeredSessions.contains(engineSession)
        if (connected) {
            sendToggle(engineSession)
            Toast.makeText(context, "DevTools 指令已发送", Toast.LENGTH_SHORT).show()
        } else {
            pendingToggle = true
            Toast.makeText(
                context,
                "通道未就绪(已注册:$registered)，刷新页面中…",
                Toast.LENGTH_SHORT,
            ).show()
            sessionUseCases?.reload?.invoke()
        }
    }

    private fun sendToggle(engineSession: EngineSession) {
        controller.sendContentMessage(
            JSONObject().put("action", "toggle"),
            engineSession,
            CONTENT_PORT,
        )
    }

    private fun makeHandler(engineSession: EngineSession) = object : MessageHandler {
        override fun onPortConnected(port: Port) {
            if (pendingToggle) {
                pendingToggle = false
                sendToggle(engineSession)
            }
            // Tell the freshly-connected panel the real proxy state so its 监听
            // button reflects reality (the proxy is reset to OFF on cold launch).
            sendProxyState(engineSession)
        }
        override fun onPortDisconnected(port: Port) {
            registeredSessions.remove(engineSession)
        }
        override fun onPortMessage(message: Any, port: Port) {
                val data = message as? JSONObject ?: return
                val action = data.optString("action", "")
                if (action == "setReplaceRules") {
                    val arr = data.optJSONArray("rules")
                    val rules = ArrayList<Pair<String, String>>()
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val o = arr.optJSONObject(i) ?: continue
                            val from = o.optString("from", "")
                            if (from.isNotEmpty()) rules.add(from to o.optString("to", ""))
                        }
                    }
                    ProxyProbe.setReplaceRules(
                        data.optBoolean("enabled", false),
                        data.optString("scope", "both"),
                        rules,
                    )
                    return
                }
                if (action == "setInterceptRules") {
                    ProxyProbe.setInterceptRules(data)
                    return
                }
                if (action == "resolveIntercept") {
                    ProxyProbe.resolveIntercept(data.optString("flowId", ""), data)
                    return
                }
                if (action == "resolveRespIntercept") {
                    ProxyProbe.resolveRespIntercept(data.optString("flowId", ""), data)
                    return
                }
                if (action == "setSseHoldConfig") {
                    ProxyProbe.setSseHoldConfig(data)
                    return
                }
                if (action == "setMockRules") {
                    ProxyProbe.setMockRules(data)
                    return
                }
                if (action == "setThrottle") {
                    ProxyProbe.setThrottle(data)
                    return
                }
                if (action.isNotEmpty()) {
                    handlePanelAction(action, engineSession)
                    return
                }
                val status = data.optString("status", "")
                if (status.isNotEmpty()) {
                    mainHandler.post {
                        appContext?.let {
                            Toast.makeText(it, "DevTools: $status", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
    }

    /** Panel → native commands (proxy on/off, export CA) sent via port.postMessage. */
    private fun handlePanelAction(action: String, engineSession: EngineSession) {
        val ctx = appContext ?: return
        mainHandler.post {
            when (action) {
                "proxyStart" -> { ProxyProbe.setEnabled(ctx, true); sendProxyState(engineSession) }
                "proxyStop" -> { ProxyProbe.setEnabled(ctx, false); sendProxyState(engineSession) }
                "exportCa" -> {
                    try {
                        val path = MitmCa.exportRootCert(ctx)
                        Toast.makeText(
                            ctx,
                            "根证书已存到 $path\n请到 设置→安全→加密与凭据→安装证书→CA证书 选择它",
                            Toast.LENGTH_LONG,
                        ).show()
                    } catch (t: Throwable) {
                        Toast.makeText(ctx, "导出根证书失败: ${t.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    /** Push the current proxy running state to the panel (channel "proxy"). */
    private fun sendProxyState(engineSession: EngineSession) {
        if (!controller.portConnected(engineSession, CONTENT_PORT)) return
        controller.sendContentMessage(
            JSONObject().put("ch", "proxy").put("type", "proxyState").put("running", ProxyProbe.isRunning()),
            engineSession,
            CONTENT_PORT,
        )
    }

    /** Send a proxy flow event to the active tab's content port, if connected. */
    private fun emitToPanel(obj: JSONObject) {
        mainHandler.post {
            val engineSession = store?.state?.selectedTab?.engineState?.engineSession ?: return@post
            if (controller.portConnected(engineSession, CONTENT_PORT)) {
                controller.sendContentMessage(obj, engineSession, CONTENT_PORT)
            }
        }
    }

    private fun registerForCurrentTab() {
        val engineSession = store?.state?.selectedTab?.engineState?.engineSession ?: return
        registerForSession(engineSession)
    }

    private fun registerForSession(engineSession: EngineSession) {
        if (registeredSessions.contains(engineSession)) return
        registeredSessions.add(engineSession)
        controller.registerContentMessageHandler(engineSession, makeHandler(engineSession), CONTENT_PORT)
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
                    if (engineSession != null) registerForSession(engineSession)
                }
        }
    }
}
