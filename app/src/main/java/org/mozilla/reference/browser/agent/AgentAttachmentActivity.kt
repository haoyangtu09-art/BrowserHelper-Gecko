/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.agent

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns

private const val EXTRA_KIND = "org.mozilla.reference.browser.agent.extra.KIND"
private const val REQ_CAMERA = 41
private const val REQ_IMAGE = 42
private const val REQ_FILE = 43

class AgentAttachmentActivity : Activity() {
    private var kind: AgentAttachmentKind = AgentAttachmentKind.File
    private var cameraUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        kind = runCatching {
            AgentAttachmentKind.valueOf(intent.getStringExtra(EXTRA_KIND).orEmpty())
        }.getOrDefault(AgentAttachmentKind.File)
        when (kind) {
            AgentAttachmentKind.Camera -> openCamera()
            AgentAttachmentKind.Image -> openImagePicker()
            AgentAttachmentKind.File -> openFilePicker()
            AgentAttachmentKind.Plugin -> finish()
        }
    }

    private fun openCamera() {
        val name = "agent_camera_${System.currentTimeMillis()}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/BrowserHelper Agent")
            }
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri == null) {
            finish()
            return
        }
        cameraUri = uri
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            .putExtra(MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        startForResult(intent, REQ_CAMERA)
    }

    private fun openImagePicker() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*")
        } else {
            Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("image/*")
                .addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startForResult(intent, REQ_IMAGE)
    }

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        startForResult(intent, REQ_FILE)
    }

    private fun startForResult(intent: Intent, requestCode: Int) {
        try {
            startActivityForResult(intent, requestCode)
        } catch (_: ActivityNotFoundException) {
            finish()
        }
    }

    @Deprecated("Deprecated in Android API; kept for this transparent result trampoline.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) {
            finish()
            return
        }
        val uri = when (requestCode) {
            REQ_CAMERA -> cameraUri
            REQ_IMAGE, REQ_FILE -> data?.data
            else -> null
        }
        if (uri != null) {
            if (requestCode != REQ_CAMERA) persistReadGrant(uri, data)
            val meta = queryMeta(uri)
            AgentAttachmentBridge.dispatch(
                AgentAttachmentResult(
                    kind = kind,
                    uri = uri.toString(),
                    displayName = meta.name,
                    mimeType = meta.mime,
                    sizeBytes = meta.size,
                ),
            )
        }
        finish()
    }

    private fun persistReadGrant(uri: Uri, data: Intent?) {
        val flags = data?.flags ?: 0
        val takeFlags = flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        if (takeFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0) return
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    private fun queryMeta(uri: Uri): AttachmentMeta {
        var name = uri.lastPathSegment.orEmpty().ifBlank { "attachment" }
        var size = -1L
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                if (nameIdx >= 0) name = c.getString(nameIdx).orEmpty().ifBlank { name }
                if (sizeIdx >= 0 && !c.isNull(sizeIdx)) size = c.getLong(sizeIdx)
            }
        }
        return AttachmentMeta(name, contentResolver.getType(uri).orEmpty(), size)
    }

    private data class AttachmentMeta(val name: String, val mime: String, val size: Long)

    companion object {
        fun launch(context: Context, kind: AgentAttachmentKind) {
            val intent = Intent(context, AgentAttachmentActivity::class.java)
                .putExtra(EXTRA_KIND, kind.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
}
