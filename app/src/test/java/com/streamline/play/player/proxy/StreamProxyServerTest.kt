package com.streamline.play.player.proxy

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
import kotlin.concurrent.thread

class StreamProxyServerTest {

    private lateinit var upstream: UpstreamServer
    private lateinit var proxy: StreamProxyServer

    private val segmentBytes = byteArrayOf(0x47, 0x40, 0x11, 0x10)

    @Before
    fun setUp() {
        upstream = UpstreamServer(
            mapOf(
                "/live/master.m3u8" to Response(
                    "application/vnd.apple.mpegurl",
                    "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000\nvariant/index.m3u8\n",
                ),
                "/live/variant/index.m3u8" to Response(
                    "application/vnd.apple.mpegurl",
                    "#EXTM3U\n#EXTINF:4.0,\nseg/one.ts\n",
                ),
                "/live/variant/seg/one.ts" to Response("video/mp2t", segmentBytes),
            ),
        )
        proxy = StreamProxyServer(allowLoopbackTargets = true)
        assertTrue(proxy.start())
        proxy.updateSessionHeaders(mapOf("User-Agent" to "StreamLineTest/1.0"))
    }

    @After
    fun tearDown() {
        proxy.stop()
        upstream.stop()
    }

    @Test
    fun rewritesManifestAndServesSegmentsThroughProxy() {
        val masterText = URL(proxy.urlFor(directUrl("/live/master.m3u8"))).readText()

        assertTrue(masterText.contains("http://127.0.0.1:${proxy.localPort}/p?u="))

        val variantUrl = masterText.lineSequence().first { it.startsWith("http") }
        val variantText = URL(variantUrl).readText()
        val segmentUrl = variantText.lineSequence().first { it.startsWith("http") }

        assertArrayEquals(segmentBytes, URL(segmentUrl).readBytes())
    }

    @Test
    fun rejectsUnsignedRequestsForUnregisteredHosts() {
        val target = URLEncoder.encode(directUrl("/live/master.m3u8"), "UTF-8")
        val connection = URL("http://127.0.0.1:${proxy.localPort}/p?u=$target")
            .openConnection() as HttpURLConnection

        assertEquals(403, connection.responseCode)
    }

    @Test
    fun rejectsTamperedSignedTargets() {
        val signed = proxy.urlFor(directUrl("/live/master.m3u8"))
        val tampered = signed.replace("master.m3u8", "master-other.m3u8")

        val connection = URL(tampered).openConnection() as HttpURLConnection
        assertEquals(403, connection.responseCode)
    }

    @Test
    fun allowsSameOriginBrowserFetches() {
        val target = URLEncoder.encode(directUrl("/live/master.m3u8"), "UTF-8")
        val (status, body) = rawResponse(
            "/p?u=$target",
            mapOf("Sec-Fetch-Site" to "same-origin"),
        )

        assertEquals(200, status)
        assertTrue(body.startsWith("#EXTM3U"))
    }

    @Test
    fun forwardsInjectedHeadersToManifestAndSegments() {
        proxy.updateSessionHeaders(
            mapOf(
                "User-Agent" to "PipeAgent/1.0",
                "Referer" to "https://portal.example/",
                "Authorization" to "Bearer token123",
            ),
        )

        val masterText = URL(proxy.urlFor(directUrl("/live/master.m3u8"))).readText()
        val variantUrl = masterText.lineSequence().first { it.startsWith("http") }
        val variantText = URL(variantUrl).readText()
        val segmentUrl = variantText.lineSequence().first { it.startsWith("http") }
        URL(segmentUrl).readBytes()

        val masterHeaders = upstream.headersFor("/live/master.m3u8")
        assertEquals("PipeAgent/1.0", masterHeaders["user-agent"])
        assertEquals("https://portal.example/", masterHeaders["referer"])
        assertEquals("Bearer token123", masterHeaders["authorization"])

        val segmentHeaders = upstream.headersFor("/live/variant/seg/one.ts")
        assertEquals("PipeAgent/1.0", segmentHeaders["user-agent"])
        assertEquals("Bearer token123", segmentHeaders["authorization"])
    }

    @Test
    fun followsRelativeRedirectBeforeRewriting() {
        upstream.respond(
            "/live/start.m3u8",
            Response("text/html", ByteArray(0), status = 302, location = "master.m3u8"),
        )

        val masterText = URL(proxy.urlFor(directUrl("/live/start.m3u8"))).readText()

        assertTrue(masterText.startsWith("#EXTM3U"))
        assertTrue(masterText.contains("http://127.0.0.1:${proxy.localPort}/p?u="))
    }

    @Test
    fun followsAbsoluteRedirectAndBasesChildUrlsOnTheFinalHop() {
        upstream.respond(
            "/live/short",
            Response("text/html", ByteArray(0), status = 307, location = directUrl("/live/master.m3u8")),
        )

        val masterText = URL(proxy.urlFor(directUrl("/live/short"))).readText()
        val variantUrl = masterText.lineSequence().first { it.startsWith("http") }
        val variantText = URL(variantUrl).readText()
        val segmentUrl = variantText.lineSequence().first { it.startsWith("http") }

        val decoded = java.net.URLDecoder.decode(
            variantUrl.substringAfter("/p?u=").substringBefore("&s="),
            "UTF-8",
        )
        assertEquals(directUrl("/live/variant/index.m3u8"), decoded)
        assertArrayEquals(segmentBytes, URL(segmentUrl).readBytes())
    }

    @Test
    fun failsWhenTheRedirectChainNeverEnds() {
        for (hop in 0..10) {
            upstream.respond(
                "/loop/$hop",
                Response("text/html", ByteArray(0), status = 302, location = "http://127.0.0.1:${upstream.port}/loop/${hop + 1}"),
            )
        }

        val connection = URL(proxy.urlFor(directUrl("/loop/0"))).openConnection() as HttpURLConnection

        assertEquals(502, connection.responseCode)
    }

    @Test
    fun allowsHostsRegisteredByThePlaybackSession() {
        proxy.allowHosts(listOf(directUrl("/live/master.m3u8")))
        val target = URLEncoder.encode(directUrl("/live/master.m3u8"), "UTF-8")
        val connection = URL("http://127.0.0.1:${proxy.localPort}/p?u=$target")
            .openConnection() as HttpURLConnection

        assertEquals(200, connection.responseCode)
    }

    private fun directUrl(path: String) = "http://127.0.0.1:${upstream.port}$path"

    /** HttpURLConnection refuses to send Sec-Fetch-Site, so browser-style requests use a socket. */
    private fun rawResponse(path: String, extraHeaders: Map<String, String>): Pair<Int, String> =
        Socket("127.0.0.1", proxy.localPort).use { socket ->
            val request = buildString {
                append("GET ").append(path).append(" HTTP/1.1\r\n")
                append("Host: 127.0.0.1:").append(proxy.localPort).append("\r\n")
                extraHeaders.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
                append("Connection: close\r\n\r\n")
            }
            socket.getOutputStream().write(request.toByteArray())
            socket.getOutputStream().flush()
            val text = socket.getInputStream().readBytes().toString(Charsets.UTF_8)
            val status = text.lineSequence().first().split(' ')[1].toInt()
            status to text.substringAfter("\r\n\r\n", "")
        }

    private data class Response(
        val contentType: String,
        val body: ByteArray,
        val status: Int = 200,
        val location: String? = null,
    ) {
        constructor(contentType: String, body: String) : this(contentType, body.toByteArray())
    }

    private class UpstreamServer(routes: Map<String, Response>) {

        private val responses = java.util.concurrent.ConcurrentHashMap(routes)
        private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        private val requestHeaders = java.util.concurrent.ConcurrentHashMap<String, Map<String, String>>()
        val port: Int get() = socket.localPort

        fun headersFor(path: String): Map<String, String> = requestHeaders[path].orEmpty()

        fun respond(path: String, response: Response) {
            responses[path] = response
        }

        init {
            thread(name = "test-upstream", isDaemon = true) {
                while (!socket.isClosed) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    thread(name = "test-upstream-io", isDaemon = true) { serve(client) }
                }
            }
        }

        fun stop() {
            runCatching { socket.close() }
        }

        private fun serve(client: Socket) {
            client.use {
                val reader = client.getInputStream().bufferedReader()
                val requestLine = reader.readLine() ?: return
                val headers = LinkedHashMap<String, String>()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    val colon = line.indexOf(':')
                    if (colon > 0) {
                        headers[line.substring(0, colon).trim().lowercase()] =
                            line.substring(colon + 1).trim()
                    }
                }
                val path = requestLine.split(' ').getOrNull(1).orEmpty()
                requestHeaders[path] = headers
                val response = responses[path]
                val output = client.getOutputStream()
                if (response == null) {
                    val body = "missing".toByteArray()
                    output.write(
                        "HTTP/1.1 404 Not Found\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                            .toByteArray(),
                    )
                    output.write(body)
                } else {
                    val builder = StringBuilder("HTTP/1.1 ${response.status} Status\r\n")
                    builder.append("Content-Type: ${response.contentType}\r\n")
                    response.location?.let { builder.append("Location: $it\r\n") }
                    if (response.status !in 300..399) {
                        builder.append("Content-Length: ${response.body.size}\r\n")
                    }
                    builder.append("Connection: close\r\n\r\n")
                    output.write(builder.toString().toByteArray())
                    if (response.status !in 300..399) output.write(response.body)
                }
                output.flush()
            }
        }
    }
}
