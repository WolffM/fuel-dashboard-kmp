package com.angussoftware.fueldashboard.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PairingLink parsing — the receiver's first line of defense: a malformed
 * or foreign link must parse to null (never fetch, never crash), and the
 * consent gate must classify transport correctly.
 */
class PairingLinkTest {

    @Test
    fun parsesTokenUrlAndAutostart() {
        val link = PairingLink.parse(
            "fueldashboard://import?token=abc-123&url=http%3A%2F%2F192.168.1.5%3A8322&autostart=1",
        )
        assertEquals("abc-123", link?.token)
        assertEquals("http://192.168.1.5:8322", link?.url)
        assertTrue(link?.autostart == true)
    }

    @Test
    fun parsesUnencodedUrl() {
        val link = PairingLink.parse("fueldashboard://import?token=t&url=http://192.168.1.5:8322")
        assertEquals("http://192.168.1.5:8322", link?.url)
        assertFalse(link?.autostart == true)
    }

    @Test
    fun missingTokenIsInvalid() {
        assertNull(PairingLink.parse("fueldashboard://import?url=http://x"))
    }

    @Test
    fun missingUrlIsInvalid() {
        assertNull(PairingLink.parse("fueldashboard://import?token=t"))
    }

    @Test
    fun wrongSchemeIsIgnored() {
        assertNull(PairingLink.parse("other://import?token=t&url=http://x"))
    }

    @Test
    fun wrongHostIsIgnored() {
        assertNull(PairingLink.parse("fueldashboard://export?token=t&url=http://x"))
    }

    @Test
    fun linkWithoutQueryIsInvalid() {
        assertNull(PairingLink.parse("fueldashboard://import"))
    }

    @Test
    fun schemeMatchIsCaseInsensitive() {
        val link = PairingLink.parse("FUELDASHBOARD://import?token=t&url=http://x")
        assertEquals("t", link?.token)
    }

    @Test
    fun autostartAcceptsOneAndTrueOnly() {
        assertTrue(PairingLink.parse("fueldashboard://import?token=t&url=http://x&autostart=1")?.autostart == true)
        assertTrue(PairingLink.parse("fueldashboard://import?token=t&url=http://x&autostart=true")?.autostart == true)
        assertFalse(PairingLink.parse("fueldashboard://import?token=t&url=http://x&autostart=0")?.autostart == true)
        assertFalse(PairingLink.parse("fueldashboard://import?token=t&url=http://x")?.autostart == true)
    }

    @Test
    fun malformedPercentSequenceKeepsLiteralAndDoesNotThrow() {
        // %zz is not a valid escape — kept literal, never a crash: hostile
        // links must not take down the receiver.
        val link = PairingLink.parse("fueldashboard://import?token=t&url=http://x%zz%2F")
        assertEquals("http://x%zz/", link?.url)
    }

    @Test
    fun httpsLinksNeedNoConsent() {
        assertFalse(PairingLink(token = "t", url = "https://fuel.example.com").requiresLanConsent())
    }

    @Test
    fun loopbackHttpNeedsNoConsent() {
        assertFalse(PairingLink(token = "t", url = "http://localhost:8322").requiresLanConsent())
        assertFalse(PairingLink(token = "t", url = "http://127.0.0.1:8322").requiresLanConsent())
        assertFalse(PairingLink(token = "t", url = "http://[::1]:8322").requiresLanConsent())
    }

    @Test
    fun plainHttpLanNeedsConsent() {
        assertTrue(PairingLink(token = "t", url = "http://192.168.1.5:8322").requiresLanConsent())
    }
}
