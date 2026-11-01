package com.streamline.play.data.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * GET helper for playlists, guides and portal APIs.
 *
 * Short links and cross-host redirects are handed to OkHttp, which resolves each Location
 * and renegotiates TLS when the chain upgrades from http to https.
 */
class HttpFetcher {

    private val clients = ConcurrentHashMap<Int, OkHttpClient>()

    suspend fun download(
        url: String,
        headers: Map<String, String>,
        target: File,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
        attempts: Int = 3,
    ): File = withContext(Dispatchers.IO) {
        target.parentFile?.mkdirs()
        var last: Throwable? = null
        repeat(attempts.coerceAtLeast(1)) { attempt ->
            runCatching {
                fetch(url, headers, timeoutMs).use { response ->
                    val body = response.body ?: throw IOException("HTTP ${response.code} without body")
                    target.outputStream().use { output ->
                        body.byteStream().use { input -> input.copyTo(output, COPY_BUFFER_BYTES) }
                    }
                }
            }.onSuccess {
                return@withContext target
            }.onFailure { error ->
                last = error
                if (attempt < attempts - 1) delay(500L shl attempt)
            }
        }
        throw last ?: IOException("Unable to download $url")
    }

    suspend fun getText(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    ): String = String(getBytes(url, headers, timeoutMs), Charsets.UTF_8)

    suspend fun getBytes(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
        attempts: Int = 3,
        maxBytes: Int = MAX_PAYLOAD_BYTES,
    ): ByteArray = withContext(Dispatchers.IO) {
        var last: Throwable? = null
        repeat(attempts.coerceAtLeast(1)) { attempt ->
            runCatching {
                fetch(url, headers, timeoutMs).use { response ->
                    val body = response.body ?: throw IOException("HTTP ${response.code} without body")
                    body.byteStream().use { readCapped(it, maxBytes) }
                }
            }.onSuccess {
                return@withContext it
            }.onFailure { error ->
                last = error
                if (!isRetryable(error) || attempt == attempts - 1) throw error
                delay(500L shl attempt)
            }
        }
        throw last ?: IOException("Unable to download $url")
    }

    /** Refuses to buffer an oversized guide instead of growing until the box runs out of heap. */
    private fun readCapped(input: java.io.InputStream, maxBytes: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
        val buffer = ByteArray(32 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) throw IOException("Payload exceeds ${maxBytes / (1024 * 1024)} MB limit")
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun isRetryable(error: Throwable): Boolean {
        val message = error.message.orEmpty()
        if (message.startsWith("Payload exceeds")) return false
        val httpCode = Regex("HTTP (\\d{3})").find(message)?.groupValues?.get(1)?.toIntOrNull()
        return httpCode == null || httpCode == 408 || httpCode == 425 || httpCode == 429 || httpCode in 500..599
    }

    private fun fetch(
        url: String,
        headers: Map<String, String>,
        timeoutMs: Int,
    ): Response {
        val parsed = url.toHttpUrlOrNull() ?: throw IOException("Invalid URL: $url")
        val builder = Request.Builder().url(parsed).get().header("Accept", "*/*")
        headers.forEach { (name, value) ->
            if (isForwardableHeader(name)) runCatching { builder.header(name, value) }
        }
        val client = clients.getOrPut(timeoutMs) { HttpClients.redirecting(timeoutMs, timeoutMs) }
        val response = client.newCall(builder.build()).execute()
        if (!response.isSuccessful) {
            val code = response.code
            val detail = runCatching { response.peekBody(ERROR_SNIPPET_BYTES).string() }.getOrNull()
            response.close()
            throw IOException("HTTP $code${if (detail.isNullOrBlank()) "" else ": $detail"}")
        }
        return response
    }

    /**
     * Leaves Accept-Encoding to OkHttp so gzip is negotiated and decoded transparently, and
     * rejects framing headers that callers must not override on a bodyless GET.
     */
    private fun isForwardableHeader(name: String): Boolean {
        if (name.isBlank() || name.any { it <= ' ' || it == ':' }) return false
        return name.lowercase() !in EXCLUDED_HEADERS
    }

    companion object {
        const val MAX_PAYLOAD_BYTES = 32 * 1024 * 1024

        private const val DEFAULT_TIMEOUT_MS = 20_000
        private const val COPY_BUFFER_BYTES = 32 * 1024
        private const val ERROR_SNIPPET_BYTES = 400L

        private val EXCLUDED_HEADERS = setOf(
            "host",
            "connection",
            "proxy-connection",
            "keep-alive",
            "transfer-encoding",
            "content-length",
            "upgrade",
            "te",
            "trailer",
            "accept-encoding",
        )
    }
}
