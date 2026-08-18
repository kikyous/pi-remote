package com.piremote.net

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Mirrors `server/src/protocol.ts`. Changes there need a matching change here.
 *
 * [Item] is a closed set of classes, which is safe because the server decides
 * what an item is: `items.ts` maps every pi entry kind onto one of these four and
 * skips the ones with no conversation content. An entry kind pi adds later cannot
 * reach the app, so it cannot blank the screen either — the compatibility burden
 * sits in the one place able to carry it. Version skew is caught up front instead,
 * by comparing [PingDto.protocol].
 */

/**
 * The wire protocol this build speaks.
 *
 * Compared against `/ping`'s `protocol` before a connection is saved, so a bridge
 * that was not upgraded alongside the app says so plainly instead of failing as an
 * unparseable frame or a blank conversation.
 */
const val WIRE_PROTOCOL = 2

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
    /** What to show in a list row: the user's name, else the opening message.
     *  Empty when there is nothing; the UI localizes the placeholder. */
    val displayTitle: String
        get() = name?.takeIf { it.isNotBlank() }
            ?: firstMessage.takeIf { it.isNotBlank() }?.lineSequence()?.firstOrNull()?.take(80)
            ?: ""
}

@Serializable
data class ModelRefDto(val provider: String, val modelId: String)

/**
 * The settings half of a session — the header bar and the pickers.
 *
 * Whether it is running, what it is doing, and how full its context is all live
 * in [SessionStatusDto], which is pushed when it changes rather than polled.
 */
@Serializable
data class SessionDetailDto(
    val id: String,
    val cwd: String,
    val name: String? = null,
    val firstMessage: String = "",
    val model: ModelRefDto? = null,
    val thinkingLevel: String = "",
    val availableThinkingLevels: List<String>? = null,
)

@Serializable
data class ContextUsageDto(
    val tokens: Int? = null,
    val contextWindow: Int? = null,
    val percent: Float? = null,
)

@Serializable
data class SessionStatusDto(
    val running: Boolean = false,
    /** Messages waiting behind the current turn. */
    val queued: List<String> = emptyList(),
    val compacting: Boolean = false,
    val context: ContextUsageDto? = null,
)

/* ---------------- items: the whole display model ---------------- */

/** Text that may have been shortened. [more] is present exactly when it was. */
@Serializable
data class TextDto(val s: String = "", val more: MoreDto? = null)

/** A handle for the rest of some shortened content, opaque to the client. */
@Serializable
data class MoreDto(val ref: String, val bytes: Int = 0) {
    val displaySize: String
        get() = when {
            bytes >= 1024 * 1024 -> "${fixed(bytes / 1024.0 / 1024.0, 1)} MB"
            bytes >= 1024 -> "${bytes / 1024} KB"
            else -> "$bytes B"
        }
}

/** An image, always a placeholder: the largest one measured was 361KB of base64. */
@Serializable
data class BlobDto(val ref: String, val mime: String = "", val bytes: Int = 0)

/** Per-turn LLM usage, as pi reports it on the assistant message. */
@Serializable
data class UsageDto(
    @SerialName("in") val input: Int = 0,
    @SerialName("out") val output: Int = 0,
    val cacheRead: Int = 0,
    /** Total cost in dollars, cache reads included. */
    val cost: Double = 0.0,
) {
    /** "2,365 in · 89 out · 131,072 cache R · $0.0004" — the pi TUI style. */
    val summary: String
        get() = buildList {
            add("${formatCount(input.toLong())} in")
            add("${formatCount(output.toLong())} out")
            if (cacheRead > 0) add("${formatCount(cacheRead.toLong())} cache R")
            add(formatCost(cost))
        }.joinToString(" · ")
}

@Serializable
data class ToolDiffDto(val path: String? = null, val hunks: List<DiffHunkDto> = emptyList())

@Serializable
data class DiffHunkDto(val old: String = "", val new: String = "")

/**
 * One row of the conversation.
 *
 * A streaming message is simply one that is not finished yet ([Assistant.pending]),
 * which is why there is no second model for live content.
 *
 * A tool call is a row of its own rather than nested inside the assistant message
 * that made it: patches then address one id with no path into a nested array, and
 * pairing calls with results is the server's job, which has the whole tree.
 * Grouping them back under their assistant message is a rendering decision, made
 * by adjacency.
 */
@Serializable
@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("kind")
sealed interface Item {
    val id: String
    val at: String

    @Serializable
    @SerialName("user")
    data class User(
        override val id: String,
        override val at: String = "",
        val text: TextDto = TextDto(),
        val images: List<BlobDto> = emptyList(),
    ) : Item

    @Serializable
    @SerialName("assistant")
    data class Assistant(
        override val id: String,
        override val at: String = "",
        val thinking: TextDto? = null,
        val text: TextDto = TextDto(),
        val usage: UsageDto? = null,
        val error: String? = null,
        /** Set while the message is still streaming. */
        val pending: Boolean = false,
    ) : Item

    @Serializable
    @SerialName("tool")
    data class Tool(
        override val id: String,
        override val at: String = "",
        val callId: String? = null,
        val name: String = "",
        /** The one identifying argument — a path, a command. */
        val title: String? = null,
        val args: TextDto? = null,
        val output: TextDto = TextDto(),
        val isError: Boolean = false,
        val hasImage: Boolean = false,
        val exit: Int? = null,
        val diff: ToolDiffDto? = null,
        /** Set while the tool is still executing. */
        val running: Boolean = false,
    ) : Item

    @Serializable
    @SerialName("notice")
    data class Notice(
        override val id: String,
        override val at: String = "",
        val note: String = "text",
        val arg: String? = null,
    ) : Item
}

@Serializable
data class ItemPageDto(
    val items: List<Item> = emptyList(),
    val hasMore: Boolean = false,
    /** Cursor to pass as `before` for the next older page. */
    val oldest: String? = null,
)

/** Thousands separators — shared with the session info sheet. */
internal fun formatCount(n: Long): String {
    val digits = n.toString()
    return if (digits.length <= 3) digits
    else digits.reversed().chunked(3).joinToString(",").reversed()
}

/** Fixed-decimal number without locale-dependent formatting. */
internal fun fixed(x: Double, decimals: Int): String {
    val factor = 10.0.pow(decimals)
    val scaled = (x * factor).roundToLong()
    val whole = scaled / factor.toLong()
    val frac = (scaled % factor.toLong()).toString().padStart(decimals, '0')
    return "$whole.$frac"
}

internal fun formatCost(cost: Double): String {
    val text = when {
        cost >= 1.0 -> fixed(cost, 2)
        cost >= 0.0001 -> fixed(cost, 4)
        else -> fixed(cost, 6) // avoid "$0.0000" for sub-cent turns
    }
    return "$$text"
}

/* ---------------- git (read-only) ---------------- */

@Serializable
data class GitStatusDto(
    val branch: String = "",
    val changes: List<GitChangeDto> = emptyList(),
)

@Serializable
data class GitChangeDto(
    val path: String,
    val status: String,
    val added: Int,
    val deleted: Int,
)

@Serializable
data class GitDiffDto(
    val path: String = "",
    val hunks: List<GitHunkDto> = emptyList(),
)

@Serializable
data class GitCommitDto(
    val hash: String,
    val shortHash: String,
    val subject: String,
    val author: String,
    val date: String,
    val added: Int,
    val deleted: Int,
)

@Serializable
data class GitCommitsPageDto(
    val commits: List<GitCommitDto> = emptyList(),
    val nextCursor: String? = null,
)

@Serializable
data class GitCommitDiffDto(
    val sha: String = "",
    val shortHash: String = "",
    val subject: String = "",
    val author: String = "",
    val date: String = "",
    val files: List<GitFileDiffDto> = emptyList(),
)

@Serializable
data class GitFileDiffDto(
    val path: String,
    val status: String,
    val hunks: List<GitHunkDto> = emptyList(),
)

@Serializable
data class GitHunkDto(
    val oldStart: Int,
    val oldCount: Int,
    val newStart: Int,
    val newCount: Int,
    val lines: List<GitDiffLineDto> = emptyList(),
)

@Serializable
data class GitDiffLineDto(val type: String, val text: String)

@Serializable
data class FullContentDto(val content: String)

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

/** An image attachment for a prompt: base64 bytes plus MIME type. */
data class PromptImage(val data: String, val mimeType: String)

@Serializable
data class AbortResultDto(val aborted: Boolean = false)

/**
 * Response of `POST /sessions/:id/compact`: how much context the summary
 * reclaimed. The "Context compacted" notice and the refreshed context bar come
 * over the push stream, so these two numbers are all the call itself returns.
 */
@Serializable
data class CompactResultDto(
    val tokensBefore: Int = 0,
    val tokensAfter: Int? = null,
)

/** How many rows of each kind a session holds. */
@Serializable
data class MessageCountsDto(
    val user: Int = 0,
    val assistant: Int = 0,
    /** Tool calls made — they live inside assistant messages, so not in [total]. */
    val toolCalls: Int = 0,
    val toolResults: Int = 0,
    val total: Int = 0,
)

/** Token totals are [Long]: a long-lived session reads cache in the millions. */
@Serializable
data class TokenTotalsDto(
    val input: Long = 0,
    val output: Long = 0,
    val cacheRead: Long = 0,
    val cacheWrite: Long = 0,
    val total: Long = 0,
)

/**
 * Response of `GET /sessions/:id/stats`: what this session has spent.
 *
 * The totals cover the whole session file, abandoned branches included — the
 * spending happened either way. [context] is the active branch instead, the same
 * number the header bar shows.
 */
@Serializable
data class SessionStatsDto(
    val id: String = "",
    val file: String = "",
    val name: String? = null,
    val messages: MessageCountsDto = MessageCountsDto(),
    val tokens: TokenTotalsDto = TokenTotalsDto(),
    /** Total cost in dollars, cache reads included. */
    val cost: Double = 0.0,
    val context: ContextUsageDto = ContextUsageDto(),
)

@Serializable
data class NewSessionDto(val id: String)

/** Response of `DELETE /sessions/:id` and `DELETE /workspaces`. */
@Serializable
data class DeleteResultDto(val deleted: Int)


/** Response of `POST /api/v1/workspaces`: the daily default workspace. */
@Serializable
data class WorkspaceDto(
    val id: String,
    val cwd: String,
    val created: Boolean,
)

@Serializable
data class PingDto(val ok: Boolean = false, val version: String = "", val protocol: Int = 0)

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

/** One growing field of one item. The streaming path. */
@Serializable
data class AppendDto(val f: String, val s: String)

/**
 * A [TextDto] update where either half may be omitted.
 *
 * `s` absent means "keep the text you have, take the handle" — the normal end of a
 * stream, where every delta already arrived and only the "show all" handle for the
 * part that was never streamed is missing.
 */
@Serializable
data class TextPatchDto(val s: String? = null, val more: MoreDto? = null)

/** Fields a [Push.Patch] may replace. Null means unchanged, not cleared. */
@Serializable
data class ItemPatchDto(
    val text: TextPatchDto? = null,
    val thinking: TextPatchDto? = null,
    val output: TextPatchDto? = null,
    val usage: UsageDto? = null,
    val error: String? = null,
    val pending: Boolean? = null,
    val running: Boolean? = null,
    val exit: Int? = null,
    val isError: Boolean? = null,
    val hasImage: Boolean? = null,
    val title: String? = null,
    val args: TextPatchDto? = null,
    val diff: ToolDiffDto? = null,
    val images: List<BlobDto>? = null,
)

/**
 * Server → client. Only mutations of the item list, never raw SDK events.
 *
 * [Hello] is the single resync path: a fresh subscribe, a replay gap, and an agent
 * reloaded because someone else wrote the session file all produce one.
 */
@Serializable
@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("t")
sealed interface Push {
    /** The session this is about; null for connection-level pushes. */
    val sessionId: String?

    @Serializable
    @SerialName("hello")
    data class Hello(
        override val sessionId: String,
        val seq: Long = 0,
        val items: List<Item> = emptyList(),
        val hasMore: Boolean = false,
        val oldest: String? = null,
        val detail: SessionDetailDto,
        val status: SessionStatusDto = SessionStatusDto(),
    ) : Push

    @Serializable
    @SerialName("add")
    data class Add(override val sessionId: String, val seq: Long = 0, val item: Item) : Push

    @Serializable
    @SerialName("patch")
    data class Patch(
        override val sessionId: String,
        val seq: Long = 0,
        val id: String,
        val append: AppendDto? = null,
        val set: ItemPatchDto? = null,
    ) : Push

    @Serializable
    @SerialName("status")
    data class Status(override val sessionId: String, val seq: Long = 0, val status: SessionStatusDto) : Push

    @Serializable
    @SerialName("unsubscribed")
    data class Unsubscribed(override val sessionId: String) : Push

    @Serializable
    @SerialName("pong")
    data object Pong : Push {
        override val sessionId: String? get() = null
    }

    @Serializable
    @SerialName("error")
    data class Error(override val sessionId: String? = null, val message: String = "", val code: String? = null) : Push
}

/** Monotonic per session, and the resume cursor. Only mutations carry one. */
val Push.cursor: Long?
    get() = when (this) {
        is Push.Hello -> seq
        is Push.Add -> seq
        is Push.Patch -> seq
        is Push.Status -> seq
        else -> null
    }

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
