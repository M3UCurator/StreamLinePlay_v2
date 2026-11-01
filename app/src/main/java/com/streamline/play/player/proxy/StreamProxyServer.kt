package com.streamline.play.player.proxy

import com.streamline.play.data.net.HttpClients
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream
import kotlin.concurrent.thread

/**
 * Loopback HTTP proxy for HLS playback.
 *
 * Every child URL in a rewritten playlist carries an HMAC signature. Requests that are
 * not signed are accepted only from same-origin browser fetches or for hosts that the
 * active playback session registered, which keeps the proxy from acting as an open relay.
 */
class StreamProxyServer(
    private val port: Int = 0,
    private val allowLoopbackTargets: Boolean = false,
) {

    private val signer = ProxySigner()
    private val http: OkHttpClient by lazy {
        HttpClients.manualRedirects(CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS)
    }
    private val allowlist = CopyOnWriteArraySet<String>()
    private val cookieJars = ConcurrentHashMap<String, MutableMap<String, String>>()
    private val liveSockets = ConcurrentHashMap<Socket, Unit>()
    private val activeRequests = AtomicInteger(0)
    private val running = AtomicBoolean(false)

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var sessionHeaders: Map<String, String> = emptyMap()

    val localPort: Int get() = serverSocket?.localPort ?: 0
    val isRunning: Boolean get() = running.get()

    fun start(): Boolean {
        if (running.get()) return true
        synchronized(this) {
            if (running.get()) return true
            val socket = runCatching {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(InetAddress.getByName(LOOPBACK), port), BACKLOG)
                }
            }.getOrNull() ?: return false
            serverSocket = socket
            running.set(true)
            thread(name = "stream-proxy-accept", isDaemon = true) { acceptLoop(socket) }
            return true
        }
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        abandonConnections()
        cookieJars.clear()
    }

    /** Drops in-flight segment requests so their threads unwind when playback ends. */
    fun abandonConnections() {
        liveSockets.keys.forEach { socket -> runCatching { socket.close() } }
        liveSockets.clear()
    }

    /** Signed loopback URL for [target]; falls back to the direct URL if the port is unavailable. */
    fun urlFor(target: String): String {
        if (!start()) return target
        val signature = signer.sign(target)
        return "http://$LOOPBACK:$localPort/p?u=${ProxyUrl.encode(target)}&s=$signature"
    }

    fun updateSessionHeaders(headers: Map<String, String>) {
        sessionHeaders = headers.filterKeys(::isForwardableHeader)
    }

    fun allowHosts(urls: Iterable<String>) {
        urls.mapNotNull(ProxyGuard::hostOf).forEach(allowlist::add)
    }

    fun clearAllowedHosts() = allowlist.clear()

    fun clearCookies() = cookieJars.clear()

    private fun isForwardableHeader(name: String): Boolean {
        if (name.isBlank() || name.any { it <= ' ' || it == ':' }) return false
        return HOP_BY_HOP_HEADERS.none { it.equals(name, ignoreCase = true) }
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get() && !socket.isClosed) {
            val client = runCatching { socket.accept() }.getOrNull() ?: break
            if (activeRequests.incrementAndGet() > MAX_CONNECTIONS) {
                activeRequests.decrementAndGet()
                runCatching { client.close() }
                continue
            }
            liveSockets[client] = Unit
            val started = runCatching {
                thread(name = "stream-proxy-io", isDaemon = true) {
                    try {
                        serve(client)
                    } finally {
                        liveSockets.remove(client)
                        activeRequests.decrementAndGet()
                    }
                }
            }.isSuccess
            if (!started) {
                liveSockets.remove(client)
                activeRequests.decrementAndGet()
                runCatching { client.close() }
            }
        }
    }

    private fun serve(socket: Socket) {
        try {
            socket.soTimeout = READ_TIMEOUT_MS
            socket.tcpNoDelay = true
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())
            val request = readRequest(input) ?: return
            when {
                request.method == "OPTIONS" -> writeHead(output, 204, CORS_HEADERS)
                request.path == "/" || request.path == "/health" ->
                    writeText(output, 200, "StreamLine stream proxy")
                request.path != "/p" -> writeText(output, 404, "Not found")
                request.method != "GET" && request.method != "HEAD" ->
                    writeText(output, 405, "Method not allowed")
                else -> forward(request, output)
            }
        } catch (_: Exception) {
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun forward(request: ProxyRequest, output: OutputStream) {
        val rawTarget = ProxyUrl.decode(request.query["u"])
        if (rawTarget.isNullOrBlank()) {
            writeText(output, 400, "Missing target")
            return
        }
        if (!isAuthorized(rawTarget, request.query["s"], request.headers["sec-fetch-site"])) {
            writeText(output, 403, "Forbidden")
            return
        }
        val target = ProxyGuard.parseTarget(rawTarget, allowLoopbackTargets)
        if (target == null) {
            writeText(output, 400, "Invalid target")
            return
        }

        when (val result = open(target, request, redirects = 0)) {
            is FetchResult.Failure -> writeText(output, result.status, result.message)
            is FetchResult.Success -> deliver(result, request, output)
        }
    }

    private fun deliver(success: FetchResult.Success, request: ProxyRequest, output: OutputStream) {
        val response = success.response
        try {
            val body = response.body
            if (body == null) {
                writeText(output, 502, "Upstream body unavailable")
                return
            }
            val gzip = response.header("Content-Encoding")?.contains("gzip", true) == true
            val raw = body.byteStream()
            val input = PushbackInputStream(if (gzip) GZIPInputStream(raw) else raw, PREFIX_SIZE)

            val prefix = ByteArray(PREFIX_SIZE)
            val prefixCount = input.read(prefix)
            if (prefixCount > 0) input.unread(prefix, 0, prefixCount)

            val declaredLength = body.contentLength()
            val manifest = prefixCount > 0 &&
                startsWithPlaylistHeader(prefix, prefixCount) &&
                (declaredLength < 0 || declaredLength <= MAX_PLAYLIST_BYTES)

            when {
                manifest && request.method == "HEAD" -> writeHead(
                    output,
                    200,
                    CORS_HEADERS + listOf("Content-Type" to PLAYLIST_CONTENT_TYPE),
                )

                manifest -> {
                    val bytes = readAll(input)
                    if (bytes == null) {
                        writeText(output, 502, "Playlist too large")
                        return
                    }
                    val playlist = ManifestRewriter.rewrite(
                        bytes.toString(Charsets.UTF_8),
                        success.target.toString(),
                        ::urlFor,
                    )
                    writePlaylist(output, playlist)
                }

                else -> streamBody(output, response, body, input, gzip, request.method == "HEAD")
            }
        } catch (_: Exception) {
        } finally {
            runCatching { response.close() }
        }
    }

    /**
     * Walks the redirect chain one hop at a time. OkHttp dials each hop (including the TLS
     * handshake for an http -> https upgrade) while every new target is validated by the
     * guard, which would be skipped if OkHttp followed redirects on its own.
     */
    private fun open(target: URI, request: ProxyRequest, redirects: Int): FetchResult {
        val call = runCatching { http.newCall(upstreamRequest(target, request)) }
            .getOrElse { return FetchResult.Failure(502, "Invalid upstream target") }
        val response = runCatching { call.execute() }.getOrElse { error ->
            return if (error is SocketTimeoutException) {
                FetchResult.Failure(504, "Upstream timeout")
            } else {
                FetchResult.Failure(502, "Upstream unavailable")
            }
        }
        storeCookies(target.host, response)
        val code = response.code

        if (code in 300..399) {
            val location = response.header("Location")?.trim()
            val next = location
                ?.takeIf { it.isNotEmpty() }
                ?.let { runCatching { response.request.url.resolve(it) }.getOrNull() }
            response.close()
            if (next == null || redirects >= MAX_REDIRECTS) {
                return FetchResult.Failure(502, "Upstream redirect failed")
            }
            val parsed = ProxyGuard.parseTarget(next.toString(), allowLoopbackTargets)
                ?: return FetchResult.Failure(502, "Upstream redirect blocked")
            return open(parsed, request, redirects + 1)
        }
        if (code !in 200..299) {
            response.close()
            return FetchResult.Failure(code, "Upstream $code")
        }
        return FetchResult.Success(target, response)
    }

    private fun upstreamRequest(target: URI, request: ProxyRequest): Request {
        val builder = Request.Builder()
            .url(target.toString())
            .apply { if (request.method == "HEAD") head() else get() }
            .header("Accept", "*/*")
            .header("Accept-Encoding", "identity")
        sessionHeaders.forEach { (name, value) ->
            if (!name.equals("Accept", true)) runCatching { builder.header(name, value) }
        }
        if (sessionHeaders.keys.none { it.equals("User-Agent", true) }) {
            builder.header("User-Agent", DEFAULT_UA)
        }
        cookieHeader(target.host)?.let { builder.header("Cookie", it) }
        if (!looksLikePlaylistPath(target)) {
            request.headers["range"]?.takeIf { it.isNotBlank() }?.let { builder.header("Range", it) }
        }
        return builder.build()
    }

    private fun streamBody(
        output: OutputStream,
        response: Response,
        body: ResponseBody,
        input: InputStream,
        gzip: Boolean,
        headOnly: Boolean,
    ) {
        val declaredLength = body.contentLength()
        val headers = mutableListOf(
            "Content-Type" to (response.header("Content-Type") ?: "application/octet-stream"),
        )
        headers += CORS_HEADERS
        response.header("Accept-Ranges")?.let { headers += "Accept-Ranges" to it }
        response.header("Content-Range")?.let { headers += "Content-Range" to it }
        if (!gzip && declaredLength >= 0) {
            headers += "Content-Length" to declaredLength.toString()
        }
        writeHead(output, response.code, headers)
        if (headOnly) return

        val buffer = ByteArray(COPY_BUFFER_BYTES)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
        }
        output.flush()
    }

    private fun isAuthorized(target: String, signature: String?, secFetchSite: String?): Boolean {
        if (!signature.isNullOrEmpty() && signer.verify(target, signature)) return true
        if (secFetchSite.equals("same-origin", ignoreCase = true)) return true
        val host = ProxyGuard.hostOf(target) ?: return false
        return allowlist.contains(host)
    }

    private fun storeCookies(host: String?, response: Response) {
        if (host.isNullOrBlank()) return
        val cookies = response.headers.values("Set-Cookie")
        if (cookies.isEmpty()) return
        if (cookieJars.size >= MAX_COOKIE_HOSTS && !cookieJars.containsKey(host.lowercase())) {
            cookieJars.keys.firstOrNull()?.let(cookieJars::remove)
        }
        val jar = cookieJars.getOrPut(host.lowercase()) { ConcurrentHashMap() }
        cookies.forEach { raw ->
            val pair = raw.substringBefore(';')
            val separator = pair.indexOf('=')
            if (separator > 0 && jar.size < MAX_COOKIES_PER_HOST) {
                jar[pair.substring(0, separator).trim()] = pair.substring(separator + 1).trim()
            }
        }
    }

    private fun cookieHeader(host: String?): String? {
        val jar = host?.lowercase()?.let(cookieJars::get) ?: return null
        if (jar.isEmpty()) return null
        return jar.entries.joinToString("; ") { (name, value) -> "$name=$value" }
    }

    private fun writePlaylist(output: OutputStream, playlist: String) {
        val body = playlist.toByteArray(Charsets.UTF_8)
        writeHead(
            output,
            200,
            CORS_HEADERS + listOf(
                "Content-Type" to PLAYLIST_CONTENT_TYPE,
                "Cache-Control" to "no-cache, no-store",
                "Content-Length" to body.size.toString(),
            ),
        )
        output.write(body)
        output.flush()
    }

    private fun writeText(output: OutputStream, status: Int, message: String) {
        val body = message.toByteArray(Charsets.UTF_8)
        writeHead(
            output,
            status,
            listOf(
                "Content-Type" to "text/plain; charset=utf-8",
                "Access-Control-Allow-Origin" to "*",
                "Content-Length" to body.size.toString(),
            ),
        )
        output.write(body)
        output.flush()
    }

    private fun writeHead(output: OutputStream, status: Int, headers: List<Pair<String, String>>) {
        val head = buildString(256) {
            append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n")
            headers.forEach { (name, value) ->
                append(name).append(": ").append(value).append("\r\n")
            }
            append("Connection: close\r\n\r\n")
        }
        output.write(head.toByteArray(Charsets.UTF_8))
        output.flush()
    }

    private fun readRequest(input: InputStream): ProxyRequest? {
        val requestLine = readLine(input) ?: return null
        val parts = requestLine.split(' ')
        if (parts.size < 2) return null
        val method = parts[0].uppercase()
        val rawPath = parts[1]
        val separator = rawPath.indexOf('?')
        val path = if (separator >= 0) rawPath.substring(0, separator) else rawPath
        val query = if (separator >= 0) parseQuery(rawPath.substring(separator + 1)) else emptyMap()

        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        return ProxyRequest(method, path, query, headers)
    }

    private fun readLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        var byte = input.read()
        if (byte < 0) return null
        while (byte >= 0 && byte != '\n'.code) {
            if (byte != '\r'.code) {
                buffer.write(byte)
                if (buffer.size() > MAX_HEADER_BYTES) return null
            }
            byte = input.read()
        }
        return buffer.toString("UTF-8")
    }

    private fun parseQuery(raw: String): Map<String, String> = buildMap {
        raw.split('&').forEach { pair ->
            val separator = pair.indexOf('=')
            if (separator > 0) put(pair.substring(0, separator), pair.substring(separator + 1))
        }
    }

    private fun readAll(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (out.size() + count > MAX_PLAYLIST_BYTES) return null
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }

    private fun looksLikePlaylistPath(target: URI): Boolean {
        val path = target.path.orEmpty().lowercase()
        return path.endsWith(".m3u8") || path.endsWith(".m3u")
    }

    private fun startsWithPlaylistHeader(bytes: ByteArray, count: Int): Boolean {
        if (count >= PREFIX_BYTES.size && bytes.copyOfRange(0, PREFIX_BYTES.size).contentEquals(PREFIX_BYTES)) {
            return true
        }
        val bomSize = 3
        return count >= PREFIX_SIZE &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() &&
            bytes.copyOfRange(bomSize, bomSize + PREFIX_BYTES.size).contentEquals(PREFIX_BYTES)
    }

    private fun reason(status: Int): String = when (status) {
        200 -> "OK"
        204 -> "No Content"
        206 -> "Partial Content"
        400 -> "Bad Request"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        502 -> "Bad Gateway"
        504 -> "Gateway Timeout"
        else -> "Response"
    }

    private sealed interface FetchResult {
        class Success(val target: URI, val response: Response) : FetchResult
        class Failure(val status: Int, val message: String) : FetchResult
    }

    private data class ProxyRequest(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
    )

    companion object {
        const val CONNECT_TIMEOUT_MS = 20_000

        /** Generous read window: live segments can be slow, short timeouts abort healthy streams. */
        const val READ_TIMEOUT_MS = 60_000

        private const val LOOPBACK = "127.0.0.1"
        private const val BACKLOG = 50
        private const val MAX_REDIRECTS = 5
        private const val MAX_HEADER_BYTES = 8 * 1024
        private const val MAX_PLAYLIST_BYTES = 8 * 1024 * 1024
        private const val MAX_COOKIES_PER_HOST = 48
        private const val MAX_COOKIE_HOSTS = 16
        private const val MAX_CONNECTIONS = 32
        private const val COPY_BUFFER_BYTES = 64 * 1024
        private const val PLAYLIST_CONTENT_TYPE = "application/vnd.apple.mpegurl"
        private const val DEFAULT_UA = "StreamLine/1.0"

        private val PREFIX_BYTES = "#EXTM3U".toByteArray(Charsets.US_ASCII)
        private const val PREFIX_SIZE = 10

        private val HOP_BY_HOP_HEADERS = listOf(
            "Host",
            "Connection",
            "Proxy-Connection",
            "Keep-Alive",
            "Transfer-Encoding",
            "Content-Length",
            "Accept-Encoding",
            "Range",
            "Cookie",
        )

        private val CORS_HEADERS = listOf(
            "Access-Control-Allow-Origin" to "*",
            "Access-Control-Allow-Methods" to "GET, HEAD, OPTIONS",
            "Access-Control-Allow-Headers" to "*",
            "Access-Control-Expose-Headers" to "Content-Length, Content-Range, Accept-Ranges",
        )
    }
}
