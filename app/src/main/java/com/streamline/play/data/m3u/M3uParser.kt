package com.streamline.play.data.m3u

import com.streamline.play.data.model.ContentType
import com.streamline.play.data.model.EpgProgram
import com.streamline.play.data.model.MediaItem
import com.streamline.play.data.model.SourceConfig
import com.streamline.play.data.model.StreamUrl
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class M3uParser {

    data class ParsedPlaylist(
        val items: List<MediaItem>,
        val epgUrls: List<String>,
    )

    private data class Pending(
        var attrs: Map<String, String>,
        var displayName: String,
        var group: String? = null,
        var headers: MutableMap<String, String> = mutableMapOf(),
    )

    private val attrRegex = Regex("([A-Za-z0-9_.-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s,]+))")
    private val seriesRegex = Regex("\\bS(\\d{1,2})\\s*[._ -]?\\s*E(\\d{1,3})\\b", RegexOption.IGNORE_CASE)
    private val altSeriesRegex = Regex("\\b(\\d{1,2})x(\\d{1,3})\\b")
    private val yearRegex = Regex("\\((\\d{4})\\)|\\b(19\\d{2}|20\\d{2})\\b")
    private val epgHeaderRegex = Regex(
        "(?:url-tvg|x-tvg-url|tvg-url)\\s*=\\s*(?:\"([^\"]+)\"|'([^']+)'|([^\\s]+))",
        RegexOption.IGNORE_CASE,
    )
    private val durationRegex = Regex("^-?\\d+(?:\\.\\d+)?\\s*")

    fun parse(content: String, source: SourceConfig): ParsedPlaylist =
        parse(content.lineSequence(), source)

    fun parse(lines: Sequence<String>, source: SourceConfig): ParsedPlaylist {
        val items = mutableListOf<MediaItem>()
        val epgUrls = LinkedHashSet<String>()
        var pending: Pending? = null
        var index = 0

        lines.forEach { rawLine ->
            val line = rawLine.trim().removePrefix("\uFEFF")
            when {
                line.startsWith("#EXTM3U", ignoreCase = true) -> {
                    epgHeaderRegex.findAll(line).forEach { match ->
                        val value = match.groupValues.drop(1).firstOrNull { it.isNotBlank() }.orEmpty()
                        value
                            .split(',')
                            .map { it.trim() }
                            .filter { it.startsWith("http", ignoreCase = true) }
                            .forEach(epgUrls::add)
                    }
                }

                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    pending = parseExtInf(line)
                }

                line.startsWith("#EXTGRP:", ignoreCase = true) -> {
                    val group = line.substringAfter(':').trim()
                    if (group.isNotEmpty()) pending?.group = group
                }

                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    applyVlcOption(pending, line.substringAfter(':').trim())
                }

                line.startsWith("#KODIPROP:", ignoreCase = true) -> {
                    applyVlcOption(pending, line.substringAfter(':').trim())
                }

                line.isEmpty() || line.startsWith("#") -> Unit

                else -> {
                    val entry = pending
                    if (entry == null) {
                        pending = null
                        return@forEach
                    }
                    val target = StreamUrl.parse(line)
                    val url = target.url
                    if (!url.contains("://")) {
                        pending = null
                        return@forEach
                    }
                    val headers = entry.headers.toMutableMap().apply { putAll(target.headers) }
                    val name = entry.displayName.ifBlank {
                        entry.attrs["tvg-name"].orEmpty().ifBlank { "Unnamed" }
                    }
                    val group = entry.group?.takeIf { it.isNotBlank() }
                        ?: entry.attrs["group-title"].orEmpty().ifBlank { "General" }
                    val type = inferType(group, name, url)
                    val episode = parseSeries(name)
                    val chno = entry.attrs["tvg-chno"]?.toIntOrNull()
                        ?: entry.attrs["tvg-chno"]?.toDoubleOrNull()?.toInt()
                        ?: entry.attrs["channel-number"]?.toIntOrNull()
                    val logo = entry.attrs["tvg-logo"]
                        ?.takeIf { it.isNotBlank() && !it.equals("null", true) }
                    val description = listOf(
                        "tvg-description",
                        "description",
                        "plot",
                        "tvg-plot",
                    ).firstNotNullOfOrNull { key ->
                        entry.attrs[key]?.takeIf { it.isNotBlank() }
                    }
                    val year = parseYear(name)
                    val rating = listOf("tvg-rating", "rating")
                        .firstNotNullOfOrNull { key -> entry.attrs[key]?.toFloatOrNull() }
                    val fallbackUrls = listOf("fallback-url", "fallback", "cdn-url", "backup-url")
                        .firstNotNullOfOrNull { entry.attrs[it] }
                        ?.split('|', ';')
                        ?.map { it.trim() }
                        ?.filter { it.startsWith("http", true) }
                        ?.distinct()
                        .orEmpty()
                    val id = "${source.id}:${index}:${url.hashCode()}"
                    items += MediaItem(
                        id = id,
                        name = name,
                        url = url,
                        type = type,
                        sourceId = source.id,
                        sourceName = source.name,
                        group = group,
                        logo = logo,
                        channelNumber = chno,
                        tvgId = entry.attrs["tvg-id"]?.takeIf { it.isNotBlank() },
                        tvgName = entry.attrs["tvg-name"]?.takeIf { it.isNotBlank() },
                        description = description,
                        year = year,
                        rating = rating,
                        seriesName = episode?.first,
                        season = episode?.second,
                        episode = episode?.third,
                        catchup = entry.attrs["catchup"],
                        catchupDays = entry.attrs["catchup-days"]?.toIntOrNull()
                            ?: entry.attrs["tvg-rec"]?.toIntOrNull()
                            ?: 0,
                        catchupSource = entry.attrs["catchup-source"]
                            ?: entry.attrs["timeshift"],
                        headers = headers,
                        fallbackUrls = fallbackUrls,
                    )
                    index++
                    pending = null
                }
            }
        }
        return ParsedPlaylist(items, epgUrls.toList())
    }

    private fun parseExtInf(line: String): Pending {
        var payload = line.substringAfter('#', "").trim()
        if (payload.startsWith("EXTINF", ignoreCase = true)) {
            payload = payload.substring(6).trimStart(':', ' ', '\t')
        }
        durationRegex.find(payload)?.let { payload = payload.substring(it.value.length) }
        val commaIndex = lastCommaOutsideQuotes(payload)
        val attrPart = if (commaIndex >= 0) payload.take(commaIndex) else payload
        val displayName = if (commaIndex >= 0) payload.substring(commaIndex + 1).trim() else ""
        val attrs = attrRegex.findAll(attrPart).associate { match ->
            val key = match.groupValues[1].lowercase(Locale.US)
            val value = match.groupValues.drop(2).firstOrNull { it.isNotEmpty() }.orEmpty()
            key to value
        }
        return Pending(attrs = attrs, displayName = displayName)
    }

    private fun lastCommaOutsideQuotes(text: String): Int {
        var inQuotes = false
        var last = -1
        text.forEachIndexed { i, c ->
            when (c) {
                '"' -> inQuotes = !inQuotes
                ',' -> if (!inQuotes) last = i
            }
        }
        return last
    }

    private fun applyVlcOption(pending: Pending?, option: String) {
        if (pending == null) return
        val lower = option.lowercase(Locale.US)
        val value = option.substringAfter('=', "").trim().trim('"', '\'')
        if (value.isEmpty()) return
        when {
            lower.startsWith("http-user-agent") || lower.startsWith("http-useragent") ->
                pending.headers["User-Agent"] = value
            lower.startsWith("http-referrer") || lower.startsWith("http-referer") ->
                pending.headers["Referer"] = value
            lower.startsWith("http-origin") -> pending.headers["Origin"] = value
            lower.startsWith("http-cookie") -> pending.headers["Cookie"] = value
            lower.startsWith("http-header") -> {
                val pair = value.split(':', limit = 2)
                if (pair.size == 2 && pair[0].isNotBlank() && pair[1].isNotBlank()) {
                    pending.headers[StreamUrl.canonicalHeader(pair[0].trim())] = pair[1].trim()
                }
            }
        }
    }

    private fun inferType(group: String, name: String, url: String): ContentType {
        val lowerGroup = group.lowercase(Locale.US)
        if (lowerGroup.contains("live") || lowerGroup.contains("channel")) {
            return ContentType.LIVE
        }
        if (seriesRegex.containsMatchIn(name) || altSeriesRegex.containsMatchIn(name) ||
            lowerGroup.contains("serie") || lowerGroup.contains("tv show")
        ) {
            return ContentType.SERIES
        }
        val extension = url.substringBefore('?').substringAfterLast('/')
            .substringAfterLast('.', "").lowercase(Locale.US)
        val movieExtensions = setOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v", "m2ts")
        val movieGroup = lowerGroup.contains("movie") || lowerGroup.contains("vod") ||
            lowerGroup.contains("film") || lowerGroup.contains("cinema")
        return if (movieGroup || extension in movieExtensions) ContentType.MOVIE else ContentType.LIVE
    }

    private fun parseSeries(name: String): Triple<String, Int, Int>? {
        seriesRegex.find(name)?.let { match ->
            val season = match.groupValues[1].toIntOrNull() ?: 1
            val episode = match.groupValues[2].toIntOrNull() ?: 1
            val series = name.replace(match.value, "")
                .trim()
                .trimEnd('-', ':', '.', ' ')
                .trim()
                .ifBlank { name }
            return Triple(series, season, episode)
        }
        altSeriesRegex.find(name)?.let { match ->
            val season = match.groupValues[1].toIntOrNull() ?: 1
            val episode = match.groupValues[2].toIntOrNull() ?: 1
            val series = name.replace(match.value, "").trim().trimEnd('-', ':', '.', ' ').trim()
            return Triple(series.ifBlank { name }, season, episode)
        }
        return null
    }

    private fun parseYear(name: String): Int? =
        yearRegex.find(name)?.let { match ->
            (match.groupValues[1].ifEmpty { match.groupValues[2] }).toIntOrNull()
        }

    companion object {
        fun generate(items: List<MediaItem>, epgUrls: List<String> = emptyList()): String {
            val builder = StringBuilder("#EXTM3U")
            if (epgUrls.isNotEmpty()) {
                builder.append(" url-tvg=\"").append(epgUrls.joinToString(",")).append('"')
            }
            builder.append('\n')
            items.forEach { item ->
                builder.append("#EXTINF:-1")
                item.tvgId?.takeIf { it.isNotBlank() }?.let {
                    builder.append(" tvg-id=\"").append(it.escapeAttr()).append('"')
                }
                if (item.name.isNotBlank()) {
                    builder.append(" tvg-name=\"")
                        .append((item.tvgName ?: item.name).escapeAttr())
                        .append('"')
                }
                item.logo?.takeIf { it.isNotBlank() }?.let {
                    builder.append(" tvg-logo=\"").append(it.escapeAttr()).append('"')
                }
                builder.append(" group-title=\"").append(item.displayGroup.escapeAttr()).append('"')
                item.channelNumber?.let { builder.append(" tvg-chno=\"").append(it).append('"') }
                item.catchup?.takeIf { it.isNotBlank() }?.let {
                    builder.append(" catchup=\"").append(it.escapeAttr()).append('"')
                }
                if (item.catchupDays > 0) {
                    builder.append(" catchup-days=\"").append(item.catchupDays).append('"')
                }
                item.catchupSource?.takeIf { it.isNotBlank() }?.let {
                    builder.append(" catchup-source=\"").append(it.escapeAttr()).append('"')
                }
                if (item.fallbackUrls.isNotEmpty()) {
                    builder.append(" fallback-url=\"")
                        .append(item.fallbackUrls.joinToString("|").escapeAttr())
                        .append('"')
                }
                builder.append(',').append(item.name).append('\n')
                item.headers.forEach { (name, value) ->
                    val directive = when (name.lowercase(Locale.US)) {
                        "user-agent" -> "#EXTVLCOPT:http-user-agent=$value"
                        "referer" -> "#EXTVLCOPT:http-referrer=$value"
                        "origin" -> "#EXTVLCOPT:http-origin=$value"
                        "cookie" -> "#EXTVLCOPT:http-cookie=$value"
                        else -> "#EXTVLCOPT:http-header=$name: $value"
                    }
                    builder.append(directive).append('\n')
                }
                builder.append(item.url).append('\n')
            }
            return builder.toString()
        }

        private fun String.escapeAttr(): String = replace('"', '\'')

        fun archiveUrl(item: MediaItem, program: EpgProgram): String? {
            val template = item.catchupSource?.takeIf { it.isNotBlank() } ?: return null
            val utcFormat = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val startUtc = utcFormat.format(program.startMs)
            val endUtc = utcFormat.format(program.endMs)
            val startUnix = program.startMs / 1000
            val endUnix = program.endMs / 1000
            var result = template
                .replace("\${start}", startUtc)
                .replace("\${end}", endUtc)
                .replace("\${utcstart}", startUtc)
                .replace("\${utcend}", endUtc)
                .replace("\${start_unix}", startUnix.toString())
                .replace("\${end_unix}", endUnix.toString())
                .replace("{utc}", startUnix.toString())
                .replace("{lutc}", (System.currentTimeMillis() / 1000).toString())
            if (result == template && !template.contains("utc=")) {
                result += if (result.contains('?')) {
                    "&utc=$startUnix&lutc=${System.currentTimeMillis() / 1000}"
                } else {
                    "?utc=$startUnix&lutc=${System.currentTimeMillis() / 1000}"
                }
            }
            return result
        }
    }
}
