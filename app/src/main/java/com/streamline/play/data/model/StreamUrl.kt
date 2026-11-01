package com.streamline.play.data.model

import java.util.Locale

/**
 * IPTV panels append request headers to a stream URL after a pipe:
 * `http://host/live.m3u8|User-Agent=…&Referer=…&Authorization=…`.
 * The headers are pulled out here so the player receives a clean URI and can
 * forward the credentials on every playlist and segment request.
 */
object StreamUrl {

    data class Parsed(val url: String, val headers: Map<String, String>)

    fun parse(raw: String): Parsed {
        val pipe = raw.indexOf('|')
        if (pipe < 0) return Parsed(raw.trim(), emptyMap())
        val url = raw.take(pipe).trim()
        val headers = LinkedHashMap<String, String>()
        raw.substring(pipe + 1)
            .split('&', '|')
            .forEach { pair ->
                val equals = pair.indexOf('=')
                if (equals <= 0) return@forEach
                val name = pair.take(equals).trim()
                val value = pair.substring(equals + 1).trim().trim('"', '\'')
                if (name.isEmpty() || value.isEmpty()) return@forEach
                headers[canonicalHeader(name)] = value
            }
        return Parsed(url, headers)
    }

    fun canonicalHeader(name: String): String = when (name.trim().lowercase(Locale.US)) {
        "user-agent", "useragent", "ua" -> "User-Agent"
        "referer", "referrer", "referer-url" -> "Referer"
        "origin" -> "Origin"
        "cookie" -> "Cookie"
        "authorization", "auth" -> "Authorization"
        else -> name.trim()
            .split('-', '_')
            .filter { it.isNotBlank() }
            .joinToString("-") { part -> part.replaceFirstChar { it.uppercaseChar() } }
    }
}
