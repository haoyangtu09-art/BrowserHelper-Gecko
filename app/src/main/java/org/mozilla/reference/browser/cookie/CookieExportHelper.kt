/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.cookie

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.method.ScrollingMovementMethod
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import mozilla.components.concept.engine.webextension.MessageHandler
import mozilla.components.concept.engine.webextension.Port
import mozilla.components.concept.engine.webextension.WebExtension
import mozilla.components.concept.engine.webextension.WebExtensionRuntime
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CookieExportHelper {
    private const val EXTENSION_ID = "cookie-export-helper@videodownloader.local"
    private const val EXTENSION_URL = "resource://android/assets/extensions/cookie_export_helper/"
    private const val NATIVE_APP = "cookie_export"
    private const val ACTION_EXPORT_COOKIE = "com.example.videodownloader.IMPORT_COOKIE"
    private const val DOWNLOADER_PACKAGE = "com.example.videodownloader"
    private const val COOKIE_DIR_NAME = "Cookie"

    private val mainHandler = Handler(Looper.getMainLooper())
    private var port: Port? = null
    private var registered = false
    private var installing = false
    private var runtime: WebExtensionRuntime? = null
    private var appContext: Context? = null
    private val pending = LinkedHashMap<String, PendingRequest>()

    fun install(runtime: WebExtensionRuntime, context: Context) {
        this.runtime = runtime
        this.appContext = context.applicationContext
        if (installing) return
        installing = true
        runtime.installBuiltInWebExtension(
            id = EXTENSION_ID,
            url = EXTENSION_URL,
            onSuccess = { extension ->
                installing = false
                register(extension, context.applicationContext)
            },
            onError = {
                installing = false
                runtime.listInstalledWebExtensions(
                    onSuccess = { extensions ->
                        val installed = extensions.firstOrNull { extension -> extension.id == EXTENSION_ID }
                        if (installed != null) {
                            register(installed, context.applicationContext)
                        } else {
                            Toast.makeText(context, "Cookie extension install failed", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onError = {
                        Toast.makeText(context, "Cookie extension install failed", Toast.LENGTH_SHORT).show()
                    },
                )
            },
        )
    }

    fun request(context: Context, action: CookieAction, url: String) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            Toast.makeText(context, "Current page is not exportable", Toast.LENGTH_SHORT).show()
            return
        }
        val requestId = System.currentTimeMillis().toString()
        pending[requestId] = PendingRequest(context, action, url)
        mainHandler.postDelayed({
            val expired = pending.remove(requestId)
            if (expired != null) {
                Toast.makeText(expired.context, "Cookie extension is not ready", Toast.LENGTH_SHORT).show()
            }
        }, 10_000)
        val activePort = port
        if (activePort == null) {
            Toast.makeText(context, "Cookie extension is starting", Toast.LENGTH_SHORT).show()
            ensureInstalled(context)
        } else {
            postRequest(activePort, requestId, url)
        }
    }

    private fun ensureInstalled(context: Context) {
        val currentRuntime = runtime
        if (currentRuntime == null) {
            Toast.makeText(context, "Cookie extension is not initialized", Toast.LENGTH_SHORT).show()
            return
        }
        install(currentRuntime, appContext ?: context.applicationContext)
    }

    private fun register(extension: WebExtension, appContext: Context) {
        if (registered) return
        registered = true
        extension.registerBackgroundMessageHandler(
            NATIVE_APP,
            object : MessageHandler {
                override fun onPortConnected(port: Port) {
                    this@CookieExportHelper.port = port
                    flushPending(port)
                }

                override fun onPortDisconnected(port: Port) {
                    if (this@CookieExportHelper.port == port) {
                        this@CookieExportHelper.port = null
                    }
                }

                override fun onPortMessage(message: Any, port: Port) {
                    val data = message as? JSONObject ?: return
                    val requestId = data.optString("id")
                    val request = pending.remove(requestId) ?: return
                    val error = data.optString("error")
                    if (error.isNotBlank()) {
                        Toast.makeText(request.context, error, Toast.LENGTH_SHORT).show()
                        return
                    }
                    val cookies = data.optJSONArray("cookies") ?: JSONArray()
                    if (cookies.length() == 0) {
                        Toast.makeText(request.context, "No cookie found for this page", Toast.LENGTH_SHORT).show()
                        return
                    }
                    val bundle = CookieBundle(
                        url = request.url,
                        cookies = cookies,
                    )
                    when (request.action) {
                        CookieAction.VIEW -> viewCookies(request.context, bundle)
                        CookieAction.EXPORT_JSON -> exportFile(request.context, bundle, json = true)
                        CookieAction.EXPORT_FULL -> exportFile(request.context, bundle, json = false)
                        CookieAction.EXPORT_TO_DOWNLOADER -> exportToDownloader(request.context, bundle)
                    }
                }
            },
        )
        Toast.makeText(appContext, "Cookie export ready", Toast.LENGTH_SHORT).show()
    }

    private fun flushPending(port: Port) {
        pending.forEach { (requestId, request) ->
            postRequest(port, requestId, request.url)
        }
    }

    private fun postRequest(port: Port, requestId: String, url: String) {
        port.postMessage(
            JSONObject()
                .put("id", requestId)
                .put("url", url),
        )
    }

    private fun viewCookies(context: Context, bundle: CookieBundle) {
        val text = bundle.cookieHeader()
        AlertDialog.Builder(context)
            .setTitle("查看 Cookie")
            .setMessage(text)
            .setPositiveButton("复制 Cookie") { _, _ ->
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Cookie", text))
                Toast.makeText(context, "Cookie 已复制", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.ok, null)
            .show()
            .findViewById<TextView>(android.R.id.message)
            ?.apply {
                setTextIsSelectable(true)
                movementMethod = ScrollingMovementMethod()
            }
    }

    private fun exportToDownloader(context: Context, bundle: CookieBundle) {
        val intent = Intent(ACTION_EXPORT_COOKIE).apply {
            setPackage(DOWNLOADER_PACKAGE)
            putExtra("domain", bundle.domain)
            putExtra("name", bundle.domain)
            putExtra("cookie", bundle.cookieHeader())
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching {
            context.startActivity(intent)
            Toast.makeText(context, "Cookie exported to downloader", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(context, "Downloader not found", Toast.LENGTH_SHORT).show()
        }
    }

    private fun exportFile(context: Context, bundle: CookieBundle, json: Boolean) {
        val fileName = "${sanitizeFileName(bundle.domain)}_cookie_${System.currentTimeMillis()}.${if (json) "json" else "txt"}"
        val content = if (json) bundle.cookies.toString(2) else bundle.toFullText()
        val path = runCatching {
            writeDirect(fileName, content)
        }.recoverCatching {
            writeMediaStore(context, fileName, content)
        }.getOrElse {
            Toast.makeText(context, "Cookie export failed", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(context, "Cookie exported to $path", Toast.LENGTH_LONG).show()
    }

    private fun writeDirect(fileName: String, content: String): String {
        val dir = File(Environment.getExternalStorageDirectory(), COOKIE_DIR_NAME)
        if (!dir.exists() && !dir.mkdirs()) error("Cannot create Cookie directory")
        val file = File(dir, fileName)
        file.writeText(content, Charsets.UTF_8)
        return file.absolutePath
    }

    private fun writeMediaStore(context: Context, fileName: String, content: String): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw IllegalStateException("MediaStore fallback requires Android Q")
        }
        return runCatching {
            writeMediaStore(context, fileName, content, "$COOKIE_DIR_NAME/", "/storage/emulated/0/$COOKIE_DIR_NAME/$fileName")
        }.getOrElse {
            val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/$COOKIE_DIR_NAME/"
            writeMediaStore(context, fileName, content, relativePath, "/storage/emulated/0/$relativePath$fileName")
        }
    }

    private fun writeMediaStore(
        context: Context,
        fileName: String,
        content: String,
        relativePath: String,
        displayPath: String,
    ): String {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, if (fileName.endsWith(".json")) "application/json" else "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Cannot create Cookie file")
        resolver.openOutputStream(uri)?.use { output ->
            output.write(content.toByteArray(Charsets.UTF_8))
        } ?: throw IllegalStateException("Cannot open Cookie file")
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return displayPath
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_")
            .trim('_')
            .take(80)
            .ifBlank { "cookies" }
    }
}

enum class CookieAction {
    VIEW,
    EXPORT_JSON,
    EXPORT_FULL,
    EXPORT_TO_DOWNLOADER,
}

private data class PendingRequest(
    val context: Context,
    val action: CookieAction,
    val url: String,
)

private data class CookieBundle(
    val url: String,
    val cookies: JSONArray,
) {
    val domain: String = runCatching { java.net.URL(url).host.removePrefix("www.") }.getOrDefault("cookies")

    fun cookieHeader(): String {
        val items = mutableListOf<String>()
        for (i in 0 until cookies.length()) {
            val cookie = cookies.optJSONObject(i) ?: continue
            val name = cookie.optString("name")
            val value = cookie.optString("value")
            if (name.isNotBlank()) {
                items += "$name=$value"
            }
        }
        return items.joinToString("; ")
    }

    fun toFullText(): String {
        return buildString {
            appendLine("URL: $url")
            appendLine("Domain: $domain")
            appendLine("Exported-At: ${timestamp()}")
            appendLine()
            appendLine(cookieHeader())
            appendLine()
            appendLine("Cookies:")
            for (i in 0 until cookies.length()) {
                val cookie = cookies.optJSONObject(i) ?: continue
                appendLine(
                    "${cookie.optString("name")}=${cookie.optString("value")}; " +
                        "domain=${cookie.optString("domain")}; path=${cookie.optString("path", "/")}",
                )
            }
        }
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date())
}
