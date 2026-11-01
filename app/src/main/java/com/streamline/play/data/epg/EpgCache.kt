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

/**
 * Guide cache written as one header line plus one compact array per programme.
 * A single JSONObject holding the whole guide briefly doubles the heap on large
 * XMLTV feeds, so both read and write stay line-oriented.
 */
object EpgCache {

    private fun file(context: Context): File = File(context.cacheDir, "epg_cache.json")

    fun read(context: Context): CachedEpg? {
        val file = file(context)
        if (!file.exists()) return null
        return runCatching {
            file.bufferedReader().use { reader ->
                val header = JSONObject(reader.readLine().orEmpty())
                if (header.has("programs")) return@use legacyCache(header)
                val namesObj = header.optJSONObject("channelNames")
                val names = buildMap {
                    namesObj?.keys()?.forEach { key -> put(key, namesObj.optString(key)) }
                }
                val urlsArray = header.optJSONArray("urls") ?: JSONArray()
                val urls = buildList {
                    for (i in 0 until urlsArray.length()) add(urlsArray.optString(i))
                }
                val programs = ArrayList<EpgProgram>()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    val row = runCatching { JSONArray(line) }.getOrNull() ?: continue
                    programs += EpgProgram(
                        channelId = row.optString(0),
                        title = row.optString(1),
                        description = row.optString(2),
                        startMs = row.optLong(3),
                        endMs = row.optLong(4),
                        icon = row.optString(5).takeIf { it.isNotBlank() },
                    )
                }
                CachedEpg(programs, names, header.optLong("fetchedAt"), urls)
            }
        }.getOrNull()
    }

    fun write(context: Context, cache: CachedEpg) {
        runCatching {
            val target = file(context)
            val temp = File(target.parentFile, "${target.name}.tmp")
            temp.bufferedWriter().use { out ->
                val header = JSONObject()
                    .put("channelNames", JSONObject().apply {
                        cache.channelNames.forEach { (id, name) -> put(id, name) }
                    })
                    .put("urls", JSONArray().apply { cache.urls.forEach { put(it) } })
                    .put("fetchedAt", cache.fetchedAtMs)
                out.write(header.toString())
                out.newLine()
                cache.programs.forEach { program ->
                    val row = JSONArray()
                        .put(program.channelId)
                        .put(program.title)
                        .put(program.description)
                        .put(program.startMs)
                        .put(program.endMs)
                        .put(program.icon.orEmpty())
                    out.write(row.toString())
                    out.newLine()
                }
            }
            if (!temp.renameTo(target)) {
                target.delete()
                if (!temp.renameTo(target)) temp.delete()
            }
        }
    }

    /** Pre-streaming releases stored the entire guide in one JSON line. */
    private fun legacyCache(root: JSONObject): CachedEpg {
        val array = root.optJSONArray("programs") ?: JSONArray()
        val programs = ArrayList<EpgProgram>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            programs += EpgProgram(
                channelId = obj.optString("channel"),
                title = obj.optString("title"),
                description = obj.optString("description"),
                startMs = obj.optLong("start"),
                endMs = obj.optLong("end"),
                icon = obj.optString("icon").takeIf { it.isNotBlank() },
            )
        }
        val namesObj = root.optJSONObject("channelNames")
        val names = buildMap {
            namesObj?.keys()?.forEach { key -> put(key, namesObj.optString(key)) }
        }
        val urlsArray = root.optJSONArray("urls") ?: JSONArray()
        val urls = buildList {
            for (i in 0 until urlsArray.length()) add(urlsArray.optString(i))
        }
        return CachedEpg(programs, names, root.optLong("fetchedAt"), urls)
    }
}
