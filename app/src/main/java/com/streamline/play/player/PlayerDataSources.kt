package com.streamline.play.player

import androidx.media3.datasource.DefaultHttpDataSource

/** Header-complete HTTP data source used for live and proxied playback paths. */
object PlayerDataSources {

    private const val TIMEOUT_MS = 20_000

    fun httpFactory(
        userAgent: String,
        customHeaders: Map<String, String>,
    ): DefaultHttpDataSource.Factory {
        val defaults = LinkedHashMap<String, String>()
        defaults["Accept"] = "*/*"
        defaults["Connection"] = "keep-alive"
        customHeaders.entries
            .firstOrNull { it.key.equals("Referer", true) }
            ?.value
            ?.takeIf { it.isNotBlank() }
            ?.let { defaults["Referer"] = it }
        customHeaders.forEach { (name, value) ->
            if (value.isNotBlank() && !isImplicitHeader(name)) defaults[name] = value
        }
        return DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setConnectTimeoutMs(TIMEOUT_MS)
            .setReadTimeoutMs(TIMEOUT_MS)
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(defaults)
    }

    private fun isImplicitHeader(name: String): Boolean =
        name.equals("User-Agent", true) ||
            name.equals("Accept", true) ||
            name.equals("Connection", true) ||
            name.equals("Referer", true)
}
