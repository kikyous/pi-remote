package com.piremote.data

import com.piremote.R
import com.piremote.net.ApiException
import com.piremote.net.CompactResultDto
import com.piremote.net.PiRemoteClient
import com.piremote.net.PromptImage
import com.piremote.net.Push
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One session's chat state.
 *
 * There is deliberately no shared "current chat" object. Each session owns its own
 * state, so switching screens is just pointing the UI at a different flow — a slow
 * response for the session you left cannot land on the one you are now looking at.
 *
 * The store holds no streaming state of its own and runs no timer. Both used to
 * live here: the server sent one frame per token, so this class ran a 33ms pump to
 * batch them and kept a separate [ChatState] twin for the message in flight, with a
 * phase-routing invariant to keep thinking deltas out of the text accumulator. The
 * server coalesces now, and a streaming message is just an item with `pending`, so
 * all of that is gone.
 */
class SessionStore(
    val sessionId: String,
    private val client: PiRemoteClient,
    private val scope: CoroutineScope,
    private val context: android.content.Context,
    /** Ask the socket for a fresh snapshot. What the refresh button does. */
    private val onResync: (String) -> Unit,
) {
    private val _state = MutableStateFlow(ChatState(loading = true))
    val state: StateFlow<ChatState> = _state.asStateFlow()

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
     * Attachments picked for the next send, previewed above the composer.
     * Survives navigation like the draft; cleared once sent.
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

    private val pagingLock = Mutex()

    /* ---------------- live pushes ---------------- */

    /**
     * Apply one push.
     *
     * Called for every push on this session's subscription, including while the
     * screen is not visible — that is what keeps a background session correct
     * without a refresh.
     */
    fun apply(push: Push) {
        _state.update { it.reduce(push) }
    }

    /**
     * Throw away local state and ask for a fresh snapshot.
     *
     * There is nothing to fetch here: `hello` carries the newest page, the
     * settings and the status in one frame, so a refresh is a re-subscribe.
     */
    fun refresh() {
        _state.update { it.copy(loading = true, error = null) }
        onResync(sessionId)
    }

    /* ---------------- paging ---------------- */

    /** Fetch the page before what is loaded. Safe to call repeatedly while scrolling. */
    fun loadOlder() {
        val cursor = _state.value.oldest ?: return
        if (_state.value.loadingOlder || !_state.value.hasMore) return

        scope.launch {
            pagingLock.withLock {
                // Re-check inside the lock: another call may have finished the job,
                // or a snapshot may have replaced the list while we waited.
                if (_state.value.oldest != cursor) return@withLock
                _state.update { it.copy(loadingOlder = true) }
                try {
                    val page = client.items(sessionId, before = cursor)
                    // A snapshot that landed during the fetch reset the cursor; its
                    // list is authoritative and this page no longer joins onto it.
                    if (_state.value.oldest != cursor) return@withLock
                    _state.update {
                        it.copy(
                            items = page.items + it.items,
                            oldest = page.oldest,
                            hasMore = page.hasMore,
                            loadingOlder = false,
                        )
                    }
                } catch (e: ApiException) {
                    // The branch moved under us — only a fresh snapshot is consistent.
                    if (e.isStaleCursor) refresh()
                    else _state.update { it.copy(loadingOlder = false, error = e.readable()) }
                } catch (e: Exception) {
                    _state.update { it.copy(loadingOlder = false, error = e.readable()) }
                }
            }
        }
    }

    /**
     * Fetch the untruncated original behind a handle.
     *
     * One call for every kind of shortened content — long text, tool arguments,
     * thinking, an image's bytes — because the handle is opaque and the server
     * resolves it. The old client had to mirror a `part` enum and an index.
     */
    fun expand(ref: String) {
        if (_state.value.expanded.containsKey(ref)) return
        scope.launch {
            try {
                val full = client.full(sessionId, ref)
                _state.update { it.copy(expanded = it.expanded + (ref to full.content)) }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.readable()) }
            }
        }
    }

    /* ---------------- sending ---------------- */

    /**
     * Send a prompt.
     *
     * @param behavior null on the first try. If the agent turns out to be busy the
     *   server answers 409 and [ChatState.busyPrompt] is set, which the UI turns
     *   into a steer/queue choice; the retry then passes the choice here.
     */
    fun send(text: String, behavior: String? = null, images: List<PromptImage>? = null) {
        // Attachments picked in the composer are carried by default; an explicit
        // `images` argument (a busy-retry) overrides them.
        val effectiveImages = images ?: _attachments.value.takeIf { it.isNotEmpty() }
        if (text.isBlank() && effectiveImages.isNullOrEmpty()) return
        scope.launch {
            try {
                client.prompt(sessionId, text, behavior, effectiveImages)
                _attachments.value = emptyList()
                _state.update { it.copy(busyPrompt = null) }
                // The user's own message arrives back as an `add` push, so nothing
                // is inserted locally — one source of truth.
            } catch (e: ApiException) {
                if (e.isBusy) _state.update { it.copy(busyPrompt = BusyPrompt(text, effectiveImages)) }
                else _state.update { it.copy(error = e.readable()) }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.readable()) }
            }
        }
    }

    fun dismissBusyPrompt() = _state.update { it.copy(busyPrompt = null) }

    fun abort() {
        scope.launch {
            runCatching { client.abort(sessionId) }
                .onFailure { e -> _state.update { it.copy(error = e.message ?: context.getString(R.string.err_abort)) } }
        }
    }

    /* ---------------- settings ---------------- */

    /**
     * Change this session's model. Scoped to this session only — the server records
     * a `model_change` entry, and other sessions are untouched.
     *
     * The write answers with the new detail, so there is no follow-up read: switching
     * model can clamp the thinking level, and the response already reflects that.
     */
    fun setModel(provider: String, modelId: String) = patchSession { client.updateSession(sessionId, provider = provider, modelId = modelId) }

    fun setThinkingLevel(level: String) = patchSession { client.updateSession(sessionId, thinkingLevel = level) }

    /** Set a display title; [onDone] receives null on success, else an error. */
    fun setName(name: String, onDone: (String?) -> Unit) {
        scope.launch {
            try {
                val detail = client.updateSession(sessionId, name = name)
                _state.update { it.copy(detail = detail) }
                onDone(null)
            } catch (e: Exception) {
                onDone(e.message ?: context.getString(R.string.err_set_title))
            }
        }
    }

    /**
     * Ask the server to have the model title this session from its conversation;
     * [onDone] receives `(title, error)` — one of them null.
     */
    fun generateTitle(onDone: (String?, String?) -> Unit) {
        _state.update { it.copy(generatingTitle = true) }
        scope.launch {
            try {
                val detail = client.generateTitle(sessionId)
                _state.update { it.copy(detail = detail, generatingTitle = false) }
                onDone(detail.name, null)
            } catch (e: Exception) {
                _state.update { it.copy(generatingTitle = false) }
                onDone(null, e.message ?: context.getString(R.string.err_generate_title))
            }
        }
    }

    /**
     * Summarize the conversation into a compaction entry; [onDone] receives
     * `(result, error)` — one of them null.
     *
     * Nothing is inserted into [ChatState.items] on success: the server publishes
     * the "Context compacted" notice and the new context estimate on the push
     * stream, so every device watching the session sees the same thing.
     */
    fun compact(onDone: (CompactResultDto?, String?) -> Unit) {
        if (_state.value.compacting) return
        _state.update { it.copy(compacting = true) }
        scope.launch {
            try {
                val result = client.compact(sessionId)
                _state.update { it.copy(compacting = false) }
                onDone(result, null)
            } catch (e: Exception) {
                _state.update { it.copy(compacting = false) }
                onDone(null, e.message ?: context.getString(R.string.err_compact))
            }
        }
    }

    /**
     * This session's spend totals, read on demand.
     *
     * Deliberately not part of [ChatState]: no push carries these numbers, and
     * the info sheet is the only thing that ever asks. Failures surface to the
     * caller, which shows them inside the sheet rather than as a snackbar behind it.
     */
    suspend fun stats(): com.piremote.net.SessionStatsDto = client.stats(sessionId)

    private fun patchSession(call: suspend () -> com.piremote.net.SessionDetailDto) {
        scope.launch {
            try {
                val detail = call()
                _state.update { it.copy(detail = detail) }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.readable()) }
            }
        }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    /** Last assistant text, used as the completion notification's body. */
    fun lastAssistantText(): String? = _state.value.lastAssistantText()
}

private fun Exception.readable(): String = when (this) {
    is ApiException -> message
    else -> message ?: this::class.simpleName ?: "Unknown error"
}
