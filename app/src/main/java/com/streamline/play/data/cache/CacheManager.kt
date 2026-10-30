package com.streamline.play.data.cache

import android.content.Context
import java.io.File

class CacheManager(private val context: Context) {

    fun cacheSizeBytes(): Long = runCatching {
        context.cacheDir.walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }
    }.getOrDefault(0L)

    fun clearCache(): Boolean = runCatching {
        context.cacheDir.listFiles()?.forEach { file ->
            if (file.isStreamCache) return@forEach
            if (file.isDirectory) file.deleteRecursively() else file.delete()
        }
        true
    }.getOrDefault(false)

    fun pruneStale(maxAgeMs: Long = DEFAULT_MAX_AGE_MS): Long = runCatching {
        val streamCache = File(context.cacheDir, STREAM_CACHE_DIR).absolutePath
        val now = System.currentTimeMillis()
        var freed = 0L
        context.cacheDir.walkTopDown()
            .filter { it.isFile && now - it.lastModified() > maxAgeMs }
            .filterNot { it.absolutePath.startsWith(streamCache) }
            .forEach { file ->
                val length = file.length()
                if (file.delete()) freed += length
            }
        freed
    }.getOrDefault(0L)

    private val File.isStreamCache: Boolean
        get() = isDirectory && name == STREAM_CACHE_DIR

    companion object {
        private const val STREAM_CACHE_DIR = "media-streams"
        private const val DEFAULT_MAX_AGE_MS = 3L * 24L * 60L * 60L * 1000L
    }
}
