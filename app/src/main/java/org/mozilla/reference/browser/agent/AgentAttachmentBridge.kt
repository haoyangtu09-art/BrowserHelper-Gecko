/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.agent

enum class AgentAttachmentKind { Camera, Image, File, Plugin }

data class AgentAttachmentResult(
    val kind: AgentAttachmentKind,
    val uri: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
)

object AgentAttachmentBridge {
    @Volatile
    var listener: ((AgentAttachmentResult) -> Unit)? = null

    fun dispatch(result: AgentAttachmentResult) {
        listener?.invoke(result)
    }
}
