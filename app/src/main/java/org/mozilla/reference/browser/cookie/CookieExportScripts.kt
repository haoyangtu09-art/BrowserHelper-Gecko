/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.cookie

object CookieExportScripts {
    fun viewCookies(): String = "javascript:(function(){alert(document.cookie||'No cookie found for this page');})()"

    fun exportFile(format: String): String = bridgeUrl("cookieexport-file", "format=$format")

    fun exportToDownloader(): String = bridgeUrl("cookieexport-downloader", "")

    private fun bridgeUrl(scheme: String, extra: String): String {
        val queryPrefix = if (extra.isBlank()) "" else "$extra&"
        return "javascript:(function(){" +
            "var q='${queryPrefix}url='+encodeURIComponent(location.href)+" +
            "'&host='+encodeURIComponent(location.hostname)+" +
            "'&secure='+encodeURIComponent(location.protocol==='https:')+" +
            "'&cookie='+encodeURIComponent(document.cookie||'');" +
            "location.href='$scheme://export?'+q;" +
            "})()"
    }
}
