package edu.uor.lancall

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.location.Location
import android.os.Build
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/* ======================================================================================================
 *  Call Ranya Relay – an opportunistic, end-to-end encrypted store-carry-forward network
 *  for places with no internet, no mobile signal and no server (camps, villages, disasters).
 *
 *  Idea: Wi-Fi networks and phone hotspots form small "islands". People walk between islands.
 *  Every phone keeps a small encrypted store of "bundles" and, whenever it meets another phone on any
 *  network, the two phones exchange what the other one is missing (anti-entropy / epidemic routing with
 *  summary vectors). A message therefore reaches someone who was never on the same network as the sender.
 *
 *  Bundle (immutable part is signed by the origin's identity key):
 *    id    128-bit random (hex)            k     class: sos | safe | ann | msg
 *    o     origin identity key (Base64)    t     origin clock (informational only)
 *    life  lifetime in ms                  mh    maximum number of hops
 *    to    recipient tag (unicast) = SHA-256("CR-tag|" + recipientKey + "|" + id)[0..16]; "" = broadcast
 *    ah    ack hash = SHA-256(ackSecret)   p     payload
 *    s     ECDSA signature                  h, a  hop count and bundle AGE (mutable, not signed)
 *
 *  - Unicast payload: ECIES – ephemeral ECDH (P-256) with the recipient's identity key → HKDF → AES-256-GCM.
 *    Carriers cannot read the content and, unless they know the recipient's key, cannot tell who it is for.
 *  - Broadcast (SOS, announcements): signed plaintext, so any rescuer or neighbour can read and verify it.
 *  - Hash-locked delivery receipts: only the real recipient can decrypt ackSecret. It publishes a
 *    tombstone {id, ackSecret}; every carrier checks SHA-256(ackSecret) == ah and deletes its copy
 *    ("vaccine"), and the sender learns that the message arrived. Receipts cannot be forged by carriers.
 *  - Clock-free expiry: phones without internet often have wrong clocks, so lifetime is enforced on the
 *    bundle AGE (time spent in the network, accumulated hop by hop) – like the Bundle Age block of RFC 9171.
 *    Delivery latency for the study is therefore measured without synchronised clocks.
 *  - Priorities (SOS > safety > announcements > messages), hop limits, per-origin quotas and a storage cap
 *    with priority-aware eviction protect the network from flooding.
 *  - Relay sessions are anonymous (throw-away handshake key, no code/name), beacons use a pseudonym that
 *    changes every hour.
 * ====================================================================================================== */

data class Bundle(
    val id: String, val k: String, val o: String, val t: Long, val life: Long, val mh: Int,
    val to: String, val ah: String, val p: String, val s: String, val h: Int, val age: Long, val rx: Long = 0
) {
    fun signable(): ByteArray = "CR1|$id|$k|$o|$t|$life|$mh|$to|$ah|$p".toByteArray(Charsets.UTF_8)
    fun ageNow(now: Long = System.currentTimeMillis()) = age + (now - rx).coerceAtLeast(0)
    fun size() = p.length + 400
    fun json(hops: Int, ageMs: Long): JSONObject = JSONObject().put("op", "b")
        .put("id", id).put("k", k).put("o", o).put("t", t).put("life", life).put("mh", mh)
        .put("to", to).put("ah", ah).put("p", p).put("s", s).put("h", hops).put("a", ageMs)

    companion object {
        fun from(o: JSONObject): Bundle? = runCatching {
            Bundle(o.getString("id"), o.getString("k"), o.getString("o"), o.getLong("t"), o.getLong("life"),
                o.getInt("mh"), o.optString("to"), o.optString("ah"), o.getString("p"), o.getString("s"),
                o.getInt("h"), o.getLong("a"))
        }.getOrNull()
    }
}

data class BoardItem(
    val id: String, val k: String, val origin: String, val code: String, val name: String,
    val title: String, val text: String, val lat: Double?, val lon: Double?,
    val rx: Long, val expires: Long, val hops: Int, val ageAtRx: Long, val mine: Boolean
) {
    val trusted get() = Store.trustedKey(origin)
}

data class RelayStats(
    val held: Int = 0, val heldBytes: Long = 0, val encounters: Int = 0, val forwarded: Int = 0,
    val received: Int = 0, val deliveredToMe: Int = 0, val myDelivered: Int = 0, val lastEncounter: Long = 0,
    val events: Int = 0
)

private class RelayDb(ctx: Context) : SQLiteOpenHelper(ctx, "relay.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE b (id TEXT PRIMARY KEY, k TEXT, o TEXT, t INTEGER, life INTEGER, mh INTEGER, tg TEXT, " +
                "ah TEXT, p TEXT, s TEXT, h INTEGER, age INTEGER, rx INTEGER, mine INTEGER, sz INTEGER)")
        db.execSQL("CREATE INDEX b_o ON b(o)")
        db.execSQL("CREATE TABLE tomb (id TEXT PRIMARY KEY, sec TEXT, rx INTEGER)")
        db.execSQL("CREATE TABLE own (id TEXT PRIMARY KEY, mid TEXT, ah TEXT, t INTEGER, dlv INTEGER)")
        db.execSQL("CREATE INDEX own_mid ON own(mid)")
        db.execSQL("CREATE TABLE board (id TEXT PRIMARY KEY, k TEXT, o TEXT, code TEXT, name TEXT, title TEXT, body TEXT, " +
                "lat REAL, lon REAL, rx INTEGER, exp INTEGER, h INTEGER, age INTEGER, mine INTEGER)")
        db.execSQL("CREATE TABLE ev (ts INTEGER, ev TEXT, bid TEXT, k TEXT, h INTEGER, age INTEGER, sz INTEGER, peer TEXT, x INTEGER)")
    }
    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {}
}

object Relay {
    const val TYPE = "relay"
    const val SOS = "sos"
    const val SAFE = "safe"
    const val ANN = "ann"
    const val MSG = "msg"
    const val CH_BOARD = "board"
    private val CLASSES = setOf(SOS, SAFE, ANN, MSG)

    private const val HOUR = 3_600_000L
    private const val DAY = 24 * HOUR
    const val MAX_FILE = 150 * 1024L                 // attachments that may travel through the relay (fits one bundle)
    private const val MAX_PAYLOAD = 320 * 1024       // Base64 payload limit per bundle
    private const val MAX_LIFE = 8 * DAY
    private const val PER_ORIGIN = 300               // bundles held per origin key
    private const val ENC_MAX_BUNDLES = 500          // per encounter
    private const val ENC_MAX_BYTES = 8L * 1024 * 1024
    private const val COOLDOWN = 45_000L             // min. time between syncs with the same address
    private const val BEACON_MS = 8_000L
    private const val TOMB_LIFE = 9 * DAY
    private const val MAX_TOMBS = 20_000
    private const val SV_TOMBS = 3_000               // tombstones advertised per encounter

    fun life(k: String) = when (k) { SOS -> DAY; SAFE -> 3 * DAY; ANN -> 2 * DAY; else -> 7 * DAY }
    fun maxHops(k: String) = when (k) { SOS -> 12; SAFE -> 10; ANN -> 16; else -> 8 }
    fun priority(k: String) = when (k) { SOS -> 0; SAFE -> 1; ANN -> 2; else -> 3 }

    private lateinit var db: RelayDb
    private lateinit var app: Context
    private val lock = Any()
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var running = false
    private val syncing = AtomicBoolean(false)
    private val lastSync = ConcurrentHashMap<String, Long>()
    @Volatile private var digestCache: String? = null
    private var pseudo = ""
    private var pseudoAt = 0L

    val stats = MutableStateFlow(RelayStats())
    val board = MutableStateFlow<List<BoardItem>>(emptyList())

    val enabled get() = Store.settings.value.relay && Store.settings.value.registered
    private val station get() = Store.settings.value.relayStation
    private fun capBytes() = if (station) 200L * 1024 * 1024 else 25L * 1024 * 1024
    private fun capCount() = if (station) 20_000 else 3_000

    fun init(ctx: Context) {
        if (::db.isInitialized) return
        app = ctx.applicationContext
        db = RelayDb(app)
        refresh()
    }

    /* ======================================================================================
     *  Lifecycle (driven by CallService)
     * ====================================================================================== */

    fun start() {
        if (running) return
        running = true
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            while (isActive) {
                if (enabled) runCatching { Discovery.broadcast(beacon()) }
                delay(BEACON_MS)
            }
        }
        scope.launch {
            while (isActive) {
                runCatching { maintain() }
                delay(5 * 60_000L)
            }
        }
    }

    fun stop() {
        running = false
        scope.cancel()
    }

    /** Something new to share: allow immediate syncs and announce right away. */
    fun kick() {
        digestCache = null
        lastSync.clear()
        if (running && enabled) scope.launch { runCatching { Discovery.broadcast(beacon()) } }
    }

    /* ======================================================================================
     *  Beacons and encounters
     * ====================================================================================== */

    private fun myPseudo(): String {
        val now = System.currentTimeMillis()
        if (pseudo.isEmpty() || now - pseudoAt > HOUR) { pseudo = Crypto.hex(Crypto.randomBytes(6)); pseudoAt = now }
        return pseudo
    }

    /** Digest of what I hold: equal digests → nothing to exchange. */
    private fun digest(): String {
        digestCache?.let { return it }
        val d = synchronized(lock) {
            val ids = ArrayList<String>()
            db.readableDatabase.rawQuery("SELECT id FROM b ORDER BY id", null).use { while (it.moveToNext()) ids.add(it.getString(0)) }
            db.readableDatabase.rawQuery("SELECT id FROM tomb ORDER BY id", null).use { while (it.moveToNext()) ids.add("t" + it.getString(0)) }
            Crypto.hex(Crypto.sha256(ids.joinToString(",").toByteArray())).take(16)
        }
        digestCache = d
        return d
    }

    private fun beacon(): JSONObject =
        JSONObject().put("t", "rly").put("v", 1).put("r", myPseudo()).put("d", digest()).put("n", stats.value.held)

    /** Called by Discovery for every "rly" beacon heard on any network. */
    fun onBeacon(o: JSONObject, ip: String) {
        if (!running || !enabled) return
        val their = o.optString("r")
        val me = myPseudo()
        if (their.isEmpty() || their == me || ip == Hub.myIp.value) return
        if (o.optString("d") == digest()) return               // same content, nothing to do
        if (me > their) return                                    // the smaller pseudonym starts the sync
        val now = System.currentTimeMillis()
        if (now - (lastSync[ip] ?: 0L) < COOLDOWN) return
        if (!syncing.compareAndSet(false, true)) return
        lastSync[ip] = now
        scope.launch {
            try { syncWith(ip, their) } catch (_: Exception) { } finally { syncing.set(false) }
        }
    }

    /* ======================================================================================
     *  Creating bundles
     * ====================================================================================== */

    private fun tag(recipientKey: String, id: String) =
        Crypto.hex(Crypto.sha256("CR-tag|$recipientKey|$id".toByteArray())).take(32)

    private fun newId() = Crypto.hex(Crypto.randomBytes(16))

    private fun sign(b: Bundle) = b.copy(s = b64(Identity.sign(b.signable())))

    /** Key to use for a person: saved contact first, otherwise a key learned from an earlier relayed message. */
    fun keyFor(code: String): String? =
        Store.contact(code)?.key?.ifBlank { null } ?: Store.prefs().getString("rk_$code", null)

    fun rememberKey(code: String, key: String) {
        if (code.length == 8 && Store.contact(code)?.key.isNullOrBlank())
            Store.prefs().edit().putString("rk_$code", key).apply()
    }

    fun hasOwnFor(mid: String): Boolean = synchronized(lock) {
        db.readableDatabase.rawQuery("SELECT 1 FROM own WHERE mid=?", arrayOf(mid)).use { it.moveToFirst() }
    }

    /** Hand a chat message / check-in / roll call to the relay network. @return true if it is (now) in the relay. */
    fun trySend(m: Msg): Boolean {
        if (!::db.isInitialized || !enabled || !m.out) return false
        if (hasOwnFor(m.id)) return true
        val key = keyFor(m.peer) ?: return false
        val me = Store.settings.value
        val cls = if (m.kind == "checkin" || m.kind == "rollcall") SAFE else MSG
        val secret = Crypto.randomBytes(16)
        val inner = JSONObject().put("from", me.code).put("n", me.name).put("mid", m.id).put("kind", m.kind)
            .put("body", m.body).put("fname", m.fileName).put("mime", m.mime).put("size", m.size).put("time", m.time)
            .put("ack", b64(secret))
        if (Messenger.isFile(m.kind)) {
            val f = File(m.path)
            if (!f.exists() || f.length() > MAX_FILE) return false
            inner.put("file", b64(f.readBytes()))
        }
        val id = newId()
        val rpub = unb64(key)
        val eph = Crypto.ephemeral()
        val ephPub = eph.public.encoded
        val k = Crypto.hkdf(Crypto.ecdh(eph.private, rpub), id.toByteArray(), "CR-relay-v1".toByteArray() + ephPub + rpub, 32)
        val p = b64(ephPub) + "." + b64(Crypto.seal(k, inner.toString().toByteArray(Charsets.UTF_8)))
        if (p.length > MAX_PAYLOAD) return false
        val now = System.currentTimeMillis()
        val b = sign(Bundle(id, cls, Identity.publicB64, now, life(cls), maxHops(cls), tag(key, id),
            Crypto.hex(Crypto.sha256(secret)), p, "", 0, 0, now))
        synchronized(lock) {
            insertBundle(b, mine = true)
            db.writableDatabase.insertWithOnConflict("own", null, ContentValues().apply {
                put("id", id); put("mid", m.id); put("ah", b.ah); put("t", now); put("dlv", 0)
            }, SQLiteDatabase.CONFLICT_IGNORE)
        }
        log("create", b, "", 0)
        refresh(); kick()
        return true
    }

    /** SOS to everyone nearby (readable by anyone, signed so it cannot be faked in my name). */
    fun broadcastSos(note: String, loc: Location?): Boolean {
        val me = Store.settings.value
        val p = JSONObject().put("code", me.code).put("n", me.name).put("note", note.take(300))
        if (loc != null) p.put("lat", loc.latitude).put("lon", loc.longitude).put("acc", loc.accuracy.toInt())
        return broadcast(SOS, p, life(SOS))
    }

    fun postAnnouncement(title: String, text: String, hours: Int): Boolean {
        val me = Store.settings.value
        val p = JSONObject().put("code", me.code).put("n", me.name).put("org", me.org)
            .put("title", title.take(100)).put("text", text.take(1500))
        return broadcast(ANN, p, (hours * HOUR).coerceIn(HOUR, 7 * DAY))
    }

    private fun broadcast(k: String, payload: JSONObject, lifeMs: Long): Boolean {
        if (!::db.isInitialized || !Store.settings.value.registered) return false
        val now = System.currentTimeMillis()
        val b = sign(Bundle(newId(), k, Identity.publicB64, now, lifeMs, maxHops(k), "", "",
            payload.toString(), "", 0, 0, now))
        synchronized(lock) { insertBundle(b, mine = true); addBoard(b, mine = true) }
        log("create", b, "", 0)
        refresh(); kick()
        return true
    }

    /* ======================================================================================
     *  Receiving bundles
     * ====================================================================================== */

    private fun exists(id: String): Boolean =
        db.readableDatabase.rawQuery("SELECT 1 FROM b WHERE id=?", arrayOf(id)).use { it.moveToFirst() }

    private fun tombSecret(id: String): String? =
        db.readableDatabase.rawQuery("SELECT sec FROM tomb WHERE id=?", arrayOf(id)).use { if (it.moveToFirst()) it.getString(0) else null }

    private fun originCount(o: String): Int =
        db.readableDatabase.rawQuery("SELECT COUNT(*) FROM b WHERE o=?", arrayOf(o)).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    private fun validAck(secretB64: String, ah: String) =
        runCatching { Crypto.hex(Crypto.sha256(unb64(secretB64))) == ah }.getOrDefault(false)

    /** @return true if the bundle was new and accepted. */
    private fun accept(b: Bundle, peer: String): Boolean {
        val now = System.currentTimeMillis()
        if (b.k !in CLASSES || b.id.length != 32 || b.p.length > MAX_PAYLOAD) return false
        if (b.h > b.mh || b.life > MAX_LIFE || b.age < 0 || b.age >= b.life) return false
        if (b.k == SOS || b.k == ANN) { if (b.to.isNotEmpty()) return false } else if (b.to.isEmpty() || b.ah.isEmpty()) return false
        synchronized(lock) {
            if (exists(b.id)) return false
            tombSecret(b.id)?.let { sec ->
                if (validAck(sec, b.ah)) return false
                db.writableDatabase.delete("tomb", "id=?", arrayOf(b.id))   // forged tombstone: ignore it
            }
        }
        if (!Identity.verify(unb64(b.o), b.signable(), unb64(b.s))) return false
        if (Store.blockedKey(b.o)) return false
        val rb = b.copy(rx = now)

        // addressed to me?
        if (b.to.isNotEmpty() && b.to == tag(Identity.publicB64, b.id)) return deliverToMe(rb, peer)

        synchronized(lock) {
            if (exists(b.id)) return false
            if (originCount(b.o) >= PER_ORIGIN) return false
            insertBundle(rb, mine = false)
            if (b.k == SOS || b.k == ANN) addBoard(rb, mine = false)
        }
        log("store", rb, peer, 0)
        if (b.k == SOS || b.k == ANN) runCatching { notifyBoard(rb) }
        refresh()
        return true
    }

    private fun deliverToMe(b: Bundle, peer: String): Boolean {
        val parts = b.p.split(".")
        if (parts.size != 2) return false
        val inner = try {
            val eph = unb64(parts[0])
            val k = Crypto.hkdf(Identity.agree(eph), b.id.toByteArray(), "CR-relay-v1".toByteArray() + eph + Identity.publicBytes, 32)
            JSONObject(String(Crypto.open(k, unb64(parts[1])), Charsets.UTF_8))
        } catch (e: Exception) { return false }
        val secret = inner.optString("ack")
        if (!validAck(secret, b.ah)) return false
        synchronized(lock) {
            db.writableDatabase.insertWithOnConflict("tomb", null, ContentValues().apply {
                put("id", b.id); put("sec", secret); put("rx", System.currentTimeMillis())
            }, SQLiteDatabase.CONFLICT_IGNORE)
        }
        digestCache = null
        Store.prefs().edit().putInt("rl_me", Store.prefs().getInt("rl_me", 0) + 1).apply()
        log("deliver", b, peer, 0)
        runCatching { Messenger.receiveRelay(inner, b.o) }
        return true
    }

    /** A tombstone {id, secret}: delete my copy if the secret proves delivery; tell the UI if it was mine. */
    private fun applyTomb(id: String, sec: String) {
        if (id.length != 32 || sec.length > 64) return
        var mid: String? = null
        synchronized(lock) {
            if (tombSecret(id) != null) return
            val ah = db.readableDatabase.rawQuery("SELECT ah FROM b WHERE id=?", arrayOf(id)).use { if (it.moveToFirst()) it.getString(0) else null }
                ?: db.readableDatabase.rawQuery("SELECT ah FROM own WHERE id=?", arrayOf(id)).use { if (it.moveToFirst()) it.getString(0) else null }
            if (ah != null && !validAck(sec, ah)) return
            val w = db.writableDatabase
            w.insertWithOnConflict("tomb", null, ContentValues().apply {
                put("id", id); put("sec", sec); put("rx", System.currentTimeMillis())
            }, SQLiteDatabase.CONFLICT_IGNORE)
            if (ah != null) {
                w.delete("b", "id=?", arrayOf(id))
                mid = w.rawQuery("SELECT mid FROM own WHERE id=? AND dlv=0", arrayOf(id)).use { if (it.moveToFirst()) it.getString(0) else null }
                if (mid != null) w.execSQL("UPDATE own SET dlv=1 WHERE id=?", arrayOf(id))
            }
        }
        digestCache = null
        mid?.let { m ->
            val t = synchronized(lock) {
                db.readableDatabase.rawQuery("SELECT t FROM own WHERE id=?", arrayOf(id)).use { if (it.moveToFirst()) it.getLong(0) else 0L }
            }
            logRaw("acked", id, "own", 0, System.currentTimeMillis() - t, 0, "")
            Messenger.markRelayDelivered(m)
        }
    }

    /* ======================================================================================
     *  Anti-entropy sync (strictly alternating, so it can never deadlock)
     *
     *   initiator                          responder
     *   SV{ids,tombs}           ───────▶
     *                           ◀───────   SV{ids,tombs,want}
     *   bundles it wants…       ───────▶
     *   WANT{ids}               ───────▶
     *                           ◀───────   bundles I want…, END
     * ====================================================================================== */

    private fun summary(): JSONObject {
        val ids = JSONArray(); val tombs = JSONArray()
        synchronized(lock) {
            db.readableDatabase.rawQuery("SELECT id FROM b", null).use { while (it.moveToNext()) ids.put(it.getString(0)) }
            db.readableDatabase.rawQuery("SELECT id, sec FROM tomb ORDER BY rx DESC LIMIT $SV_TOMBS", null).use {
                while (it.moveToNext()) tombs.put(JSONArray().put(it.getString(0)).put(it.getString(1)))
            }
        }
        return JSONObject().put("op", "sv").put("v", 1).put("ids", ids).put("tomb", tombs)
    }

    private fun applySummary(o: JSONObject): JSONArray {
        val tombs = o.optJSONArray("tomb") ?: JSONArray()
        for (i in 0 until minOf(tombs.length(), SV_TOMBS)) {
            val t = tombs.optJSONArray(i) ?: continue
            runCatching { applyTomb(t.getString(0), t.getString(1)) }
        }
        val ids = o.optJSONArray("ids") ?: JSONArray()
        val want = JSONArray()
        synchronized(lock) {
            for (i in 0 until ids.length()) {
                val id = ids.optString(i)
                if (id.length != 32 || exists(id) || tombSecret(id) != null) continue
                want.put(id)
                if (want.length() >= ENC_MAX_BUNDLES) break
            }
        }
        return want
    }

    /** Sends the requested bundles, most urgent first, within the per-encounter budget. */
    private fun sendBundles(ch: SecureChannel, want: JSONArray?, peer: String): Pair<Int, Long> {
        if (want == null || want.length() == 0) return 0 to 0L
        val now = System.currentTimeMillis()
        val list = ArrayList<Bundle>()
        synchronized(lock) {
            for (i in 0 until minOf(want.length(), ENC_MAX_BUNDLES)) getBundle(want.optString(i))?.let { list.add(it) }
        }
        list.sortWith(compareBy<Bundle> { priority(it.k) }.thenBy { it.h }.thenBy { it.ageNow(now) })
        var n = 0; var bytes = 0L
        for (b in list) {
            val age = b.ageNow(now)
            if (b.h + 1 > b.mh || age >= b.life) continue
            if (bytes + b.size() > ENC_MAX_BYTES) break
            ch.sendJson(b.json(b.h + 1, age))
            bytes += b.size(); n++
            log("fwd", b, peer, 0)
        }
        return n to bytes
    }

    private fun readBundles(ch: SecureChannel, until: String, peer: String): Pair<Int, JSONObject?> {
        var n = 0
        while (true) {
            val o = ch.recvJson() ?: return n to null
            val op = o.optString("op")
            if (op == until) return n to o
            if (op != "b") return n to null
            Bundle.from(o)?.let { if (runCatching { accept(it, peer) }.getOrDefault(false)) n++ }
        }
    }

    private fun syncWith(ip: String, peer: String) {
        val t0 = System.currentTimeMillis()
        val s = Socket()
        s.connect(InetSocketAddress(ip, Net.SIGNAL_PORT), 2500)
        s.soTimeout = 20_000
        s.tcpNoDelay = true
        val ch = SecureChannel(s)
        try {
            if (ch.connectHandshake(TYPE) != null) return
            ch.sendJson(summary())
            val r = ch.recvJson() ?: return
            if (r.optString("op") != "sv") return
            val myWant = applySummary(r)
            val (sent, bytes) = sendBundles(ch, r.optJSONArray("want"), peer)
            ch.sendJson(JSONObject().put("op", "want").put("ids", myWant))
            val (got, _) = readBundles(ch, "end", peer)
            encounter(peer, sent, got, bytes, System.currentTimeMillis() - t0)
        } finally { ch.close() }
    }

    /** Responder side, called by CallService for an incoming "relay" connection. */
    fun serve(ch: SecureChannel) {
        if (!::db.isInitialized || !enabled) return
        val t0 = System.currentTimeMillis()
        val peer = "in-" + Crypto.hex(Crypto.sha256((ch.remote?.key ?: "").toByteArray())).take(8)
        val o = ch.recvJson() ?: return
        if (o.optString("op") != "sv") return
        val want = applySummary(o)
        ch.sendJson(summary().put("want", want))
        val (got, w) = readBundles(ch, "want", peer)
        val (sent, bytes) = sendBundles(ch, w?.optJSONArray("ids"), peer)
        ch.sendJson(JSONObject().put("op", "end"))
        encounter(peer, sent, got, bytes, System.currentTimeMillis() - t0)
    }

    private fun encounter(peer: String, sent: Int, got: Int, bytes: Long, ms: Long) {
        digestCache = null
        Store.prefs().edit().putInt("rl_enc", Store.prefs().getInt("rl_enc", 0) + 1)
            .putInt("rl_fwd", Store.prefs().getInt("rl_fwd", 0) + sent)
            .putInt("rl_rcv", Store.prefs().getInt("rl_rcv", 0) + got)
            .putLong("rl_last", System.currentTimeMillis()).apply()
        logRaw("encounter", "", "", sent, ms, bytes.toInt(), peer, got)
        maintain()
    }

    /* ======================================================================================
     *  Storage
     * ====================================================================================== */

    private fun insertBundle(b: Bundle, mine: Boolean) {
        db.writableDatabase.insertWithOnConflict("b", null, ContentValues().apply {
            put("id", b.id); put("k", b.k); put("o", b.o); put("t", b.t); put("life", b.life); put("mh", b.mh)
            put("tg", b.to); put("ah", b.ah); put("p", b.p); put("s", b.s); put("h", b.h); put("age", b.age)
            put("rx", b.rx); put("mine", if (mine) 1 else 0); put("sz", b.size())
        }, SQLiteDatabase.CONFLICT_IGNORE)
        digestCache = null
    }

    private fun getBundle(id: String): Bundle? =
        db.readableDatabase.rawQuery("SELECT id,k,o,t,life,mh,tg,ah,p,s,h,age,rx FROM b WHERE id=?", arrayOf(id)).use { c ->
            if (!c.moveToFirst()) null
            else Bundle(c.getString(0), c.getString(1), c.getString(2), c.getLong(3), c.getLong(4), c.getInt(5),
                c.getString(6), c.getString(7), c.getString(8), c.getString(9), c.getInt(10), c.getLong(11), c.getLong(12))
        }

    /** Drop expired bundles/tombstones/board items and evict when the store is over its cap. */
    fun maintain() {
        if (!::db.isInitialized) return
        val now = System.currentTimeMillis()
        val dropped = ArrayList<Triple<String, String, Long>>()
        synchronized(lock) {
            val w = db.writableDatabase
            w.rawQuery("SELECT id, k, age + ($now - rx), life FROM b", null).use { c ->
                while (c.moveToNext()) if (c.getLong(2) >= c.getLong(3)) dropped.add(Triple(c.getString(0), c.getString(1), c.getLong(2)))
            }
            dropped.forEach { w.delete("b", "id=?", arrayOf(it.first)) }
            w.delete("tomb", "rx < ?", arrayOf((now - TOMB_LIFE).toString()))
            w.execSQL("DELETE FROM tomb WHERE id NOT IN (SELECT id FROM tomb ORDER BY rx DESC LIMIT $MAX_TOMBS)")
            w.delete("board", "exp < ?", arrayOf(now.toString()))
            w.delete("ev", "ts < ?", arrayOf((now - 60 * DAY).toString()))
            // eviction: others' bundles first; lowest priority, most hops, oldest first
            var count = 0; var bytes = 0L
            w.rawQuery("SELECT COUNT(*), IFNULL(SUM(sz),0) FROM b", null).use { if (it.moveToFirst()) { count = it.getInt(0); bytes = it.getLong(1) } }
            if (count > capCount() || bytes > capBytes()) {
                w.rawQuery("SELECT id, k, sz FROM b ORDER BY mine ASC, " +
                        "CASE k WHEN 'msg' THEN 0 WHEN 'ann' THEN 1 WHEN 'safe' THEN 2 ELSE 3 END ASC, h DESC, (age + ($now - rx)) DESC", null).use { c ->
                    val del = ArrayList<String>()
                    while (c.moveToNext() && (count > capCount() || bytes > capBytes())) {
                        del.add(c.getString(0)); count--; bytes -= c.getLong(2)
                        logRaw("evict", c.getString(0), c.getString(1), 0, 0, c.getInt(2), "")
                    }
                    del.forEach { w.delete("b", "id=?", arrayOf(it)) }
                }
            }
        }
        dropped.forEach { logRaw("expire", it.first, it.second, 0, it.third, 0, "") }
        digestCache = null
        refresh()
    }

    fun clearStore() {
        synchronized(lock) {
            db.writableDatabase.delete("b", "mine=0", null)
            db.writableDatabase.delete("tomb", null, null)
        }
        digestCache = null
        refresh()
    }

    /* ======================================================================================
     *  Community board (SOS + announcements that reached this phone)
     * ====================================================================================== */

    private fun addBoard(b: Bundle, mine: Boolean) {
        val o = runCatching { JSONObject(b.p) }.getOrNull() ?: return
        val now = System.currentTimeMillis()
        db.writableDatabase.insertWithOnConflict("board", null, ContentValues().apply {
            put("id", b.id); put("k", b.k); put("o", b.o); put("code", o.optString("code")); put("name", o.optString("n"))
            put("title", if (b.k == ANN) o.optString("title") else "")
            put("body", if (b.k == ANN) o.optString("text") else o.optString("note"))
            if (o.has("lat")) { put("lat", o.optDouble("lat")); put("lon", o.optDouble("lon")) }
            put("rx", now); put("exp", now + (b.life - b.age).coerceAtLeast(0)); put("h", b.h); put("age", b.age)
            put("mine", if (mine) 1 else 0)
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    private fun loadBoard(): List<BoardItem> {
        val out = ArrayList<BoardItem>()
        val now = System.currentTimeMillis()
        db.readableDatabase.rawQuery("SELECT id,k,o,code,name,title,body,lat,lon,rx,exp,h,age,mine FROM board WHERE exp > ? ORDER BY rx DESC",
            arrayOf(now.toString())).use { c ->
            while (c.moveToNext()) out.add(BoardItem(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4),
                c.getString(5) ?: "", c.getString(6) ?: "", if (c.isNull(7)) null else c.getDouble(7), if (c.isNull(8)) null else c.getDouble(8),
                c.getLong(9), c.getLong(10), c.getInt(11), c.getLong(12), c.getInt(13) == 1))
        }
        return out
    }

    fun deleteBoardItem(id: String) {
        synchronized(lock) { db.writableDatabase.delete("board", "id=?", arrayOf(id)) }
        refresh()
    }

    private fun nm() = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun notifyBoard(b: Bundle) {
        val o = runCatching { JSONObject(b.p) }.getOrNull() ?: return
        val name = o.optString("n").ifBlank { o.optString("code") }
        val fromContact = Store.contactByKey(b.o)?.let { !it.blocked } == true
        val open = PendingIntent.getActivity(app, 77, Intent(app, RelayActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = if (b.k == SOS) {
            val text = (o.optString("note").ifBlank { L.t("need_help") }) + (if (o.has("lat")) " 📍" else "") +
                    " • " + L.t("hops_n").format(b.h)
            NotificationCompat.Builder(app, if (fromContact) Safety.CH_SOS else CH_BOARD)
                .setSmallIcon(R.drawable.ic_call)
                .setContentTitle("🆘 " + L.t("sos_from").format(name))
                .setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(if (fromContact) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_MESSAGE)
                .setAutoCancel(true).setContentIntent(open).build()
        } else {
            val text = o.optString("title") + "\n" + o.optString("text")
            NotificationCompat.Builder(app, CH_BOARD)
                .setSmallIcon(R.drawable.ic_call)
                .setContentTitle("📢 " + L.t("ann_from").format(name) + if (Store.trustedKey(b.o)) " ✅" else "")
                .setContentText(o.optString("title")).setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true).setContentIntent(open).build()
        }
        runCatching { nm().notify(b.id.hashCode(), n) }
    }

    /* ======================================================================================
     *  Statistics and the research log (opt-in, pseudonymous: no names, codes, numbers or content)
     * ====================================================================================== */

    private fun log(ev: String, b: Bundle, peer: String, x: Int) =
        logRaw(ev, b.id, b.k, b.h, b.ageNow(), b.size(), peer, x)

    private fun logRaw(ev: String, id: String, k: String, h: Int, age: Long, sz: Int, peer: String, x: Int = 0) {
        if (!Store.settings.value.research || !::db.isInitialized) return
        runCatching {
            db.writableDatabase.insert("ev", null, ContentValues().apply {
                put("ts", System.currentTimeMillis()); put("ev", ev); put("bid", id.take(10)); put("k", k)
                put("h", h); put("age", age); put("sz", sz); put("peer", peer); put("x", x)
            })
        }
    }

    fun studyId(): String {
        val sp = Store.prefs()
        return sp.getString("study_id", null) ?: Crypto.hex(Crypto.randomBytes(4)).uppercase().also {
            sp.edit().putString("study_id", it).apply()
        }
    }

    /** Writes the research log as CSV (for the article's evaluation) and returns the file. */
    fun exportCsv(): File {
        val dir = File(app.filesDir, "media").apply { mkdirs() }
        val f = File(dir, "callranya-relay-${studyId()}-${System.currentTimeMillis() / 1000}.csv")
        f.bufferedWriter().use { w ->
            w.write("# Call Ranya relay log; study_id=${studyId()}; app=${BuildConfig.VERSION_NAME}; sdk=${Build.VERSION.SDK_INT}; " +
                    "station=${station}\n")
            w.write("study_id,time_ms,event,bundle,class,hops,age_ms,bytes,peer,extra\n")
            synchronized(lock) {
                db.readableDatabase.rawQuery("SELECT ts,ev,bid,k,h,age,sz,peer,x FROM ev ORDER BY ts", null).use { c ->
                    while (c.moveToNext()) w.write("${studyId()},${c.getLong(0)},${c.getString(1)},${c.getString(2)},${c.getString(3)}," +
                            "${c.getInt(4)},${c.getLong(5)},${c.getInt(6)},${c.getString(7)},${c.getInt(8)}\n")
                }
            }
        }
        return f
    }

    fun clearLog() {
        synchronized(lock) { db.writableDatabase.delete("ev", null, null) }
        refresh()
    }

    fun refresh() {
        if (!::db.isInitialized) return
        runCatching {
            val sp = Store.prefs()
            var held = 0; var bytes = 0L; var mine = 0; var events = 0
            synchronized(lock) {
                val r = db.readableDatabase
                r.rawQuery("SELECT COUNT(*), IFNULL(SUM(sz),0) FROM b", null).use { if (it.moveToFirst()) { held = it.getInt(0); bytes = it.getLong(1) } }
                r.rawQuery("SELECT COUNT(*) FROM own WHERE dlv=1", null).use { if (it.moveToFirst()) mine = it.getInt(0) }
                r.rawQuery("SELECT COUNT(*) FROM ev", null).use { if (it.moveToFirst()) events = it.getInt(0) }
            }
            val toMe = sp.getInt("rl_me", 0)
            stats.value = RelayStats(held, bytes, sp.getInt("rl_enc", 0), sp.getInt("rl_fwd", 0), sp.getInt("rl_rcv", 0),
                toMe, mine, sp.getLong("rl_last", 0L), events)
            board.value = synchronized(lock) { loadBoard() }
        }
    }
}
