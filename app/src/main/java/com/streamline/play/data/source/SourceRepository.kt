package com.streamline.play.data.source

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.streamline.play.data.model.SourceConfig
import com.streamline.play.data.model.SourceKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

private val Context.sourcesStore: DataStore<Preferences> by preferencesDataStore(
    name = "streamline_sources",
)

class SourceRepository(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val key = stringPreferencesKey("sources_json")

    private val flow = context.sourcesStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { prefs -> decode(prefs[key]) }

    val sources: StateFlow<List<SourceConfig>> =
        flow.stateIn(scope, SharingStarted.Eagerly, emptyList())

    init {
        scope.launch {
            context.sourcesStore.edit { prefs ->
                val stored = prefs[key]
                if (stored != null && decode(stored).isEmpty() && stored.contains("DEMO")) {
                    prefs[key] = encode(emptyList())
                }
            }
        }
    }

    suspend fun add(source: SourceConfig) {
        context.sourcesStore.edit { prefs ->
            val current = decode(prefs[key]).filterNot { it.id == source.id }
            prefs[key] = encode(current + source)
        }
    }

    suspend fun remove(id: String) {
        context.sourcesStore.edit { prefs ->
            prefs[key] = encode(decode(prefs[key]).filterNot { it.id == id })
        }
    }

    suspend fun update(source: SourceConfig) {
        context.sourcesStore.edit { prefs ->
            val current = decode(prefs[key]).map { if (it.id == source.id) source else it }
            prefs[key] = encode(current)
        }
    }

    private fun encode(sources: List<SourceConfig>): String {
        val array = JSONArray()
        sources.forEach { source ->
            array.put(
                JSONObject()
                    .put("id", source.id)
                    .put("kind", source.kind.name)
                    .put("name", source.name)
                    .put("url", source.url)
                    .put("username", source.username)
                    .put("password", source.password)
                    .put("mac", source.mac)
                    .put("epgUrl", source.epgUrl),
            )
        }
        return array.toString()
    }

    private fun decode(raw: String?): List<SourceConfig> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            val out = mutableListOf<SourceConfig>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val kind = SourceKind.entries.firstOrNull { it.name == obj.optString("kind") }
                    ?: continue
                val id = obj.optString("id")
                if (id.isBlank() || id.equals("demo", true)) continue
                out += SourceConfig(
                    id = id,
                    kind = kind,
                    name = obj.optString("name").ifBlank { "Source" },
                    url = obj.optString("url"),
                    username = obj.optString("username"),
                    password = obj.optString("password"),
                    mac = obj.optString("mac"),
                    epgUrl = obj.optString("epgUrl"),
                )
            }
            out
        }.getOrDefault(emptyList())
    }
}
