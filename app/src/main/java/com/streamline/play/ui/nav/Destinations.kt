package com.streamline.play.ui.nav

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

sealed interface RootTab : NavKey {
    val label: String
}

@Serializable
data object HomeKey : RootTab {
    override val label = "Home"
}

@Serializable
data object LiveKey : RootTab {
    override val label = "Live TV"
}

@Serializable
data object MoviesKey : RootTab {
    override val label = "Movies"
}

@Serializable
data object SeriesKey : RootTab {
    override val label = "Series"
}

@Serializable
data object GuideKey : RootTab {
    override val label = "Guide"
}

@Serializable
data object SettingsKey : RootTab {
    override val label = "Settings"
}

@Serializable
data class DetailKey(val itemId: String) : NavKey

@Serializable
data class PlayerKey(
    val itemId: String,
    val streamUrl: String? = null,
    val catchupStartMs: Long? = null,
    val catchupEndMs: Long? = null,
) : NavKey

val rootTabs: List<RootTab> = listOf(
    HomeKey,
    LiveKey,
    MoviesKey,
    SeriesKey,
    GuideKey,
    SettingsKey,
)
