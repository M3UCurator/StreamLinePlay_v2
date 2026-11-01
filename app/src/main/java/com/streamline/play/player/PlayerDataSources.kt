package com.streamline.play.player

import androidx.media3.datasource.DefaultHttpDataSource
import com.streamline.play.data.settings.AppSettings

/**
 * Header-complete HTTP data source used for live and proxied playback paths.
 *
 * The read timeout is disabled on purpose: providers pause between segments and a
 * short socket timeout would abort healthy live streams with
 * `HttpDataSourceException` / `ERROR_CODE_IO_UNSPECIFIED`. Hang detection is handled
 * by the player's stall watchdog instead.
 */
object PlayerDataSources {

    const val CONNECT_TIMEOUT_MS = 20_000
    const val READ_TIMEOUT_MS = 0

    fun httpFactory(
        userAgent: String,
        customHeaders: Map<String, String>,
    ): DefaultHttpDataSource.Factory = DefaultHttpDataSource.Factory()
        .setUserAgent(resolveUserAgent(userAgent, customHeaders))
        .setConnectTimeoutMs(CONNECT_TIMEOUT_MS)
        .setReadTimeoutMs(READ_TIMEOUT_MS)
        .setAllowCrossProtocolRedirects(true)
        .setDefaultRequestProperties(headersFor(customHeaders))

    /** Request headers forwarded on every playlist and segment request. */
    fun headersFor(customHeaders: Map<String, String>): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        headers["Accept"] = "*/*"
        headers["Connection"] = "keep-alive"
        customHeaders.forEach { (name, value) ->
            if (value.isNotBlank() && !isImplicitHeader(name)) headers[name] = value
        }
        return headers
    }

    fun resolveUserAgent(userAgent: String, customHeaders: Map<String, String>): String =
        customHeaders.entries
            .firstOrNull { it.key.equals("User-Agent", true) }
            ?.value
            ?.takeIf { it.isNotBlank() }
            ?: userAgent.takeIf { it.isNotBlank() }
            ?: AppSettings.DEFAULT_UA

    private fun isImplicitHeader(name: String): Boolean =
        name.equals("User-Agent", true) ||
            name.equals("Accept", true) ||
            name.equals("Connection", true)
}
