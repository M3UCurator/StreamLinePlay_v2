package com.streamline.play.player

import android.content.Context
import android.os.Build
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.ConnectionPool
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.TlsVersion
import java.io.File
import java.util.concurrent.TimeUnit

/** Forces TLS 1.2 on API 19-21 devices where the platform disables it by default. */
object Tls12Helper {

    fun connectionSpecs(): List<ConnectionSpec> {
        val modern = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.LOLLIPOP) {
            ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                .tlsVersions(TlsVersion.TLS_1_2)
                .build()
        } else {
            ConnectionSpec.MODERN_TLS
        }
        return listOf(modern, ConnectionSpec.CLEARTEXT)
    }

    fun apply(builder: OkHttpClient.Builder): OkHttpClient.Builder =
        builder.connectionSpecs(connectionSpecs())
}

object StreamNetworking {

    private const val CONNECT_TIMEOUT_S = 10L
    private const val READ_TIMEOUT_S = 15L

    val client: OkHttpClient by lazy {
        Tls12Helper.apply(
            OkHttpClient.Builder()
                .connectionPool(ConnectionPool(6, 5, TimeUnit.MINUTES))
                .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
                .retryOnConnectionFailure(false)
                .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
                .writeTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS),
        ).build()
    }

    fun httpFactory(
        userAgent: String,
        headers: Map<String, String>,
    ): HttpDataSource.Factory = OkHttpDataSource.Factory(client)
        .setUserAgent(userAgent)
        .setDefaultRequestProperties(headers)

    /** Wraps the HTTP factory with a bounded on-disk cache for on-demand titles. */
    fun cachedFactory(
        context: Context,
        http: HttpDataSource.Factory,
    ): DataSource.Factory? {
        val cache = StreamCache.get(context) ?: return null
        return CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(http)
            .setCacheWriteDataSinkFactory(
                CacheDataSink.Factory()
                    .setCache(cache)
                    .setFragmentSize(CacheDataSink.DEFAULT_FRAGMENT_SIZE),
            )
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }
}

/** Single SimpleCache instance shared by every player engine in the process. */
object StreamCache {

    private const val MAX_BYTES = 256L * 1024L * 1024L

    @Volatile
    private var cache: SimpleCache? = null

    fun get(context: Context): SimpleCache? = synchronized(this) {
        cache ?: runCatching {
            val appContext = context.applicationContext
            SimpleCache(
                File(appContext.cacheDir, "media-streams"),
                LeastRecentlyUsedCacheEvictor(MAX_BYTES),
                StandaloneDatabaseProvider(appContext),
            )
        }.getOrNull().also { cache = it }
    }
}
