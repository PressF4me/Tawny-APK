package com.tawny.monitor

import java.io.ByteArrayOutputStream

/**
 * What a pairing link says, once it has been read and vetted.
 *
 * This used to be a private nested class of MainActivity, alongside a
 * parsePairing() that reached into the activity's preferences for the
 * rendezvous it compares against. Pulling both out here is what makes the
 * rules below testable on a plain JVM — an Activity cannot be stood up in a
 * unit test, and everything a pairing link is allowed to do to this phone is
 * decided in these few dozen lines.
 */
data class Pairing(
    /** `ws://<lan-ip>:<port>` from `h=`, or null for a relay-only pairing. */
    val signal: String?,
    val key: String,
    val name: String,
    val token: String?,
    /** The pairing code from `c`, presented to the Monitor to be let in. */
    val code: String?,
    /** `e` in epoch millis, or 0 when the code carried no deadline. */
    val expiresAt: Long,
    /**
     * The rendezvous the far end is on, when the link carried one: a web
     * Monitor's QR is `https://<host>/#…`, and that host *is* the relay
     * (`wss://<host>`) unless `rv=` names another. null for a plain
     * `tawny://pair` link, which never carries a relay — the app uses its own
     * configured one.
     */
    val relay: String? = null,
    /**
     * Whether [relay] may be written into this phone's own settings without
     * asking. A link names its own relay, so believing it because it said so
     * is circular: any QR code from anywhere would then be able to point this
     * phone's signalling at a server of the sender's choosing. See
     * [PairLink.relayAdoptable] for what does count as an anchor. A relay that
     * is not adoptable is still parsed and still reported — it just does not
     * get to rewrite the Servers screen.
     */
    val relayTrusted: Boolean = false
) {
    val expired get() = expiresAt > 0 && System.currentTimeMillis() > expiresAt
}

/**
 * Reading a pairing link, in one place with no Android in it.
 *
 * Two dialects arrive here. The app's own links are `tawny://pair?…` with the
 * parameters in the query. A web Monitor (Tawny Docker) hands out
 * `https://<host>/#k=…&n=…&r=viewer&t=…&c=…&e=…` with the parameters in the
 * *fragment* — a fragment never reaches a server or an access log, which is
 * the only place the channel key may travel. A query string on an http(s) link
 * is that same secret in the logged shape, and is deliberately never read.
 */
object PairLink {

    private val KEY_RE = Regex("^[A-Za-z0-9_-]{16,64}$")
    private val TOKEN_RE = Regex("^[A-Za-z0-9_-]{8,64}$")
    private val CODE_RE = Regex("^[A-Za-z0-9_-]{8,32}$")
    private val HOST_PORT_RE = Regex("^\\d{1,3}(\\.\\d{1,3}){3}:\\d{2,5}$")

    /**
     * Parse a link that the caller has already split with the platform's URI
     * parser — [android.net.Uri] on the phone, so this code never has to
     * re-implement URL syntax, and the two callers below can hand it strings.
     *
     * [preferredRelayHost] and [buildRelayHost] are the anchors: what the user
     * put on the Servers screen, and what this build was compiled with. They
     * are the only relays this install already trusts, and passing them in
     * rather than reading them from preferences is what lets the rule be
     * tested.
     */
    fun parse(
        scheme: String?,
        host: String?,
        port: Int,
        encodedQuery: String?,
        encodedFragment: String?,
        preferredRelayHost: String?,
        buildRelayHost: String?,
        hasRendezvous: Boolean
    ): Pairing? {
        val isWeb = scheme == "https" || scheme == "http"
        val paramSrc = when {
            scheme == "tawny" && host == "pair" -> encodedQuery ?: ""
            isWeb && !encodedFragment.isNullOrEmpty() -> encodedFragment
            else -> return null
        }
        val q = params(paramSrc)

        val key = q["k"] ?: return null
        if (!KEY_RE.matches(key)) return null

        var h = q["h"]
        if (h != null && (!HOST_PORT_RE.matches(h) || !isPrivateHostPort(h))) {
            h = null   // a public IP in a pairing link is not something we dial
        }

        val token = q["t"]
        if (token != null && !TOKEN_RE.matches(token)) return null

        // Where the far end can be met. On a web link its own origin is the
        // relay unless `rv=` names another — and `rv=` is honoured only when it
        // is the link's own host or a rendezvous this install already uses, so
        // a forwarded link cannot smuggle in a third host.
        val linkHost = if (isWeb) host?.takeIf { it.isNotBlank() } else null
        var relayHostPort: String? = linkHost?.let { it + (if (port > 0) ":$port" else "") }
        val rv = q["rv"]
        if (!rv.isNullOrBlank()) {
            val r = splitRelay(rv)
            relayHostPort = if (r != null && sameHost(r.substringBefore(':'), linkHost)) r
            else if (r != null && (sameHost(r.substringBefore(':'), preferredRelayHost?.substringBefore(':')) ||
                    sameHost(r.substringBefore(':'), buildRelayHost?.substringBefore(':')))) r
            else relayHostPort   // an rv= pointing anywhere else is simply ignored
        }
        val relay = relayHostPort?.let { "wss://$it" }

        // Nothing named at all: no LAN address, no relay in the link, and this
        // build has none configured. Note that an *untrusted* relay still
        // counts as "the link named somewhere" — the link is read and shown,
        // and it is adoption, not parsing, that the anchor below gates.
        if (h == null && relay == null && !hasRendezvous) return null

        val trusted = relay != null &&
            relayAdoptable(relayHostPort, preferredRelayHost, buildRelayHost)

        // `n=` present but empty would otherwise put an empty string in the
        // "Connect to …?" dialog's title.
        val name = q["n"]?.takeIf { it.isNotBlank() }?.take(40) ?: "Pet camera"
        val code = q["c"]?.takeIf { CODE_RE.matches(it) }
        val exp = q["e"]?.toLongOrNull()?.takeIf { it > 0 }?.times(1000) ?: 0L
        return Pairing(h?.let { "ws://$it" }, key, name, token, code, exp, relay, trusted)
    }

    /**
     * May a relay named by a link be written into this phone's settings without
     * anyone being asked?
     *
     * The link's own host is not an answer to that question — it is the thing
     * being asked about. public/app.js has an anchor to check against
     * (`location.host` plus the rendezvous it was configured with); the phone
     * has no page origin, so these are its anchors:
     *
     *  - `*.ts.net` — a Tawny Docker web Monitor is always
     *    `<node>.<tailnet>.ts.net`, and a name under .ts.net resolves for
     *    nobody who is not signed in to that same tailnet. A stranger's QR
     *    cannot aim this phone anywhere it could not already reach.
     *  - a relay this install already uses: the Servers screen, or the build.
     *  - a private IPv4 address — the same trust as `h=` in the link, which is
     *    already dialled, and it cannot reach past the house.
     *
     * Anything else parses fine and is reported in the diagnostics; it just
     * does not get to rewrite Servers. The user can still paste it there
     * themselves, which is a decision rather than a side effect of scanning.
     */
    fun relayAdoptable(
        hostPort: String?,
        preferredRelayHost: String?,
        buildRelayHost: String?
    ): Boolean {
        val h = hostPort?.substringBefore(':')?.lowercase()?.takeIf { it.isNotBlank() } ?: return false
        // Port is deliberately no part of these two rules. `*.ts.net` and a
        // private address are anchors about *reachability*: a name under
        // .ts.net resolves for nobody who is not on that tailnet, and an
        // RFC1918 address cannot leave the house — on any port. Which port a
        // link names changes nothing about who can be behind it.
        if (h.endsWith(".ts.net")) return true
        if (isPrivateIpv4(h)) return true
        // These two are a different claim: "this exact relay is one this
        // install already uses". A port is part of that identity, and comparing
        // hostnames alone let a link naming a trusted host on some *other*
        // port — relay.example.net:9999, a service the operator never chose —
        // be adopted as trusted.
        return sameEndpoint(hostPort, preferredRelayHost) || sameEndpoint(hostPort, buildRelayHost)
    }

    /**
     * Hostname only, and deliberately so: this is the *pre-filter* on `rv=`,
     * answering "is this pointing somewhere related to the link at all" before
     * relayAdoptable() decides whether the result may be trusted. Keeping it
     * loose here is what lets `rv=wss://<link host>:8443` be read at all; the
     * port-aware test is the one that grants trust.
     */
    private fun sameHost(a: String?, b: String?): Boolean =
        !a.isNullOrBlank() && !b.isNullOrBlank() && a.equals(b, ignoreCase = true)

    /**
     * Same host *and* same port.
     *
     * An absent port means the default for the secure transports a relay is
     * ever named with (https / wss), so the bare `relay.example.net` a web
     * Monitor hands out still matches a `relay.example.net` on the Servers
     * screen — and matches `relay.example.net:443` too, because those are one
     * endpoint written two ways. relayHost() keeps the port while a parsed URI
     * host never does, which is why both sides are normalised here rather than
     * compared as given.
     */
    private fun sameEndpoint(a: String?, b: String?): Boolean {
        if (a.isNullOrBlank() || b.isNullOrBlank()) return false
        val ah = a.substringBefore(':').lowercase()
        val bh = b.substringBefore(':').lowercase()
        if (ah.isBlank() || ah != bh) return false
        return port(a) == port(b)
    }

    private fun port(hostPort: String): Int {
        val p = hostPort.substringAfter(':', "").toIntOrNull()
        return if (p != null && p in 1..65535) p else 443
    }

    /** `https://h[:p]` or `wss://h[:p]` from an `rv=`, reduced to `h[:p]`. */
    private fun splitRelay(rv: String): String? {
        val i = rv.indexOf("://")
        if (i < 0) return null
        val scheme = rv.substring(0, i).lowercase()
        if (scheme != "https" && scheme != "wss") return null
        val hostPort = rv.substring(i + 3).substringBefore('/').substringBefore('?').substringBefore('#')
        // No userinfo: `wss://good.ts.net@evil.example` is a host of
        // evil.example wearing a trusted name, and there is no legitimate
        // pairing link that carries credentials in the authority.
        if (hostPort.isBlank() || hostPort.contains('@')) return null
        return hostPort
    }

    /** RFC1918 / link-local only — `h` in a pairing link is a home-LAN address. */
    fun isPrivateHostPort(hostPort: String): Boolean = isPrivateIpv4(hostPort.substringBeforeLast(':'))

    private fun isPrivateIpv4(ip: String): Boolean {
        val parts = ip.split('.')
        if (parts.size != 4) return false
        val o = parts.map { it.toIntOrNull() ?: return false }
        if (o.any { it !in 0..255 }) return false
        return o[0] == 10 ||
            (o[0] == 172 && o[1] in 16..31) ||
            (o[0] == 192 && o[1] == 168) ||
            (o[0] == 169 && o[1] == 254)
    }

    /**
     * `a=1&b=2` to a map, first occurrence winning — the same answer
     * `Uri.getQueryParameter` gives, which is what this replaces. A name with
     * no `=` maps to the empty string (as Android does), an empty segment from
     * a trailing or doubled `&` is dropped, and `+` decodes to a space:
     * `Uri.getQueryParameter` converts plus, so a web Monitor's `n=Pet+camera`
     * has to arrive here as "Pet camera" and not "Pet+camera".
     */
    private fun params(src: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (part in src.split('&')) {
            if (part.isEmpty()) continue
            val eq = part.indexOf('=')
            val name = decode(if (eq < 0) part else part.substring(0, eq))
            if (name.isEmpty()) continue
            val value = if (eq < 0) "" else decode(part.substring(eq + 1))
            if (!out.containsKey(name)) out[name] = value
        }
        return out
    }

    private fun decode(s: String): String {
        if (s.indexOf('%') < 0 && s.indexOf('+') < 0) return s
        val bytes = ByteArrayOutputStream(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '+' -> { bytes.write(' '.code); i++ }
                c == '%' && i + 2 < s.length && hex(s[i + 1]) >= 0 && hex(s[i + 2]) >= 0 -> {
                    bytes.write((hex(s[i + 1]) shl 4) or hex(s[i + 2])); i += 3
                }
                // A stray or truncated `%` is kept as itself rather than
                // failing the whole link, which is what Uri.decode does too.
                else -> { bytes.write(c.toString().toByteArray(Charsets.UTF_8)); i++ }
            }
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    private fun hex(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }
}
