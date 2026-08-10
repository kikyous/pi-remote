package com.piremote.data

import com.piremote.R

import com.piremote.net.ApiException
import com.piremote.net.PiRemoteClient
import com.piremote.net.PromptImage
import com.piremote.net.SessionDetailDto
import com.piremote.net.WsMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.concurrent.atomic.AtomicLong

data class ChatState(
    val items: List<ChatItem> = emptyList(),
    val detail: SessionDetailDto? = null,
    val loading: Boolean = false,
    val loadingOlder: Boolean = false,
    val hasMore: Boolean = false,
    val error: String? = null,
    /** Set when the agent is busy and the user must pick steer vs followUp. */
    val busyPrompt: BusyPrompt? = null,
    /** True while the model is deriving a session title. */
    val generatingTitle: Boolean = false,
    /** Expanded originals, keyed by "entryId:part:index". */
    val expanded: Map<String, String> = emptyMap(),
)

/** A message that hit a busy agent; the choice dialog retries with these. */
data class BusyPrompt(val text: String, val images: List<PromptImage>? = null)

/**
 * One session's chat state.
 *
 * There is deliberately no shared "current chat" object. Each session owns its
 * own state, so switching screens is just pointing the UI at a different flow —
 * a slow response for the session you left cannot land on the one you are now
 * looking at.
 *
 * The second half of that guarantee is [epoch]: every reload bumps it, and any
 * in-flight response carrying a stale epoch is dropped instead of applied.
 */
class SessionStore(
    val sessionId: String,
    private val client: PiRemoteClient,
    private val scope: CoroutineScope,
    private val context: android.content.Context,
) {
    private val _state = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = _state.asStateFlow()

    private val _streaming = MutableStateFlow(StreamingState())
    val streaming: StateFlow<StreamingState> = _streaming.asStateFlow()

    /**
     * Composer draft text. Lives here, not in the UI: leaving the chat screen
     * (e.g. to the git view) disposes the input's local state, but the store is
     * cached per session, so the draft survives navigation.
     */
    private val _draft = MutableStateFlow("")
    val draft: StateFlow<String> = _draft.asStateFlow()

    fun setDraft(text: String) {
        _draft.value = text
    }

    /**
     * Attachments picked for the next send (方案 A: preview bar above the
     * composer). Survives navigation like the draft; cleared once sent.
     */
    private val _attachments = MutableStateFlow<List<PromptImage>>(emptyList())
    val attachments: StateFlow<List<PromptImage>> = _attachments.asStateFlow()

    fun addAttachment(image: PromptImage) {
        _attachments.value = _attachments.value + image
    }

    fun removeAttachment(index: Int) {
        _attachments.value = _attachments.value.filterIndexed { i, _ -> i != index }
    }

    fun clearAttachments() {
        _attachments.value = emptyList()
    }

    private val epoch = AtomicLong(0)
    private val pagingLock = Mutex()
    private var oldestId: String? = null
    private var activeJob: Job? = null

    /**
     * Deltas are coalesced here and flushed on a timer.
     *
     * A fast model emits well over a hundred deltas a second; writing each one
     * straight to state would recompose the bubble that many times and drop
     * frames. Buffering into one write per frame keeps it smooth.
     *
     * One channel serves both text and thinking deltas: within a message the
     * phases never overlap (thinking deltas strictly precede text deltas), and
     * every phase boundary flushes, so the buffer never mixes them and the
     * current `thinking` flag routes each flush to the right accumulator.
     */
    private val deltas = Channel<String>(Channel.UNLIMITED)
    private val pending = StringBuilder()

    init {
        scope.launch { runDeltaPump() }
    }

    /**
     * Reload from the server, discarding local state.
     *
     * This is what the refresh button does. It rebuilds rather than merges: on
     * a server other clients also write to, messages interleave by time, and
     * appending only what is new would put them in the wrong places.
     */
    fun refresh() {
        val myEpoch = epoch.incrementAndGet()
        activeJob?.cancel()
        _state.update { it.copy(loading = true, error = null) }

        activeJob = scope.launch {
            try {
                val detail = client.sessionDetail(sessionId)
                val page = client.entries(sessionId, before = null, limit = PAGE_SIZE)
                if (!isCurrent(myEpoch)) return@launch

                oldestId = page.oldestId
                _state.value = ChatState(
                    items = linkToolResults(parseEntries(page.entries)),
                    detail = detail,
                    loading = false,
                    hasMore = page.hasMore,
                )
                _streaming.value = StreamingState(running = detail.running)
            } catch (e: Exception) {
                if (!isCurrent(myEpoch)) return@launch
                _state.update { it.copy(loading = false, error = e.readable()) }
            }
        }
    }

    /** Fetch the page before what is loaded. Safe to call repeatedly while scrolling. */
    fun loadOlder() {
        val myEpoch = epoch.get()
        val cursor = oldestId ?: return
        if (_state.value.loadingOlder || !_state.value.hasMore) return

        scope.launch {
            pagingLock.withLock {
                // Re-check inside the lock: another call may have finished the
                // job, or the list may have been rebuilt while we waited.
                if (!isCurrent(myEpoch) || oldestId != cursor) return@withLock
                _state.update { it.copy(loadingOlder = true) }
                try {
                    val page = client.entries(sessionId, before = cursor, limit = PAGE_SIZE)
                    if (!isCurrent(myEpoch)) return@withLock

                    oldestId = page.oldestId
                    val older = parseEntries(page.entries)
                    _state.update { current ->
                        current.copy(
                            items = linkToolResults(older + current.items),
                            loadingOlder = false,
                            hasMore = page.hasMore,
                        )
                    }
                } catch (e: ApiException) {
                    if (!isCurrent(myEpoch)) return@withLock
                    // The branch moved under us — a full reload is the only way
                    // back to a consistent view.
                    if (e.isStaleCursor) refresh()
                    else _state.update { it.copy(loadingOlder = false, error = e.readable()) }
                } catch (e: Exception) {
                    if (!isCurrent(myEpoch)) return@withLock
                    _state.update { it.copy(loadingOlder = false, error = e.readable()) }
                }
            }
        }
    }

    /** Fetch the untruncated original behind a "show all" affordance. */
    fun expand(truncation: Truncation) {
        val myEpoch = epoch.get()
        val key = truncation.key()
        if (_state.value.expanded.containsKey(key)) return

        scope.launch {
            try {
                val full = client.fullPart(sessionId, truncation.entryId, truncation.part, truncation.index)
                if (!isCurrent(myEpoch)) return@launch
                _state.update { it.copy(expanded = it.expanded + (key to full.content)) }
            } catch (e: Exception) {
                if (!isCurrent(myEpoch)) return@launch
                _state.update { it.copy(error = e.readable()) }
            }
        }
    }

    /* ---------------- sending ---------------- */

    /**
     * Send a prompt.
     *
     * @param behavior null on the first try. If the agent turns out to be busy
     *   the server answers 409 and [ChatState.busyPrompt] is set, which the UI
     *   turns into a steer/queue choice; the retry then passes the choice here.
     */
    fun send(text: String, behavior: String? = null, images: List<PromptImage>? = null) {
        // Attachments picked in the composer are carried by default; an explicit
        // `images` argument (busy-retry etc.) overrides them.
        val effectiveImages = images ?: _attachments.value.takeIf { it.isNotEmpty() }
        if (text.isBlank() && effectiveImages.isNullOrEmpty()) return
        scope.launch {
            try {
                client.prompt(sessionId, text, behavior, effectiveImages)
                _attachments.value = emptyList()
                _state.update { it.copy(busyPrompt = null) }
                // The user's own message arrives back as an entry_appended event,
                // so nothing is inserted locally — one source of truth.
            } catch (e: ApiException) {
                if (e.isBusy) {
                    _state.update { it.copy(busyPrompt = BusyPrompt(text, effectiveImages)) }
                } else {
                    _state.update { it.copy(error = e.readable()) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.readable()) }
            }
        }
    }

    fun dismissBusyPrompt() = _state.update { it.copy(busyPrompt = null) }

    /**
     * Change this session's model. Scoped to this session only — the server
     * records a `model_change` entry, and other sessions are untouched.
     */
    fun setModel(provider: String, modelId: String) {
        scope.launch {
            try {
                client.updateSession(sessionId, provider = provider, modelId = modelId)
                // Re-read rather than assume: switching model can clamp the
                // thinking level when the new model supports fewer levels.
                val detail = client.sessionDetail(sessionId)
                _state.update { it.copy(detail = detail) }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.readable()) }
            }
        }
    }

    fun setThinkingLevel(level: String) {
        scope.launch {
            try {
                client.updateSession(sessionId, thinkingLevel = level)
                _state.update { it.copy(detail = it.detail?.copy(thinkingLevel = level)) }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.readable()) }
            }
        }
    }

    /** Set a display title; [onDone] receives null on success, else an error. */
    fun setName(name: String, onDone: (String?) -> Unit) {
        scope.launch {
            try {
                client.updateSession(sessionId, name = name)
                val detail = client.sessionDetail(sessionId)
                _state.update { it.copy(detail = detail) }
                onDone(null)
            } catch (e: Exception) {
                onDone(e.message ?: context.getString(R.string.err_set_title))
            }
        }
    }

    /**
     * Ask the server to have the model title this session from its
     * conversation; [onDone] receives `(title, error)` — one of them null.
     */
    fun generateTitle(onDone: (String?, String?) -> Unit) {
        _state.update { it.copy(generatingTitle = true) }
        scope.launch {
            try {
                val title = client.generateTitle(sessionId)
                val detail = client.sessionDetail(sessionId)
                _state.update { it.copy(detail = detail, generatingTitle = false) }
                onDone(title, null)
            } catch (e: Exception) {
                _state.update { it.copy(generatingTitle = false) }
                onDone(null, e.message ?: context.getString(R.string.err_generate_title))
            }
        }
    }

    fun abort() {
        scope.launch {
            runCatching { client.abort(sessionId) }
                .onFailure { e -> _state.update { it.copy(error = e.message ?: context.getString(R.string.err_abort)) } }
        }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    /** Last assistant text, used as the completion notification's body. */
    fun lastAssistantText(): String? =
        _state.value.items.filterIsInstance<ChatItem.Assistant>().lastOrNull()?.text?.takeIf { it.isNotBlank() }

    /**
     * Refetch just the detail (model, thinking level, context usage) without
     * touching the message list — called when a run settles so the context bar
     * stays current without a full reload that would reset the scroll.
     */
    fun refreshDetail() {
        scope.launch {
            runCatching { client.sessionDetail(sessionId) }
                .onSuccess { detail -> _state.update { it.copy(detail = detail) } }
        }
    }

    /* ---------------- live events ---------------- */

    /**
     * Apply one server event.
     *
     * Called for every message on this session's subscription, including while
     * the screen is not visible — that is what keeps a background session
     * correct without a refresh.
     */
    fun onSocketMessage(message: WsMessage) {
        when (message.op) {
            "subscribed" -> {
                // The server could not replay far enough back; only a refetch
                // restores a consistent view.
                if (message.gap) refresh()
                _streaming.update { it.copy(running = message.running) }
            }
            "event" -> message.event?.let(::onAgentEvent)
        }
    }

    private fun onAgentEvent(event: JsonObject) {
        when (event.type()) {
            // The server replaced the agent (its file changed underneath it);
            // sequence numbers restart, so the only correct move is a refetch.
            "session_reloaded" -> refresh()

            "agent_start" -> _streaming.value = StreamingState(running = true)

            "agent_settled" -> {
                flushDeltasNow()
                _streaming.value = StreamingState(running = false)
                // Context usage changed over the run; refresh just the detail
                // so the bar moves without a full reload.
                refreshDetail()
            }

            "message_start" -> {
                flushDeltasNow()
                _streaming.update { it.copy(text = "", thinking = false, thinkingText = "", activeTool = null) }
            }

            "message_update" -> onDelta(event)

            "message_end" -> {
                // The authoritative message lands as entry_appended; clearing
                // here avoids briefly showing it twice.
                flushDeltasNow()
                _streaming.update { it.copy(text = "", thinking = false, thinkingText = "") }
            }

            "entry_appended" -> {
                val entry = event["entry"] as? JsonObject ?: return
                parseEntry(entry)?.let(::appendItem)
            }

            "tool_execution_start" -> _streaming.update {
                it.copy(
                    activeTool = ActiveTool(
                        callId = event.str("toolCallId").orEmpty(),
                        name = event.str("toolName").orEmpty(),
                        subtitle = (event["args"] as? JsonObject)?.let { args ->
                            (args["command"] ?: args["file_path"] ?: args["path"]) as? JsonPrimitive
                        }?.contentOrNull,
                    ),
                )
            }

            "tool_execution_update" -> _streaming.update { current ->
                val partial = (event["partialResult"] as? JsonObject).textContent()
                current.copy(activeTool = current.activeTool?.copy(partialOutput = partial))
            }

            "tool_execution_end" -> _streaming.update { it.copy(activeTool = null) }

            "queue_update" -> _streaming.update { current ->
                val steering = event.stringList("steering")
                val followUp = event.stringList("followUp")
                current.copy(queued = steering + followUp)
            }

            "compaction_start" -> _streaming.update { it.copy(compacting = true) }
            "compaction_end" -> _streaming.update { it.copy(compacting = false) }

            "thinking_level_changed" -> {
                val level = event.str("level").orEmpty()
                _state.update { it.copy(detail = it.detail?.copy(thinkingLevel = level)) }
            }
        }
    }

    private fun onDelta(event: JsonObject) {
        val inner = event["assistantMessageEvent"] as? JsonObject ?: return
        when (inner.str("type")) {
            "text_delta" -> inner.str("delta")?.let { deltas.trySend(it) }
            // The SDK streams thinking as deltas too; accumulate them so the
            // streaming thinking card can expand to the live content (pi-web).
            "thinking_delta" -> inner.str("delta")?.let { deltas.trySend(it) }
            "thinking_start" -> {
                flushDeltasNow()
                _streaming.update { it.copy(thinking = true, thinkingText = "") }
            }
            // text_start only retires the spinner: the thinking card itself stays
            // until message_end (pi-web keeps it above the streaming text). The
            // flush guarantees the pending buffer is routed to thinkingText first.
            "text_start" -> {
                flushDeltasNow()
                _streaming.update { it.copy(thinking = false) }
            }
        }
    }

    /** Drain buffered deltas once per frame rather than once per delta. */
    private suspend fun runDeltaPump() {
        while (scope.isActive) {
            val first = deltas.receive()
            pending.append(first)
            // Sweep up anything that arrived in the same burst.
            while (true) {
                val next = deltas.tryReceive().getOrNull() ?: break
                pending.append(next)
            }
            kotlinx.coroutines.delay(FLUSH_INTERVAL_MS)
            flushDeltasNow()
        }
    }

    private fun flushDeltasNow() {
        while (true) {
            val next = deltas.tryReceive().getOrNull() ?: break
            pending.append(next)
        }
        if (pending.isEmpty()) return
        val chunk = pending.toString()
        pending.setLength(0)
        // Phase-correct by construction: flush runs at every thinking/text
        // boundary, so the buffer holds one phase's deltas only.
        if (_streaming.value.thinking) {
            _streaming.update { it.copy(thinkingText = it.thinkingText + chunk) }
        } else {
            _streaming.update { it.copy(text = it.text + chunk) }
        }
    }

    private fun appendItem(item: ChatItem) {
        _state.update { current ->
            val withoutDuplicate = current.items.filterNot { it.entryId == item.entryId }
            current.copy(items = linkToolResults(trim(withoutDuplicate + item)))
        }
    }

    /**
     * Drop the oldest items once the list grows past what a phone should hold.
     *
     * The cursor stays valid, so scrolling back up simply refetches — cheaper
     * than keeping a 2.7MB session resident.
     */
    private fun trim(items: List<ChatItem>): List<ChatItem> =
        if (items.size <= MAX_ITEMS) items else items.takeLast(MAX_ITEMS)

    private fun isCurrent(myEpoch: Long) = epoch.get() == myEpoch

    companion object {
        const val PAGE_SIZE = 50

        /**
         * Ceiling on resident items. The largest real session is 2.7MB across
         * 953 entries; holding all of it costs far more than refetching a page.
         */
        const val MAX_ITEMS = 400

        /** ~1 frame at 30fps: fast enough to look live, slow enough to batch. */
        const val FLUSH_INTERVAL_MS = 33L
    }
}

fun Truncation.key(): String = "$entryId:$part:${index ?: -1}"

private fun JsonObject.type(): String? = str("type")

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.stringList(key: String): List<String> {
    val array = this[key] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
    return array.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
}

/** Pull the text out of a tool result payload's `content` blocks. */
private fun JsonObject?.textContent(): String {
    val blocks = this?.get("content") as? kotlinx.serialization.json.JsonArray ?: return ""
    return blocks.mapNotNull { block ->
        (block as? JsonObject)?.takeIf { it.str("type") == "text" }?.str("text")
    }.joinToString("")
}

private fun Exception.readable(): String = when (this) {
    is ApiException -> message
    else -> message ?: this::class.simpleName ?: "Unknown error"
}
