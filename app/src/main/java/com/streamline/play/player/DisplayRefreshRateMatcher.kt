package com.streamline.play.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.Display
import android.view.Surface
import android.view.SurfaceView
import kotlin.math.abs

/** Switches the TV display mode to the refresh rate that matches the video frame rate. */
object DisplayRefreshRateMatcher {

    private const val TOLERANCE_HZ = 0.08f
    private const val DOUBLE_RATE_PENALTY = 1.05f

    data class Match(val modeId: Int, val refreshRateHz: Float, val width: Int, val height: Int)

    @Suppress("DEPRECATION")
    fun apply(activity: Activity, frameRate: Float): Match? {
        if (frameRate < 1f) return null
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.display
        } else {
            activity.windowManager.defaultDisplay
        } ?: return null
        val match = chooseMode(display, frameRate) ?: return null
        val attrs = activity.window.attributes
        if (attrs.preferredDisplayModeId != match.modeId) {
            attrs.preferredDisplayModeId = match.modeId
            activity.window.attributes = attrs
        }
        return match
    }

    fun restore(activity: Activity) {
        val attrs = activity.window.attributes
        if (attrs.preferredDisplayModeId != 0) {
            attrs.preferredDisplayModeId = 0
            activity.window.attributes = attrs
        }
    }

    fun chooseMode(display: Display, frameRate: Float): Match? {
        val targets = buildList {
            add(frameRate)
            if (frameRate < 55f) add(frameRate * 2f)
        }
        var best: Match? = null
        var bestScore = Float.MAX_VALUE
        for (mode in display.supportedModes) {
            val rates = buildList {
                add(mode.refreshRate)
                runCatching { addAll(mode.alternativeRefreshRates.toList()) }
            }
            for ((targetIndex, target) in targets.withIndex()) {
                for (rate in rates) {
                    val score = abs(rate - target) * if (targetIndex == 0) 1f else DOUBLE_RATE_PENALTY
                    if (score > TOLERANCE_HZ) continue
                    val pixels = mode.physicalWidth * mode.physicalHeight
                    val currentBest = best
                    val currentPixels = currentBest?.let { it.width * it.height } ?: 0
                    if (score < bestScore - 0.001f ||
                        (abs(score - bestScore) <= 0.001f && pixels > currentPixels)
                    ) {
                        bestScore = score
                        best = Match(mode.modeId, rate, mode.physicalWidth, mode.physicalHeight)
                    }
                }
            }
        }
        return best
    }

    /** Hints the display pipeline even when the window cannot switch modes. */
    fun applySurfaceFrameRate(surfaceView: SurfaceView, frameRate: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || frameRate <= 0f) return
        runCatching {
            val surface = surfaceView.holder.surface
            if (surface != null && surface.isValid) {
                surface.setFrameRate(frameRate, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
            }
        }
    }
}

fun Context.findHostActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
