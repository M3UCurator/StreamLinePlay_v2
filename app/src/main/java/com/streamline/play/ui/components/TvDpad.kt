package com.streamline.play.ui.components

import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager

/**
 * Single D-pad router shared by a window. Arrow keys are consumed here instead of relying on
 * per-surface handlers or the platform focus search, which is unreliable on low-end TV boxes.
 */
class TvDpad(private val focusManager: FocusManager) {

    private val throttleClock = LongArray(1)
    private var heldDirection: FocusDirection? = null
    private val horizontalOwners = HashSet<Any>()
    private val focusedTargets = HashSet<Any>()

    fun registerHorizontal(owner: Any) {
        horizontalOwners.add(owner)
    }

    fun unregisterHorizontal(owner: Any) {
        horizontalOwners.remove(owner)
    }

    fun setFocused(owner: Any, focused: Boolean) {
        if (focused) focusedTargets.add(owner) else focusedTargets.remove(owner)
    }

    fun hasFocus(): Boolean = focusedTargets.isNotEmpty()

    /** Recovers a lost focus owner by entering the first focusable element in this window. */
    fun focusFirst(): Boolean = focusManager.moveFocus(FocusDirection.Down)

    fun handle(event: KeyEvent): Boolean {
        val direction = event.key.dpadDirection() ?: return false
        val horizontal = direction == FocusDirection.Left || direction == FocusDirection.Right
        if (horizontal && horizontalOwners.isNotEmpty()) return false
        when (event.type) {
            KeyEventType.KeyDown -> {
                val now = SystemClock.uptimeMillis()
                if (heldDirection == direction && now - throttleClock[0] < REPEAT_THROTTLE_MS) {
                    return true
                }
                heldDirection = direction
                throttleClock[0] = now
                var moved = focusManager.moveFocus(direction)
                if (!moved && focusedTargets.isEmpty()) moved = focusFirst()
                log { "dpad $direction moved=$moved focused=${focusedTargets.size}" }
                return true
            }

            KeyEventType.KeyUp -> {
                if (heldDirection == direction) heldDirection = null
                return true
            }

            else -> return false
        }
    }

    private inline fun log(message: () -> String) {
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, message())
    }

    companion object {
        const val TAG = "StreamLineDpad"
        private const val REPEAT_THROTTLE_MS = 50L
    }
}

val LocalTvDpad = staticCompositionLocalOf<TvDpad?> { null }

@Composable
fun rememberTvDpad(): TvDpad {
    val focusManager = LocalFocusManager.current
    return remember(focusManager) { TvDpad(focusManager) }
}

fun Modifier.dpadKeys(dpad: TvDpad?): Modifier =
    if (dpad == null) this else this.onPreviewKeyEvent { event -> dpad.handle(event) }

fun Key.dpadDirection(): FocusDirection? = when (this) {
    Key.DirectionUp -> FocusDirection.Up
    Key.DirectionDown -> FocusDirection.Down
    Key.DirectionLeft -> FocusDirection.Left
    Key.DirectionRight -> FocusDirection.Right
    else -> null
}

fun Key.isActivationKey(): Boolean =
    this == Key.DirectionCenter || this == Key.Enter || this == Key.NumPadEnter || this == Key.Spacebar
