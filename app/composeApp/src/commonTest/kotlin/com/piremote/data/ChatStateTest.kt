package com.piremote.data

import com.piremote.net.AppendDto
import com.piremote.net.Item
import com.piremote.net.ItemPatchDto
import com.piremote.net.MoreDto
import com.piremote.net.Push
import com.piremote.net.SessionDetailDto
import com.piremote.net.SessionStatusDto
import com.piremote.net.TextDto
import com.piremote.net.TextPatchDto
import com.piremote.net.UsageDto
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

/**
 * The reducer is the whole client-side protocol, so this is where "does the app end
 * up with what the server has" gets settled.
 */
class ChatStateTest {

    private val detail = SessionDetailDto(id = "s1", cwd = "/tmp/p")

    private fun add(item: Item) = Push.Add(sessionId = "s1", seq = 1, item = item)

    private fun append(id: String, field: String, chunk: String) =
        Push.Patch(sessionId = "s1", seq = 1, id = id, append = AppendDto(field, chunk))

    private fun set(id: String, patch: ItemPatchDto) =
        Push.Patch(sessionId = "s1", seq = 1, id = id, set = patch)

    private fun assistant(id: String) = Item.Assistant(id = id, at = "t", pending = true)

    private fun ChatState.assistantAt(index: Int) = items[index] as Item.Assistant

    private fun ChatState.toolAt(index: Int) = items[index] as Item.Tool

    private fun fold(vararg pushes: Push): ChatState = pushes.fold(ChatState()) { state, push -> state.reduce(push) }

    @Test
    fun `a streamed turn assembles into one settled message`() {
        val state = fold(
            Push.Status(sessionId = "s1", seq = 1, status = SessionStatusDto(running = true)),
            add(Item.User(id = "e1", at = "t", text = TextDto("hello"))),
            add(assistant("live-1")),
            append("live-1", "thinking", "let me "),
            append("live-1", "thinking", "think"),
            append("live-1", "text", "the "),
            append("live-1", "text", "answer"),
            set("live-1", ItemPatchDto(pending = false, usage = UsageDto(input = 10, output = 5))),
            Push.Status(sessionId = "s1", seq = 9, status = SessionStatusDto(running = false)),
        )

        assertEquals(2, state.items.size)
        val reply = state.assistantAt(1)
        assertEquals("the answer", reply.text.s)
        assertEquals("let me think", reply.thinking?.s)
        assertEquals(false, reply.pending)
        assertEquals(5, reply.usage?.output)
        assertEquals(false, state.status.running)
    }

    @Test
    fun `add is an upsert - which is how a reconnect is caught up`() {
        // The server resends each item that changed while we were away, in full,
        // rather than replaying the pushes we missed.
        val partial = Item.Assistant(id = "live-1", at = "t", text = TextDto("half a sen"), pending = true)
        val whole = Item.Assistant(id = "live-1", at = "t", text = TextDto("half a sentence, then the rest"), pending = false)

        val state = fold(add(partial), add(whole))

        assertEquals(1, state.items.size, "one item, not two")
        assertEquals("half a sentence, then the rest", state.assistantAt(0).text.s)
        assertEquals(false, state.assistantAt(0).pending)
    }

    @Test
    fun `re-adding an item a snapshot already has leaves one copy`() {
        // A `hello` is assembled while pushes are in flight, so the snapshot can
        // already contain an item whose `add` arrives right after it.
        val item = Item.User(id = "e1", at = "t", text = TextDto("once"))
        val state = fold(
            Push.Hello(sessionId = "s1", seq = 3, items = listOf(item), detail = detail),
            add(item),
        )

        assertEquals(1, state.items.size)
        assertEquals("once", (state.items[0] as Item.User).text.s)
    }

    @Test
    fun `a patch for an item we do not have is dropped - not fatal`() {
        val before = fold(Push.Hello(sessionId = "s1", seq = 1, items = emptyList(), detail = detail))
        val after = before.reduce(append("live-9", "text", "orphan"))

        assertEquals(before.items, after.items)
    }

    @Test
    fun `hello replaces everything - including a stale pending message`() {
        val state = fold(
            add(assistant("live-1")),
            append("live-1", "text", "half a sen"),
            Push.Hello(
                sessionId = "s1",
                seq = 20,
                items = listOf(Item.Assistant(id = "e7", at = "t", text = TextDto("the whole sentence"))),
                hasMore = true,
                oldest = "e7",
                detail = detail,
                status = SessionStatusDto(running = false),
            ),
        )

        assertEquals(1, state.items.size)
        assertEquals("the whole sentence", state.assistantAt(0).text.s)
        assertTrue(state.hasMore)
        assertEquals("e7", state.oldest)
        assertEquals(false, state.loading)
    }

    @Test
    fun `a text patch carrying only a handle keeps the text we streamed`() {
        // The server does this on purpose: we received every delta, so our copy is
        // the fuller one, and all that is missing is the handle for the tail that
        // was never streamed.
        val state = fold(
            add(assistant("live-1")),
            append("live-1", "text", "a very long answer"),
            set("live-1", ItemPatchDto(pending = false, text = TextPatchDto(more = MoreDto(ref = "e1|text|0", bytes = 45_000)))),
        )

        val reply = state.assistantAt(0)
        assertEquals("a very long answer", reply.text.s)
        assertEquals("e1|text|0", reply.text.more?.ref)
        assertEquals("43 KB", reply.text.more?.displaySize)
    }

    @Test
    fun `a text patch carrying s replaces - which is the drift escape hatch`() {
        val state = fold(
            add(assistant("live-1")),
            append("live-1", "text", "what we streamed"),
            set("live-1", ItemPatchDto(text = TextPatchDto(s = "what was actually stored"))),
        )

        assertEquals("what was actually stored", state.assistantAt(0).text.s)
    }

    @Test
    fun `a tool row grows its output and then settles`() {
        val state = fold(
            add(Item.Tool(id = "e1#0", at = "t", callId = "c1", name = "bash", title = "seq 3", running = true)),
            append("e1#0", "output", "1\n"),
            append("e1#0", "output", "2\n3\n"),
            set("e1#0", ItemPatchDto(running = false, exit = 0)),
        )

        val tool = state.toolAt(0)
        assertEquals("1\n2\n3\n", tool.output.s)
        assertEquals(false, tool.running)
        assertEquals(0, tool.exit)
    }

    @Test
    fun `a null field in a patch means unchanged - never cleared`() {
        val state = fold(
            add(Item.Tool(id = "t1", at = "t", name = "read", title = "/a.txt", isError = true, running = true)),
            // Only `running` is being set; everything else must survive.
            set("t1", ItemPatchDto(running = false)),
        )

        val tool = state.toolAt(0)
        assertEquals("/a.txt", tool.title)
        assertTrue(tool.isError)
        assertEquals(false, tool.running)
    }

    @Test
    fun `a catch-up that resends the tail converges without duplicating it`() {
        // What a reconnect mid-turn actually looks like: we hold a partly streamed
        // message and a running tool, and the server resends both, settled.
        val live = fold(
            add(Item.User(id = "e1", at = "t", text = TextDto("q"))),
            add(assistant("live-1")),
            append("live-1", "text", "thinking out lou"),
            add(Item.Tool(id = "e2#0", at = "t", callId = "c1", name = "bash", running = true)),
            append("e2#0", "output", "partial"),
        )

        val caughtUp = fold(
            *arrayOf(
                add(Item.User(id = "e1", at = "t", text = TextDto("q"))),
                add(assistant("live-1")),
                append("live-1", "text", "thinking out lou"),
                add(Item.Tool(id = "e2#0", at = "t", callId = "c1", name = "bash", running = true)),
                append("e2#0", "output", "partial"),
                // The catch-up: each changed item, whole.
                add(Item.Assistant(id = "live-1", at = "t", text = TextDto("thinking out loud, done"), pending = false)),
                add(Item.Tool(id = "e2#0", at = "t", callId = "c1", name = "bash", output = TextDto("partial then all"), exit = 0)),
                Push.Status(sessionId = "s1", seq = 99, status = SessionStatusDto(running = false)),
            ),
        )

        assertEquals(3, live.items.size, "still three items")
        assertEquals(3, caughtUp.items.size)
        assertEquals("thinking out loud, done", caughtUp.assistantAt(1).text.s)
        assertEquals("partial then all", caughtUp.toolAt(2).output.s)
        assertEquals(false, caughtUp.toolAt(2).running)
        assertEquals(false, caughtUp.status.running)
    }

    @Test
    fun `replaying any suffix converges on the same state`() {
        // A reconnect replays from a cursor, and the boundary is not always exact.
        // Re-applying a tail of the stream must not double-count anything except
        // appends, which the sequence filter is what protects — so this checks the
        // part the client is responsible for: adds and sets are idempotent.
        val stream = listOf(
            add(Item.User(id = "e1", at = "t", text = TextDto("q"))),
            add(assistant("live-1")),
            set("live-1", ItemPatchDto(pending = false, usage = UsageDto(input = 1, output = 2))),
            add(Item.Tool(id = "e2#0", at = "t", callId = "c1", name = "bash", running = true)),
            set("e2#0", ItemPatchDto(running = false, exit = 0)),
        )

        val once = stream.fold(ChatState()) { s, p -> s.reduce(p) }
        for (from in stream.indices) {
            val replayed = stream.drop(from).fold(once) { s, p -> s.reduce(p) }
            assertEquals(once, replayed, "replaying from $from")
        }
    }

    @Test
    fun `an error push surfaces without touching the conversation`() {
        val state = fold(
            add(Item.User(id = "e1", at = "t", text = TextDto("q"))),
            Push.Error(sessionId = "s1", message = "no such session", code = "session_not_found"),
        )

        assertEquals(1, state.items.size)
        assertEquals("no such session", state.error)
    }

    @Test
    fun `the list is capped - oldest first`() {
        val state = (1..MAX_ITEMS + 10).fold(ChatState()) { s, i ->
            s.reduce(add(Item.User(id = "e$i", at = "t", text = TextDto("m$i"))))
        }

        assertEquals(MAX_ITEMS, state.items.size)
        assertEquals("e11", state.items.first().id)
        assertEquals("e${MAX_ITEMS + 10}", state.items.last().id)
    }

    @Test
    fun `trimming re-anchors the paging cursor on what survived`() {
        // Left pointing at a dropped item, the next page would be fetched before a
        // message that is no longer in the list and stitched onto the survivors,
        // silently losing everything between the two — and a snapshot only carries
        // the newest 50 items, so scrolling back would never bring it back.
        val loaded = Push.Hello(
            sessionId = "s1",
            seq = 1,
            items = (1..MAX_ITEMS).map { Item.User(id = "e$it", at = "t", text = TextDto("m$it")) },
            hasMore = true,
            oldest = "e1",
            detail = detail,
        )
        val full = ChatState().reduce(loaded)
        assertEquals("e1", full.oldest)

        val after = full.reduce(add(Item.User(id = "new", at = "t", text = TextDto("newest"))))

        assertEquals(MAX_ITEMS, after.items.size)
        assertEquals("e2", after.items.first().id, "e1 was dropped")
        assertEquals("e2", after.oldest, "the cursor follows the window")
        assertTrue(after.hasMore, "there is history before the window again")
    }

    @Test
    fun `trimming a fully loaded session reopens paging`() {
        // hasMore was false — the very start of the session was resident — and then
        // trimming pushed it out again.
        val loaded = Push.Hello(
            sessionId = "s1",
            seq = 1,
            items = (1..MAX_ITEMS).map { Item.User(id = "e$it", at = "t", text = TextDto("m$it")) },
            hasMore = false,
            oldest = null,
            detail = detail,
        )
        val after = ChatState().reduce(loaded).reduce(add(Item.User(id = "new", at = "t", text = TextDto("newest"))))

        assertTrue(after.hasMore)
        assertEquals("e2", after.oldest)
    }

    @Test
    fun `the completion notification reads the last assistant text`() {
        val state = fold(
            add(Item.User(id = "e1", at = "t", text = TextDto("q"))),
            add(Item.Assistant(id = "e2", at = "t", text = TextDto("first"))),
            add(Item.Assistant(id = "e3", at = "t", text = TextDto("last"))),
        )

        assertEquals("last", state.lastAssistantText())
        assertNull(ChatState().lastAssistantText())
    }
}
