package com.piremote.net

import com.piremote.platform.createPlatformHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** Talks to pi-remote-server over HTTP. The WebSocket lives in [EventSocket]. */
class PiRemoteClient(
    @Volatile var baseUrl: String,
    @Volatile var token: String,
) {
    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    private val http: HttpClient = createPlatformHttpClient()

    /** The shared client; the WebSocket rides on it too. */
    fun httpClient(): HttpClient = http

    /* ---------------- endpoints ---------------- */

    suspend fun ping(): PingDto = get("ping")

    suspend fun listProjects(): List<ProjectDto> = get("projects")

    suspend fun listSessions(cwd: String): List<SessionSummaryDto> =
        get("sessions?cwd=${encodeCwd(cwd)}")

    /**
     * One page of older history. Pass the previous page's `oldest` to walk back.
     *
     * There is no endpoint for the newest page, and none for the session's settings:
     * both arrive in the WebSocket's `hello`, so opening a session costs one frame
     * rather than two round trips.
     */
    suspend fun items(id: String, before: String? = null, limit: Int = 50): ItemPageDto {
        val cursor = if (before != null) "&before=${before.urlEncoded()}" else ""
        return get("sessions/$id/items?limit=$limit$cursor")
    }

    /** The original behind a `more` handle. The handle is opaque; we just echo it. */
    suspend fun full(id: String, ref: String): FullContentDto =
        get("sessions/$id/full?ref=${ref.urlEncoded()}")

    suspend fun models(): ModelsResponseDto = get("models")

    /**
     * What this session has spent: messages, tokens, dollars.
     *
     * Read off the session file, so asking costs no agent — which is why it is a
     * plain GET rather than something the push stream has to carry.
     */
    suspend fun stats(id: String): SessionStatsDto = get("sessions/$id/stats")

    /* ---------------- git (read-only) ---------------- */

    suspend fun gitStatus(cwd: String): GitStatusDto =
        get("git/status?cwd=${encodeCwd(cwd)}")

    suspend fun gitDiff(cwd: String, path: String): GitDiffDto =
        get("git/diff?cwd=${encodeCwd(cwd)}&file=${encodeCwd(path)}")

    suspend fun gitCommits(cwd: String, limit: Int = 20, before: String? = null): GitCommitsPageDto {
        val cursor = if (before != null) "&before=$before" else ""
        return get("git/commits?cwd=${encodeCwd(cwd)}&limit=$limit$cursor")
    }

    suspend fun gitCommitDiff(cwd: String, sha: String): GitCommitDiffDto =
        get("git/commit?cwd=${encodeCwd(cwd)}&sha=$sha")

    suspend fun createSession(
        cwd: String,
        provider: String? = null,
        modelId: String? = null,
        thinkingLevel: String? = null,
    ): NewSessionDto = post(
        "sessions",
        buildJsonBody(
            "cwd" to cwd,
            "provider" to provider,
            "modelId" to modelId,
            "thinkingLevel" to thinkingLevel,
        ),
    )

    /** One-tap daily default workspace on the server: `~/pi-cwd-YYYYMMDD`. */
    suspend fun createWorkspace(): WorkspaceDto = post("workspaces", "{}")

    /** Have the model derive a title from the conversation; answers with the new detail. */
    suspend fun generateTitle(id: String): SessionDetailDto = post("sessions/$id/title", "{}")

    /**
     * Summarize this session's context into a compaction entry.
     *
     * The model reads the whole branch first, which on a long session takes well
     * past the shared 30s read timeout — so this one call gets its own, or the
     * phone would report a failure while the PC is still summarizing.
     */
    suspend fun compact(id: String): CompactResultDto =
        post("sessions/$id/compact", "{}", readTimeoutSeconds = COMPACT_READ_TIMEOUT_S)

    /** Delete a single session (fails with 409 if it has forked children). */
    suspend fun deleteSession(id: String): DeleteResultDto = delete("sessions/$id")

    /** Delete every session in a workspace directory (the dir itself stays). */
    suspend fun deleteWorkspace(cwd: String): DeleteResultDto =
        delete("workspaces?cwd=${encodeCwd(cwd)}")

    /**
     * @param streamingBehavior `"steer"` to interrupt a running turn, `"followUp"`
     *   to queue after it. Omitting it while the session runs yields
     *   [ApiException.isBusy], which is the signal to ask the user which they want.
     */
    suspend fun prompt(
        id: String,
        message: String,
        streamingBehavior: String? = null,
        images: List<PromptImage>? = null,
    ): PromptResultDto {
        // buildJsonBody quotes every value, so the images array (already JSON)
        // must be spliced in raw — otherwise it arrives as a quoted string and
        // the server rejects it with "images must be an array".
        val imageJson = images?.takeIf { it.isNotEmpty() }?.let(::buildImagesJson)
        val base = buildJsonBody(
            "message" to message,
            "streamingBehavior" to streamingBehavior,
        )
        val body = if (imageJson != null) base.dropLast(1) + ",\"images\":$imageJson}" else base
        return post("sessions/$id/prompt", body)
    }

    suspend fun abort(id: String): AbortResultDto = post("sessions/$id/abort", "{}")

    /** Answers with the new detail, so no follow-up read is needed. */
    suspend fun updateSession(
        id: String,
        provider: String? = null,
        modelId: String? = null,
        thinkingLevel: String? = null,
        name: String? = null,
    ): SessionDetailDto = patch(
        "sessions/$id",
        buildJsonBody(
            "provider" to provider,
            "modelId" to modelId,
            "thinkingLevel" to thinkingLevel,
            "name" to name,
        ),
    )

    /* ---------------- plumbing ---------------- */

    fun wsUrl(): String {
        val base = baseUrl.trimEnd('/')
        val scheme = if (base.startsWith("https")) "wss" else "ws"
        val hostPart = base.substringAfter("://")
        return "$scheme://$hostPart/ws?token=${token.urlEncoded()}"
    }

    private suspend inline fun <reified T> get(path: String): T =
        request { this.method = HttpMethod.Get; url(url(path)) }

    private suspend inline fun <reified T> post(path: String, body: String, readTimeoutSeconds: Long = 0): T =
        request(readTimeoutSeconds) {
            this.method = HttpMethod.Post
            url(url(path))
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend inline fun <reified T> patch(path: String, body: String): T =
        request {
            this.method = HttpMethod.Patch
            url(url(path))
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend inline fun <reified T> delete(path: String): T =
        request { this.method = HttpMethod.Delete; url(url(path)) }

    private suspend inline fun <reified T> request(
        readTimeoutSeconds: Long = 0,
        noinline configure: HttpRequestBuilder.() -> Unit,
    ): T {
        val text = execute(readTimeoutSeconds, configure)
        return withContext(Dispatchers.Default) { json.decodeFromString(text) }
    }

    private fun url(path: String) = "${baseUrl.trimEnd('/')}/api/v1/$path"

    /**
     * Performs the request, converting error bodies to [ApiException].
     *
     * @param readTimeoutSeconds Overrides the shared 30s read timeout for this
     *   one call via `withTimeout` (the compaction call outlasts the default).
     */
    private suspend fun execute(
        readTimeoutSeconds: Long = 0,
        configure: HttpRequestBuilder.() -> Unit,
    ): String {
        val builder = HttpRequestBuilder().apply {
            header("Authorization", "Bearer $token")
            configure()
        }
        val call: suspend () -> String = {
            val response = http.request(builder)
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) throw toApiException(response.status.value, body)
            body
        }
        return if (readTimeoutSeconds > 0) withTimeout(readTimeoutSeconds * 1000) { call() } else call()
    }

    private fun toApiException(status: Int, body: String): ApiException {
        val parsed = runCatching { json.decodeFromString<ErrorDto>(body) }.getOrNull()
        return ApiException(status, parsed?.code, parsed?.error ?: "HTTP $status")
    }

    private fun buildJsonBody(vararg pairs: Pair<String, String?>): String =
        pairs.filter { it.second != null }
            .joinToString(",", prefix = "{", postfix = "}") { (k, v) ->
                "${quote(k)}:${quote(v!!)}"
            }

    /** `[{type:"image",data,mimeType},…]` — values escaped, quotes from the literal. */
    private fun buildImagesJson(images: List<PromptImage>): String =
        images.joinToString(",", prefix = "[", postfix = "]") { img ->
            "{\"type\":\"image\",\"data\":\"${escape(img.data)}\",\"mimeType\":\"${escape(img.mimeType)}\"}"
        }

    /** Escape JSON string content without the surrounding quotes. */
    private fun escape(value: String): String = quote(value).drop(1).dropLast(1)

    private fun quote(value: String): String = buildString {
        append('"')
        for (ch in value) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch < ' ') append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
        append('"')
    }

    private companion object {
        /** Matches the server's own compaction deadline, with room for the round trip. */
        const val COMPACT_READ_TIMEOUT_S = 310L
    }
}

/**
 * The server takes `cwd` as base64url so paths survive the query string intact.
 */
@OptIn(ExperimentalEncodingApi::class)
fun encodeCwd(cwd: String): String =
    Base64.UrlSafe
        .withPadding(Base64.PaddingOption.ABSENT)
        .encode(cwd.encodeToByteArray())

/** Percent-encode a value for use in a query string (UTF-8). */
private fun String.urlEncoded(): String = buildString {
    for (b in this@urlEncoded.encodeToByteArray()) {
        val v = b.toInt() and 0xFF
        val c = v.toChar()
        if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '-' || c == '_' || c == '.' || c == '~') {
            append(c)
        } else {
            append('%')
            append("0123456789ABCDEF"[v shr 4])
            append("0123456789ABCDEF"[v and 0xF])
        }
    }
}
