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
import mozilla.components.concept.engine.webextension.WebExtensionRuntime
import mozilla.components.support.webextensions.BuiltInWebExtensionController
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CookieExportHelper {
    private const val EXTENSION_ID = "cookie-export-helper-v2@videodownloader.local"
    private const val EXTENSION_URL = "resource://android/assets/extensions/cookie_export_helper/"
    private const val NATIVE_APP = "cookie_export"
    private const val ACTION_EXPORT_COOKIE = "com.example.videodownloader.IMPORT_COOKIE"
    private const val DOWNLOADER_PACKAGE = "com.example.videodownloader"
    private const val COOKIE_DIR_NAME = "Cookie"

    private val mainHandler = Handler(Looper.getMainLooper())
    private var port: Port? = null
    private var registered = false
    private var runtime: WebExtensionRuntime? = null
    private var appContext: Context? = null
    private val pending = LinkedHashMap<String, PendingRequest>()

    private val controller = BuiltInWebExtensionController(EXTENSION_ID, EXTENSION_URL, NATIVE_APP)

    fun install(runtime: WebExtensionRuntime, context: Context) {
        this.runtime = runtime
        this.appContext = context.applicationContext
        registerHandler()
        controller.install(
            runtime,
            onSuccess = {},
            onError = { throwable ->
                mainHandler.post {
                    appContext?.let {
                        Toast.makeText(it, "Cookie extension install failed: ${throwable.message}", Toast.LENGTH_LONG).show()
                    }
                }
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
                val state = if (port != null) "通道已连接但未返回结果" else "未能连接到 Cookie 通道(background 未连上)"
                showError(
                    expired.context,
                    "Cookie 通道超时",
                    "等待 10 秒仍无响应。\n状态: $state",
                    JSONObject().put("portConnected", port != null).put("url", url),
                )
            }
        }, 10_000)
        val activePort = port
        if (activePort != null) {
            postRequest(activePort, requestId, url)
        } else {
            Toast.makeText(context, "正在连接 Cookie 通道", Toast.LENGTH_SHORT).show()
            ensureInstalled(context)
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

    private fun registerHandler() {
        if (registered) return
        registered = true
        controller.registerBackgroundMessageHandler(
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
                    handlePortMessage(message)
                }
            },
            NATIVE_APP,
        )
    }

    private fun flushPending(port: Port) {
        pending.forEach { (requestId, request) ->
            postRequest(port, requestId, request.url)
        }
    }

    private fun handlePortMessage(message: Any) {
        val data = message as? JSONObject ?: return
        val requestId = data.optString("id")
        val request = pending.remove(requestId) ?: return
        val error = data.optString("error")
        if (error.isNotBlank()) {
            showError(request.context, "Cookie 读取失败", error, data)
            return
        }
        val cookies = data.optJSONArray("cookies") ?: JSONArray()
        if (cookies.length() == 0) {
            showError(request.context, "未读到 Cookie", "该页面没有返回任何 Cookie。", data)
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

    private fun showError(context: Context, title: String, summary: String, raw: JSONObject) {
        val detail = buildString {
            appendLine(summary)
            appendLine()
            appendLine("原始返回:")
            append(runCatching { raw.toString(2) }.getOrDefault(raw.toString()))
        }
        AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(detail)
            .setPositiveButton("复制") { _, _ ->
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("error", detail))
                Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.ok, null)
            .show()
            .findViewById<TextView>(android.R.id.message)
            ?.apply {
                setTextIsSelectable(true)
                movementMethod = ScrollingMovementMethod()
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
