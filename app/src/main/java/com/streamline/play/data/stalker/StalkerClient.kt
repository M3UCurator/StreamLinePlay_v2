package com.streamline.play.data.stalker

import com.streamline.play.data.model.ContentType
import com.streamline.play.data.model.MediaItem
import com.streamline.play.data.model.SourceConfig
import com.streamline.play.data.net.HttpFetcher
import com.streamline.play.data.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

class StalkerClient(private val http: HttpFetcher) {

    data class Loaded(
        val items: List<MediaItem>,
    )

    suspend fun load(config: SourceConfig, settings: AppSettings): Loaded = withContext(Dispatchers.IO) {
        require(config.url.isNotBlank()) { "Stalker portal URL is empty" }
        require(config.mac.isNotBlank()) { "Stalker MAC address is empty" }
        val portal = normalizePortal(config.url)
        val mac = config.mac.trim()
        val baseHeaders = settings.requestHeaders + mapOf(
            "Cookie" to "mac=$mac; stb_lang=en; timezone=UTC",
            "Referer" to "$portal/c/",
        )

        val handshakeUrl = "$portal/server/portal.php?type=stb&action=handshake&JsHttpRequest=1-xml"
        val handshake = JSONObject(http.getText(handshakeUrl, baseHeaders, 25_000))
        val token = handshake.optJSONObject("js")?.optString("token")
            ?.takeIf { it.isNotBlank() }
            ?: handshake.optString("token").takeIf { it.isNotBlank() }
            ?: error("Portal did not return a session token")

        val authHeaders = baseHeaders + ("Authorization" to "Bearer $token")

        runCatching {
            http.getText(
                "$portal/server/portal.php?type=stb&action=get_profile&JsHttpRequest=1-xml",
                authHeaders,
                20_000,
            )
        }

        val channelsUrl = "$portal/server/portal.php?type=itv&action=get_all_channels&JsHttpRequest=1-xml"
        val root = JSONObject(http.getText(channelsUrl, authHeaders, 25_000))
        val data = root.optJSONObject("js")?.optJSONArray("data")
            ?: root.optJSONArray("data")
            ?: JSONArray()

        val limit = minOf(data.length(), 80)
        val semaphore = Semaphore(4)
        val items = supervisorScope {
            (0 until limit).map { index ->
                async {
                    val obj = data.optJSONObject(index) ?: return@async null
                    runCatching {
                        semaphore.withPermit { resolveChannel(portal, obj, token, config, settings, authHeaders) }
                    }.getOrNull()
                }
            }.awaitAll()
        }.filterNotNull()

        if (items.isEmpty()) {
            error("Portal returned no playable channels")
        }
        Loaded(items)
    }

    private suspend fun resolveChannel(
        portal: String,
        obj: JSONObject,
        token: String,
        config: SourceConfig,
        settings: AppSettings,
        headers: Map<String, String>,
    ): MediaItem? {
        val name = obj.optString("name").ifBlank { return null }
        val channelId = obj.optString("id")
        val cmd = obj.optString("cmd").ifBlank { "ffmpeg $channelId" }
        val linkUrl = "$portal/server/portal.php?type=itv&action=create_link&cmd=" +
            URLEncoder.encode(cmd, "UTF-8") + "&JsHttpRequest=1-xml"
        val response = JSONObject(http.getText(linkUrl, headers, 20_000))
        var streamUrl = response.optJSONObject("js")?.optString("cmd").orEmpty()
            .ifBlank { response.optString("cmd") }
        streamUrl = streamUrl.substringAfter(" ").trim()
        if (!streamUrl.startsWith("http")) return null
        if (streamUrl.contains("/localhost/")) {
            streamUrl = streamUrl.replace("/localhost/", "/")
        }
        val logo = obj.optString("logo").takeIf { it.isNotBlank() }
            ?.let { if (it.startsWith("http")) it else portal + it }
        return MediaItem(
            id = "${config.id}:stalker:$channelId",
            name = name.trim(),
            url = streamUrl,
            type = ContentType.LIVE,
            sourceId = config.id,
            sourceName = config.name,
            group = "MAG Portal",
            logo = logo,
            channelNumber = obj.optString("number").toIntOrNull(),
            tvgId = obj.optString("xmltv_id").takeIf { it.isNotBlank() },
            catchup = if (obj.optInt("tv_archive", 0) == 1) "default" else null,
            catchupDays = obj.optInt("tv_archive_duration", 0),
        )
    }

    private fun normalizePortal(raw: String): String {
        var portal = raw.trim().trimEnd('/')
        portal = portal.substringBefore("/server/portal.php")
        portal = portal.substringBefore("/portal.php")
        return portal
    }
}
