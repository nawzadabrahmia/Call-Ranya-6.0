package edu.uor.lancall

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class CloudUser(val code: String, val name: String, val key: String)

/**
 * Internet mode (free, via Supabase):
 *  - "users" table = public directory of 8-digit IDs + identity public keys (phone stored only as a hash, only if you allow it)
 *  - Realtime broadcast = doorbell/signalling. Every message is signed with the sender's identity key.
 *  - The call itself is peer-to-peer WebRTC (DTLS-SRTP encrypted); Supabase never sees audio or video.
 */
object Cloud {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private val JSON = "application/json".toMediaType()
    private val keys = ConcurrentHashMap<String, String>()      // code -> registry key (cache)
    private val seen = ConcurrentHashMap<String, Long>()        // replay protection
    @Volatile private var ws: WebSocket? = null
    private var loop: Job? = null
    private var ref = 1

    /** state: 0 not configured / off, 1 connecting, 2 online */
    val configured get() = BuildConfig.SUPABASE_URL.startsWith("https://") && BuildConfig.SUPABASE_KEY.isNotBlank()
    val enabled get() = configured && Store.settings.value.internet && Store.settings.value.registered

    var onMessage: ((from: String, call: String, body: JSONObject, key: String, security: Int) -> Unit)? = null

    fun start() {
        if (!enabled || loop?.isActive == true) return
        loop = scope.launch {
            var backoff = 3000L
            while (isActive && enabled) {
                try {
                    Hub.cloud.value = 1
                    if (!register()) { delay(30_000); continue }
                    connectOnce()
                    backoff = 3000L
                } catch (_: Exception) { }
                Hub.cloud.value = 1
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(60_000)
            }
            Hub.cloud.value = 0
        }
    }

    fun stop() {
        loop?.cancel(); loop = null
        runCatching { ws?.close(1000, "bye") }; ws = null
        Hub.cloud.value = 0
    }

    fun restart() { stop(); start() }

    private fun req(path: String) = Request.Builder()
        .url(BuildConfig.SUPABASE_URL + path)
        .header("apikey", BuildConfig.SUPABASE_KEY)
        .header("Authorization", "Bearer " + BuildConfig.SUPABASE_KEY)

    private fun phoneHash(p: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(("uorcall:" + Store.phoneKey(p)).toByteArray())
        return d.joinToString("") { "%02x".format(it) }
    }

    /* ---------------- directory ---------------- */

    /** Publishes my 8-digit ID + public key once. If the ID is taken by someone else, picks a new one. */
    private fun register(): Boolean {
        val sp = Store.prefs()
        val s = Store.settings.value
        val marker = s.code + ":" + Identity.publicB64.hashCode() + ":" + s.sharePhone
        if (sp.getString("cloud_reg", "") == marker) return true
        http.newCall(req("/rest/v1/users?code=eq.${s.code}&select=code,pubkey").get().build()).execute().use { r ->
            if (!r.isSuccessful) return false
            val a = JSONArray(r.body?.string() ?: "[]")
            if (a.length() > 0) {
                if (a.getJSONObject(0).optString("pubkey") == Identity.publicB64) {
                    sp.edit().putString("cloud_reg", marker).apply(); return true
                }
                // someone else already owns this ID on the internet → take a fresh one
                Store.save(s.copy(code = Store.newCode()))
                return register()
            }
        }
        val body = JSONObject().put("code", s.code).put("name", s.name.take(60)).put("pubkey", Identity.publicB64)
            .put("phone_hash", if (s.sharePhone && s.phone.isNotBlank()) phoneHash(s.phone) else JSONObject.NULL)
        http.newCall(req("/rest/v1/users").header("Prefer", "return=minimal")
            .post(body.toString().toRequestBody(JSON)).build()).execute().use { r ->
            if (!r.isSuccessful) return false
        }
        sp.edit().putString("cloud_reg", marker).apply()
        return true
    }

    /** Look up an ID (8 digits) or a phone number in the internet directory. */
    suspend fun lookup(q: String): CloudUser? = withContext(Dispatchers.IO) {
        if (!enabled) return@withContext null
        val isCode = q.length == 8 && q.all(Char::isDigit)
        val filter = if (isCode) "code=eq.$q" else "phone_hash=eq.${phoneHash(q)}"
        try {
            http.newCall(req("/rest/v1/users?$filter&select=code,name,pubkey&limit=1").get().build()).execute().use { r ->
                if (!r.isSuccessful) return@withContext null
                val a = JSONArray(r.body?.string() ?: "[]")
                if (a.length() == 0) return@withContext null
                val o = a.getJSONObject(0)
                CloudUser(o.getString("code"), o.optString("name"), o.optString("pubkey")).also { keys[it.code] = it.key }
            }
        } catch (e: Exception) { null }
    }

    suspend fun registryKey(code: String): String? = keys[code] ?: lookup(code)?.key

    var onOnline: (() -> Unit)? = null

    /* ---------------- offline inbox (encrypted messages wait here up to 14 days) ---------------- */

    fun insertInbox(id: String, to: String, env: String): Boolean = try {
        val body = JSONObject().put("id", id).put("to_code", to).put("from_code", Store.settings.value.code).put("env", env)
        http.newCall(req("/rest/v1/inbox").header("Prefer", "return=minimal")
            .post(body.toString().toRequestBody(JSON)).build()).execute().use { it.isSuccessful || it.code == 409 }
    } catch (e: Exception) { false }

    fun fetchInbox(sinceIso: String): JSONArray? = try {
        val me = Store.settings.value.code
        val since = java.net.URLEncoder.encode(sinceIso, "UTF-8")
        http.newCall(req("/rest/v1/inbox?to_code=eq.$me&created_at=gt.$since&order=created_at.asc&limit=200" +
                "&select=id,from_code,env,created_at").get().build()).execute().use { r ->
            if (r.isSuccessful) JSONArray(r.body?.string() ?: "[]") else null
        }
    } catch (e: Exception) { null }

    /** Encrypted photo/video/file blobs (random names, content is AES-GCM encrypted). */
    fun upload(name: String, bytes: ByteArray): Boolean = try {
        http.newCall(req("/storage/v1/object/media/$name")
            .post(bytes.toRequestBody("application/octet-stream".toMediaType())).build()).execute().use { it.isSuccessful }
    } catch (e: Exception) { false }

    fun download(name: String): ByteArray? = try {
        http.newCall(Request.Builder().url(BuildConfig.SUPABASE_URL + "/storage/v1/object/public/media/$name").get().build())
            .execute().use { r -> if (r.isSuccessful) r.body?.bytes() else null }
    } catch (e: Exception) { null }

    /* ---------------- realtime ---------------- */

    private suspend fun connectOnce() {
        val me = Store.settings.value.code
        val topic = "realtime:u-$me"
        val url = BuildConfig.SUPABASE_URL.replaceFirst("https://", "wss://") +
                "/realtime/v1/websocket?apikey=${BuildConfig.SUPABASE_KEY}&vsn=1.0.0"
        val closed = kotlinx.coroutines.CompletableDeferred<Unit>()
        val socket = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val join = JSONObject().put("topic", topic).put("event", "phx_join").put("ref", "${ref++}")
                    .put("payload", JSONObject()
                        .put("config", JSONObject()
                            .put("broadcast", JSONObject().put("self", false).put("ack", false))
                            .put("presence", JSONObject().put("key", ""))
                            .put("private", false))
                        .put("access_token", BuildConfig.SUPABASE_KEY))
                webSocket.send(join.toString())
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val o = JSONObject(text)
                    when (o.optString("event")) {
                        "phx_reply" -> if (o.optString("topic") == topic &&
                            o.optJSONObject("payload")?.optString("status") == "ok") {
                            if (Hub.cloud.value != 2) { Hub.cloud.value = 2; onOnline?.invoke() }
                        }
                        "broadcast" -> {
                            val p = o.optJSONObject("payload") ?: return@runCatching
                            if (p.optString("event") == "sig") p.optJSONObject("payload")?.let { handle(it) }
                        }
                        "phx_error", "phx_close" -> webSocket.close(1000, null)
                    }
                    Unit
                }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { closed.complete(Unit) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { closed.complete(Unit) }
        })
        ws = socket
        val hb = scope.launch {
            while (isActive) {
                delay(25_000)
                socket.send(JSONObject().put("topic", "phoenix").put("event", "heartbeat")
                    .put("payload", JSONObject()).put("ref", "${ref++}").toString())
            }
        }
        try { closed.await() } finally { hb.cancel(); ws = null }
    }

    /** Sends a signed message to another ID (blocking – call from a background thread). */
    fun sendBlocking(to: String, call: String, body: JSONObject) {
        if (!enabled) return
        run {
            try {
                val me = Store.settings.value
                val m = JSONObject(body.toString()).put("from", me.code).put("to", to).put("call", call)
                    .put("ts", System.currentTimeMillis()).put("n", me.name).toString()
                val env = JSONObject().put("m", m).put("s", b64(Identity.sign(m.toByteArray()))).put("k", Identity.publicB64)
                val payload = JSONObject().put("messages", JSONArray().put(
                    JSONObject().put("topic", "u-$to").put("event", "sig").put("payload", env).put("private", false)))
                http.newCall(req("/realtime/v1/api/broadcast").post(payload.toString().toRequestBody(JSON)).build())
                    .execute().close()
            } catch (_: Exception) { }
        }
    }

    private fun handle(env: JSONObject) {
        val m = env.optString("m"); val s = env.optString("s"); val k = env.optString("k")
        if (m.isEmpty() || !Identity.verify(unb64(k), m.toByteArray(), unb64(s))) return
        val o = JSONObject(m)
        val me = Store.settings.value.code
        val from = o.optString("from")
        if (o.optString("to") != me || from.isEmpty()) return
        val ts = o.optLong("ts")
        if (kotlin.math.abs(System.currentTimeMillis() - ts) > 120_000) return
        val id = from + ":" + ts + ":" + s.hashCode()
        if (seen.putIfAbsent(id, ts) != null) return
        if (seen.size > 500) seen.entries.removeIf { System.currentTimeMillis() - it.value > 180_000 }
        scope.launch {
            // the key must match the one registered for that ID → nobody can pretend to be someone else
            val reg = registryKey(from)
            if (reg != null && reg != k) return@launch
            val c = Store.contact(from)
            val sec = when { c == null || c.key.isBlank() -> 0; c.key != k -> 2; c.verified -> 1; else -> 0 }
            onMessage?.invoke(from, o.optString("call"), o, k, sec)
        }
    }
}
