package com.streamline.play.data.epg

import com.streamline.play.data.model.EpgProgram
import com.streamline.play.data.model.MediaItem
import java.text.Normalizer
import java.util.Locale

/**
 * Resolves playlist channel names against XMLTV channel ids.
 *
 * Playlist metadata routinely carries quality markers, country flags, provider
 * prefixes and punctuation that never appear in guide data
 * ("SABC 1ᴴᴰ 🇿🇦 (720).s" vs id "SABC.1.HD.za"), so each name is expanded into
 * progressively cleaner variants and both sides are indexed under the same keys.
 */
object EpgMatcher {

    private const val SORTED_PREFIX = "\u0001"
    private const val COMPACT_PREFIX = "\u0002"
    private const val ID_PREFIX = "\u0003"
    private const val NAME_PREFIX = "\u0004"

    private val COUNTRY_CODES = setOf(
        "za", "uk", "us", "ca", "au", "ie", "nz", "in", "ph", "sa", "ng", "ke",
        "gh", "ae", "eg", "fr", "de", "es", "it", "pt", "nl", "be", "br", "mx",
        "ar", "tr", "ru", "pl", "ro", "gr", "se", "no", "dk", "fi", "ch", "at",
        "jp", "kr", "cn", "tw", "hk", "sg", "my", "id", "th",
    )

    private val PROVIDERS = setOf(
        "dstv", "showmax", "openview", "vodacom", "telkom", "starsat", "zuku",
    )

    private val QUALITY = Regex(
        "(?i)(?<![\\p{L}\\p{N}])(?:UHD|FHD|QHD|HD|SD|4K|8K|HEVC|H\\.?264|H\\.?265|AVC|RAW|HDR|" +
            "1080[PI]?|720[PI]?|576[PI]?|480[PI]?)(?![\\p{L}\\p{N}])",
    )
    private val DIGIT_QUALITY = Regex("(\\d)\\s*(uhd|fhd|hd|sd|4k|8k)\\b")
    private val LETTER_QUALITY = Regex("([\\p{L}])(uhd|fhd|hd|sd|4k|8k)\\b")
    private val TRAILING_LETTER = Regex("^(.+?)\\s+([a-z])$")
    private val LEADING_CHANNEL_NUMBER = Regex("^0*\\d{2,4}\\s+(?=\\p{L})(.+)$")
    private val NON_WORD = Regex("[^\\p{L}\\p{N}']+")
    private val MARKS = Regex("\\p{M}+")

    /**
     * Index keys for a guide channel:
     * 1. strict id key (playlist tvg-id == programme channel),
     * 2. display-name keys (playlist tvg-name/name against channelMetadata.name),
     * 3. normalized id/name variants for providers that rewrite both sides.
     */
    fun variantKeys(channelId: String?, displayName: String?): List<String> {
        val keys = LinkedHashSet<String>()
        channelId?.trim()?.takeIf { it.isNotEmpty() }?.let { keys += ID_PREFIX + it }
        displayName?.takeIf { it.isNotBlank() }?.let { name ->
            variants(name).forEach { keys += NAME_PREFIX + it }
        }
        channelId?.let { keys += variants(it) }
        displayName?.let { keys += variants(it) }
        return keys.toList()
    }

    /** Lookup keys for a playlist channel, evaluated in matching-strategy order. */
    fun channelKeys(item: MediaItem): List<String> = buildList {
        item.tvgId?.trim()?.takeIf { it.isNotEmpty() }?.let { add(ID_PREFIX + it) }
        listOfNotNull(item.tvgName, item.name)
            .filter { it.isNotBlank() }
            .forEach { alias -> variants(alias).forEach { add(NAME_PREFIX + it) } }
        item.tvgId?.takeIf { it.isNotBlank() }?.let { addAll(variants(it)) }
        item.tvgName?.takeIf { it.isNotBlank() }?.let { addAll(variants(it)) }
        addAll(variants(item.name))
    }.distinct()

    fun scheduleFor(index: Map<String, List<EpgProgram>>, item: MediaItem): List<EpgProgram> {
        for (key in channelKeys(item)) {
            index[key]?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return emptyList()
    }

    /** Expanded, normalized keys for a single raw name or id. */
    fun variants(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        val keys = LinkedHashSet<String>()
        addVariants(raw, keys)
        raw.split('|').forEach { part ->
            if (part.trim() != raw.trim()) addVariants(part, keys)
        }
        return keys.toList()
    }

    private fun addVariants(raw: String, out: MutableSet<String>) {
        var text = base(raw)
        if (text.isBlank()) return
        var guard = 0
        while (guard++ < 8) {
            emit(text, out)
            val next = nextStep(text) ?: break
            if (next == text) break
            text = next
        }
        if (text.isNotBlank()) emit(text, out)
    }

    private fun base(raw: String): String {
        val decomposed = Normalizer.normalize(raw, Normalizer.Form.NFKD)
            .replace(MARKS, "")
            .replace('’', '\'')
            .replace('‘', '\'')
            .replace('&', ' ')
            .lowercase(Locale.US)
        var text = decomposed.replace(NON_WORD, " ")
        text = text.replace(DIGIT_QUALITY, "$1 $2")
        // Superscript markers decompose into letters glued to the name ("Newsᴴᴰ").
        text = text.replace(LETTER_QUALITY, "$1 $2")
        return collapse(text)
    }

    private fun nextStep(text: String): String? {
        TRAILING_LETTER.find(text)?.let { match ->
            val rest = collapse(match.groupValues[1])
            if (rest.isNotBlank()) return rest
        }
        LEADING_CHANNEL_NUMBER.find(text)?.let { match ->
            val rest = collapse(match.groupValues[1])
            if (rest.isNotBlank()) return rest
        }
        val noQuality = collapse(text.replace(QUALITY, " "))
        if (noQuality.isNotBlank() && noQuality != text) return noQuality
        dropTrailing(text, COUNTRY_CODES)?.let { return it }
        dropTrailing(text, PROVIDERS)?.let { return it }
        dropLeadingTag(text)?.let { return it }
        if (text.contains('\'')) return collapse(text.replace("'", ""))
        return null
    }

    private fun dropTrailing(text: String, set: Set<String>): String? {
        val tokens = text.split(' ')
        if (tokens.size < 2 || tokens.last() !in set) return null
        return collapse(tokens.dropLast(1).joinToString(" "))
    }

    /** Removes a leading "[ZA]"/"DSTV" style tag left after punctuation stripping. */
    private fun dropLeadingTag(text: String): String? {
        val tokens = text.split(' ')
        if (tokens.size < 3) return null
        if (tokens.first() !in COUNTRY_CODES && tokens.first() !in PROVIDERS) return null
        return collapse(tokens.drop(1).joinToString(" "))
    }

    private fun emit(text: String, out: MutableSet<String>) {
        if (text.isBlank()) return
        out += text
        val sorted = text.split(' ').filter { it.isNotBlank() }.sorted().joinToString(" ")
        if (sorted != text) out += SORTED_PREFIX + sorted
        val compact = text.replace(Regex("[^\\p{L}\\p{N}]"), "")
        if (compact.isNotBlank()) out += COMPACT_PREFIX + compact
    }

    private fun collapse(value: String): String =
        value.replace(Regex("\\s+"), " ").trim()
}
