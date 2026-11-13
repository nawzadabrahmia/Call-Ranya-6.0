package edu.uor.lancall

import org.json.JSONObject
import java.util.concurrent.Executors

/** A signalling path to the other phone: local Wi-Fi (encrypted TCP) or internet (signed Supabase messages). */
interface Link {
    val internet: Boolean
    fun send(o: JSONObject)
    fun close()
}

/** Local Wi-Fi: messages go through the end-to-end encrypted SecureChannel, in order. */
class LanLink(val ch: SecureChannel) : Link {
    private val ex = Executors.newSingleThreadExecutor()
    override val internet = false
    override fun send(o: JSONObject) { runCatching { ex.execute { runCatching { ch.sendJson(o) } } } }
    override fun close() { runCatching { ex.execute { ch.close() }; ex.shutdown() } }
}

/** Internet: every message is signed with my identity key and delivered via Supabase Realtime. */
class CloudLink(val to: String, val call: String) : Link {
    private val ex = Executors.newSingleThreadExecutor()
    override val internet = true
    override fun send(o: JSONObject) { runCatching { ex.execute { Cloud.sendBlocking(to, call, o) } } }
    override fun close() { runCatching { ex.shutdown() } }
}
