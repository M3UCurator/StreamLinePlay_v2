package com.streamline.play.data.net

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPOutputStream
import kotlin.concurrent.thread

class HttpFetcherTest {

    private lateinit var upstream: Upstream
    private val fetcher = HttpFetcher()

    @Before
    fun setUp() {
        upstream = Upstream()
        upstream.route("/start", Reply.redirect("/plain"))
        upstream.route("/chain", Reply.redirect("/start"))
        upstream.route("/relative", Reply.redirect("final"))
        upstream.route("/plain", Reply.ok("redirected"))
        upstream.route("/final", Reply.ok("relative"))
        upstream.route("/gzip", Reply.gzip("compressed payload"))
        upstream.route("/loop", Reply.redirect("/loop"))
    }

    @After
    fun tearDown() {
        upstream.stop()
    }

    private fun url(path: String) = "http://127.0.0.1:${upstream.port}$path"

    @Test
    fun followsAbsoluteAndChainedRedirects() = runBlocking {
        assertEquals("redirected", fetcher.getText(url("/start")))
        assertEquals("redirected", fetcher.getText(url("/chain")))
    }

    @Test
    fun followsRelativeRedirectLocations() = runBlocking {
        assertEquals("relative", fetcher.getText(url("/relative")))
    }

    @Test
    fun decodesTransparentGzipBodies() = runBlocking {
        assertEquals("compressed payload", fetcher.getText(url("/gzip")))
    }

    @Test
    fun failsOnRedirectLoops() = runBlocking {
        val error = runCatching { fetcher.getBytes(url("/loop"), attempts = 1) }.exceptionOrNull()

        assertTrue(error is java.io.IOException)
        assertTrue(error?.message.orEmpty().contains("too many follow-up requests", ignoreCase = true))
    }

    @Test
    fun surfacesHttpStatusForCallers() = runBlocking {
        val error = runCatching { fetcher.getBytes(url("/missing"), attempts = 1) }.exceptionOrNull()

        assertTrue(error?.message.orEmpty().startsWith("HTTP 404"))
    }

    @Test
    fun downloadsThroughRedirectToFile() = runBlocking {
        val target = File.createTempFile("fetcher", ".txt")
        try {
            fetcher.download(url("/start"), emptyMap(), target)
            assertEquals("redirected", target.readText())
        } finally {
            target.delete()
        }
    }

    private data class Reply(val status: Int, val body: ByteArray, val headers: Map<String, String>) {
        companion object {
            fun ok(text: String) = Reply(200, text.toByteArray(), mapOf("Content-Type" to "text/plain"))

            fun redirect(location: String) = Reply(302, ByteArray(0), mapOf("Location" to location))

            fun gzip(text: String): Reply {
                val out = ByteArrayOutputStream()
                GZIPOutputStream(out).use { it.write(text.toByteArray()) }
                return Reply(200, out.toByteArray(), mapOf("Content-Encoding" to "gzip"))
            }
        }
    }

    private class Upstream {

        private val socket = ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"))
        private val routes = ConcurrentHashMap<String, Reply>()

        val port: Int get() = socket.localPort

        init {
            thread(name = "fetcher-upstream", isDaemon = true) {
                while (!socket.isClosed) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    thread(name = "fetcher-upstream-io", isDaemon = true) { serve(client) }
                }
            }
        }

        fun route(path: String, reply: Reply) {
            routes[path] = reply
        }

        fun stop() {
            runCatching { socket.close() }
        }

        private fun serve(client: Socket) {
            client.use {
                val reader = client.getInputStream().bufferedReader()
                val requestLine = reader.readLine() ?: return
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }
                val path = requestLine.split(' ').getOrNull(1).orEmpty()
                val reply = routes[path] ?: Reply(404, ByteArray(0), emptyMap())
                val head = buildString {
                    append("HTTP/1.1 ").append(reply.status).append(" Status\r\n")
                    reply.headers.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
                    if (reply.status !in 300..399) append("Content-Length: ").append(reply.body.size).append("\r\n")
                    append("Connection: close\r\n\r\n")
                }
                val output = client.getOutputStream()
                output.write(head.toByteArray())
                if (reply.status !in 300..399) output.write(reply.body)
                output.flush()
            }
        }
    }
}
