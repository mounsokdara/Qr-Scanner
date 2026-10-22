package com.localscanner.qr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkSafetyTest {
    @Test fun plainHttpsIsClean() {
        assertTrue(LinkSafety.check("https://example.com/path?q=1").isEmpty())
        assertTrue(LinkSafety.check("www.example.com").isEmpty())
    }

    @Test fun httpIsInsecure() {
        assertEquals(setOf(LinkRisk.INSECURE), LinkSafety.check("http://example.com"))
    }

    @Test fun ipHostsAreFlagged() {
        assertTrue(LinkRisk.IP_HOST in LinkSafety.check("https://192.168.1.10/login"))
        assertTrue(LinkRisk.IP_HOST in LinkSafety.check("https://[::1]/x"))
        assertTrue(LinkRisk.IP_HOST in LinkSafety.check("http://3232235777/"))
    }

    @Test fun punycodeAndUnicodeHostsAreFlagged() {
        assertTrue(LinkRisk.PUNYCODE in LinkSafety.check("https://xn--pple-43d.com"))
        assertTrue(LinkRisk.NON_ASCII_HOST in LinkSafety.check("https://exämple.com"))
    }

    @Test fun shortenersAreFlagged() {
        assertTrue(LinkRisk.SHORTENER in LinkSafety.check("https://bit.ly/abc123"))
        assertTrue(LinkRisk.SHORTENER in LinkSafety.check("https://go.t.co/abc"))
    }

    @Test fun credentialTrickIsFlagged() {
        val risks = LinkSafety.check("https://google.com@evil.example/login")
        assertTrue(LinkRisk.CREDENTIALS in risks)
    }

    @Test fun nonHttpAndGarbageAreIgnored() {
        assertTrue(LinkSafety.check("tel:+1555").isEmpty())
        assertTrue(LinkSafety.check("not a url").isEmpty())
        assertTrue(LinkSafety.check("").isEmpty())
    }
}
