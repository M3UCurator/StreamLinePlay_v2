package com.streamline.play.data.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

class HttpFetcher {

    suspend fun download(
        url: String,
        headers: Map<String, String>,
        target: File,
        timeoutMs: Int = 20_000,
        attempts: Int = 3,
    ): File = withContext(Dispatchers.IO) {
        target.parentFile?.mkdirs()
        var last: Throwable? = null
        repeat(attempts.coerceAtLeast(1)) { attempt ->
            runCatching {
                fetch(url, headers, timeoutMs).use { input ->
                    target.outputStream().use { output ->
                        input.copyTo(output, 32 * 1024)
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
        timeoutMs: Int = 20_000,
    ): String = String(getBytes(url, headers, timeoutMs), Charsets.UTF_8)

    suspend fun getBytes(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Int = 20_000,
        attempts: Int = 3,
    ): ByteArray = withContext(Dispatchers.IO) {
        var last: Throwable? = null
        repeat(attempts.coerceAtLeast(1)) { attempt ->
            runCatching {
                fetch(url, headers, timeoutMs).use { it.readBytes() }
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

    private fun isRetryable(error: Throwable): Boolean {
        val message = error.message.orEmpty()
        val httpCode = Regex("HTTP (\\d{3})").find(message)?.groupValues?.get(1)?.toIntOrNull()
        return httpCode == null || httpCode == 408 || httpCode == 425 || httpCode == 429 || httpCode in 500..599
    }

    private fun fetch(
        url: String,
        headers: Map<String, String>,
        timeoutMs: Int,
    ): java.io.InputStream {
        var current = URL(url)
        repeat(6) {
            val connection = (current.openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = false
                requestMethod = "GET"
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Accept-Encoding", "gzip")
                headers.forEach { (name, value) -> setRequestProperty(name, value) }
            }
            val code = connection.responseCode
            if (code in 300..399) {
                val location = connection.getHeaderField("Location")
                    ?: throw IOException("HTTP $code without redirect location")
                runCatching { connection.inputStream.use { it.read() } }
                connection.disconnect()
                current = URL(current, location)
                return@repeat
            }
            if (code !in 200..299) {
                val message = runCatching {
                    connection.errorStream?.use { stream ->
                        stream.bufferedReader().readText().take(400)
                    }
                }.getOrNull()
                connection.disconnect()
                throw IOException("HTTP $code${if (message.isNullOrBlank()) "" else ": $message"}")
            }
            val raw = connection.inputStream
            val encoding = connection.contentEncoding
            val stream = if (encoding?.contains("gzip", ignoreCase = true) == true) {
                GZIPInputStream(raw)
            } else {
                raw
            }
            return stream
        }
        throw IOException("Too many redirects")
    }
}
