/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import mozilla.components.browser.engine.gecko.GeckoEngine
import mozilla.components.browser.engine.gecko.fetch.GeckoViewFetchClient
import mozilla.components.concept.engine.DefaultSettings
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.fetch.Client
import mozilla.components.feature.webcompat.WebCompatFeature
import mozilla.components.lib.crash.handler.CrashHandlerService
import org.mozilla.geckoview.GeckoPreferenceController
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.reference.browser.devtools.MitmCa
import java.security.KeyStore

object EngineProvider {
    private const val SPIKE_TAG = "BHProxySpike"
    private var runtime: GeckoRuntime? = null

    @Synchronized
    fun getOrCreateRuntime(context: Context): GeckoRuntime {
        if (runtime == null) {
            val builder = GeckoRuntimeSettings.Builder()

            builder.crashHandler(CrashHandlerService::class.java)

            // About config it's no longer enabled by default
            builder.aboutConfigEnabled(true)
            builder.extensionsWebAPIEnabled(true)

            // Official path to import the Android user CA store into Gecko's NSS
            // trust set (for the local TLS-terminating debug proxy). This wires up
            // GeckoView's EnterpriseRoots.gatherEnterpriseRoots() at runtime init,
            // unlike poking the raw pref via GeckoPreferenceController which may not
            // trigger the Java-side gather on this GeckoView version.
            builder.enterpriseRootsEnabled(true)

            // Render web content slightly smaller than the device default so
            // pages show more at once. displayDensityOverride takes an absolute
            // density, so scale the device density rather than hardcoding.
            val deviceDensity = context.resources.displayMetrics.density
            builder.displayDensityOverride(deviceDensity * 0.85f)

            runtime = GeckoRuntime.create(context, builder.build())

            // Phase 0 spike (MITM-proxy plan): confirm we can set arbitrary Gecko
            // prefs programmatically on this GeckoView version. enterprise_roots
            // makes Gecko's NSS also trust the Android system CA store — a
            // prerequisite for the planned local TLS-terminating proxy. Harmless
            // to normal browsing; result is surfaced as a Toast for on-device check.
            verifyPrefMechanism(context.applicationContext)
            diagnoseUserCaStore(context.applicationContext)

            // The proxy probe writes persistent USER proxy prefs, but its local
            // server dies with the process. On a fresh launch those stale prefs
            // would point at a dead port and break all browsing, so reset to
            // direct here; the user re-arms the probe manually.
            org.mozilla.reference.browser.devtools.ProxyProbe.resetProxyStateOnStartup(
                context.applicationContext,
            )
        }

        return runtime!!
    }

    private fun verifyPrefMechanism(appContext: Context) {
        val main = Handler(Looper.getMainLooper())
        fun report(msg: String) {
            Log.i(SPIKE_TAG, msg)
            main.post { Toast.makeText(appContext, msg, Toast.LENGTH_LONG).show() }
        }
        try {
            GeckoPreferenceController.setGeckoPref(
                "security.enterprise_roots.enabled",
                true,
                GeckoPreferenceController.PREF_BRANCH_USER,
            ).accept(
                { _ -> report("PROXY-SPIKE: enterprise_roots set OK") },
                { e -> report("PROXY-SPIKE: set FAILED: ${e?.message}") },
            )
        } catch (t: Throwable) {
            report("PROXY-SPIKE: API threw: ${t.message}")
        }
    }

    /**
     * Diagnostic: enumerate the same Android keystore GeckoView's EnterpriseRoots
     * reads ("AndroidCAStore", "user:"-prefixed aliases) and check, by exact DER
     * byte match, whether our current signing root CA is actually installed there.
     * This avoids the Chinese-CN encoding ambiguity of name matching and tells us
     * definitively whether the trust failure is "CA not installed" vs "installed
     * but Gecko didn't import it".
     */
    private fun diagnoseUserCaStore(appContext: Context) {
        val main = Handler(Looper.getMainLooper())
        fun report(msg: String) {
            Log.i(SPIKE_TAG, msg)
            main.post { Toast.makeText(appContext, msg, Toast.LENGTH_LONG).show() }
        }
        try {
            val ourRootDer = MitmCa.rootCertDer(appContext)
            val ks = KeyStore.getInstance("AndroidCAStore")
            ks.load(null)
            var userCount = 0
            var match = false
            val aliases = ks.aliases()
            while (aliases.hasMoreElements()) {
                val alias = aliases.nextElement()
                if (!alias.startsWith("user:")) continue
                userCount++
                val cert = ks.getCertificate(alias) ?: continue
                if (cert.encoded.contentEquals(ourRootDer)) match = true
            }
            report("PROXY-SPIKE: 用户CA库=${userCount}条, 抓包前置已安装=${if (match) "YES" else "NO"}")
        } catch (t: Throwable) {
            report("PROXY-SPIKE: 读用户CA库失败: ${t.message}")
        }
    }

    fun createEngine(
        context: Context,
        defaultSettings: DefaultSettings,
    ): Engine {
        val runtime = getOrCreateRuntime(context)

        return GeckoEngine(context, defaultSettings, runtime).also {
            WebCompatFeature.install(it)
        }
    }

    fun createClient(context: Context): Client {
        val runtime = getOrCreateRuntime(context)
        return GeckoViewFetchClient(context, runtime)
    }
}
