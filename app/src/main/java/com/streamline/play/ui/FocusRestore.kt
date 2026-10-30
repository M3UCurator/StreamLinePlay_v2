package com.streamline.play.ui

/** Requests that a catalog screen puts the remote cursor back on [itemId] after a return. */
data class FocusRestore(
    val itemId: String,
    val token: Int,
)
