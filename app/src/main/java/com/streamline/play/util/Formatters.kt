package com.streamline.play.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

fun Long.asClock(): String = SimpleDateFormat("HH:mm", Locale.US).format(Date(this))

fun Long.asClockSeconds(): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(this))

fun Long.asDayDate(): String = SimpleDateFormat("EEE, d MMM", Locale.US).format(Date(this))

fun Long.asShortDate(): String = SimpleDateFormat("d MMM", Locale.US).format(Date(this))

fun Long.asFullDate(): String = SimpleDateFormat("EEEE, d MMMM", Locale.US).format(Date(this))

fun Long.asMediaClock(): String {
    val safe = coerceAtLeast(0)
    val hours = TimeUnit.MILLISECONDS.toHours(safe)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(safe) % 60
    val seconds = TimeUnit.MILLISECONDS.toSeconds(safe) % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}

fun formatBytes(bytes: Long): String {
    if (bytes < 1_024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.0f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
    return String.format(Locale.US, "%.2f GB", mb / 1024.0)
}

fun formatBitrate(bitsPerSecond: Int): String {
    if (bitsPerSecond <= 0) return "—"
    val kbps = bitsPerSecond / 1000.0
    return if (kbps >= 1000) {
        String.format(Locale.US, "%.1f Mbps", kbps / 1000.0)
    } else {
        String.format(Locale.US, "%.0f kbps", kbps)
    }
}

fun formatFrameRate(frameRate: Float): String =
    if (frameRate <= 0f) "—" else String.format(Locale.US, "%.0f fps", frameRate)

fun String.initials(): String {
    val parts = trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    return when {
        parts.isEmpty() -> "TV"
        parts.size == 1 -> parts[0].take(2).uppercase(Locale.US)
        else -> (parts[0].take(1) + parts[1].take(1)).uppercase(Locale.US)
    }
}
