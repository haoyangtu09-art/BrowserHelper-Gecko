/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.agent.core

/** Wire format of the configured endpoint: OpenAI Chat Completions or Anthropic Messages. */
enum class ApiFormat { OpenAI, Anthropic }

/** A conversation role. [wire] is the string both APIs expect in the messages array. */
enum class Role(val wire: String) {
    System("system"),
    User("user"),
    Assistant("assistant"),
    Tool("tool"),
}

/** One turn in the conversation history handed to a backend. */
data class AgentMessage(
    val role: Role,
    val content: String,
    val toolCallId: String? = null,
    val toolCalls: List<ChatToolCall> = emptyList(),
)

/**
 * Everything a backend needs for one call. [baseUrl] may be blank (provider default),
 * a bare host, a `.../v1` base, or a full endpoint — [Endpoints] normalizes it.
 */
data class AgentConfig(
    val format: ApiFormat,
    val apiKey: String,
    val baseUrl: String,
    val model: String,
    val maxTokens: Int = 1024,
)

/** Tool declaration passed to a model backend. */
data class ChatToolSpec(
    val name: String,
    val description: String,
    val parameters: org.json.JSONObject,
)

data class AgentToolInfo(
    val name: String,
    val description: String,
    val tier: AgentPermissionTier,
)

/** One model-requested tool call. */
data class ChatToolCall(
    val id: String,
    val name: String,
    val arguments: String,
)

/** Non-streaming response used by the tool loop. */
data class ChatReply(
    val content: String,
    val toolCalls: List<ChatToolCall> = emptyList(),
)

enum class AgentPermissionTier { S1, S2, S3 }

enum class AgentApprovalDecision { Approve, ApproveSession, Deny }

data class AgentApprovalRequest(
    val kind: String,
    val title: String,
    val detail: String,
    val scopeKey: String,
    val scopeLabel: String,
    val approveLabel: String = "允许本次",
    val approveSessionLabel: String = "允许，并记住这个范围",
    val denyLabel: String = "拒绝，并告诉 Agent 换个做法",
)

/** Non-2xx HTTP response from a provider; carries the (truncated) error body. */
class AgentHttpException(val code: Int, val bodyText: String) :
    Exception("HTTP $code: ${bodyText.take(300)}")
