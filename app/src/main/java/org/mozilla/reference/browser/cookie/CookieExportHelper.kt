/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.cookie

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CookieExportHelper {
    private const val ACTION_EXPORT_COOKIE = "com.example.videodownloader.IMPORT_COOKIE"
    private const val DOWNLOADER_PACKAGE = "com.example.videodownloader"
    private const val COOKIE_DIR_NAME = "Cookie"

    fun handle(context: Context, uri: String): Boolean {
        val parsed = Uri.parse(uri)
        val bundle = CookieBundle.fromUri(parsed)
        if (bundle.cookie.isBlank()) {
            Toast.makeText(context, "No cookie found for this page", Toast.LENGTH_SHORT).show()
            return true
        }

        return when (parsed.scheme) {
            "cookieexport-file" -> {
                exportFile(context, bundle, parsed.getQueryParameter("format").orEmpty())
                true
            }
            "cookieexport-downloader" -> {
                exportToDownloader(context, bundle)
                true
            }
            else -> false
        }
    }

    private fun exportToDownloader(context: Context, bundle: CookieBundle) {
        val intent = Intent(ACTION_EXPORT_COOKIE).apply {
            setPackage(DOWNLOADER_PACKAGE)
            putExtra("domain", bundle.domain)
            putExtra("name", bundle.host)
            putExtra("cookie", bundle.cookie)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching {
            context.startActivity(intent)
            Toast.makeText(context, "Cookie exported to downloader", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(context, "Downloader not found", Toast.LENGTH_SHORT).show()
        }
    }

    private fun exportFile(context: Context, bundle: CookieBundle, format: String) {
        val isJson = format == "json"
        val fileName = "${sanitizeFileName(bundle.host)}_cookie_${System.currentTimeMillis()}.${if (isJson) "json" else "txt"}"
        val content = if (isJson) {
            bundle.toCookieEditorJson().toString(2)
        } else {
            bundle.toFullText()
        }
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

private data class CookieBundle(
    val url: String,
    val host: String,
    val secure: Boolean,
    val cookie: String,
) {
    val domain: String = host.removePrefix("www.")

    fun toCookieEditorJson(): JSONArray {
        val items = JSONArray()
        entries().forEach { entry ->
            items.put(
                JSONObject()
                    .put("name", entry.name)
                    .put("value", entry.value)
                    .put("domain", domain)
                    .put("path", "/")
                    .put("secure", secure)
                    .put("httpOnly", false)
                    .put("sameSite", "Lax"),
            )
        }
        return items
    }

    fun toFullText(): String {
        return buildString {
            appendLine("URL: $url")
            appendLine("Host: $host")
            appendLine("Domain: $domain")
            appendLine("Exported-At: ${timestamp()}")
            appendLine()
            appendLine(cookie)
            appendLine()
            appendLine("Cookies:")
            entries().forEach { entry ->
                appendLine("${entry.name}=${entry.value}; domain=$domain; path=/")
            }
        }
    }

    private fun entries(): List<CookieEntry> =
        cookie.split(';').mapNotNull { part ->
            val item = part.trim()
            val eq = item.indexOf('=')
            if (eq <= 0) return@mapNotNull null
            CookieEntry(
                name = item.substring(0, eq).trim(),
                value = item.substring(eq + 1).trim(),
            )
        }.filter { it.name.isNotEmpty() && it.value.isNotEmpty() }
            .distinctBy { it.name }

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date())

    companion object {
        fun fromUri(uri: Uri): CookieBundle =
            CookieBundle(
                url = uri.getQueryParameter("url").orEmpty(),
                host = uri.getQueryParameter("host").orEmpty(),
                secure = uri.getQueryParameter("secure").toBoolean(),
                cookie = uri.getQueryParameter("cookie").orEmpty(),
            )
    }
}

private data class CookieEntry(
    val name: String,
    val value: String,
)
