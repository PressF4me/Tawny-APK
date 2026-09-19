package com.tawny.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

/**
 * What a pairing link is allowed to do to this phone.
 *
 * PairLink takes a link already split by the platform's URI parser, so these
 * tests split with java.net.URI — the same fields, on a JVM, with no Android
 * on the classpath. MainActivity.parsePairing() passes exactly these five
 * pieces from android.net.Uri.
 */
class PairLinkTest {

    /** Stand-in for MainActivity.parsePairing(): split, then apply the rules. */
    private fun parse(
        raw: String,
        preferredRelayHost: String? = null,
        buildRelayHost: String? = null,
        hasRendezvous: Boolean = false
    ): Pairing? {
        val u = try { URI(raw.trim()) } catch (e: Exception) { return null }
        return PairLink.parse(
            scheme = u.scheme,
            host = u.host,
            port = u.port,
            encodedQuery = u.rawQuery,
            encodedFragment = u.rawFragment,
            preferredRelayHost = preferredRelayHost,
            buildRelayHost = buildRelayHost,
            hasRendezvous = hasRendezvous
        )
    }

    // ------------------------------------------------------ the reported link

    private val WEB_LINK = "https://tawny.ainu-anoles.ts.net/#k=GpgOcJxe4H6eAhLZpgZ72w" +
        "&n=Pet+camera&r=viewer&t=NvM7UXwo8X79vagb9VARxQ&c=WXXHeH07nDk&e=1789060818"

    @Test
    fun `web Monitor link parses exactly as reported`() {
        val p = parse(WEB_LINK)!!
        assertNull(p.signal)
        assertEquals("GpgOcJxe4H6eAhLZpgZ72w", p.key)
        assertEquals("Pet camera", p.name)          // n=Pet+camera, plus is a space
        assertEquals("NvM7UXwo8X79vagb9VARxQ", p.token)
        assertEquals("WXXHeH07nDk", p.code)
        assertEquals(1789060818000L, p.expiresAt)
        assertEquals("wss://tawny.ainu-anoles.ts.net", p.relay)
        assertTrue(p.relayTrusted)
    }

    @Test
    fun `a query string on an https link is never read`() {
        // Same parameters, in the shape that lands in every access log.
        assertNull(parse("https://tawny.ainu-anoles.ts.net/?k=GpgOcJxe4H6eAhLZpgZ72w&e=1789060818"))
    }

    // ------------------------------------------------- tawny:// must not change

    @Test
    fun `tawny pair link is unchanged`() {
        val p = parse("tawny://pair?k=GpgOcJxe4H6eAhLZpgZ72w&h=192.168.1.50:8820" +
            "&t=NvM7UXwo8X79vagb9VARxQ&c=WXXHeH07nDk&e=1789060818")!!
        assertEquals("ws://192.168.1.50:8820", p.signal)
        assertEquals("GpgOcJxe4H6eAhLZpgZ72w", p.key)
        assertEquals("Pet camera", p.name)          // no n= at all: the default
        assertEquals("NvM7UXwo8X79vagb9VARxQ", p.token)
        assertEquals("WXXHeH07nDk", p.code)
        assertEquals(1789060818000L, p.expiresAt)
        assertNull(p.relay)                          // never carries one
        assertFalse(p.relayTrusted)
    }

    @Test
    fun `a public IP in h is dropped, not dialled`() {
        // 8.8.8.8 is not a home LAN. With no relay and no rendezvous there is
        // then nothing left to dial, so the link is refused outright.
        assertNull(parse("tawny://pair?k=GpgOcJxe4H6eAhLZpgZ72w&h=8.8.8.8:8820"))
        // With a rendezvous configured, it parses and simply has no LAN path.
        val p = parse("tawny://pair?k=GpgOcJxe4H6eAhLZpgZ72w&h=8.8.8.8:8820", hasRendezvous = true)!!
        assertNull(p.signal)
    }

    @Test
    fun `every private range is accepted`() {
        for (h in listOf("10.0.0.4:8820", "172.16.5.9:8820", "192.168.1.50:8820", "169.254.3.3:8820")) {
            assertEquals("ws://$h", parse("tawny://pair?k=GpgOcJxe4H6eAhLZpgZ72w&h=$h")!!.signal)
        }
    }

    @Test
    fun `a malformed key is refused`() {
        assertNull(parse("tawny://pair?k=short&h=192.168.1.50:8820"))            // too short
        assertNull(parse("tawny://pair?k=has+a+space+in+it+here&h=192.168.1.50:8820"))
        assertNull(parse("tawny://pair?h=192.168.1.50:8820"))                     // no k at all
        assertNull(parse("https://tawny.ainu-anoles.ts.net/#k=nope"))
    }

    @Test
    fun `a malformed token is refused outright`() {
        assertNull(parse("tawny://pair?k=GpgOcJxe4H6eAhLZpgZ72w&h=192.168.1.50:8820&t=no"))
    }

    @Test
    fun `an expiry in the past reads as expired`() {
        val past = parse("$WEB_LINK")!!.copy(expiresAt = 1000L)
        assertTrue(past.expired)
        val future = parse(WEB_LINK)!!.copy(expiresAt = System.currentTimeMillis() + 600_000)
        assertFalse(future.expired)
        // No e= at all means no deadline, not "expired in 1970".
        val none = parse("tawny://pair?k=GpgOcJxe4H6eAhLZpgZ72w&h=192.168.1.50:8820")!!
        assertEquals(0L, none.expiresAt)
        assertFalse(none.expired)
    }

    @Test
    fun `a Viewer clock hours off does not call a fresh code expired`() {
        // The Monitor minted this code ten minutes ahead of its own clock; on a
        // Viewer running two hours fast it reads as long past. The Monitor is
        // the one that refuses a lapsed code, so this must still dial.
        val skewed = parse(WEB_LINK)!!.copy(
            expiresAt = System.currentTimeMillis() - 2 * 60 * 60 * 1000 + 600_000)
        assertFalse(skewed.expired)
        // A code more than a day stale is expired on any plausible clock.
        val stale = parse(WEB_LINK)!!.copy(
            expiresAt = System.currentTimeMillis() - Pairing.CLOCK_SKEW_MS - 60_000)
        assertTrue(stale.expired)
    }

    @Test
    fun `nonsense is refused`() {
        assertNull(parse(""))
        assertNull(parse("not a link at all"))
        assertNull(parse("tawny://notpair?k=GpgOcJxe4H6eAhLZpgZ72w"))
        assertNull(parse("ftp://example.com/#k=GpgOcJxe4H6eAhLZpgZ72w"))
        assertNull(parse("https://tawny.ainu-anoles.ts.net/"))          // empty fragment
        assertNull(parse("https://tawny.ainu-anoles.ts.net/#"))
    }

    // --------------------------------------------------------------- SECURITY

    @Test
    fun `a link from a stranger's origin never gets its host adopted`() {
        val p = parse("https://evil.example/#k=GpgOcJxe4H6eAhLZpgZ72w&e=1789060818")!!
        // Still parsed — the user's own Servers setting can carry it, and
        // refusing to read the link at all would be a worse answer.
        assertEquals("GpgOcJxe4H6eAhLZpgZ72w", p.key)
        assertEquals("wss://evil.example", p.relay)
        // But it does not get to write itself into this phone's settings.
        assertFalse(p.relayTrusted)
    }

    @Test
    fun `rv pointing at a third host is ignored, not adopted`() {
        val p = parse("https://tawny.ainu-anoles.ts.net/#k=GpgOcJxe4H6eAhLZpgZ72w" +
            "&rv=wss%3A%2F%2Fevil.example")!!
        assertEquals("wss://tawny.ainu-anoles.ts.net", p.relay)   // fell back to the origin
        assertTrue(p.relayTrusted)
    }

    @Test
    fun `rv naming the link's own host is honoured`() {
        val p = parse("https://tawny.ainu-anoles.ts.net/#k=GpgOcJxe4H6eAhLZpgZ72w" +
            "&rv=wss%3A%2F%2Ftawny.ainu-anoles.ts.net%3A8443")!!
        assertEquals("wss://tawny.ainu-anoles.ts.net:8443", p.relay)
        assertTrue(p.relayTrusted)
    }

    @Test
    fun `a userinfo trick cannot borrow a trusted name`() {
        // wss://good.ts.net@evil.example has a *host* of evil.example.
        val p = parse("https://evil.example/#k=GpgOcJxe4H6eAhLZpgZ72w" +
            "&rv=wss%3A%2F%2Fgood.ts.net%40evil.example")!!
        assertEquals("wss://evil.example", p.relay)
        assertFalse(p.relayTrusted)
    }

    @Test
    fun `a name that merely contains ts_net is not a tailnet name`() {
        assertFalse(PairLink.relayAdoptable("ts.net.evil.example", null, null))
        assertFalse(PairLink.relayAdoptable("evilts.net", null, null))
        assertFalse(PairLink.relayAdoptable("ts.net", null, null))
        assertTrue(PairLink.relayAdoptable("tawny.ainu-anoles.ts.net", null, null))
        assertTrue(PairLink.relayAdoptable("TAWNY.AINU-ANOLES.TS.NET", null, null))
    }

    @Test
    fun `the relay this install already uses is an anchor, and the port is part of it`() {
        // Bare on both sides — the shipping shape. A web Monitor hands out a
        // host with no port and the Servers screen holds one with no port.
        assertTrue(PairLink.relayAdoptable("relay.example.net", "relay.example.net", null))
        assertTrue(PairLink.relayAdoptable("worker.example.dev", null, "worker.example.dev"))
        // Bare and :443 are the same endpoint written two ways.
        assertTrue(PairLink.relayAdoptable("relay.example.net", "relay.example.net:443", null))
        assertTrue(PairLink.relayAdoptable("relay.example.net:443", "relay.example.net", null))
        // The same explicit port on both sides.
        assertTrue(PairLink.relayAdoptable("relay.example.net:8443", "relay.example.net:8443", null))
        // A trusted NAME on an untrusted PORT is not the relay this install
        // uses — it is some other service on that box.
        assertFalse(PairLink.relayAdoptable("relay.example.net:9999", "relay.example.net", null))
        assertFalse(PairLink.relayAdoptable("relay.example.net", "relay.example.net:8443", null))
        assertFalse(PairLink.relayAdoptable("relay.example.net:8443", "relay.example.net:9999", null))
        assertFalse(PairLink.relayAdoptable("relay.example.net:9999", null, "relay.example.net"))
        // ...and a different name is still a different name.
        assertFalse(PairLink.relayAdoptable("other.example.net", "relay.example.net", null))
    }

    @Test
    fun `a port never weakens the reachability anchors`() {
        // .ts.net and RFC1918 are about who can reach the host at all, which no
        // port changes — and the real QR carries no port anyway.
        assertTrue(PairLink.relayAdoptable("tawny.ainu-anoles.ts.net", null, null))
        assertTrue(PairLink.relayAdoptable("tawny.ainu-anoles.ts.net:8443", null, null))
        assertTrue(PairLink.relayAdoptable("192.168.1.50:8099", null, null))
        assertFalse(PairLink.relayAdoptable("evil.example:443", null, null))
    }

    @Test
    fun `THE SHIPPING CASE - a same-origin ts_net QR with no rv is adopted`() {
        // What the confirmed-working v2.0.7 flow actually sends: a web Monitor
        // at https://tawny.<tailnet>.ts.net/ with the parameters in the
        // fragment and no rv= at all.
        val p = parse(WEB_LINK)!!
        assertEquals("wss://tawny.ainu-anoles.ts.net", p.relay)
        assertTrue(p.relayTrusted)
    }

    @Test
    fun `a private address is the same trust as h=`() {
        assertTrue(PairLink.relayAdoptable("192.168.1.50:8099", null, null))
        assertTrue(PairLink.relayAdoptable("10.1.2.3", null, null))
        assertFalse(PairLink.relayAdoptable("8.8.8.8", null, null))
        assertFalse(PairLink.relayAdoptable("", null, null))
        assertFalse(PairLink.relayAdoptable(null, null, null))
    }

    @Test
    fun `a stranger's origin is adopted once the user has pointed at it themselves`() {
        val p = parse("https://relay.example.net/#k=GpgOcJxe4H6eAhLZpgZ72w",
            preferredRelayHost = "relay.example.net")!!
        assertTrue(p.relayTrusted)
    }

    // -------------------------------------------------------- parameter edges

    @Test
    fun `parameter oddities do not derail the parse`() {
        // Trailing &, a doubled &, and a bare name with no =.
        val p = parse("tawny://pair?k=GpgOcJxe4H6eAhLZpgZ72w&&r=viewer&h=192.168.1.50:8820&")!!
        assertEquals("ws://192.168.1.50:8820", p.signal)
        // Percent-encoded name, and a name longer than the 40-char cap.
        assertEquals("Séamus' cat", parse("tawny://pair?k=GpgOcJxe4H6eAhLZpgZ72w" +
            "&h=192.168.1.50:8820&n=S%C3%A9amus%27%20cat")!!.name)
        assertEquals(40, parse("tawny://pair?k=GpgOcJxe4H6eAhLZpgZ72w" +
            "&h=192.168.1.50:8820&n=" + "x".repeat(80))!!.name.length)
        // n= present but empty falls back rather than showing a blank title.
        assertEquals("Pet camera", parse("tawny://pair?k=GpgOcJxe4H6eAhLZpgZ72w" +
            "&h=192.168.1.50:8820&n=")!!.name)
        // First occurrence wins, as Uri.getQueryParameter does.
        assertEquals("ws://192.168.1.50:8820", parse("tawny://pair?k=GpgOcJxe4H6eAhLZpgZ72w" +
            "&h=192.168.1.50:8820&h=10.0.0.9:8820")!!.signal)
        // A junk c= is dropped, it does not fail the link.
        assertNull(parse("tawny://pair?k=GpgOcJxe4H6eAhLZpgZ72w&h=192.168.1.50:8820&c=nope")!!.code)
        // A literal percent survives decoding. (A *truncated* escape cannot be
        // tested through this harness: java.net.URI rejects the whole string,
        // where android.net.Uri is lenient and hands PairLink the raw text —
        // decode() keeps a stray `%` as itself for exactly that reason.)
        assertEquals("100% cat", parse("tawny://pair?k=GpgOcJxe4H6eAhLZpgZ72w" +
            "&h=192.168.1.50:8820&n=100%25+cat")!!.name)
    }

    @Test
    fun `an explicit port on a web link is carried into the relay`() {
        val p = parse("https://tawny.ainu-anoles.ts.net:8443/#k=GpgOcJxe4H6eAhLZpgZ72w")!!
        assertEquals("wss://tawny.ainu-anoles.ts.net:8443", p.relay)
    }
}
