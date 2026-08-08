package com.piremote.net

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Mirrors `server/src/protocol.ts`. Changes there need a matching change here.
 *
 * Session entries deliberately stay as raw JSON: their shape varies by
 * type and role, and pi adds entry kinds over time. Parsing them into a closed
 * set of classes would make an unknown kind a crash instead of something the UI
 * can skip. [com.piremote.data.ChatItem] does the interpreting.
 */

@Serializable
data class ProjectDto(
    val cwd: String,
    val name: String,
    val sessionCount: Int,
    val lastModified: String,
)

@Serializable
data class SessionSummaryDto(
    val id: String,
    val cwd: String,
    val name: String? = null,
    val created: String,
    val modified: String,
    val messageCount: Int,
    val firstMessage: String = "",
    val parentSessionId: String? = null,
) {
    /** What to show in a list row: the user's name, else the opening message. */
    val displayTitle: String
        get() = name?.takeIf { it.isNotBlank() }
            ?: firstMessage.takeIf { it.isNotBlank() }?.lineSequence()?.firstOrNull()?.take(80)
            ?: "(空会话)"
}

@Serializable
data class ModelRefDto(val provider: String, val modelId: String)

@Serializable
data class SessionDetailDto(
    val id: String,
    val cwd: String,
    val name: String? = null,
    val created: String,
    val modified: String,
    val messageCount: Int,
    val firstMessage: String = "",
    val parentSessionId: String? = null,
    val model: ModelRefDto? = null,
    val thinkingLevel: String = "",
    val leafId: String? = null,
    val totalEntries: Int = 0,
    val running: Boolean = false,
    val availableThinkingLevels: List<String>? = null,
)

@Serializable
data class EntryPageDto(
    val entries: List<JsonObject> = emptyList(),
    val hasMore: Boolean = false,
    val oldestId: String? = null,
    val leafId: String? = null,
)

@Serializable
data class FullPartDto(val content: String)

@Serializable
data class ModelDto(
    val provider: String,
    val id: String,
    val name: String,
    val reasoning: Boolean = false,
    val contextWindow: Int? = null,
    val thinkingLevels: List<String> = emptyList(),
) {
    val key: String get() = "$provider/$id"
}

@Serializable
data class ModelsResponseDto(val models: List<ModelDto> = emptyList())

@Serializable
data class PromptResultDto(val accepted: Boolean = false, val queued: Boolean = false)

@Serializable
data class AbortResultDto(val aborted: Boolean = false)

@Serializable
data class NewSessionDto(val id: String)

@Serializable
data class UpdateResultDto(val updated: List<String> = emptyList())

@Serializable
data class PingDto(val ok: Boolean = false, val version: String = "")

@Serializable
data class ErrorDto(val error: String = "", val code: String? = null)

/* ---------------- WebSocket ---------------- */

@Serializable
data class WsSubscribe(
    val op: String = "subscribe",
    val sessionId: String,
    val sinceSeq: Long? = null,
)

@Serializable
data class WsUnsubscribe(val op: String = "unsubscribe", val sessionId: String)

/**
 * Server → client. One class rather than a sealed hierarchy because the wire
 * format discriminates on `op` with fields that only apply to some of them;
 * a permissive shape keeps an unrecognised `op` from killing the socket.
 */
@Serializable
data class WsMessage(
    val op: String = "",
    val sessionId: String? = null,
    val seq: Long? = null,
    val entryId: String? = null,
    val event: JsonObject? = null,
    val gap: Boolean = false,
    val running: Boolean = false,
    val message: String? = null,
    val code: String? = null,
)

/** Server error carried as an exception so call sites can branch on `code`. */
class ApiException(
    val status: Int,
    val code: String?,
    override val message: String,
) : Exception(message) {
    /** The session is running and the prompt needs a steer/followUp decision. */
    val isBusy: Boolean get() = code == "session_busy"

    /** The paging cursor no longer exists on the active branch — refetch. */
    val isStaleCursor: Boolean get() = code == "stale_cursor"
}
