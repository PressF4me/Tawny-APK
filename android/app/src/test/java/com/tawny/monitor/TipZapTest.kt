package com.tawny.monitor

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TipZapTest {
    private fun res(name: String) = javaClass.getResource("/tipzap/$name")!!.readText()

    /** Signatures from @noble/secp256k1 (the web build's signer); the first is BIP340's vector 0. */
    @Test fun signsLikeTheReference() {
        val v = JSONArray(res("vectors.json"))
        for (i in 0 until v.length()) {
            val o = v.getJSONObject(i)
            val sec = TipZap.unhex(o.getString("sec"))
            assertEquals(o.getString("pub"), TipZap.hex(TipZap.publicKey(sec)))
            val sig = TipZap.sign(TipZap.unhex(o.getString("msg")), sec, TipZap.unhex(o.getString("aux")))
            assertEquals(o.getString("sig"), TipZap.hex(sig))
            assertTrue(TipZap.verify(sig, TipZap.unhex(o.getString("msg")), TipZap.unhex(o.getString("pub"))))
            sig[5] = (sig[5].toInt() xor 1).toByte()
            assertFalse(TipZap.verify(sig, TipZap.unhex(o.getString("msg")), TipZap.unhex(o.getString("pub"))))
        }
    }

    @Test fun jsonIsNip01() {
        assertEquals("[\"wss://a/b\",\"q\\\"x\\\\y\\nz\",1,\"⚡\"]", TipZap.json(listOf("wss://a/b", "q\"x\\y\nz", 1, "⚡")))
    }

    @Test fun ownEventsVerify() {
        val sec = TipZap.newSecret()
        val ev = JSONObject(TipZap.signedEvent(sec, 9734, listOf(listOf("relays", "wss://nos.lol"), listOf("p", "ab")), "A tip ⚡ \"quoted\""))
        val id = TipZap.eventId(ev.getString("pubkey"), ev.getLong("created_at"), 9734, ev.getJSONArray("tags"), ev.getString("content"))
        assertEquals(ev.getString("id"), TipZap.hex(id))
        assertTrue(TipZap.verify(TipZap.unhex(ev.getString("sig")), id, TipZap.unhex(ev.getString("pubkey"))))
    }

    /** A real receipt the tip service published (its zap-request tag has slashes and quotes). */
    @Test fun realReceiptIsAcceptedAndForgeriesAreNot() {
        val ev = JSONObject(res("receipt.json"))
        val tags = ev.getJSONArray("tags")
        fun tag(n: String) = (0 until tags.length()).map { tags.getJSONArray(it) }.first { it.getString(0) == n }.getString(1)
        val w = TipZap.Watch(tag("bolt11"), tag("p"), ev.getString("pubkey"))
        assertTrue(TipZap.isReceipt(ev, w))
        assertFalse(TipZap.isReceipt(ev, w.copy(invoice = "lnbc1other")))
        assertFalse(TipZap.isReceipt(ev, w.copy(signer = "00".repeat(32))))
        assertFalse(TipZap.isReceipt(JSONObject(res("receipt.json")).put("content", "changed"), w))
    }
}
