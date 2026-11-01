package com.streamline.play.data.net

import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * OkHttp policy shared by the data layer and the loopback stream proxy.
 *
 * OkHttp resolves redirect targets (relative, protocol-relative, absolute and http -> https)
 * and performs the TLS handshake for the upgraded scheme. The proxy instead keeps every hop
 * visible so it can validate the next host before dialing it.
 */
object HttpClients {

    private val idleConnections = ConnectionPool(8, 5, TimeUnit.MINUTES)

    private fun builder(connectTimeoutMs: Int, readTimeoutMs: Int): OkHttpClient.Builder =
        OkHttpClient.Builder()
            .connectionPool(idleConnections)
            .connectTimeout(connectTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(readTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .writeTimeout(readTimeoutMs.toLong(), TimeUnit.MILLISECONDS)

    /** Follows the whole redirect chain, switching to TLS when a hop upgrades http -> https. */
    fun redirecting(connectTimeoutMs: Int, readTimeoutMs: Int): OkHttpClient =
        builder(connectTimeoutMs, readTimeoutMs)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()

    /** Surfaces the first 3xx so callers can validate the next target before following it. */
    fun manualRedirects(connectTimeoutMs: Int, readTimeoutMs: Int): OkHttpClient =
        builder(connectTimeoutMs, readTimeoutMs)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()
}
