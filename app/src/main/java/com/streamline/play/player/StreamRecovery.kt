package com.streamline.play.player

import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.streamline.play.data.model.MediaItem
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Retries every load up to three times with exponential backoff before surfacing the error. */
class StreamRetryPolicy(
    private val maxRetries: Int = 3,
    private val baseDelayMs: Long = 400L,
) : DefaultLoadErrorHandlingPolicy(maxRetries) {

    override fun getMinimumLoadableRetryCount(dataType: Int): Int = maxRetries

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        if (loadErrorInfo.errorCount > maxRetries) return C.TIME_UNSET
        if (!isTransient(loadErrorInfo.exception)) return C.TIME_UNSET
        return (baseDelayMs shl (loadErrorInfo.errorCount - 1).coerceIn(0, 4))
            .coerceAtMost(5_000L)
    }

    private fun isTransient(error: IOException): Boolean {
        val code = (error as? HttpDataSource.InvalidResponseCodeException)?.responseCode
        if (code != null) return isTransientCode(code)
        return error is SocketTimeoutException ||
            error is UnknownHostException ||
            error is java.io.InterruptedIOException
    }

    companion object {
        fun isTransientCode(code: Int): Boolean =
            code == 408 || code == 425 || code == 429 || code == 500 || code == 502 ||
                code == 503 || code == 504
    }
}

data class StreamFailure(
    val fatal: Boolean,
    val code: Int?,
    val message: String,
)

fun PlaybackException.streamFailure(): StreamFailure {
    val http = generateSequence<Throwable>(this) { it.cause }
        .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
        .firstOrNull()
    val code = http?.responseCode
    val message = when {
        code == 401 -> "Authorization failed (401). Check the account credentials for this source."
        code == 403 -> "Stream blocked by the provider (403 Forbidden)."
        code == 404 -> "Stream not found (404). The provider may have removed this channel."
        code == 429 -> "Too many connections (429). Close another player or try again shortly."
        code == 500 || code == 502 || code == 503 || code == 504 ->
            "Provider server error ($code). The stream is temporarily unavailable."
        code != null -> "Stream request failed (HTTP $code)."
        errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ->
            "Network connection lost. Check the internet link and try again."
        errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
            errorCode == PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE ->
            "Network timeout while loading the stream."
        errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
            errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
            errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
            errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED ->
            "This stream format is not supported by the current decoder."
        else -> message ?: "Playback failed (${errorCodeName})."
    }
    val fatal = code != null ||
        errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
        errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
    return StreamFailure(fatal = fatal, code = code, message = message)
}

/**
 * Ordered failover list: provider URL, explicit backups, the opposite adaptive
 * packaging (HLS ↔ DASH), then the other HTTP scheme so a CDN that only serves
 * one signature or protocol can still be reached.
 */
fun streamCandidates(item: MediaItem): List<String> = buildList {
    fun addCandidate(url: String?) {
        val clean = url?.trim().orEmpty()
        if (clean.startsWith("http://", true) || clean.startsWith("https://", true)) {
            if (none { it.equals(clean, ignoreCase = true) }) add(clean)
        }
    }
    addCandidate(item.url)
    item.fallbackUrls.forEach(::addCandidate)
    addCandidate(swapProtocol(item.url))
    item.fallbackUrls.forEach { addCandidate(swapProtocol(it)) }
    addCandidate(swapScheme(item.url))
    item.fallbackUrls.forEach { addCandidate(swapScheme(it)) }
}

private fun swapProtocol(url: String): String? {
    val lower = url.lowercase()
    return when {
        lower.contains(".m3u8") -> url.replaceFirst(Regex("(?i)\\.m3u8"), ".mpd")
        lower.contains(".mpd") -> url.replaceFirst(Regex("(?i)\\.mpd"), ".m3u8")
        else -> null
    }
}

private fun swapScheme(url: String): String? = when {
    url.startsWith("https://", ignoreCase = true) -> "http://" + url.substring(8)
    url.startsWith("http://", ignoreCase = true) -> "https://" + url.substring(7)
    else -> null
}
