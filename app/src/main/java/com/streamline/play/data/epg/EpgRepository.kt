package com.streamline.play.data.epg

import android.content.Context
import com.streamline.play.data.model.EpgProgram
import com.streamline.play.data.model.MediaItem
import com.streamline.play.data.net.HttpFetcher
import com.streamline.play.data.settings.SettingsRepository
import com.streamline.play.data.model.EpgRefreshMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

class EpgRepository(
    private val context: Context,
    private val http: HttpFetcher,
    private val settingsRepository: SettingsRepository,
    private val parser: XmltvParser = XmltvParser(),
) {

    private val _programs = MutableStateFlow<Map<String, List<EpgProgram>>>(emptyMap())
    val programs: StateFlow<Map<String, List<EpgProgram>>> = _programs.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _lastRefreshMs = MutableStateFlow(0L)
    val lastRefreshMs: StateFlow<Long> = _lastRefreshMs.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    suspend fun refresh(
        channels: List<MediaItem>,
        epgUrls: List<String>,
        force: Boolean,
    ) = withContext(Dispatchers.IO) {
        if (_refreshing.value) return@withContext
        _refreshing.value = true
        try {
            val settings = settingsRepository.current.value
            val now = System.currentTimeMillis()
            val urls = epgUrls.map { it.trim() }
                .filter { it.startsWith("http://", true) || it.startsWith("https://", true) }
                .distinct()
            val cache = EpgCache.read(context)
            val dayMs = 24L * 60L * 60L * 1000L
            val stale = settings.epgLastRefreshMs == 0L || now - settings.epgLastRefreshMs >= dayMs
            val autoMode = settings.epgRefreshMode == EpgRefreshMode.AUTO_24H
            val guidesChanged = cache == null || cache.urls.toSet() != urls.toSet()
            val needFetch = force || (
                urls.isNotEmpty() &&
                    (guidesChanged || cache.programs.isEmpty() || (autoMode && stale))
                )

            var externalPrograms: List<EpgProgram> = cache?.programs.orEmpty()
            var channelNames: Map<String, String> = cache?.channelNames.orEmpty()
            var fetchedAt = cache?.fetchedAtMs ?: 0L
            var error: String? = null

            if (needFetch) {
                val collected = mutableListOf<EpgProgram>()
                val names = mutableMapOf<String, String>()
                val errors = mutableListOf<String>()
                var anySuccess = false
                urls.forEach { url ->
                    runCatching {
                        val bytes = fetchGuideBytes(url, settings)
                        parseGuide(bytes).also { parsed ->
                            collected += parsed.programs
                            names.putAll(parsed.channelNames)
                        }
                    }.onSuccess {
                        anySuccess = true
                    }.onFailure { throwable ->
                        errors += "$url: ${throwable.message ?: "failed"}"
                    }
                }
                if (anySuccess) {
                    val unique = collected
                        .distinctBy { "${it.channelId}|${it.startMs}|${it.title}" }
                        .sortedBy { it.startMs }
                    externalPrograms = unique
                    channelNames = names
                    fetchedAt = now
                    _lastRefreshMs.value = now
                    EpgCache.write(
                        context,
                        CachedEpg(unique, names, now, urls),
                    )
                    settingsRepository.save(settings.copy(epgLastRefreshMs = now))
                    if (errors.isNotEmpty()) {
                        error = "Some EPG sources failed: ${errors.first()}"
                    }
                } else {
                    if (cache != null) {
                        externalPrograms = cache.programs
                        channelNames = cache.channelNames
                        fetchedAt = cache.fetchedAtMs
                    }
                    if (urls.isNotEmpty()) {
                        error = errors.firstOrNull() ?: "No EPG data was returned"
                    }
                }
            } else {
                _lastRefreshMs.value = maxOf(fetchedAt, settings.epgLastRefreshMs)
            }

            val cutoffStart = now - 36L * 60L * 60L * 1000L
            val cutoffEnd = now + 7L * 24L * 60L * 60L * 1000L
            val keysByChannel = HashMap<String, List<String>>()
            val index = mutableMapOf<String, MutableList<EpgProgram>>()

            externalPrograms
                .filter { it.endMs >= cutoffStart && it.startMs <= cutoffEnd }
                .forEach { program ->
                    val keys = keysByChannel.getOrPut(program.channelId) {
                        EpgMatcher.variantKeys(program.channelId, channelNames[program.channelId])
                    }
                    keys.forEach { key ->
                        index.getOrPut(key) { mutableListOf() }.add(program)
                    }
                }

            val finalIndex = index.mapValues { (_, list) ->
                list.distinctBy { "${it.startMs}|${it.title}" }.sortedBy { it.startMs }
            }
            _programs.value = finalIndex
            if (fetchedAt > 0L) _lastRefreshMs.value = maxOf(_lastRefreshMs.value, fetchedAt)
            _message.value = error
        } finally {
            _refreshing.value = false
        }
    }

    /**
     * Fetches a guide and retries once with a neutral player signature when the
     * provider rejects the configured headers (401/403/404 or 5xx).
     */
    private suspend fun fetchGuideBytes(url: String, settings: com.streamline.play.data.settings.AppSettings): ByteArray {
        val primary = settings.requestHeaders
        return runCatching {
            http.getBytes(url, primary, timeoutMs = 30_000)
        }.getOrElse { first ->
            val neutral = buildMap {
                put("User-Agent", NEUTRAL_UA)
                put("Accept", "application/xml,text/xml,*/*")
                settings.authorization.trim().takeIf { it.isNotEmpty() }?.let { put("Authorization", it) }
            }
            runCatching { http.getBytes(url, neutral, timeoutMs = 30_000) }
                .getOrElse { throw first }
        }
    }

    /**
     * Handles plain .xml and gzip-packed .xml.gz payloads. Native gzip is tried
     * first; pako (QuickJS) is the fallback for streams Android's inflater rejects.
     */
    private fun parseGuide(bytes: ByteArray): XmltvParser.Result {
        if (!isGzip(bytes)) {
            return ByteArrayInputStream(bytes).use { parser.parse(it) }
        }
        val native = runCatching {
            GZIPInputStream(ByteArrayInputStream(bytes)).use { parser.parse(it) }
        }
        native.getOrNull()?.let { return it }
        val inflated = PakoGunzip.ungzip(context, bytes)
        if (inflated == null) {
            throw native.exceptionOrNull() ?: IllegalStateException("Unable to inflate EPG guide")
        }
        return ByteArrayInputStream(inflated).use { parser.parse(it) }
    }

    private fun isGzip(bytes: ByteArray): Boolean =
        bytes.size >= 2 &&
            bytes[0] == 0x1f.toByte() &&
            bytes[1] == 0x8b.toByte()

    fun programsFor(item: MediaItem): List<EpgProgram> =
        EpgMatcher.scheduleFor(_programs.value, item)

    fun hasEpg(item: MediaItem): Boolean = programsFor(item).isNotEmpty()

    fun current(item: MediaItem, now: Long = System.currentTimeMillis()): EpgProgram? {
        val programs = programsFor(item)
        return programs.firstOrNull { it.isLiveAt(now) }
            ?: programs.firstOrNull { it.startMs <= now && it.endMs > now }
    }

    fun next(item: MediaItem, now: Long = System.currentTimeMillis()): EpgProgram? =
        programsFor(item).firstOrNull { it.startMs > now }

    companion object {
        private const val NEUTRAL_UA =
            "Mozilla/5.0 (Linux; Android 12; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
    }
}
