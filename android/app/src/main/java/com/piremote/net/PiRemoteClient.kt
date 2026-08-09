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

    suspend fun sessionDetail(id: String): SessionDetailDto = get("sessions/$id")

    /**
     * One page of history. Omit [before] for the newest page; pass the previous
     * page's `oldestId` to walk backwards.
     */
    suspend fun entries(id: String, before: String? = null, limit: Int = 50): EntryPageDto {
        val cursor = if (before != null) "&before=$before" else ""
        return get("sessions/$id/entries?limit=$limit$cursor")
    }

    suspend fun fullPart(id: String, entryId: String, part: String, index: Int?): FullPartDto {
        val idx = if (index != null) "&index=$index" else ""
        return get("sessions/$id/entries/$entryId/full?part=$part$idx")
    }

    suspend fun models(): ModelsResponseDto = get("models")

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

    suspend fun updateSession(
        id: String,
        provider: String? = null,
        modelId: String? = null,
        thinkingLevel: String? = null,
        name: String? = null,
    ): UpdateResultDto = patch(
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

    private suspend inline fun <reified T> post(path: String, body: String): T =
        request(Request.Builder().url(url(path)).post(body.toRequestBody(JSON_MEDIA)))

    private suspend inline fun <reified T> patch(path: String, body: String): T =
        request(Request.Builder().url(url(path)).patch(body.toRequestBody(JSON_MEDIA)))

    private suspend inline fun <reified T> delete(path: String): T =
        request(Request.Builder().url(url(path)).delete())

    private suspend inline fun <reified T> request(builder: Request.Builder): T {
        val text = execute(builder.header("Authorization", "Bearer $token").build())
        return withContext(Dispatchers.Default) { json.decodeFromString(text) }
    }

    private fun url(path: String) = "${baseUrl.trimEnd('/')}/api/v1/$path"

    /** Runs the call off the main thread and converts error bodies to [ApiException]. */
    private suspend fun execute(request: Request): String = suspendCancellableCoroutine { cont ->
        val call = http.newCall(request)
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
    }
}

/** The server takes `cwd` as base64url so paths survive the query string intact. */
fun encodeCwd(cwd: String): String =
    Base64.encodeToString(cwd.toByteArray(), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

private fun String.urlEncoded(): String =
    java.net.URLEncoder.encode(this, "UTF-8")
