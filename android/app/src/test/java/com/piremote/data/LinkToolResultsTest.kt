package com.piremote.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LinkToolResultsTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun entry(raw: String): JsonObject = json.parseToJsonElement(raw) as JsonObject

    /** The live order: assistant entry (toolCall) first, toolResult entry after. */
    @Test
    fun livePathLinksResultToCall() {
        val assistant = entry(
            """{"type":"message","id":"assistant-1","message":{"role":"assistant","content":[
                {"type":"toolCall","id":"call_1","name":"bash","arguments":{"command":"echo hi"}}
            ]}}""",
        )
        val toolResult = entry(
            """{"type":"message","id":"result-1","message":{"role":"toolResult","toolCallId":"call_1","toolName":"bash",
                "content":[{"type":"text","text":"hi from bash"}],"isError":false}}""",
        )

        // Simulate the two entry_appended events, each re-linking.
        val items1 = linkToolResults(listOfNotNull(parseEntry(assistant)))
        val items2 = linkToolResults(items1 + listOfNotNull(parseEntry(toolResult)))

        val assistantItem = items2.filterIsInstance<ChatItem.Assistant>().single()
        assertEquals("call_1", assistantItem.toolCalls.single().id)
        val result = assistantItem.toolCalls.single().result
        assertNotNull("result must be linked on the live path", result)
        assertEquals("hi from bash", result?.text)
        // The claimed result is no longer orphaned.
        assertEquals(0, items2.filterIsInstance<ChatItem.OrphanToolResult>().size)
    }

    /** Same sequence in the opposite order must also link. */
    @Test
    fun resultFirstThenCallLinks() {
        val assistant = entry(
            """{"type":"message","id":"assistant-1","message":{"role":"assistant","content":[
                {"type":"toolCall","id":"call_1","name":"bash","arguments":{"command":"echo hi"}}
            ]}}""",
        )
        val toolResult = entry(
            """{"type":"message","id":"result-1","message":{"role":"toolResult","toolCallId":"call_1","toolName":"bash",
                "content":[{"type":"text","text":"hi from bash"}],"isError":false}}""",
        )
        val items1 = linkToolResults(listOfNotNull(parseEntry(toolResult)))
        val items2 = linkToolResults(items1 + listOfNotNull(parseEntry(assistant)))
        val assistantItem = items2.filterIsInstance<ChatItem.Assistant>().single()
        assertNotNull("result must link when it arrived first", assistantItem.toolCalls.single().result)
    }

    /**
     * Re-linking over a larger list (loadOlder merges a page, then links the
     * whole list again) must not wipe results that were already linked: their
     * orphan was consumed on the first pass, so the orphan map is empty for
     * them on the second.
     */
    @Test
    fun relinkingKeepsAlreadyLinkedResults() {
        val assistant = entry(
            """{"type":"message","id":"assistant-1","message":{"role":"assistant","content":[
                {"type":"toolCall","id":"call_1","name":"bash","arguments":{"command":"echo hi"}}
            ]}}""",
        )
        val toolResult = entry(
            """{"type":"message","id":"result-1","message":{"role":"toolResult","toolCallId":"call_1","toolName":"bash",
                "content":[{"type":"text","text":"hi from bash"}],"isError":false}}""",
        )
        // The merged older page has its own (different) pair, so the orphan map
        // is non-empty and re-linking actually runs.
        val olderCall = entry(
            """{"type":"message","id":"assistant-2","message":{"role":"assistant","content":[
                {"type":"toolCall","id":"call_2","name":"bash","arguments":{"command":"old"}}
            ]}}""",
        )
        val olderResult = entry(
            """{"type":"message","id":"result-2","message":{"role":"toolResult","toolCallId":"call_2","toolName":"bash",
                "content":[{"type":"text","text":"old out"}],"isError":false}}""",
        )

        // First pass links the pair and consumes the orphan.
        val linked = linkToolResults(listOfNotNull(parseEntry(assistant), parseEntry(toolResult)))
        assertEquals(0, linked.filterIsInstance<ChatItem.OrphanToolResult>().size)
        assertNotNull(linked.filterIsInstance<ChatItem.Assistant>().single().toolCalls.single().result)

        // A later merge re-links the whole list; the already-linked result must survive.
        val relinked = linkToolResults(linked + listOfNotNull(parseEntry(olderCall), parseEntry(olderResult)))
        val call1 = relinked.filterIsInstance<ChatItem.Assistant>().first { it.entryId == "assistant-1" }
        assertNotNull(
            "re-linking must keep an already-linked result",
            call1.toolCalls.single().result,
        )
        // The older pair still links.
        val call2 = relinked.filterIsInstance<ChatItem.Assistant>().first { it.entryId == "assistant-2" }
        assertEquals("old out", call2.toolCalls.single().result?.text)
    }
}
