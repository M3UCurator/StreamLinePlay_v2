package com.streamline.play.data.playlist

import android.content.Context
import com.streamline.play.data.m3u.M3uParser
import com.streamline.play.data.model.MediaItem
import com.streamline.play.data.model.SourceConfig
import java.io.File
import java.io.InputStream

/** Playlists are kept in filesDir/playlists and parsed line by line on demand. */
class PlaylistStore(context: Context) {

    private val root = File(context.filesDir, "playlists")

    fun file(sourceId: String): File = File(root, "${sanitize(sourceId)}.m3u")

    fun tempFile(sourceId: String): File {
        root.mkdirs()
        return File(root, "${sanitize(sourceId)}.m3u.tmp")
    }

    fun exists(sourceId: String): Boolean = file(sourceId).let { it.isFile && it.length() > 0L }

    fun commit(sourceId: String, temp: File): Boolean {
        val target = file(sourceId)
        if (!temp.isFile || temp.length() == 0L) return false
        root.mkdirs()
        if (target.exists()) target.delete()
        return temp.renameTo(target)
    }

    fun writeRaw(sourceId: String, text: String) {
        val temp = tempFile(sourceId)
        runCatching { temp.writeText(text, Charsets.UTF_8) }
            .onSuccess { commit(sourceId, temp) }
    }

    fun writeStream(sourceId: String, input: InputStream) {
        val temp = tempFile(sourceId)
        runCatching {
            input.use { stream ->
                temp.outputStream().use { output -> stream.copyTo(output, DEFAULT_BUFFER_SIZE) }
            }
        }.onSuccess { commit(sourceId, temp) }
    }

    fun writeItems(sourceId: String, items: List<MediaItem>, epgUrls: List<String>) {
        writeRaw(sourceId, M3uParser.generate(items, epgUrls))
    }

    fun parse(sourceId: String, parser: M3uParser, source: SourceConfig): M3uParser.ParsedPlaylist? {
        val file = file(sourceId)
        if (!file.isFile || file.length() == 0L) return null
        return runCatching {
            file.bufferedReader(Charsets.UTF_8).use { reader ->
                parser.parse(reader.lineSequence(), source)
            }
        }.getOrNull()?.takeIf { it.items.isNotEmpty() }
    }

    fun delete(sourceId: String) {
        runCatching { file(sourceId).delete() }
        runCatching { tempFile(sourceId).delete() }
    }

    fun sizeBytes(): Long = runCatching {
        root.listFiles()?.sumOf { it.length() } ?: 0L
    }.getOrDefault(0L)

    private fun sanitize(id: String): String =
        id.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "playlist" }
}
