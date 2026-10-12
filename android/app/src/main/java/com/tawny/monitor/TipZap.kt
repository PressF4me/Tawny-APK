package com.tawny.monitor

import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONArray
import org.json.JSONObject

/**
 * Tip in Bitcoin, the full flow (GitHub and F-Droid builds; the Play build
 * keeps the plain hand-off, see MainActivity.showLightningTip).
 *
 * An invoice for a chosen amount comes from the Lightning Address's LNURL-pay
 * service, asked for as a Nostr zap (NIP-57) under a throwaway key made for
 * this one tip. When it is paid, the service publishes a receipt signed with
 * its own key to the relays named in the request; [listen] waits for the
 * receipt that carries exactly this invoice and checks its id and signature.
 *
 * Free of Android types like PairLink.kt, so `./gradlew test` covers it on a
 * plain JVM. The same flow is web/tip.js in the browser builds.
 */
object TipZap {
    const val LNURLP = "https://strike.me/.well-known/lnurlp/loustrikes"
    const val PRICE = "https://api.coinbase.com/v2/prices/BTC-USD/spot"
    val RELAYS = listOf("wss://relay.damus.io", "wss://nos.lol", "wss://relay.primal.net", "wss://relay.snort.social")
    private const val LISTEN_MS = 20 * 60 * 1000L

    /** What to wait for: receipts tagged to [tag], signed by [signer], carrying [invoice]. */
    data class Watch(val invoice: String, val tag: String, val signer: String)
    data class Invoice(val pr: String, val watch: Watch?)

    // ---------------------------------------------------------------- BIP340

    private val P = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16)
    private val N = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)
    private val G = Pt(
        BigInteger("79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798", 16),
        BigInteger("483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8", 16),
    )
    private val SEVEN = BigInteger.valueOf(7)
    private val random = SecureRandom()

    private data class Pt(val x: BigInteger, val y: BigInteger)

    private fun add(a: Pt?, b: Pt?): Pt? {
        if (a == null) return b
        if (b == null) return a
        if (a.x == b.x && a.y != b.y) return null
        val l = if (a == b) {
            BigInteger.valueOf(3).multiply(a.x).multiply(a.x).multiply(BigInteger.valueOf(2).multiply(a.y).modInverse(P)).mod(P)
        } else {
            b.y.subtract(a.y).multiply(b.x.subtract(a.x).modInverse(P)).mod(P)
        }
        val x = l.multiply(l).subtract(a.x).subtract(b.x).mod(P)
        return Pt(x, l.multiply(a.x.subtract(x)).subtract(a.y).mod(P))
    }

    private fun mul(p: Pt?, k: BigInteger): Pt? {
        var r: Pt? = null
        var q = p
        for (i in 0 until k.bitLength()) {
            if (k.testBit(i)) r = add(r, q)
            q = add(q, q)
        }
        return r
    }

    private fun bytes32(n: BigInteger): ByteArray {
        val b = n.toByteArray()
        return when {
            b.size == 32 -> b
            b.size > 32 -> b.copyOfRange(b.size - 32, b.size)
            else -> ByteArray(32 - b.size) + b
        }
    }

    private fun int(b: ByteArray) = BigInteger(1, b)
    fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)
    private fun tagged(tag: String, vararg parts: ByteArray): ByteArray {
        val t = sha256(tag.toByteArray())
        return sha256(parts.fold(t + t) { acc, p -> acc + p })
    }

    private fun liftX(x: BigInteger): Pt? {
        if (x >= P) return null
        val c = x.modPow(BigInteger.valueOf(3), P).add(SEVEN).mod(P)
        val y = c.modPow(P.add(BigInteger.ONE).divide(BigInteger.valueOf(4)), P)
        if (y.modPow(BigInteger.valueOf(2), P) != c) return null
        return Pt(x, if (y.testBit(0)) P.subtract(y) else y)
    }

    fun publicKey(secret: ByteArray): ByteArray = bytes32(mul(G, int(secret))!!.x)

    fun newSecret(): ByteArray {
        while (true) {
            val b = ByteArray(32).also { random.nextBytes(it) }
            val d = int(b)
            if (d.signum() > 0 && d < N) return b
        }
    }

    fun sign(msg: ByteArray, secret: ByteArray, aux: ByteArray = ByteArray(32).also { random.nextBytes(it) }): ByteArray {
        val d0 = int(secret)
        require(d0.signum() > 0 && d0 < N) { "bad key" }
        val pt = mul(G, d0)!!
        val d = if (pt.y.testBit(0)) N.subtract(d0) else d0
        val t = bytes32(d.xor(int(tagged("BIP0340/aux", aux))))
        val px = bytes32(pt.x)
        val k0 = int(tagged("BIP0340/nonce", t, px, msg)).mod(N)
        require(k0.signum() != 0) { "bad nonce" }
        val r = mul(G, k0)!!
        val k = if (r.y.testBit(0)) N.subtract(k0) else k0
        val rx = bytes32(r.x)
        val e = int(tagged("BIP0340/challenge", rx, px, msg)).mod(N)
        return rx + bytes32(k.add(e.multiply(d)).mod(N))
    }

    fun verify(sig: ByteArray, msg: ByteArray, pub: ByteArray): Boolean {
        if (sig.size != 64 || pub.size != 32) return false
        val p = liftX(int(pub)) ?: return false
        val r = int(sig.copyOfRange(0, 32))
        val s = int(sig.copyOfRange(32, 64))
        if (r >= P || s >= N) return false
        val e = int(tagged("BIP0340/challenge", sig.copyOfRange(0, 32), pub, msg)).mod(N)
        val rr = add(mul(G, s), mul(p, N.subtract(e))) ?: return false
        return !rr.y.testBit(0) && rr.x == r
    }

    // ---------------------------------------------------------------- nostr

    fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    fun unhex(s: String): ByteArray {
        require(s.length % 2 == 0 && s.all { it in "0123456789abcdefABCDEF" })
        return ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    /**
     * NIP-01 JSON: no whitespace, and strings escaped exactly as the spec says.
     * org.json would write "/" as "\/" — a different string, a different event
     * id, and a request every service rejects.
     */
    fun json(v: Any?): String = when (v) {
        null -> "null"
        is String -> buildString {
            append('"')
            for (c in v) when (c) {
                '"' -> append("\\\""); '\\' -> append("\\\\")
                '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
                '\b' -> append("\\b"); '\u000C' -> append("\\f")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
            append('"')
        }
        is Number, is Boolean -> v.toString()
        is List<*> -> v.joinToString(",", "[", "]") { json(it) }
        is JSONArray -> (0 until v.length()).joinToString(",", "[", "]") { json(v.get(it).takeUnless { x -> x == JSONObject.NULL }) }
        else -> error("unsupported ${v::class}")
    }

    fun eventId(pubkey: String, createdAt: Long, kind: Int, tags: Any, content: String): ByteArray =
        sha256(json(listOf(0, pubkey, createdAt, kind, tags, content)).toByteArray(Charsets.UTF_8))

    fun signedEvent(secret: ByteArray, kind: Int, tags: List<List<String>>, content: String, createdAt: Long = System.currentTimeMillis() / 1000): String {
        val pub = hex(publicKey(secret))
        val id = eventId(pub, createdAt, kind, tags, content)
        val sig = hex(sign(id, secret))
        return "{\"id\":${json(hex(id))},\"pubkey\":${json(pub)},\"created_at\":$createdAt,\"kind\":$kind," +
            "\"tags\":${json(tags)},\"content\":${json(content)},\"sig\":${json(sig)}}"
    }

    /** The paid receipt for [w]: right kind, signer, tag and invoice, and a valid id + signature. */
    fun isReceipt(ev: JSONObject, w: Watch): Boolean = try {
        val tags = ev.getJSONArray("tags")
        fun tag(name: String): String? = (0 until tags.length()).map { tags.getJSONArray(it) }
            .firstOrNull { it.length() > 1 && it.getString(0) == name }?.getString(1)
        val pub = ev.getString("pubkey")
        ev.getInt("kind") == 9735 && pub.equals(w.signer, ignoreCase = true) &&
            tag("p") == w.tag && tag("bolt11").equals(w.invoice, ignoreCase = true) &&
            run {
                val id = eventId(pub, ev.getLong("created_at"), 9735, tags, ev.getString("content"))
                hex(id) == ev.getString("id").lowercase() && verify(unhex(ev.getString("sig")), id, unhex(pub))
            }
    } catch (e: Exception) { false }

    // ---------------------------------------------------------------- network (call off the UI thread)

    private fun getJson(url: String): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 12_000; c.readTimeout = 12_000
            c.setRequestProperty("Accept", "application/json")
            if (c.responseCode !in 200..299) error("http ${c.responseCode}")
            return JSONObject(c.inputStream.bufferedReader().readText())
        } finally { c.disconnect() }
    }

    /** Dollars per bitcoin, or null offline. */
    fun btcUsd(): Double? = try {
        getJson(PRICE).getJSONObject("data").getString("amount").toDouble().takeIf { it > 0 }
    } catch (e: Exception) { null }

    fun invoice(sats: Long): Invoice {
        val pay = getJson(LNURLP)
        val msats = sats * 1000
        val callback = pay.getString("callback")
        require(callback.startsWith("https://")) { "callback" }
        require(msats >= pay.optLong("minSendable", 1000) && msats <= pay.optLong("maxSendable", Long.MAX_VALUE)) { "range" }
        val base = callback + (if ('?' in callback) "&" else "?") + "amount=$msats"
        val signer = pay.optString("nostrPubkey").lowercase()
        if (pay.optBoolean("allowsNostr") && signer.matches(Regex("[0-9a-f]{64}"))) {
            try {
                val secret = newSecret()
                val tag = hex(publicKey(secret))
                val zap = signedEvent(secret, 9734, listOf(listOf("relays") + RELAYS, listOf("amount", msats.toString()), listOf("p", tag)), "A tip for Tawny ⚡")
                val pr = getJson(base + "&nostr=" + URLEncoder.encode(zap, "UTF-8")).optString("pr")
                if (pr.startsWith("lnbc", ignoreCase = true)) return Invoice(pr, Watch(pr, tag, signer))
            } catch (e: Exception) { /* a plain invoice instead */ }
        }
        val pr = getJson(base).optString("pr")
        require(pr.startsWith("lnbc", ignoreCase = true)) { "no invoice" }
        return Invoice(pr, null)
    }

    /** Waits on the relays until [w]'s receipt arrives; [onPaid] runs once (on a socket thread). */
    class Listener(private val w: Watch, private val log: (String) -> Unit = {}, private val onPaid: () -> Unit) {
        @Volatile private var done = false
        private val sockets = mutableListOf<WebSocketClient>()
        private val since = System.currentTimeMillis() / 1000 - 120
        private val deadline = System.currentTimeMillis() + LISTEN_MS
        private val drops = java.util.concurrent.ConcurrentHashMap<String, Int>()

        fun start(): Listener { RELAYS.forEach { open(it) }; return this }

        private fun open(url: String) {
            if (done || System.currentTimeMillis() > deadline) return
            val ws = object : WebSocketClient(URI(url)) {
                override fun onOpen(h: ServerHandshake?) {
                    log("tip: listening on $url")
                    send("[\"REQ\",\"tip\",{\"kinds\":[9735],\"#p\":[${json(w.tag)}],\"since\":$since}]")
                }
                override fun onMessage(message: String?) {
                    try {
                        val v = JSONArray(message ?: return)
                        if (v.optString(0) == "EVENT" && isReceipt(v.getJSONObject(2), w)) { log("tip: paid (receipt from $url)"); finish(true) }
                    } catch (e: Exception) { }
                }
                override fun onClose(code: Int, reason: String?, remote: Boolean) {
                    if (!done) log("tip: $url closed ($code), reconnecting")
                    // back off on a relay that keeps dropping: 5s, 10s, 20s… at most a minute
                    val n = drops.merge(url, 1, Int::plus) ?: 1
                    val wait = minOf(60_000L, 5_000L shl minOf(n - 1, 4))
                    if (!done) Thread { Thread.sleep(wait); open(url) }.apply { isDaemon = true }.start()
                }
                override fun onError(ex: Exception?) { }
            }
            synchronized(sockets) { sockets.add(ws) }
            try { ws.connect() } catch (e: Exception) { }
        }

        @Synchronized private fun finish(paid: Boolean) {
            if (done) return
            done = true
            synchronized(sockets) { sockets.forEach { try { it.close() } catch (e: Exception) { } } }
            if (paid) onPaid()
        }

        fun stop() = finish(false)
    }
}
