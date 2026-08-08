package com.piremote.data

/**
 * The in-flight assistant turn.
 *
 * Kept apart from [ChatState] on purpose: text deltas arrive many times per
 * second, and if they lived in the same object every settled message would
 * recompose alongside them. Only the live bubble subscribes here.
 */
data class StreamingState(
    val running: Boolean = false,
    /** Assembled text of the assistant message currently streaming. */
    val text: String = "",
    /** True once a thinking block has started, so the UI can show the label. */
    val thinking: Boolean = false,
    /** Tool currently executing, with whatever partial output has arrived. */
    val activeTool: ActiveTool? = null,
    /** Messages waiting behind the current turn. */
    val queued: List<String> = emptyList(),
    /** Set while the server is compacting context. */
    val compacting: Boolean = false,
) {
    val hasContent: Boolean
        get() = text.isNotBlank() || thinking || activeTool != null
}

data class ActiveTool(
    val callId: String,
    val name: String,
    val subtitle: String?,
    val partialOutput: String = "",
)
