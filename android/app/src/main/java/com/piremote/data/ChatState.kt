package com.piremote.data

import com.piremote.net.Item
import com.piremote.net.ItemPatchDto
import com.piremote.net.MoreDto
import com.piremote.net.Push
import com.piremote.net.PromptImage
import com.piremote.net.SessionDetailDto
import com.piremote.net.SessionStatusDto
import com.piremote.net.TextDto
import com.piremote.net.TextPatchDto

/**
 * Everything one session's screen renders, and a pure reducer over the push
 * stream.
 *
 * Kept free of coroutines and the client on purpose: the whole "does the client
 * converge on what the server has" question is then a matter of folding a list of
 * pushes, which a unit test can do exhaustively. [SessionStore] is left holding
 * only the plumbing.
 */
data class ChatState(
    val items: List<Item> = emptyList(),
    val detail: SessionDetailDto? = null,
    val status: SessionStatusDto = SessionStatusDto(),
    val loading: Boolean = false,
    val loadingOlder: Boolean = false,
    val hasMore: Boolean = false,
    /** Cursor for the page before [items]; null when the start is loaded. */
    val oldest: String? = null,
    val error: String? = null,
    /** Set when the agent is busy and the user must pick steer vs followUp. */
    val busyPrompt: BusyPrompt? = null,
    /** True while the model is deriving a session title. */
    val generatingTitle: Boolean = false,
    /** Expanded originals, keyed by the `more` handle that fetched them. */
    val expanded: Map<String, String> = emptyMap(),
) {
    /** Last assistant text, used as the completion notification's body. */
    fun lastAssistantText(): String? =
        items.filterIsInstance<Item.Assistant>().lastOrNull()?.text?.s?.takeIf { it.isNotBlank() }
}

/** A message that hit a busy agent; the choice dialog retries with these. */
data class BusyPrompt(val text: String, val images: List<PromptImage>? = null)

/**
 * Ceiling on resident items.
 *
 * The largest real session is 7.7MB across 1460 entries; holding all of it costs
 * far more than refetching a page, and the cursor stays valid either way.
 */
const val MAX_ITEMS = 400

/**
 * Fold one push into the state.
 *
 * **`add` is an upsert**, and that is load-bearing rather than defensive. It is how
 * a reconnect is caught up: instead of replaying the pushes missed — a 2000-word
 * answer streams over a thousand of them — the server resends each item that
 * changed, in full, and one `add` per item collapses whatever happened to it.
 *
 * The rest is written to tolerate arriving out of step with a snapshot, because
 * that happens: a `hello` is assembled while pushes are still in flight, and a
 * patch can name an item a newer `hello` has already replaced. Ordering against a
 * snapshot is the server's job (it withholds pushes the snapshot already covers);
 * a patch for an id we do not have is simply dropped.
 */
fun ChatState.reduce(push: Push): ChatState = when (push) {
    is Push.Hello -> copy(
        items = push.items,
        detail = push.detail,
        status = push.status,
        hasMore = push.hasMore,
        oldest = push.oldest,
        loading = false,
        loadingOlder = false,
    )

    is Push.Add -> {
        val at = items.indexOfFirst { it.id == push.item.id }
        if (at != -1) copy(items = items.toMutableList().also { it[at] = push.item })
        else appended(push.item)
    }

    is Push.Patch -> {
        val at = items.indexOfFirst { it.id == push.id }
        if (at == -1) this else copy(items = items.toMutableList().also { it[at] = it[at].patched(push) })
    }

    is Push.Status -> copy(status = push.status)

    is Push.Error -> copy(error = push.message)

    is Push.Unsubscribed, Push.Pong -> this
}

/**
 * Append an item, dropping the oldest ones once the list grows past what a phone
 * should hold.
 *
 * Trimming has to move the paging cursor with it. [oldest] names the item the next
 * page is fetched *before*, so leaving it pointing at something that was just
 * dropped tears a hole in the history: `loadOlder()` would fetch the page before a
 * message that is no longer resident and stitch it straight onto the survivors,
 * losing everything in between until a full resync — and a snapshot only carries
 * the newest 50 items, so scrolling back would never recover it.
 */
private fun ChatState.appended(item: Item): ChatState {
    val grown = items + item
    if (grown.size <= MAX_ITEMS) return copy(items = grown)
    val kept = grown.takeLast(MAX_ITEMS)
    // Something was dropped, so there is definitely history before `kept` now —
    // even if we had previously loaded all the way to the start.
    return copy(items = kept, oldest = kept.first().id, hasMore = true)
}

private fun Item.patched(push: Push.Patch): Item {
    val appended = push.append?.let { append ->
        when (this) {
            is Item.Assistant -> when (append.f) {
                "text" -> copy(text = text.append(append.s))
                "thinking" -> copy(thinking = (thinking ?: TextDto()).append(append.s))
                else -> this
            }
            is Item.Tool -> if (append.f == "output") copy(output = output.append(append.s)) else this
            else -> this
        }
    } ?: this

    return push.set?.let { appended.applying(it) } ?: appended
}

private fun TextDto.append(chunk: String): TextDto = copy(s = s + chunk)

/**
 * A text field is merged rather than replaced.
 *
 * A patch carrying only `more` means the server kept our streamed copy — which is
 * the fuller one — and is just handing over the handle for the part that was never
 * streamed.
 */
private fun TextDto?.merge(patch: TextPatchDto?): TextDto? {
    if (patch == null) return this
    val current = this ?: TextDto()
    return TextDto(s = patch.s ?: current.s, more = patch.more ?: current.more)
}

private fun Item.applying(set: ItemPatchDto): Item = when (this) {
    is Item.User -> copy(
        text = text.merge(set.text) ?: text,
        images = set.images ?: images,
    )

    is Item.Assistant -> copy(
        text = text.merge(set.text) ?: text,
        thinking = thinking.merge(set.thinking),
        usage = set.usage ?: usage,
        error = set.error ?: error,
        pending = set.pending ?: pending,
    )

    is Item.Tool -> copy(
        output = output.merge(set.output) ?: output,
        args = args.merge(set.args),
        title = set.title ?: title,
        isError = set.isError ?: isError,
        hasImage = set.hasImage ?: hasImage,
        exit = set.exit ?: exit,
        diff = set.diff ?: diff,
        running = set.running ?: running,
    )

    is Item.Notice -> this
}

/** The expanded original of a handle, if it has been fetched. */
fun ChatState.expandedOf(more: MoreDto?): String? = more?.let { expanded[it.ref] }
