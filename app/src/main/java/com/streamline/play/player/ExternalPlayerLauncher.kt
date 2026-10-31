package com.streamline.play.player

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.streamline.play.data.model.ExternalPlayerApp
import com.streamline.play.data.model.MediaItem
import com.streamline.play.data.settings.AppSettings

object ExternalPlayerLauncher {

    /**
     * Hands the active stream to another player at [startPositionMs]. When [app] has no
     * package the native chooser lists every installed activity that can open video;
     * nothing is filtered to a fixed player list.
     */
    fun launch(
        context: Context,
        item: MediaItem,
        settings: AppSettings,
        app: ExternalPlayerApp,
        startPositionMs: Long = 0L,
    ): Boolean {
        val headers = settings.requestHeaders + item.headers
        val headerLines = headers.map { (name, value) -> "$name: $value" }.toTypedArray()
        val position = if (item.isLive) 0L else startPositionMs.coerceAtLeast(0L)
        val direct = app.packageName != null

        val target = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(item.url), if (direct) mimeFor(item.url) else "video/*")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra("headers", headerLines)
            putExtra("http-headers", headerLines)
            putExtra("bar.header.keys", headers.keys.toTypedArray())
            putExtra("bar.header.values", headers.values.toTypedArray())
            putExtra("android.media.intent.extra.HTTP_HEADERS", bundleOfHeaders(headers))
            putExtra(Intent.EXTRA_TITLE, item.name)
            putExtra("title", item.name)
            putExtra("isLive", item.isLive)
            if (position > 0L) putPositionExtras(position)
            app.packageName?.let { setPackage(it) }
        }

        val intent = if (direct) {
            target
        } else {
            Intent.createChooser(target, "Play with").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }

        return runCatching {
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    fun isInstalled(context: Context, app: ExternalPlayerApp): Boolean {
        val pkg = app.packageName ?: return true
        return runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess
    }

    /** Every media app understands at least one of these position extras. */
    private fun Intent.putPositionExtras(positionMs: Long) {
        putExtra("position", positionMs)
        putExtra("start_position", positionMs)
        putExtra("extra_position", positionMs)
        putExtra("android.media.intent.extra.START_POSITION", positionMs)
        putExtra("start-time", positionMs)
        putExtra("resume_position", positionMs)
        putExtra("playback_position", positionMs)
    }

    private fun bundleOfHeaders(headers: Map<String, String>): Bundle = Bundle().apply {
        headers.forEach { (name, value) -> putString(name, value) }
    }

    private fun mimeFor(url: String): String = when {
        url.contains(".m3u8", true) || url.contains("type=m3u", true) -> "application/x-mpegURL"
        url.contains(".mpd", true) -> "application/dash+xml"
        url.contains(".mkv", true) -> "video/x-matroska"
        url.contains(".mp4", true) -> "video/mp4"
        url.contains(".ts", true) -> "video/mp2t"
        url.contains(".avi", true) -> "video/x-msvideo"
        else -> "video/*"
    }
}
