package com.streamline.play.player.proxy

import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** HMAC-SHA256 signer used to authenticate URLs the proxy rewrote itself. */
class ProxySigner(secret: ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }) {

    private val key = SecretKeySpec(secret, "HmacSHA256")

    fun sign(target: String): String = hex(hmac(target))

    fun verify(target: String, signature: String): Boolean {
        val actual = unhex(signature) ?: return false
        return MessageDigest.isEqual(hmac(target), actual)
    }

    private fun hmac(value: String): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(key) }
            .doFinal(value.toByteArray(Charsets.UTF_8))

    private companion object {
        private val DIGITS = "0123456789abcdef".toCharArray()

        fun hex(bytes: ByteArray): String {
            val out = CharArray(bytes.size * 2)
            bytes.forEachIndexed { index, byte ->
                val value = byte.toInt() and 0xFF
                out[index * 2] = DIGITS[value ushr 4]
                out[index * 2 + 1] = DIGITS[value and 0x0F]
            }
            return String(out)
        }

        fun unhex(text: String): ByteArray? {
            if (text.length % 2 != 0) return null
            val out = ByteArray(text.length / 2)
            for (index in out.indices) {
                val high = Character.digit(text[index * 2], 16)
                val low = Character.digit(text[index * 2 + 1], 16)
                if (high < 0 || low < 0) return null
                out[index] = ((high shl 4) or low).toByte()
            }
            return out
        }
    }
}

/** Query-string codec for the proxied upstream URL. */
object ProxyUrl {
    fun encode(target: String): String = URLEncoder.encode(target, "UTF-8")

    fun decode(value: String?): String? =
        value?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }
}

/** Validates upstream targets so the loopback proxy cannot be turned into an open relay. */
object ProxyGuard {

    private const val MAX_TARGET_LENGTH = 16 * 1024

    fun parseTarget(raw: String?, allowLoopback: Boolean = false): URI? {
        if (raw.isNullOrBlank() || raw.length > MAX_TARGET_LENGTH) return null
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host ?: return null
        if (host.isBlank()) return null
        if (!allowLoopback && isLoopback(host)) return null
        return uri
    }

    fun hostOf(raw: String?): String? =
        raw?.let { runCatching { URI(it).host }.getOrNull() }
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }

    fun isLoopback(host: String): Boolean {
        val value = host.lowercase().removePrefix("[").removeSuffix("]")
        return value == "localhost" ||
            value == "::1" ||
            value == "::" ||
            value == "0.0.0.0" ||
            value.startsWith("127.")
    }
}

/** Rewrites every URI reference in an HLS playlist so children load through the proxy. */
object ManifestRewriter {

    private val uriAttribute = Regex("URI=\"([^\"]*)\"")

    fun rewrite(playlist: String, baseUrl: String, transform: (String) -> String): String {
        val hadBom = playlist.startsWith('\uFEFF')
        val body = if (hadBom) playlist.substring(1) else playlist
        val hadTrailingNewline = body.endsWith("\n")
        val rewritten = body.lineSequence().joinToString("\n") { line ->
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> ""
                trimmed.startsWith("#") -> uriAttribute.replace(line) { match ->
                    "URI=\"${proxied(baseUrl, match.groupValues[1], transform)}\""
                }
                else -> proxied(baseUrl, trimmed, transform)
            }
        }
        return buildString(rewritten.length + 8) {
            if (hadBom) append('\uFEFF')
            append(rewritten)
            if (hadTrailingNewline && !rewritten.endsWith("\n")) append('\n')
        }
    }

    fun resolve(baseUrl: String, reference: String): String {
        val cleaned = reference.trim().replace(" ", "%20")
        if (cleaned.isEmpty()) return baseUrl
        return runCatching { URL(URL(baseUrl), cleaned).toString() }
            .recoverCatching { URI(baseUrl).resolve(cleaned).toString() }
            .getOrDefault(cleaned)
    }

    private fun proxied(baseUrl: String, reference: String, transform: (String) -> String): String {
        val resolved = resolve(baseUrl, reference)
        return if (isHttpUrl(resolved)) transform(resolved) else resolved
    }

    private fun isHttpUrl(value: String): Boolean {
        val scheme = runCatching { URI(value).scheme }.getOrNull()?.lowercase() ?: return false
        return scheme == "http" || scheme == "https"
    }
}
