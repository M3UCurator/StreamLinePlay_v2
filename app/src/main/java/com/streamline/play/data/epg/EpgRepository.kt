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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
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

    /**
     * Two full guide parses at once can exhaust a 1 GB TV box, so refreshes never overlap.
     * Waiting callers re-run against the state the finished pass left behind, which also
     * means a reload that arrives mid-refresh is never dropped.
     */
    private val refreshMutex = Mutex()

    suspend fun refresh(
        channels: List<MediaItem>,
        epgUrls: List<String>,
        force: Boolean,
    ) = withContext(Dispatchers.IO) {
        refreshMutex.withLock { refreshGuides(channels, epgUrls, force) }
    }

    private suspend fun refreshGuides(
        channels: List<MediaItem>,
        epgUrls: List<String>,
        force: Boolean,
    ) {
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
            // Index once per guide channel and share that list across its alias keys. Indexing
            // per alias copied every program 5-10x and doubled the live heap on large guides.
            val byChannel = HashMap<String, MutableList<EpgProgram>>()
            externalPrograms
                .filter { it.endMs >= cutoffStart && it.startMs <= cutoffEnd }
                .forEach { program ->
                    byChannel.getOrPut(program.channelId) { mutableListOf() }.add(program)
                }

            // Channels from two sources can normalise to the same alias key; merge their
            // programmes instead of letting an arbitrary channel win the key.
            val index = HashMap<String, List<EpgProgram>>(byChannel.size * 8)
            byChannel.forEach { (channelId, collected) ->
                val programs = collected
                    .distinctBy { "${it.startMs}|${it.title}" }
                    .sortedBy { it.startMs }
                val keys = EpgMatcher.variantKeys(channelId, channelNames[channelId])
                keys.forEach { key ->
                    val existing = index[key]
                    index[key] = if (existing == null) {
                        programs
                    } else {
                        (existing + programs)
                            .distinctBy { "${it.startMs}|${it.title}" }
                            .sortedBy { it.startMs }
                    }
                }
            }
            _programs.value = index
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
        val limit = HttpFetcher.MAX_PAYLOAD_BYTES.toLong()
        val native = runCatching {
            GZIPInputStream(ByteArrayInputStream(bytes)).use { parser.parse(CappedStream(it, limit)) }
        }
        native.getOrNull()?.let { return it }
        val inflated = PakoGunzip.ungzip(context, bytes)
        if (inflated == null) {
            throw native.exceptionOrNull() ?: IllegalStateException("Unable to inflate EPG guide")
        }
        return ByteArrayInputStream(inflated).use { parser.parse(it) }
    }

    /** Stops a compressed guide from inflating past the same ceiling as a plain download. */
    private class CappedStream(input: InputStream, private val limit: Long) : FilterInputStream(input) {
        private var total = 0L

        override fun read(): Int {
            val value = super.read()
            if (value >= 0) advance(1)
            return value
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val count = super.read(b, off, len)
            if (count > 0) advance(count)
            return count
        }

        private fun advance(count: Int) {
            total += count
            if (total > limit) {
                throw IOException("Inflated guide exceeds ${limit / (1024 * 1024)} MB")
            }
        }
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
