package com.localscanner.qr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class ParsersTest {
    private val utc = TimeZone.getTimeZone("UTC")

    @Test fun kindDetection() {
        assertEquals(ScanKind.WIFI, Parsers.kindOf("WIFI:T:WPA;S:x;P:y;;"))
        assertEquals(ScanKind.CONTACT, Parsers.kindOf("begin:vcard\nFN:A"))
        assertEquals(ScanKind.EVENT, Parsers.kindOf("BEGIN:VEVENT\nEND:VEVENT"))
        assertEquals(ScanKind.EVENT, Parsers.kindOf("BEGIN:VCALENDAR\nEND:VCALENDAR"))
        assertEquals(ScanKind.EMAIL, Parsers.kindOf("mailto:a@b.c"))
        assertEquals(ScanKind.SMS, Parsers.kindOf("SMSTO:123:hi"))
        assertEquals(ScanKind.PHONE, Parsers.kindOf("tel:+123"))
        assertEquals(ScanKind.GEO, Parsers.kindOf("geo:1,2"))
        assertEquals(ScanKind.URL, Parsers.kindOf("https://x.y"))
        assertEquals(ScanKind.URL, Parsers.kindOf("www.x.y"))
        assertEquals(ScanKind.TEXT, Parsers.kindOf("5901234123457"))
    }

    @Test fun parsesVCard() {
        val card = """
            BEGIN:VCARD
            VERSION:3.0
            N:Doe;John;;;
            FN:John Doe
            ORG:Acme\, Inc.;R&D
            TEL;TYPE=CELL:+1 555 0100
            TEL:+1 555 0101
            EMAIL:john@example.com
            ADR:;;1 Main St\, Apt 2;Springfield;;12345;US
            URL:https://example.com/a,b
            NOTE:line1\nline2
            END:VCARD
        """.trimIndent()
        val info = Parsers.parseVCard(card)
        assertNotNull(info)
        info!!
        assertEquals("John Doe", info.name)
        assertEquals("Acme, Inc., R&D", info.organization)
        assertEquals(listOf("+1 555 0100", "+1 555 0101"), info.phones)
        assertEquals(listOf("john@example.com"), info.emails)
        assertTrue(info.address.startsWith("1 Main St, Apt 2"))
        assertEquals("https://example.com/a,b", info.website)
        assertEquals("line1\nline2", info.note)
    }

    @Test fun vCardFallsBackToStructuredName() {
        val info = Parsers.parseVCard("BEGIN:VCARD\nN:Doe;Jane;;;\nEND:VCARD")
        assertEquals("Jane Doe", info?.name)
    }

    @Test fun vCardUnfoldsLongLines() {
        val info = Parsers.parseVCard("BEGIN:VCARD\nFN:Very Long\n  Name\nEND:VCARD")
        assertEquals("Very Long Name", info?.name)
    }

    @Test fun nonVCardReturnsNull() {
        assertNull(Parsers.parseVCard("hello"))
        assertNull(Parsers.parseVEvent("hello"))
    }

    @Test fun parsesUtcEvent() {
        val event = Parsers.parseVEvent(
            "BEGIN:VCALENDAR\nVERSION:2.0\nBEGIN:VEVENT\nSUMMARY:Launch\\, party\n" +
                "DTSTART:20261011T210000Z\nDTEND:20261011T223000Z\nLOCATION:HQ\nDESCRIPTION:Cake\nEND:VEVENT\nEND:VCALENDAR",
            utc
        )
        assertNotNull(event)
        event!!
        assertEquals("Launch, party", event.title)
        assertEquals("HQ", event.location)
        assertEquals("Cake", event.description)
        assertEquals(1791752400000L, event.startMillis)
        assertEquals(1791757800000L, event.endMillis)
        assertEquals(false, event.allDay)
    }

    @Test fun parsesLocalAndAllDayDates() {
        val local = Parsers.parseIcsDate("20261011T210000", utc)
        assertEquals(1791752400000L to false, local)
        val day = Parsers.parseIcsDate("20261011", utc)
        assertEquals(1791676800000L to true, day)
        assertNull(Parsers.parseIcsDate("garbage", utc))
        assertNull(Parsers.parseIcsDate("20261341T250000", utc))
    }

    @Test fun roundTripsGeneratedVCard() {
        val text = Payloads.vCard("John Doe", "Acme", "+1 555 0100", "j@x.com", "1 Main St", "https://x.com/a,b", "")
        val info = Parsers.parseVCard(text!!)
        assertEquals("John Doe", info?.name)
        assertEquals("https://x.com/a,b", info?.website)
        assertTrue(text.contains("N:Doe;John;;;"))
    }

    @Test fun detailGivesShortLabels() {
        assertEquals("example.com", Parsers.detail("https://www.example.com/path?q=1"))
        assertEquals("example.com", Parsers.detail("www.example.com"))
        assertEquals("MyNet", Parsers.detail("WIFI:T:WPA;S:MyNet;P:secret;;"))
        assertEquals("My;Net", Parsers.detail("WIFI:T:WPA;S:My\\;Net;P:secret;;"))
        assertEquals("John Doe", Parsers.detail("BEGIN:VCARD\nFN:John Doe\nEND:VCARD"))
        assertEquals("Launch", Parsers.detail("BEGIN:VEVENT\nSUMMARY:Launch\nDTSTART:20261011\nEND:VEVENT"))
        assertEquals("a@b.com", Parsers.detail("mailto:a@b.com?subject=Hi"))
        assertEquals("555", Parsers.detail("smsto:555:hello"))
        assertEquals("+123", Parsers.detail("tel:+123"))
        assertEquals("1.5,2.5", Parsers.detail("geo:1.5,2.5?z=3"))
        assertEquals("5901234123457", Parsers.detail("5901234123457"))
    }

    @Test fun detailCollapsesWhitespaceAndEllipsizes() {
        assertEquals("hello world", Parsers.detail("  hello \n  world  "))
        val long = Parsers.detail("x".repeat(100), 10)
        assertEquals(10, long.length)
        assertTrue(long.endsWith("…"))
    }
}
