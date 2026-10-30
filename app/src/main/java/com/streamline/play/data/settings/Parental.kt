package com.streamline.play.data.settings

import com.streamline.play.data.model.MediaItem
import java.security.MessageDigest

/** Parental gate helpers: adult keyword detection, group locks and salted PIN hashing. */
object Parental {

    private val ADULT = Regex(
        "(?i)\\b(?:adult|adults|xxx|x-rated|porn|pornography|erotic|erotica|playboy|hustler|" +
            "brazzers|sex|sexual|onlyfans|18\\+|18 plus|adultos|adult channels|adult movies)\\b",
    )

    fun looksAdult(group: String?, name: String? = null): Boolean =
        ADULT.containsMatchIn(group.orEmpty()) || ADULT.containsMatchIn(name.orEmpty())

    fun isLocked(item: MediaItem, settings: AppSettings): Boolean {
        if (!settings.parentalLockEnabled) return false
        if (item.displayGroup in settings.parentalLockedGroups) return true
        return settings.parentalAutoLockAdult && looksAdult(item.displayGroup, item.name)
    }

    fun groupIsLocked(group: String, settings: AppSettings): Boolean {
        if (!settings.parentalLockEnabled) return false
        if (group in settings.parentalLockedGroups) return true
        return settings.parentalAutoLockAdult && looksAdult(group)
    }

    fun hashPin(pin: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest("streamline-play:$pin".toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
}
