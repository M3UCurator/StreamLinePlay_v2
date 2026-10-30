package com.streamline.play.data.epg

import android.util.Xml
import com.streamline.play.data.model.EpgProgram
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.util.Locale
import java.util.TimeZone

class XmltvParser {

    data class Result(
        val programs: List<EpgProgram>,
        val channelNames: Map<String, String>,
        val channelOrder: List<String>,
    )

    fun parse(input: InputStream): Result {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)

        val programs = mutableListOf<EpgProgram>()
        val channelNames = LinkedHashMap<String, String>()
        val order = mutableListOf<String>()

        var currentChannel: String? = null
        var programmeChannel: String? = null
        var programmeStart = 0L
        var programmeStop = 0L
        var title: String? = null
        var description: String? = null
        var icon: String? = null

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (parser.name.lowercase(Locale.US)) {
                        "channel" -> {
                            currentChannel = parser.getAttributeValue(null, "id")
                            currentChannel?.let { if (it !in order) order += it }
                        }

                        "display-name" -> {
                            val channelId = currentChannel
                            val text = parser.nextText().trim()
                            if (!channelId.isNullOrBlank() && text.isNotBlank() &&
                                !channelNames.containsKey(channelId)
                            ) {
                                channelNames[channelId] = text
                            }
                        }

                        "programme" -> {
                            programmeChannel = parser.getAttributeValue(null, "channel")
                            programmeStart = XmltvDate.parse(parser.getAttributeValue(null, "start")) ?: 0L
                            programmeStop = XmltvDate.parse(parser.getAttributeValue(null, "stop")) ?: 0L
                            title = null
                            description = null
                            icon = parser.getAttributeValue(null, "icon")
                            if (programmeChannel != null && programmeChannel !in order) {
                                order += programmeChannel!!
                            }
                        }

                        "title" -> if (programmeChannel != null) {
                            title = parser.nextText().trim()
                        }

                        "desc" -> if (programmeChannel != null) {
                            val text = parser.nextText().trim()
                            if (description.isNullOrBlank()) description = text
                        }

                        "icon" -> if (programmeChannel != null && icon == null) {
                            icon = parser.getAttributeValue(null, "src")
                        }
                    }
                }

                XmlPullParser.END_TAG -> {
                    if (parser.name.equals("programme", ignoreCase = true)) {
                        val channel = programmeChannel
                        val name = title
                        if (channel != null && name != null && programmeStop > programmeStart) {
                            programs += EpgProgram(
                                channelId = channel,
                                title = name,
                                description = description.orEmpty(),
                                startMs = programmeStart,
                                endMs = programmeStop,
                                icon = icon,
                            )
                        }
                        programmeChannel = null
                    } else if (parser.name.equals("channel", ignoreCase = true)) {
                        currentChannel = null
                    }
                }
            }
            event = parser.next()
        }

        programs.sortBy { it.startMs }
        return Result(programs, channelNames, order)
    }
}

object XmltvDate {
    private val DATE = Regex(
        "^(\\d{4})-?(\\d{2})-?(\\d{2})[T ]?(\\d{2}):?(\\d{2})(?::?(\\d{2}))?\\s*([+-]\\d{2}:?\\d{2})?\\s*$",
    )

    fun parse(raw: String?): Long? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        val match = DATE.matchEntire(value) ?: return null
        val (year, month, day, hour, minute) = match.destructured
        val second = match.groupValues[6].ifBlank { "0" }
        val offset = match.groupValues[7]
        val calendar = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(
                year.toInt(),
                month.toInt() - 1,
                day.toInt(),
                hour.toInt(),
                minute.toInt(),
                second.toIntOrNull() ?: 0,
            )
        }
        val localMs = runCatching { calendar.timeInMillis }.getOrNull() ?: return null
        val offsetMinutes = parseOffsetMinutes(offset)
        return localMs - offsetMinutes * 60_000L
    }

    /** Accepts +HHMM, -HH:MM and providers that omit the sign space (offset ±12h). */
    private fun parseOffsetMinutes(raw: String): Int {
        if (raw.isBlank()) return 0
        val digits = raw.replace(":", "")
        val sign = if (digits.startsWith("-")) -1 else 1
        val body = digits.removePrefix("+").removePrefix("-")
        if (body.length != 4) return 0
        val hours = body.substring(0, 2).toIntOrNull() ?: return 0
        val minutes = body.substring(2, 4).toIntOrNull() ?: return 0
        if (hours > 14 || minutes > 59) return 0
        return sign * (hours * 60 + minutes)
    }
}
