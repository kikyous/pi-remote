package com.piremote.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * The display model. Session entries arrive as raw JSON whose shape varies by
 * type and role; this turns them into something a composable can render, and
 * silently skips anything it does not recognise so a future pi entry kind
 * cannot crash the screen.
 */
sealed interface ChatItem {
    val entryId: String

    data class User(override val entryId: String, val text: String) : ChatItem

    data class Assistant(
        override val entryId: String,
        val text: String,
        val thinking: Thinking?,
        val toolCalls: List<ToolCall>,
        val error: String?,
    ) : ChatItem

    /** A tool result not claimed by any visible call — shown standalone. */
    data class OrphanToolResult(override val entryId: String, val result: ToolResult) : ChatItem

    data class Bash(
        override val entryId: String,
        val command: String,
        val output: String,
        val exitCode: Int?,
        val truncation: Truncation?,
    ) : ChatItem

    /** Compaction, branch summaries, model/thinking switches — thin dividers. */
    data class Notice(override val entryId: String, val text: String) : ChatItem
}

data class Thinking(val preview: String, val truncation: Truncation?)

data class ToolCall(
    val id: String,
    val name: String,
    /** Pretty-printed arguments, already shortened server-side when huge. */
    val arguments: String,
    /** The single most identifying argument, e.g. a path or a command. */
    val subtitle: String?,
    val truncation: Truncation?,
    /** Filled in by [linkToolResults]. */
    val result: ToolResult? = null,
    /** Parsed when the call is an edit: renders as a unified diff instead of JSON. */
    val diff: EditDiff? = null,
)

/** The edit tool's arguments, rendered as red/green diff lines. */
data class EditDiff(
    val filePath: String?,
    val hunks: List<EditHunk> = emptyList(),
)

data class EditHunk(val oldText: String, val newText: String)

data class ToolResult(
    /** The `toolCallId` that produced this, used to pair it with its call. */
    val callId: String?,
    val toolName: String,
    val text: String,
    val isError: Boolean,
    val hasImage: Boolean,
    val truncation: Truncation?,
)

/**
 * Marks content the server shortened, with everything needed to fetch the rest
 * from `/entries/{entryId}/full`.
 */
data class Truncation(
    val entryId: String,
    val part: String,
    val index: Int?,
    val fullLength: Int,
) {
    val displaySize: String
        get() = when {
            fullLength >= 1024 * 1024 -> "%.1f MB".format(fullLength / 1024.0 / 1024.0)
            fullLength >= 1024 -> "${fullLength / 1024} KB"
            else -> "$fullLength B"
        }
}

/** Parse one page of entries, oldest first. Unknown kinds yield null. */
fun parseEntries(entries: List<JsonObject>): List<ChatItem> = entries.mapNotNull(::parseEntry)

fun parseEntry(entry: JsonObject): ChatItem? {
    val id = entry.str("id") ?: return null
    return when (entry.str("type")) {
        "message" -> parseMessage(id, entry["message"] as? JsonObject ?: return null)
        "compaction" -> ChatItem.Notice(id, "上下文已压缩")
        "branch_summary" -> ChatItem.Notice(id, "分支摘要")
        "model_change" -> {
            val provider = entry.str("provider").orEmpty()
            val model = entry.str("modelId").orEmpty()
            ChatItem.Notice(id, "模型切换为 $provider/$model")
        }
        "thinking_level_change" -> ChatItem.Notice(id, "思考等级：${entry.str("thinkingLevel").orEmpty()}")
        "session_info" -> entry.str("name")?.let { ChatItem.Notice(id, "会话命名为「$it」") }
        // label / custom carry no conversation content.
        else -> null
    }
}

private fun parseMessage(entryId: String, message: JsonObject): ChatItem? =
    when (message.str("role")) {
        "user" -> ChatItem.User(entryId, message.contentText())
        "assistant" -> parseAssistant(entryId, message)
        "toolResult" -> ChatItem.OrphanToolResult(entryId, parseToolResult(entryId, message))
        "bashExecution" -> ChatItem.Bash(
            entryId = entryId,
            command = message.str("command").orEmpty(),
            output = message.str("output").orEmpty(),
            exitCode = message.int("exitCode"),
            truncation = message.truncation(entryId),
        )
        "custom" -> message.contentText().takeIf { it.isNotBlank() }?.let { ChatItem.Notice(entryId, it) }
        "compactionSummary" -> ChatItem.Notice(entryId, "上下文已压缩")
        "branchSummary" -> ChatItem.Notice(entryId, "分支摘要")
        else -> null
    }

private fun parseAssistant(entryId: String, message: JsonObject): ChatItem {
    val blocks = message["content"] as? JsonArray ?: JsonArray(emptyList())
    val text = StringBuilder()
    var thinking: Thinking? = null
    val calls = mutableListOf<ToolCall>()

    for (block in blocks) {
        val obj = block as? JsonObject ?: continue
        when (obj.str("type")) {
            "text" -> text.append(obj.str("text").orEmpty())
            "thinking" -> if (thinking == null) {
                thinking = Thinking(obj.str("thinking").orEmpty(), obj.truncation(entryId))
            }
            "toolCall" -> calls += parseToolCall(entryId, obj)
        }
    }

    return ChatItem.Assistant(
        entryId = entryId,
        text = text.toString(),
        thinking = thinking,
        toolCalls = calls,
        error = message.str("errorMessage"),
    )
}

private fun parseToolCall(entryId: String, block: JsonObject): ToolCall {
    val args = block["arguments"] as? JsonObject
    return ToolCall(
        id = block.str("id") ?: block.str("toolCallId").orEmpty(),
        name = block.str("name") ?: block.str("toolName").orEmpty(),
        arguments = args?.prettyPrint().orEmpty(),
        subtitle = args?.headlineArgument(),
        truncation = block.truncation(entryId),
        diff = args?.let(::parseEditDiff),
    )
}

/**
 * An edit call carries `{path, edits: [{oldText, newText}, …]}`. Recognised by
 * the edits array whatever the tool name, so renamed tools keep working.
 */
private fun parseEditDiff(args: JsonObject): EditDiff? {
    val edits = args["edits"] as? JsonArray ?: return null
    val hunks = edits.mapNotNull { edit ->
        val obj = edit as? JsonObject ?: return@mapNotNull null
        val old = obj.str("oldText") ?: return@mapNotNull null
        val new = obj.str("newText") ?: return@mapNotNull null
        EditHunk(old, new)
    }
    if (hunks.isEmpty()) return null
    return EditDiff(filePath = args.str("path") ?: args.str("file_path"), hunks = hunks)
}

private fun parseToolResult(entryId: String, message: JsonObject): ToolResult {
    val blocks = message["content"] as? JsonArray ?: JsonArray(emptyList())
    val text = StringBuilder()
    var hasImage = false
    var truncation: Truncation? = null

    for (block in blocks) {
        val obj = block as? JsonObject ?: continue
        when (obj.str("type")) {
            "text" -> {
                text.append(obj.str("text").orEmpty())
                truncation = truncation ?: obj.truncation(entryId)
            }
            "image" -> {
                hasImage = true
                truncation = truncation ?: obj.truncation(entryId)
            }
        }
    }

    return ToolResult(
        callId = message.str("toolCallId"),
        toolName = message.str("toolName").orEmpty(),
        text = text.toString(),
        isError = message.bool("isError"),
        hasImage = hasImage,
        truncation = truncation,
    )
}

/**
 * Attach each tool result to the call that produced it, and drop the results
 * that found a home.
 *
 * Calls and their results can land in different pages — a result is always
 * newer than its call, so paging backwards sees the result first. Running this
 * over the whole list after every change keeps them together regardless.
 */
fun linkToolResults(items: List<ChatItem>): List<ChatItem> {
    val resultsByCallId = HashMap<String, ToolResult>()
    for (item in items) {
        if (item is ChatItem.OrphanToolResult) {
            // The server keeps toolCallId on the message; parse kept only what
            // the UI needs, so re-key on the entry's own id when absent.
            resultsByCallId[item.result.callId ?: continue] = item.result
        }
    }
    if (resultsByCallId.isEmpty()) return items

    val claimed = HashSet<String>()
    val linked = items.mapNotNull { item ->
        when (item) {
            is ChatItem.Assistant -> {
                if (item.toolCalls.isEmpty()) return@mapNotNull item
                item.copy(
                    toolCalls = item.toolCalls.map { call ->
                        val result = resultsByCallId[call.id]
                        if (result != null) claimed += call.id
                        call.copy(result = result)
                    },
                )
            }
            is ChatItem.OrphanToolResult ->
                if (item.result.callId != null && item.result.callId in claimed) null else item
            else -> item
        }
    }
    return linked
}

/* ---------------- JSON helpers ---------------- */

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

private fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull ?: false

/** `content` may be a bare string or an array of blocks. */
private fun JsonObject.contentText(): String {
    val content = this["content"] ?: return ""
    (content as? JsonPrimitive)?.contentOrNull?.let { return it }
    val blocks = content as? JsonArray ?: return ""
    return blocks.mapNotNull { block ->
        (block as? JsonObject)?.takeIf { it.str("type") == "text" }?.str("text")
    }.joinToString("")
}

private fun JsonObject.truncation(entryId: String): Truncation? {
    if (!bool("truncated")) return null
    return Truncation(
        entryId = entryId,
        part = str("part") ?: return null,
        index = int("index"),
        fullLength = int("fullLength") ?: 0,
    )
}

private fun JsonObject.prettyPrint(): String =
    entries.joinToString("\n") { (k, v) ->
        // Nested objects and arrays keep their JSON form; only strings unwrap.
        val text = (v as? JsonPrimitive)?.contentOrNull ?: v.toString()
        "$k: $text"
    }

/**
 * Pick the argument worth showing on the collapsed tool row — a path or command
 * says far more than the first key alphabetically.
 */
private fun JsonObject.headlineArgument(): String? {
    for (key in HEADLINE_KEYS) {
        str(key)?.let { return it }
    }
    return (entries.firstOrNull()?.value as? JsonPrimitive)?.contentOrNull
}

private val HEADLINE_KEYS = listOf("command", "file_path", "filePath", "path", "pattern", "query", "url")
