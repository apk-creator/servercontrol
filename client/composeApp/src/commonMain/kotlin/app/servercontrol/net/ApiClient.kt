package app.servercontrol.net

import app.servercontrol.data.AppJson
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
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
    /** called on 401 so the UI can return to the login screen */
    var onUnauthorized: (() -> Unit)? = null

    suspend fun raw(
        method: HttpMethod,
        path: String,
        body: String? = null,
        timeoutMs: Long = 60_000,
        block: HttpRequestBuilder.() -> Unit = {},
    ): String {
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
        val text = resp.bodyAsText()
        if (resp.status.value == 401 && token != null) onUnauthorized?.invoke()
        if (!resp.status.isSuccess()) {
            throw ApiException(resp.status.value, extractError(text) ?: "HTTP ${resp.status.value}")
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
