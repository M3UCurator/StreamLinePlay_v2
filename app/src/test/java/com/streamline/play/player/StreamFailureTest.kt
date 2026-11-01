package com.streamline.play.player

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class StreamFailureTest {

    private fun error(code: Int, cause: Throwable? = null) =
        PlaybackException("boom", cause, code)

    @Test
    fun unspecifiedIoErrorIsTransient() {
        assertTrue(
            error(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, IOException("reset"))
                .isTransientIoError(),
        )
    }

    @Test
    fun connectionTimeoutIsTransient() {
        assertTrue(
            error(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT).isTransientIoError(),
        )
    }

    @Test
    fun socketTimeoutCauseIsTransient() {
        assertTrue(
            error(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, SocketTimeoutException())
                .isTransientIoError(),
        )
    }

    @Test
    fun unknownHostCauseIsTransient() {
        assertTrue(
            error(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, UnknownHostException("cdn"))
                .isTransientIoError(),
        )
    }

    @Test
    fun decoderFailureIsNotTransient() {
        assertFalse(error(PlaybackException.ERROR_CODE_DECODING_FAILED).isTransientIoError())
    }

    @Test
    fun unspecifiedIoErrorIsReportedAsDroppedConnection() {
        val failure = error(PlaybackException.ERROR_CODE_IO_UNSPECIFIED).streamFailure()

        assertEquals("Connection dropped while loading the stream.", failure.message)
    }
}
