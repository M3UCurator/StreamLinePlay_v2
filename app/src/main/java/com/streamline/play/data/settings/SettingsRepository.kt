package com.streamline.play.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.streamline.play.data.model.BitrateLimit
import com.streamline.play.data.model.BufferPreset
import com.streamline.play.data.model.ChannelSortMode
import com.streamline.play.data.model.DecoderMode
import com.streamline.play.data.model.EpgRefreshMode
import com.streamline.play.data.model.ExternalPlayerApp
import com.streamline.play.data.model.NetworkProfile
import com.streamline.play.data.model.PlaybackEngineType
import com.streamline.play.data.model.ResolutionCap
import com.streamline.play.data.model.UserAgentPreset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(
    name = "streamline_settings",
)

class SettingsRepository(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private object Keys {
        val engine = stringPreferencesKey("engine")
        val externalPlayer = stringPreferencesKey("external_player")
        val decoder = stringPreferencesKey("decoder_mode")
        val passthrough = booleanPreferencesKey("audio_passthrough")
        val resolution = stringPreferencesKey("resolution_cap")
        val bitrateKbps = intPreferencesKey("bitrate_limit_kbps")
        val legacyBitrate = stringPreferencesKey("bitrate_cap")
        val buffer = stringPreferencesKey("buffer_preset")
        val networkProfile = stringPreferencesKey("network_profile")
        val userAgentPreset = stringPreferencesKey("user_agent_preset")
        val customUserAgent = stringPreferencesKey("custom_user_agent")
        val referer = stringPreferencesKey("referer")
        val origin = stringPreferencesKey("origin")
        val authorization = stringPreferencesKey("authorization")
        val streamProxy = booleanPreferencesKey("stream_proxy")
        val epgRefreshMode = stringPreferencesKey("epg_refresh_mode")
        val globalEpgUrls = stringPreferencesKey("global_epg_urls")
        val clearCacheOnExit = booleanPreferencesKey("clear_cache_on_exit")
        val epgLastRefresh = longPreferencesKey("epg_last_refresh")
        val preferredAspect = stringPreferencesKey("preferred_aspect")
        val channelSort = stringPreferencesKey("channel_sort")
        val parentalEnabled = booleanPreferencesKey("parental_enabled")
        val parentalPin = stringPreferencesKey("parental_pin")
        val parentalAuto = booleanPreferencesKey("parental_auto_adult")
        val parentalGroups = stringSetPreferencesKey("parental_groups")
        val confirmExit = booleanPreferencesKey("confirm_exit")
    }

    private val cached = MutableStateFlow(AppSettings())

    val settings: Flow<AppSettings> = context.settingsStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { prefs -> prefs.toSettings() }
        .onEach { cached.value = it }

    val current: StateFlow<AppSettings> = settings.stateIn(
        scope = scope,
        started = kotlinx.coroutines.flow.SharingStarted.Eagerly,
        initialValue = AppSettings(),
    )

    private fun Preferences.toSettings(): AppSettings = AppSettings(
        engine = enumOr(Keys.engine, PlaybackEngineType.MEDIA3),
        externalPlayer = enumOr(Keys.externalPlayer, ExternalPlayerApp.CHOOSER),
        decoderMode = enumOr(Keys.decoder, DecoderMode.AUTO),
        audioPassthrough = this[Keys.passthrough] ?: false,
        resolutionCap = enumOr(Keys.resolution, ResolutionCap.AUTO),
        bitrateLimitKbps = this[Keys.bitrateKbps]
            ?: legacyBitrateKbps(this[Keys.legacyBitrate]),
        bufferPreset = enumOr(Keys.buffer, BufferPreset.SMALL),
        networkProfile = enumOr(Keys.networkProfile, NetworkProfile.LOW_RAM),
        userAgentPreset = enumOr(Keys.userAgentPreset, UserAgentPreset.TIVIMATE),
        customUserAgent = this[Keys.customUserAgent].orEmpty(),
        referer = this[Keys.referer].orEmpty(),
        origin = this[Keys.origin].orEmpty(),
        authorization = this[Keys.authorization].orEmpty(),
        streamProxyEnabled = this[Keys.streamProxy] ?: true,
        epgRefreshMode = enumOr(Keys.epgRefreshMode, EpgRefreshMode.AUTO_24H),
        globalEpgUrls = this[Keys.globalEpgUrls]
            ?.lineSequence()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toList()
            .orEmpty(),
        clearCacheOnExit = this[Keys.clearCacheOnExit] ?: false,
        epgLastRefreshMs = this[Keys.epgLastRefresh] ?: 0L,
        preferredAspect = enumOr(
            Keys.preferredAspect,
            com.streamline.play.data.model.AspectMode.FIT,
        ),
        channelSort = enumOr(Keys.channelSort, ChannelSortMode.PLAYLIST),
        parentalLockEnabled = this[Keys.parentalEnabled] ?: false,
        parentalPinHash = this[Keys.parentalPin].orEmpty(),
        parentalAutoLockAdult = this[Keys.parentalAuto] ?: true,
        parentalLockedGroups = this[Keys.parentalGroups].orEmpty(),
        confirmExit = this[Keys.confirmExit] ?: true,
    )

    private fun legacyBitrateKbps(stored: String?): Int = when (stored) {
        "KBPS_1500" -> 1_500
        "KBPS_3000" -> 3_000
        "KBPS_6000" -> 6_000
        "KBPS_8000" -> 8_000
        "KBPS_25000" -> BitrateLimit.MAX_KBPS
        else -> BitrateLimit.AUTO
    }

    private inline fun <reified T : Enum<T>> Preferences.enumOr(
        key: Preferences.Key<String>,
        fallback: T,
    ): T = this[key]?.let { stored ->
        enumValues<T>().firstOrNull { it.name == stored }
    } ?: fallback

    suspend fun save(settings: AppSettings) {
        context.settingsStore.edit { prefs ->
            prefs[Keys.engine] = settings.engine.name
            prefs[Keys.externalPlayer] = settings.externalPlayer.name
            prefs[Keys.decoder] = settings.decoderMode.name
            prefs[Keys.passthrough] = settings.audioPassthrough
            prefs[Keys.resolution] = settings.resolutionCap.name
            prefs[Keys.bitrateKbps] = settings.bitrateLimitKbps
            prefs[Keys.buffer] = settings.bufferPreset.name
            prefs[Keys.networkProfile] = settings.networkProfile.name
            prefs[Keys.userAgentPreset] = settings.userAgentPreset.name
            prefs[Keys.customUserAgent] = settings.customUserAgent
            prefs[Keys.referer] = settings.referer
            prefs[Keys.origin] = settings.origin
            prefs[Keys.authorization] = settings.authorization
            prefs[Keys.streamProxy] = settings.streamProxyEnabled
            prefs[Keys.epgRefreshMode] = settings.epgRefreshMode.name
            prefs[Keys.globalEpgUrls] = settings.globalEpgUrls.joinToString("\n")
            prefs[Keys.clearCacheOnExit] = settings.clearCacheOnExit
            prefs[Keys.epgLastRefresh] = settings.epgLastRefreshMs
            prefs[Keys.preferredAspect] = settings.preferredAspect.name
            prefs[Keys.channelSort] = settings.channelSort.name
            prefs[Keys.parentalEnabled] = settings.parentalLockEnabled
            prefs[Keys.parentalPin] = settings.parentalPinHash
            prefs[Keys.parentalAuto] = settings.parentalAutoLockAdult
            prefs[Keys.parentalGroups] = settings.parentalLockedGroups
            prefs[Keys.confirmExit] = settings.confirmExit
        }
        cached.value = settings
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(cached.value)
        cached.value = next
        scope.launch { save(next) }
    }
}
