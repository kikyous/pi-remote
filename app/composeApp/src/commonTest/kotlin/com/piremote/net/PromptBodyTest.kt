package com.piremote.net

import kotlinx.serialization.json.Json
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test

class PromptBodyTest {

    // Mirrors PiRemoteClient.buildJsonBody exactly.
    private fun buildJsonBody(vararg pairs: Pair<String, String?>): String =
        pairs.filter { it.second != null }
            .joinToString(",", prefix = "{", postfix = "}") { (k, v) ->
                "${quote(k)}:${quote(v!!)}"
            }

    // Mirrors PiRemoteClient.quote exactly.
    private fun quote(value: String): String = buildString {
        append('"')
        for (ch in value) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch < ' ') append("\\u" + ch.code.toString(16).padStart(4, '0')) else append(ch)
            }
        }
        append('"')
    }

    // Mirrors PiRemoteClient.buildImagesJson exactly (escape = quote without the outer quotes).
    private fun buildImagesJson(images: List<PromptImage>): String =
        images.joinToString(",", prefix = "[", postfix = "]") { img ->
            "{\"type\":\"image\",\"data\":\"${escape(img.data)}\",\"mimeType\":\"${escape(img.mimeType)}\"}"
        }

    private fun escape(value: String): String = quote(value).drop(1).dropLast(1)

    @Test
    fun promptBodyWithImagesIsValidJson() {
        val images = listOf(PromptImage(data = "QUJD+/=", mimeType = "image/png"))
        val imageJson = buildImagesJson(images)
        val base = buildJsonBody("message" to "看图", "streamingBehavior" to null)
        val body = base.dropLast(1) + ",\"images\":$imageJson}"
        println("BODY: $body")
        // Must parse as JSON — the server rejects malformed bodies.
        val parsed = Json.parseToJsonElement(body)
        val obj = parsed as kotlinx.serialization.json.JsonObject
        assertEquals("看图", obj["message"]?.toString()?.trim('"'))
        val imagesArr = obj["images"]!!
        assertTrue(imagesArr.toString().startsWith("["))
    }
}
