package com.streamline.play.data.playlist

import android.content.Context
import android.net.Uri
import com.streamline.play.data.m3u.M3uParser
import com.streamline.play.data.model.MediaItem
import com.streamline.play.data.model.PlaylistResult
import com.streamline.play.data.model.SourceConfig
import com.streamline.play.data.model.SourceKind
import com.streamline.play.data.net.HttpFetcher
import com.streamline.play.data.settings.AppSettings
import com.streamline.play.data.stalker.StalkerClient
import com.streamline.play.data.xtream.XtreamClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File

class PlaylistRepository(
    private val context: Context,
    private val m3uParser: M3uParser,
    private val http: HttpFetcher,
    private val xtreamClient: XtreamClient,
    private val stalkerClient: StalkerClient,
    private val store: PlaylistStore,
) {

    private data class Loaded(
        val items: List<MediaItem> = emptyList(),
        val epgUrls: List<String> = emptyList(),
    )

    private data class SourceResult(
        val items: List<MediaItem> = emptyList(),
        val epgUrls: List<String> = emptyList(),
        val error: String? = null,
    )

    suspend fun load(sources: List<SourceConfig>, settings: AppSettings): PlaylistResult =
        withContext(Dispatchers.IO) {
            val results = coroutineScope {
                sources.map { source -> async { loadSource(source, settings) } }.awaitAll()
            }
            val items = results.flatMap { it.items }
            val urls = results.flatMap { it.epgUrls }.distinct()
            val errors = results.mapNotNull { it.error }
            PlaylistResult(items = items, epgUrls = urls, sourceErrors = errors)
        }

    private suspend fun loadSource(source: SourceConfig, settings: AppSettings): SourceResult {
        val fresh = runCatching { fetch(source, settings) }
        return fresh.fold(
            onSuccess = { loaded ->
                SourceResult(items = loaded.items, epgUrls = loaded.epgUrls)
            },
            onFailure = { error ->
                val cached = store.parse(source.id, m3uParser, source)
                if (cached != null) {
                    SourceResult(
                        items = cached.items,
                        epgUrls = cached.epgUrls + source.epgUrl.takeIf { it.isNotBlank() }.orEmpty(),
                        error = "${source.name}: ${error.message ?: "connection failed"} · using saved playlist",
                    )
                } else {
                    SourceResult(error = "${source.name}: ${error.message ?: "failed to load"}")
                }
            },
        )
    }

    private suspend fun fetch(source: SourceConfig, settings: AppSettings): Loaded {
        val headers = settings.requestHeaders
        return when (source.kind) {
            SourceKind.M3U_URL -> {
                require(source.url.startsWith("http://", true) ||
                    source.url.startsWith("https://", true)) {
                    "Playlist URL must start with http:// or https://"
                }
                val temp = store.tempFile(source.id)
                http.download(source.url, headers, temp)
                val parsed = parseFile(temp, source)
                if (parsed.items.isEmpty()) {
                    temp.delete()
                    error("Playlist contains no playable entries")
                }
                store.commit(source.id, temp)
                Loaded(
                    items = parsed.items,
                    epgUrls = parsed.epgUrls + source.epgUrl.takeIf { it.isNotBlank() }.orEmpty(),
                )
            }

            SourceKind.M3U_FILE -> {
                val input = context.contentResolver.openInputStream(Uri.parse(source.url))
                    ?: error("Unable to open the selected playlist file")
                store.writeStream(source.id, input)
                val parsed = store.parse(source.id, m3uParser, source)
                    ?: error("Playlist contains no playable entries")
                Loaded(
                    items = parsed.items,
                    epgUrls = parsed.epgUrls + source.epgUrl.takeIf { it.isNotBlank() }.orEmpty(),
                )
            }

            SourceKind.XTREAM -> {
                val loaded = xtreamClient.load(source, settings)
                val epgUrls = listOfNotNull(
                    loaded.epgUrl,
                    source.epgUrl.takeIf { it.isNotBlank() },
                )
                store.writeItems(source.id, loaded.items, epgUrls)
                Loaded(items = loaded.items, epgUrls = epgUrls)
            }

            SourceKind.STALKER -> {
                val loaded = stalkerClient.load(source, settings)
                val epgUrls = listOfNotNull(source.epgUrl.takeIf { it.isNotBlank() })
                store.writeItems(source.id, loaded.items, epgUrls)
                Loaded(items = loaded.items, epgUrls = epgUrls)
            }
        }
    }

    private fun parseFile(file: File, source: SourceConfig): M3uParser.ParsedPlaylist =
        file.bufferedReader(Charsets.UTF_8).use { reader ->
            m3uParser.parse(reader.lineSequence(), source)
        }
}
