package com.piremote.net

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        // The WebSocket shares this client and must not be reaped mid-run.
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    fun okHttp(): OkHttpClient = http

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
        request(Request.Builder().url(url(path)).get())

    private suspend inline fun <reified T> post(path: String, body: String, readTimeoutSeconds: Long = 0): T =
        request(Request.Builder().url(url(path)).post(body.toRequestBody(JSON_MEDIA)), readTimeoutSeconds)

    private suspend inline fun <reified T> patch(path: String, body: String): T =
        request(Request.Builder().url(url(path)).patch(body.toRequestBody(JSON_MEDIA)))

    private suspend inline fun <reified T> delete(path: String): T =
        request(Request.Builder().url(url(path)).delete())

    private suspend inline fun <reified T> request(builder: Request.Builder, readTimeoutSeconds: Long = 0): T {
        val text = execute(builder.header("Authorization", "Bearer $token").build(), readTimeoutSeconds)
        return withContext(Dispatchers.Default) { json.decodeFromString(text) }
    }

    private fun url(path: String) = "${baseUrl.trimEnd('/')}/api/v1/$path"

    /**
     * Runs the call off the main thread and converts error bodies to [ApiException].
     *
     * @param readTimeoutSeconds Overrides the shared 30s read timeout for this one
     *   call. `newBuilder()` keeps the connection pool and dispatcher, so the
     *   derived client costs nothing beyond the object itself.
     */
    private suspend fun execute(request: Request, readTimeoutSeconds: Long = 0): String = suspendCancellableCoroutine { cont ->
        val client =
            if (readTimeoutSeconds > 0) http.newBuilder().readTimeout(readTimeoutSeconds, TimeUnit.SECONDS).build()
            else http
        val call = client.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val body = it.body?.string().orEmpty()
                    if (!cont.isActive) return
                    if (it.isSuccessful) {
                        cont.resume(body)
                    } else {
                        cont.resumeWithException(toApiException(it.code, body))
                    }
                }
            }
        })
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
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        /** Matches the server's own compaction deadline, with room for the round trip. */
        const val COMPACT_READ_TIMEOUT_S = 310L
    }
}

/** The server takes `cwd` as base64url so paths survive the query string intact. */
fun encodeCwd(cwd: String): String =
    Base64.encodeToString(cwd.toByteArray(), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

private fun String.urlEncoded(): String =
    java.net.URLEncoder.encode(this, "UTF-8")
