package com.streamline.play.data.epg

import android.content.Context
import com.streamline.play.data.model.EpgProgram
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class CachedEpg(
    val programs: List<EpgProgram>,
    val channelNames: Map<String, String>,
    val fetchedAtMs: Long,
    val urls: List<String>,
)

object EpgCache {

    private fun file(context: Context): File = File(context.cacheDir, "epg_cache.json")

    fun read(context: Context): CachedEpg? {
        val file = file(context)
        if (!file.exists()) return null
        return runCatching {
            val root = JSONObject(file.readText())
            val array = root.optJSONArray("programs") ?: JSONArray()
            val programs = buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    add(
                        EpgProgram(
                            channelId = obj.optString("channel"),
                            title = obj.optString("title"),
                            description = obj.optString("description"),
                            startMs = obj.optLong("start"),
                            endMs = obj.optLong("end"),
                            icon = obj.optString("icon").takeIf { it.isNotBlank() },
                        ),
                    )
                }
            }
            val namesObj = root.optJSONObject("channelNames")
            val names = buildMap {
                namesObj?.keys()?.forEach { key -> put(key, namesObj.optString(key)) }
            }
            val urlsArray = root.optJSONArray("urls") ?: JSONArray()
            val urls = buildList {
                for (i in 0 until urlsArray.length()) add(urlsArray.optString(i))
            }
            CachedEpg(
                programs = programs,
                channelNames = names,
                fetchedAtMs = root.optLong("fetchedAt"),
                urls = urls,
            )
        }.getOrNull()
    }

    fun write(context: Context, cache: CachedEpg) {
        runCatching {
            val programs = JSONArray()
            cache.programs.forEach { program ->
                programs.put(
                    JSONObject()
                        .put("channel", program.channelId)
                        .put("title", program.title)
                        .put("description", program.description)
                        .put("start", program.startMs)
                        .put("end", program.endMs)
                        .put("icon", program.icon.orEmpty()),
                )
            }
            val names = JSONObject()
            cache.channelNames.forEach { (id, name) -> names.put(id, name) }
            val urls = JSONArray()
            cache.urls.forEach { urls.put(it) }
            val root = JSONObject()
                .put("programs", programs)
                .put("channelNames", names)
                .put("urls", urls)
                .put("fetchedAt", cache.fetchedAtMs)
            file(context).writeText(root.toString())
        }
    }
}
