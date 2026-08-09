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
}
