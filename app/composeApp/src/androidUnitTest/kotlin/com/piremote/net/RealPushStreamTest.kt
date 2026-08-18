package com.piremote.net

import com.piremote.data.ChatState
import com.piremote.data.reduce
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replays a stream captured off the real bridge.
 *
 * `protocol.ts` and `Protocol.kt` are mirrored by hand, so the failure mode worth
 * guarding is a silent drift between them: a renamed field or a changed shape that
 * both sides still compile, and that only shows up as a frame the app quietly drops.
 * Synthetic pushes cannot catch that — these are the actual bytes the server sent
 * for a turn with thinking, a bash call and a text answer.
 *
 * Re-capture with `server/` running:
 *   cd server && node test/capture.mjs ../android/app/src/test/resources/pushes.jsonl
 */
class RealPushStreamTest {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private fun frames(): List<String> =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("pushes.jsonl")) { "fixture missing" }
            .bufferedReader()
            .readLines()
            .filter { it.isNotBlank() }

    @Test
    fun `every captured frame decodes`() {
        val pushes = frames().map { json.decodeFromString<Push>(it) }

        assertTrue("the capture has content", pushes.size > 10)
        // A frame the app cannot classify would deserialize into nothing useful.
        assertTrue("all frames belong to one session", pushes.all { it.sessionId != null || it is Push.Pong })
        assertNotNull("the stream opens with a snapshot", pushes.firstOrNull() as? Push.Hello)
    }

    @Test
    fun `the captured turn folds into the conversation the server stored`() {
        val state = frames()
            .map { json.decodeFromString<Push>(it) }
            .fold(ChatState()) { acc, push -> acc.reduce(push) }

        // The server reported: notice → notice → user → assistant → tool → assistant.
        assertEquals(
            listOf("notice", "notice", "user", "assistant", "tool", "assistant"),
            state.items.map {
                when (it) {
                    is Item.Notice -> "notice"
                    is Item.User -> "user"
                    is Item.Assistant -> "assistant"
                    is Item.Tool -> "tool"
                }
            },
        )

        val tool = state.items.filterIsInstance<Item.Tool>().single()
        assertEquals("bash", tool.name)
        assertTrue("the command is on the row: ${tool.title}", tool.title?.isNotBlank() == true)
        assertTrue("the output arrived: ${tool.output.s}", tool.output.s.contains("1"))
        assertEquals("the tool finished", false, tool.running)

        val answer = state.items.filterIsInstance<Item.Assistant>().last()
        assertTrue("the answer has text", answer.text.s.isNotBlank())
        assertEquals("no message is left pending", false, answer.pending)
        assertNotNull("per-turn usage came through", answer.usage)

        assertEquals("the run is over", false, state.status.running)
        assertNotNull("the settings arrived with the snapshot", state.detail)
    }

    @Test
    fun `no frame leaves an item pending or a patch unapplied`() {
        val pushes = frames().map { json.decodeFromString<Push>(it) }
        val state = pushes.fold(ChatState()) { acc, push -> acc.reduce(push) }

        assertTrue("nothing still streaming", state.items.filterIsInstance<Item.Assistant>().none { it.pending })

        // Every patch must have found its target. A patch for an unknown id is
        // dropped by design, but in a full capture that would mean the ids the
        // server mints and the ones it patches have drifted apart.
        val ids = state.items.map { it.id }.toSet()
        val patchTargets = pushes.filterIsInstance<Push.Patch>().map { it.id }.toSet()
        assertTrue("patch targets not in the list: ${patchTargets - ids}", patchTargets.all { it in ids })
    }
}
