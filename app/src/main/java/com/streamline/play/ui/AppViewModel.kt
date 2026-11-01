package com.streamline.play.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.streamline.play.data.model.ContentType
import com.streamline.play.data.model.EpgProgram
import com.streamline.play.data.model.MediaItem
import com.streamline.play.data.model.SourceConfig
import com.streamline.play.data.settings.AppSettings
import com.streamline.play.data.settings.Parental
import com.streamline.play.streamLineContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CatalogState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val items: List<MediaItem> = emptyList(),
    val epgUrls: List<String> = emptyList(),
    val errors: List<String> = emptyList(),
    val lastUpdatedMs: Long = 0L,
) {
    // Derived once per catalog update: these are read by the shell and every screen
    // recomposition, and walking 20k items each time dominated TV frame time.
    val liveChannels: List<MediaItem> = items.filter { it.type == ContentType.LIVE }
    val movies: List<MediaItem> = items.filter { it.type == ContentType.MOVIE }
    val episodes: List<MediaItem> = items.filter { it.type == ContentType.SERIES }
        .sortedWith(compareBy({ it.seriesName.orEmpty() }, { it.season ?: 0 }, { it.episode ?: 0 }))
    val groups: List<String> = liveChannels.map { it.displayGroup }.distinct()
}

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val container = application.streamLineContainer

    val settings: StateFlow<AppSettings> = container.settingsRepository.current
    val sources: StateFlow<List<SourceConfig>> = container.sourceRepository.sources
    val epgPrograms: StateFlow<Map<String, List<EpgProgram>>> = container.epgRepository.programs
    val epgRefreshing: StateFlow<Boolean> = container.epgRepository.refreshing
    val epgLastRefreshMs: StateFlow<Long> = container.epgRepository.lastRefreshMs
    val epgMessage: StateFlow<String?> = container.epgRepository.message

    private val _catalog = MutableStateFlow(CatalogState())

    private val _parentalUnlocked = MutableStateFlow(false)
    val parentalUnlocked: StateFlow<Boolean> = _parentalUnlocked.asStateFlow()

    /** Every group in the raw catalog, including ones hidden by the parental filter. */
    val allGroups: StateFlow<List<String>> = _catalog
        .map { state -> state.items.map { it.displayGroup }.distinct().sortedBy { it.lowercase() } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Filtered for the UI: locked groups disappear while the pin session is locked. */
    val catalog: StateFlow<CatalogState> =
        combine(_catalog, settings, _parentalUnlocked) { raw, prefs, unlocked ->
            raw.withParentalFilter(prefs, unlocked)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, _catalog.value)

    private val _cacheSize = MutableStateFlow(0L)
    val cacheSize: StateFlow<Long> = _cacheSize.asStateFlow()

    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            container.sourceRepository.sources
                .drop(1)
                .debounce(350)
                .collect { loadCatalog(it, forceEpg = false) }
        }
        viewModelScope.launch { loadCatalog(container.sourceRepository.sources.value, forceEpg = false) }
        viewModelScope.launch {
            settings.map { it.globalEpgUrls }
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    // loadCatalog runs its own refresh with the merged guide list.
                    if (_catalog.value.items.isEmpty()) return@collect
                    container.epgRepository.refresh(
                        channels = _catalog.value.items,
                        epgUrls = mergedEpgUrls(),
                        force = false,
                    )
                }
        }
        viewModelScope.launch(Dispatchers.IO) { container.cacheManager.pruneStale() }
        refreshCacheSize()
    }

    /** Playlist-declared EPG headers plus the app-wide guides, de-duplicated. */
    private fun mergedEpgUrls(sourceUrls: List<String> = _catalog.value.epgUrls): List<String> =
        (sourceUrls + settings.value.globalEpgUrls)
            .map { it.trim() }
            .filter { it.startsWith("http://", true) || it.startsWith("https://", true) }
            .distinct()

    private suspend fun loadCatalog(sources: List<SourceConfig>, forceEpg: Boolean) {
        _catalog.update { it.copy(loading = it.items.isEmpty(), refreshing = true, errors = emptyList()) }
        val result = container.playlistRepository.load(sources, settings.value)
        val guides = mergedEpgUrls(result.epgUrls)
        _catalog.value = CatalogState(
            loading = false,
            refreshing = false,
            items = result.items,
            epgUrls = guides,
            errors = result.sourceErrors,
            lastUpdatedMs = System.currentTimeMillis(),
        )
        container.epgRepository.refresh(result.items, guides, force = forceEpg)
    }

    fun reloadPlaylists() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch { loadCatalog(sources.value, forceEpg = false) }
    }

    fun refreshEpg() {
        viewModelScope.launch {
            container.epgRepository.refresh(
                channels = _catalog.value.items,
                epgUrls = mergedEpgUrls(),
                force = true,
            )
        }
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        container.settingsRepository.update(transform)
    }

    fun addSource(source: SourceConfig) {
        viewModelScope.launch { container.sourceRepository.add(source) }
    }

    fun removeSource(id: String) {
        viewModelScope.launch {
            container.sourceRepository.remove(id)
            withContext(Dispatchers.IO) { container.playlistStore.delete(id) }
        }
    }

    fun updateSource(source: SourceConfig) {
        viewModelScope.launch { container.sourceRepository.update(source) }
    }

    fun itemById(id: String): MediaItem? = _catalog.value.items.firstOrNull { it.id == id }

    fun verifyPin(pin: String): Boolean {
        val hash = settings.value.parentalPinHash
        return hash.isNotBlank() && Parental.hashPin(pin) == hash
    }

    fun unlockParental(pin: String): Boolean {
        if (!verifyPin(pin)) return false
        _parentalUnlocked.value = true
        return true
    }

    fun lockParental() {
        _parentalUnlocked.value = false
    }

    fun currentProgram(item: MediaItem, now: Long = System.currentTimeMillis()): EpgProgram? =
        container.epgRepository.current(item, now)

    fun nextProgram(item: MediaItem, now: Long = System.currentTimeMillis()): EpgProgram? =
        container.epgRepository.next(item, now)

    fun programsFor(item: MediaItem): List<EpgProgram> = container.epgRepository.programsFor(item)

    fun hasEpg(item: MediaItem): Boolean = container.epgRepository.hasEpg(item)

    fun featuredMovies(): List<MediaItem> =
        _catalog.value.movies.sortedByDescending { it.rating ?: 0f }.take(5)

    fun featuredSeries(): List<MediaItem> =
        _catalog.value.episodes.distinctBy { it.seriesName ?: it.name }.take(6)

    fun refreshCacheSize() {
        viewModelScope.launch(Dispatchers.IO) {
            _cacheSize.value = container.cacheManager.cacheSizeBytes()
        }
    }

    fun clearCacheNow() {
        viewModelScope.launch(Dispatchers.IO) {
            container.cacheManager.clearCache()
            _cacheSize.value = container.cacheManager.cacheSizeBytes()
            withContext(Dispatchers.Main) {
                container.epgRepository.refresh(
                    channels = _catalog.value.items,
                    epgUrls = mergedEpgUrls(),
                    force = false,
                )
            }
        }
    }
}

private fun CatalogState.withParentalFilter(prefs: AppSettings, unlocked: Boolean): CatalogState {
    if (!prefs.parentalLockEnabled || !prefs.hasParentalPin || unlocked) return this
    val visible = items.filterNot { Parental.isLocked(it, prefs) }
    return if (visible.size == items.size) this else copy(items = visible)
}
