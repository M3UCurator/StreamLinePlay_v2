package com.streamline.play.player

import androidx.media3.effect.ConvolutionFunction1D
import androidx.media3.effect.SeparableConvolution
import kotlin.math.abs

/**
 * Three-tap unsharp mask evaluated on the GPU as a separable convolution.
 * Applied after a Lanczos upscale it restores the edge contrast that interpolation softens.
 */
class UltraSharpEffect(private val amount: Float = DEFAULT_AMOUNT) : SeparableConvolution() {

    override fun getConvolution(presentationTimeUs: Long): ConvolutionFunction1D =
        Kernel(amount.coerceIn(0f, 1f))

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = amount <= 0f

    private class Kernel(private val amount: Float) : ConvolutionFunction1D {
        override fun domainStart(): Float = -1f

        override fun domainEnd(): Float = 1f

        override fun value(samplePosition: Float): Float = when {
            abs(samplePosition) < 0.5f -> 1f + 2f * amount
            abs(samplePosition) < 1.5f -> -amount
            else -> 0f
        }
    }

    companion object {
        const val DEFAULT_AMOUNT = 0.22f
    }
}
