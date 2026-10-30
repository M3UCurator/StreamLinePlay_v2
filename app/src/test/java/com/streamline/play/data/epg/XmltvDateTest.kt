package com.streamline.play.data.epg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class XmltvDateTest {

    @Test
    fun compactDateWithOffset() {
        // 14:00 +0200 == 12:00 UTC
        assertEquals(1792497600000L, XmltvDate.parse("20261020140000 +0200"))
    }

    @Test
    fun isoDateWithColonOffset() {
        assertEquals(1792497600000L, XmltvDate.parse("2026-10-20 14:00:00 +02:00"))
    }

    @Test
    fun offsetIsSubtractedWhenNegative() {
        // 12:00 -0500 == 17:00 UTC
        assertEquals(1792515600000L, XmltvDate.parse("20261020120000 -0500"))
    }

    @Test
    fun missingOffsetIsTreatedAsUtc() {
        assertEquals(1792497600000L, XmltvDate.parse("20261020120000"))
    }

    @Test
    fun malformedInputReturnsNull() {
        assertNull(XmltvDate.parse(null))
        assertNull(XmltvDate.parse(""))
        assertNull(XmltvDate.parse("not a date"))
    }
}
