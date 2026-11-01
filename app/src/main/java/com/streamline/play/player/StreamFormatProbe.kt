package com.streamline.play.player

import androidx.media3.common.MimeTypes
import com.streamline.play.data.net.HttpClients
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

/**
 * Resolves links whose format is invisible in the URL (shorteners, redirect-only endpoints) so
 * ExoPlayer builds an HLS/DASH source instead of assuming progressive media.
 *
 * Each probe is a ranged GET, because short-link targets do not reliably answer HEAD.
 */
object StreamFormatProbe {

    data class Result(val mimeType: String?, val reachable: Boolean)

    private const val PROBE_TIMEOUT_MS = 8_000
    private const val SNIFF_BYTES = 2047

    private val hlsTypes = setOf(
        "application/vnd.apple.mpegurl",
        "application/x-mpegurl",
        "audio/mpegurl",
        "audio/x-mpegurl",
    )

    private val blockedHeaders = setOf(
        "host",
        "connection",
        "content-length",
        "transfer-encoding",
        "accept-encoding",
        "range",
    )

    private val client by lazy { HttpClients.redirecting(PROBE_TIMEOUT_MS, PROBE_TIMEOUT_MS) }

    fun probe(url: String, headers: Map<String, String>): Result {
        val parsed = url.toHttpUrlOrNull() ?: return Result(null, false)
        val builder = Request.Builder()
            .url(parsed)
            .get()
            .header("Accept", "*/*")
            .header("Range", "bytes=0-$SNIFF_BYTES")
        headers.forEach { (name, value) ->
            if (name.isNotBlank() && name.lowercase() !in blockedHeaders) {
                runCatching { builder.header(name, value) }
            }
        }
        return runCatching {
            client.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) return@use Result(null, false)
                Result(formatOf(response.header("Content-Type"), response.request.url), reachable = true)
            }
        }.getOrDefault(Result(null, false))
    }

    private fun formatOf(contentType: String?, url: HttpUrl): String? {
        val type = contentType?.substringBefore(';')?.trim()?.lowercase()
        return when {
            type != null && type in hlsTypes -> MimeTypes.APPLICATION_M3U8
            type == "application/dash+xml" -> MimeTypes.APPLICATION_MPD
            url.encodedPath.endsWith(".m3u8", true) -> MimeTypes.APPLICATION_M3U8
            url.encodedPath.endsWith(".mpd", true) -> MimeTypes.APPLICATION_MPD
            else -> null
        }
    }
}
