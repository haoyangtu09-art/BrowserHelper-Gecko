/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser

import android.content.Context
import mozilla.components.browser.engine.gecko.GeckoEngine
import mozilla.components.browser.engine.gecko.fetch.GeckoViewFetchClient
import mozilla.components.concept.engine.DefaultSettings
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.fetch.Client
import mozilla.components.feature.webcompat.WebCompatFeature
import mozilla.components.lib.crash.handler.CrashHandlerService
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings

object EngineProvider {
    private var runtime: GeckoRuntime? = null

    @Synchronized
    fun getOrCreateRuntime(context: Context): GeckoRuntime {
        if (runtime == null) {
            val builder = GeckoRuntimeSettings.Builder()

            builder.crashHandler(CrashHandlerService::class.java)

            // About config it's no longer enabled by default
            builder.aboutConfigEnabled(true)
            builder.extensionsWebAPIEnabled(true)

            // Import the Android user CA store into Gecko's NSS trust set (for the
            // local TLS-terminating debug proxy). enterprise_roots is imported only
            // at NSS startup, so this must be set on the builder before the runtime
            // is created; a CA installed later needs an app restart to be picked up.
            builder.enterpriseRootsEnabled(true)

            // Render web content slightly smaller than the device default so
            // pages show more at once. displayDensityOverride takes an absolute
            // density, so scale the device density rather than hardcoding.
            val deviceDensity = context.resources.displayMetrics.density
            builder.displayDensityOverride(deviceDensity * 0.85f)

            runtime = GeckoRuntime.create(context, builder.build())

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
