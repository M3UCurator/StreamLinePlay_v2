package com.streamline.play.data.model

import java.net.URLEncoder
import java.util.UUID

enum class SourceKind(val label: String) {
    M3U_URL("M3U / M3U8 URL"),
    M3U_FILE("Local playlist file"),
    XTREAM("Xtream Codes"),
    STALKER("MAG / Stalker portal"),
}

data class SourceConfig(
    val id: String = UUID.randomUUID().toString(),
    val kind: SourceKind = SourceKind.M3U_URL,
    val name: String = "New source",
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val mac: String = "",
    val epgUrl: String = "",
) {
    fun encodedCredentials(): String = URLEncoder.encode(username, "UTF-8") +
        "&password=" + URLEncoder.encode(password, "UTF-8")
}
