package app.servercontrol.net

import app.servercontrol.data.AppJson
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

class ApiException(val status: Int, message: String) : Exception(message)

/** Thin REST client for the ServerControl agent. */
class ApiClient(
    private val http: HttpClient,
    val baseUrl: String,
) {
    var token: String? = null
    /** called when the session is gone and could not be renewed – the UI returns to the login screen */
    var onUnauthorized: (() -> Unit)? = null
    /**
     * Renews the session (remembered device). Gets the token that failed, returns a new token or null.
     * Like a refresh token in other apps: the user does not notice an expired session.
     */
    var refresher: (suspend (String?) -> String?)? = null

    private suspend fun send(
        method: HttpMethod,
        path: String,
        body: String?,
        timeoutMs: Long,
        block: HttpRequestBuilder.() -> Unit,
    ): Pair<Int, String> {
        val resp: HttpResponse = http.request(baseUrl.trimEnd('/') + path) {
            this.method = method
            timeout { requestTimeoutMillis = timeoutMs }
            token?.let { header("Authorization", "Bearer $it") }
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            block()
        }
        return resp.status.value to resp.bodyAsText()
    }

    suspend fun raw(
        method: HttpMethod,
        path: String,
        body: String? = null,
        timeoutMs: Long = 60_000,
        block: HttpRequestBuilder.() -> Unit = {},
    ): String {
        var (status, text) = send(method, path, body, timeoutMs, block)
        val renewable = status == 401 && token != null && path != "/api/refresh" && path != "/api/login"
        if (renewable) {
            val failed = token
            val fresh = refresher?.invoke(failed)
            if (fresh != null) {
                token = fresh
                val again = send(method, path, body, timeoutMs, block)
                status = again.first
                text = again.second
            }
            if (status == 401) onUnauthorized?.invoke()
        }
        if (status !in 200..299) {
            throw ApiException(status, extractError(text) ?: "HTTP $status")
        }
        return text
    }

    private fun extractError(text: String): String? = runCatching {
        val o = AppJson.parseToJsonElement(text) as JsonObject
        o["error"]?.jsonPrimitive?.contentOrNull
            ?: listOfNotNull(
                o["stderr"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                o["stdout"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
            ).joinToString("\n").takeIf { it.isNotBlank() }
    }.getOrNull()

    suspend inline fun <reified T> get(path: String, timeoutMs: Long = 60_000): T =
        AppJson.decodeFromString(raw(HttpMethod.Get, path, timeoutMs = timeoutMs))

    suspend fun getJson(path: String): JsonElement = AppJson.parseToJsonElement(raw(HttpMethod.Get, path))

    suspend inline fun <reified T> post(path: String, body: String? = null, timeoutMs: Long = 120_000): T =
        AppJson.decodeFromString(raw(HttpMethod.Post, path, body ?: "{}", timeoutMs))

    suspend fun postRaw(path: String, body: String? = null): String = raw(HttpMethod.Post, path, body ?: "{}", 120_000)
    suspend fun putRaw(path: String, body: String): String = raw(HttpMethod.Put, path, body)
    suspend fun deleteRaw(path: String): String = raw(HttpMethod.Delete, path)

    /** Uploads raw bytes (e.g. a new agent binary). */
    suspend fun postBytes(path: String, data: ByteArray, timeoutMs: Long = 300_000): String =
        raw(HttpMethod.Post, path, timeoutMs = timeoutMs) {
            contentType(ContentType.Application.OctetStream)
            setBody(data)
        }

    /** Streams a (large) download to [sink]; [onProgress] gets (bytes so far, total or -1). */
    suspend fun download(
        path: String,
        sink: (ByteArray, Int) -> Unit,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ) {
        http.prepareGet(baseUrl.trimEnd('/') + path) {
            timeout { requestTimeoutMillis = 30 * 60_000L; socketTimeoutMillis = 120_000L }
            token?.let { header("Authorization", "Bearer $it") }
        }.execute { resp ->
            if (!resp.status.isSuccess()) {
                throw ApiException(resp.status.value, extractError(resp.bodyAsText()) ?: "HTTP ${resp.status.value}")
            }
            val total = resp.headers["Content-Length"]?.toLongOrNull() ?: -1L
            val ch = resp.bodyAsChannel()
            val buf = ByteArray(64 * 1024)
            var done = 0L
            while (true) {
                val n = ch.readAvailable(buf, 0, buf.size)
                if (n < 0) break
                if (n > 0) {
                    sink(buf, n)
                    done += n
                    onProgress(done, total)
                }
            }
        }
    }

    /** Renews the session via the remembered device; true when a new token is in place. */
    suspend fun renew(): Boolean {
        val fresh = refresher?.invoke(token) ?: return false
        token = fresh
        return true
    }

    suspend fun ping(timeoutMs: Long): Boolean = runCatching {
        raw(HttpMethod.Get, "/api/ping", timeoutMs = timeoutMs).contains("servercontrol-agent")
    }.getOrDefault(false)

    companion object {
        fun configure(builder: io.ktor.client.HttpClientConfig<*>) {
            builder.install(HttpTimeout) {
                connectTimeoutMillis = 5_000
                requestTimeoutMillis = 60_000
            }
            builder.expectSuccess = false
        }
    }
}
