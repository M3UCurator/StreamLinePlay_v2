package com.streamline.play

import android.content.Context
import com.streamline.play.data.cache.CacheManager
import com.streamline.play.data.epg.EpgRepository
import com.streamline.play.data.m3u.M3uParser
import com.streamline.play.data.net.HttpFetcher
import com.streamline.play.data.playlist.PlaylistRepository
import com.streamline.play.data.playlist.PlaylistStore
import com.streamline.play.data.settings.SettingsRepository
import com.streamline.play.data.source.SourceRepository
import com.streamline.play.data.stalker.StalkerClient
import com.streamline.play.data.xtream.XtreamClient

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val settingsRepository = SettingsRepository(appContext)
    val sourceRepository = SourceRepository(appContext)
    val httpFetcher = HttpFetcher()
    val m3uParser = M3uParser()
    val xtreamClient = XtreamClient(httpFetcher)
    val stalkerClient = StalkerClient(httpFetcher)
    val playlistStore = PlaylistStore(appContext)
    val playlistRepository = PlaylistRepository(
        context = appContext,
        m3uParser = m3uParser,
        http = httpFetcher,
        xtreamClient = xtreamClient,
        stalkerClient = stalkerClient,
        store = playlistStore,
    )
    val epgRepository = EpgRepository(appContext, httpFetcher, settingsRepository)
    val cacheManager = CacheManager(appContext)
}
