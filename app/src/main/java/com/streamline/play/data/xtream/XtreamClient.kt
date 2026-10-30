package com.streamline.play.data.xtream

import com.streamline.play.data.model.ContentType
import com.streamline.play.data.model.MediaItem
import com.streamline.play.data.model.SourceConfig
import com.streamline.play.data.net.HttpFetcher
import com.streamline.play.data.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

class XtreamClient(private val http: HttpFetcher) {

    data class Loaded(
        val items: List<MediaItem>,
        val epgUrl: String? = null,
    )

    suspend fun load(config: SourceConfig, settings: AppSettings): Loaded = withContext(Dispatchers.IO) {
        require(config.url.isNotBlank()) { "Xtream server URL is empty" }
        require(config.username.isNotBlank()) { "Xtream username is empty" }
        val base = config.url.trim().trimEnd('/')
            .removeSuffix("/player_api.php")
            .removeSuffix("/get.php")
        val urls = UrlSet(base, config.username, config.password)
        val headers = settings.requestHeaders

        val login = JSONObject(http.getText(urls.api(null), headers))
        val userInfo = login.optJSONObject("user_info")
        if (userInfo != null) {
            val status = userInfo.optString("status")
            if (status.equals("Banned", true) || status.equals("Disabled", true)) {
                error("Xtream account is $status")
            }
        }

        val liveCategories = categoryMap(urls.api("get_live_categories"), headers)
        val vodCategories = categoryMap(urls.api("get_vod_categories"), headers)
        val seriesCategories = categoryMap(urls.api("get_series_categories"), headers)

        val items = mutableListOf<MediaItem>()
        val liveArray = jsonArray(urls.api("get_live_streams"), headers)
        for (i in 0 until liveArray.length()) {
            val obj = liveArray.optJSONObject(i) ?: continue
            val streamId = obj.optInt("stream_id", -1)
            if (streamId <= 0) continue
            val name = obj.optString("name").ifBlank { "Channel $streamId" }
            val categoryId = obj.optString("category_id")
            val archiveDays = obj.optInt("tv_archive_duration", 0)
            val hasArchive = obj.optInt("tv_archive", 0) == 1
            items += MediaItem(
                id = "${config.id}:live:$streamId",
                name = name,
                url = urls.live(streamId),
                type = ContentType.LIVE,
                sourceId = config.id,
                sourceName = config.name,
                group = liveCategories[categoryId] ?: "Live TV",
                logo = obj.optString("stream_icon").takeIf { it.isNotBlank() },
                channelNumber = obj.optInt("num", 0).takeIf { it > 0 },
                tvgId = obj.optString("epg_channel_id").takeIf { it.isNotBlank() },
                catchup = if (hasArchive) "default" else null,
                catchupDays = archiveDays,
                catchupSource = if (hasArchive) urls.catchup(streamId) else null,
            )
        }

        val vodArray = jsonArray(urls.api("get_vod_streams"), headers)
        for (i in 0 until vodArray.length()) {
            val obj = vodArray.optJSONObject(i) ?: continue
            val streamId = obj.optInt("stream_id", -1)
            if (streamId <= 0) continue
            val extension = obj.optString("container_extension").ifBlank { "mp4" }
            val name = obj.optString("name").ifBlank { "Movie $streamId" }
            items += MediaItem(
                id = "${config.id}:vod:$streamId",
                name = name,
                url = urls.vod(streamId, extension),
                type = ContentType.MOVIE,
                sourceId = config.id,
                sourceName = config.name,
                group = vodCategories[obj.optString("category_id")] ?: "Movies",
                logo = obj.optString("stream_icon").takeIf { it.isNotBlank() },
                rating = obj.optString("rating").toFloatOrNull(),
                year = obj.optString("year").toIntOrNull()
                    ?: obj.optString("releaseDate").take(4).toIntOrNull(),
                description = obj.optString("plot").takeIf { it.isNotBlank() },
            )
        }

        val seriesArray = jsonArray(urls.api("get_series"), headers)
        val limit = minOf(seriesArray.length(), 40)
        for (i in 0 until limit) {
            val obj = seriesArray.optJSONObject(i) ?: continue
            val seriesId = obj.optInt("series_id", -1)
            if (seriesId <= 0) continue
            val seriesName = obj.optString("name").ifBlank { "Series $seriesId" }
            val cover = obj.optString("cover").takeIf { it.isNotBlank() }
            val group = seriesCategories[obj.optString("category_id")] ?: "Series"
            val info = runCatching {
                JSONObject(http.getText(urls.seriesInfo(seriesId), headers))
            }.getOrNull() ?: continue
            val episodes = info.optJSONObject("episodes") ?: continue
            val keys = episodes.keys()
            while (keys.hasNext()) {
                val seasonKey = keys.next()
                val season = seasonKey.toIntOrNull() ?: 1
                val seasonArray = episodes.optJSONArray(seasonKey) ?: continue
                for (e in 0 until seasonArray.length()) {
                    val episode = seasonArray.optJSONObject(e) ?: continue
                    val episodeId = episode.optInt("id", -1)
                    if (episodeId <= 0) continue
                    val episodeNum = episode.optInt("episode_num", e + 1)
                    val extension = episode.optString("container_extension").ifBlank { "mp4" }
                    val title = episode.optString("title").ifBlank { null }
                    val episodeName = "S%02dE%02d".format(season, episodeNum) +
                        if (title.isNullOrBlank()) "" else " · $title"
                    items += MediaItem(
                        id = "${config.id}:episode:$episodeId",
                        name = "$seriesName $episodeName",
                        url = urls.episode(episodeId, extension),
                        type = ContentType.SERIES,
                        sourceId = config.id,
                        sourceName = config.name,
                        group = group,
                        logo = cover,
                        tvgId = null,
                        seriesName = seriesName,
                        season = season,
                        episode = episodeNum,
                        description = info.optString("overview").takeIf { it.isNotBlank() },
                    )
                }
            }
        }

        val epgUrl = login.optJSONObject("server_info")
            ?.optString("url")
            ?.takeIf { it.isNotBlank() }
            ?.let { serverUrl ->
                "${serverUrl.trimEnd('/')}/xmltv.php?username=${enc(config.username)}&password=${enc(config.password)}"
            }

        Loaded(items = items, epgUrl = epgUrl ?: config.epgUrl.takeIf { it.isNotBlank() })
    }

    private suspend fun categoryMap(url: String, headers: Map<String, String>): Map<String, String> {
        val array = runCatching { jsonArray(url, headers) }.getOrElse { return emptyMap() }
        val map = mutableMapOf<String, String>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            map[obj.optString("category_id")] = obj.optString("category_name")
        }
        return map
    }

    private suspend fun jsonArray(url: String, headers: Map<String, String>): JSONArray {
        val text = http.getText(url, headers)
        val trimmed = text.trim()
        return when {
            trimmed.startsWith("[") -> JSONArray(trimmed)
            trimmed.startsWith("{") -> JSONObject(trimmed).optJSONArray("data") ?: JSONArray()
            else -> JSONArray()
        }
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private class UrlSet(
        private val base: String,
        private val username: String,
        private val password: String,
    ) {
        private fun creds(): String = "username=${enc(username)}&password=${enc(password)}"

        fun api(action: String?): String =
            "$base/player_api.php?${creds()}" + if (action != null) "&action=$action" else ""

        fun live(streamId: Int): String = "$base/live/${enc(username)}/${enc(password)}/$streamId.m3u8"
        fun vod(streamId: Int, extension: String): String =
            "$base/movie/${enc(username)}/${enc(password)}/$streamId.$extension"

        fun episode(episodeId: Int, extension: String): String =
            "$base/series/${enc(username)}/${enc(password)}/$episodeId.$extension"

        fun seriesInfo(seriesId: Int): String = "${api("get_series_info")}&series_id=$seriesId"

        fun catchup(streamId: Int): String =
            "$base/streaming/timeshift.php?${creds()}&stream=$streamId&start={utc}&end={lutc}"

        private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
    }
}
