package edu.uor.lancall

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class Msg(
    val id: String,
    val peer: String,
    val peerName: String,
    val out: Boolean,
    val kind: String,        // text, image, video, file
    val body: String,
    val path: String,
    val fileName: String,
    val mime: String,
    val size: Long,
    val time: Long,
    val status: Int,         // 0 sending, 1 sent, 2 failed, 3 received, 4 travelling via relay, 5 delivered via relay
    val read: Boolean
)

data class Conversation(val peer: String, val name: String, val last: Msg, val unread: Int)

private class Db(ctx: Context) : SQLiteOpenHelper(ctx, "messages.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE m (id TEXT PRIMARY KEY, peer TEXT, pname TEXT, out INTEGER, kind TEXT, body TEXT, " +
                    "path TEXT, fname TEXT, mime TEXT, size INTEGER, time INTEGER, status INTEGER, rd INTEGER)"
        )
        db.execSQL("CREATE INDEX mp ON m(peer, time)")
    }
    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {}
}

/** Encrypted text / photo / video / file messages over the LAN, with automatic retry. */
object Messenger {
    private lateinit var db: Db
    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    const val MAX_FILE = 1024L * 1024 * 1024   // 1 GB
    private const val CHUNK = 60 * 1024
    const val CH_MSG = "messages"

    fun isFile(kind: String) = kind == "image" || kind == "video" || kind == "audio" || kind == "file"

    fun init(ctx: Context) {
        if (::db.isInitialized) return
        app = ctx.applicationContext
        db = Db(app)
        Relay.init(app)
        Cloud.onOnline = { scope.launch { fetchInbox() } }
        scope.launch {
            delay(8000)
            while (true) { if (Hub.cloud.value == 2) runCatching { fetchInbox() }; delay(60_000) }
        }
        scope.launch {
            delay(5000)
            while (true) { runCatching { retryPending() }; delay(25_000) }
        }
    }

    fun mediaDir(): File = File(app.filesDir, "media").apply { mkdirs() }

    /* ---------------- queries ---------------- */

    private fun row(c: android.database.Cursor) = Msg(
        c.getString(0), c.getString(1), c.getString(2), c.getInt(3) == 1, c.getString(4), c.getString(5),
        c.getString(6), c.getString(7), c.getString(8), c.getLong(9), c.getLong(10), c.getInt(11), c.getInt(12) == 1
    )

    private const val COLS = "id,peer,pname,out,kind,body,path,fname,mime,size,time,status,rd"

    fun messages(peer: String): List<Msg> =
        db.readableDatabase.rawQuery("SELECT $COLS FROM m WHERE peer=? ORDER BY time ASC", arrayOf(peer)).use { c ->
            val l = ArrayList<Msg>(); while (c.moveToNext()) l.add(row(c)); l
        }

    fun conversations(): List<Conversation> {
        val out = ArrayList<Conversation>()
        db.readableDatabase.rawQuery(
            "SELECT $COLS FROM m WHERE time IN (SELECT MAX(time) FROM m GROUP BY peer) ORDER BY time DESC", null
        ).use { c ->
            while (c.moveToNext()) {
                val m = row(c)
                if (out.any { it.peer == m.peer }) continue
                val unread = db.readableDatabase.rawQuery(
                    "SELECT COUNT(*) FROM m WHERE peer=? AND out=0 AND rd=0", arrayOf(m.peer)
                ).use { u -> if (u.moveToFirst()) u.getInt(0) else 0 }
                val name = Store.contact(m.peer)?.name ?: m.peerName
                out.add(Conversation(m.peer, name, m, unread))
            }
        }
        return out
    }

    fun totalUnread(): Int = db.readableDatabase.rawQuery("SELECT COUNT(*) FROM m WHERE out=0 AND rd=0", null)
        .use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun markRead(peer: String) {
        db.writableDatabase.execSQL("UPDATE m SET rd=1 WHERE peer=?", arrayOf(peer))
        runCatching { (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(peer.hashCode()) }
        Hub.bumpChats()
    }

    fun deleteChat(peer: String) {
        messages(peer).forEach { if (it.path.isNotEmpty()) runCatching { File(it.path).delete() } }
        db.writableDatabase.delete("m", "peer=?", arrayOf(peer))
        Hub.bumpChats()
    }

    private fun insert(m: Msg) {
        val v = ContentValues().apply {
            put("id", m.id); put("peer", m.peer); put("pname", m.peerName); put("out", if (m.out) 1 else 0)
            put("kind", m.kind); put("body", m.body); put("path", m.path); put("fname", m.fileName)
            put("mime", m.mime); put("size", m.size); put("time", m.time); put("status", m.status)
            put("rd", if (m.read) 1 else 0)
        }
        db.writableDatabase.insertWithOnConflict("m", null, v, SQLiteDatabase.CONFLICT_IGNORE)
        Hub.bumpChats()
    }

    private fun setStatus(id: String, st: Int) {
        db.writableDatabase.execSQL("UPDATE m SET status=? WHERE id=?", arrayOf<Any>(st, id))
        Hub.bumpChats()
    }

    private fun pending(): List<Msg> =
        db.readableDatabase.rawQuery("SELECT $COLS FROM m WHERE out=1 AND status IN (0,2,4) ORDER BY time ASC", null)
            .use { c -> val l = ArrayList<Msg>(); while (c.moveToNext()) l.add(row(c)); l }

    /* ---------------- sending ---------------- */

    fun sendText(peer: String, peerName: String, text: String) {
        if (text.isBlank()) return
        sendControl(peer, peerName, "text", text.trim())
    }

    /** Text-like messages: text, rollcall, checkin, room invites. */
    fun sendControl(peer: String, peerName: String, kind: String, body: String) {
        val m = Msg(UUID.randomUUID().toString(), peer, peerName, true, kind, body, "", "", "",
            0, System.currentTimeMillis(), 0, true)
        insert(m)
        scope.launch { deliver(m) }
    }

    fun findById(id: String): Msg? =
        db.readableDatabase.rawQuery("SELECT $COLS FROM m WHERE id=?", arrayOf(id)).use { c -> if (c.moveToFirst()) row(c) else null }

    /** All messages of one kind (used by the safety dashboard). */
    fun byKind(kind: String): List<Msg> =
        db.readableDatabase.rawQuery("SELECT $COLS FROM m WHERE kind=? ORDER BY time ASC", arrayOf(kind)).use { c ->
            val l = ArrayList<Msg>(); while (c.moveToNext()) l.add(row(c)); l
        }

    /** Copies the picked file into app storage, then sends it. Returns an error key or null. */
    fun sendFile(peer: String, peerName: String, uri: Uri, caption: String = ""): String? {
        val cr = app.contentResolver
        var name = "file"
        var size = -1L
        runCatching {
            cr.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (ni >= 0) name = c.getString(ni) ?: name
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        }
        if (size > MAX_FILE) return "file_too_big"
        val mime = cr.getType(uri) ?: "application/octet-stream"
        val id = UUID.randomUUID().toString()
        scope.launch {
            try {
                val dest = File(mediaDir(), id + "_" + safe(name))
                cr.openInputStream(uri)?.use { i -> FileOutputStream(dest).use { o -> i.copyTo(o, 64 * 1024) } }
                val m = Msg(id, peer, peerName, true, kindOf(mime), caption, dest.absolutePath, name, mime,
                    dest.length(), System.currentTimeMillis(), 0, true)
                insert(m)
                deliver(m)
            } catch (_: Exception) { }
        }
        return null
    }

    private fun safe(n: String) = n.replace(Regex("[^A-Za-z0-9._\\-\\u0600-\\u06FF]"), "_").takeLast(80)

    fun kindOf(mime: String) = when {
        mime.startsWith("image/") -> "image"
        mime.startsWith("video/") -> "video"
        mime.startsWith("audio/") -> "audio"
        else -> "file"
    }

    fun retryNow(id: String) {
        scope.launch { pending().firstOrNull { it.id == id }?.let { deliver(it) } }
    }

    private suspend fun retryPending() {
        val list = pending()
        if (list.isEmpty()) return
        list.groupBy { it.peer }.forEach { (_, msgs) ->
            var reachable = true
            for (m in msgs) {
                if (reachable) { if (!deliver(m)) reachable = false }
                else if (m.status != 4 && runCatching { Relay.trySend(m) }.getOrDefault(false)) setStatus(m.id, 4)   // person still away → hand to the relay
            }
        }
    }

    private suspend fun findIp(code: String): String? =
        Hub.online.value[code]?.ip ?: Discovery.resolve(code, 1500)?.ip ?: Store.contact(code)?.ip?.ifEmpty { null }

    /** Direct (same Wi-Fi) first, then internet if configured, otherwise hand it to the offline relay network. */
    private suspend fun deliver(m: Msg): Boolean {
        if (!inFlight.add(m.id)) return false
        try {
            if (deliverLan(m)) { setStatus(m.id, 1); return true }
            if (Cloud.enabled && deliverCloud(m)) { setStatus(m.id, 1); return true }
            val cur = findById(m.id)?.status ?: m.status
            if (cur == 5) return false
            setStatus(m.id, if (runCatching { Relay.trySend(m) }.getOrDefault(false)) 4 else 2)
            return false
        } finally {
            inFlight.remove(m.id)
        }
    }

    private suspend fun deliverLan(m: Msg): Boolean {
        try {
            val ip = findIp(m.peer) ?: return false
            val s = Socket()
            s.connect(InetSocketAddress(ip, Net.SIGNAL_PORT), 3000)
            s.soTimeout = 30_000
            val ch = SecureChannel(s)
            try {
                val err = ch.connectHandshake("msg")
                if (err != null) return false
                ch.remote?.let { r -> learn(r, ip) }
                ch.sendJson(
                    JSONObject().put("id", m.id).put("kind", m.kind).put("body", m.body)
                        .put("fname", m.fileName).put("mime", m.mime).put("size", m.size).put("time", m.time)
                )
                if (isFile(m.kind)) {
                    File(m.path).inputStream().use { i ->
                        val buf = ByteArray(CHUNK)
                        while (true) {
                            val n = i.read(buf); if (n <= 0) break
                            ch.send(if (n == buf.size) buf else buf.copyOf(n))
                        }
                    }
                }
                val ack = ch.recvJson()
                return ack?.optString("ok") == m.id
            } finally { ch.close() }
        } catch (e: Exception) {
            return false
        }
    }

    /* ---------------- internet delivery (end-to-end encrypted, stored up to 14 days) ---------------- */

    const val MAX_CLOUD_FILE = 25L * 1024 * 1024

    private fun msgKey(theirKey: String, id: String): ByteArray =
        Crypto.hkdf(Identity.agree(unb64(theirKey)), id.toByteArray(), "uorcall-msg-v1".toByteArray(), 32)

    private suspend fun deliverCloud(m: Msg): Boolean {
        val key = Cloud.registryKey(m.peer) ?: return false
        val c = Store.contact(m.peer)
        if (c != null && c.key.isNotBlank() && c.key != key) return false   // key mismatch → never send to an impostor
        val payload = JSONObject().put("kind", m.kind).put("body", m.body).put("fname", m.fileName)
            .put("mime", m.mime).put("size", m.size).put("time", m.time)
        if (isFile(m.kind)) {
            val f = File(m.path)
            if (f.length() > MAX_CLOUD_FILE) return false
            val fileKey = Crypto.randomBytes(32)
            val blob = UUID.randomUUID().toString().replace("-", "")
            if (!Cloud.upload(blob, Crypto.seal(fileKey, f.readBytes()))) return false
            payload.put("blob", blob).put("fk", b64(fileKey))
        }
        val me = Store.settings.value
        val inner = JSONObject().put("from", me.code).put("to", m.peer).put("id", m.id).put("n", me.name)
            .put("ts", System.currentTimeMillis())
            .put("c", b64(Crypto.seal(msgKey(key, m.id), payload.toString().toByteArray(Charsets.UTF_8))))
            .toString()
        val env = JSONObject().put("m", inner).put("s", b64(Identity.sign(inner.toByteArray()))).put("k", Identity.publicB64)
        if (!Cloud.insertInbox(m.id, m.peer, env.toString())) return false
        Cloud.sendBlocking(m.peer, "msg", JSONObject().put("c", "inbox"))   // doorbell: fetch now
        return true
    }

    private val fetching = java.util.concurrent.atomic.AtomicBoolean(false)

    suspend fun fetchInbox() {
        if (!Cloud.enabled || !fetching.compareAndSet(false, true)) return
        try {
            val sp = Store.prefs()
            val since = sp.getString("inbox_since", "2000-01-01T00:00:00Z") ?: "2000-01-01T00:00:00Z"
            val rows = Cloud.fetchInbox(since) ?: return
            var last = since
            for (i in 0 until rows.length()) {
                val r = rows.getJSONObject(i)
                last = r.optString("created_at", last)
                runCatching { receiveCloud(r.getString("id"), r.getString("from_code"), JSONObject(r.getString("env"))) }
            }
            if (last != since) sp.edit().putString("inbox_since", last).apply()
        } finally { fetching.set(false) }
    }

    private suspend fun receiveCloud(id: String, from: String, env: JSONObject) {
        if (findById(id) != null || !Store.allowed(from)) return
        val inner = env.getString("m"); val k = env.getString("k")
        if (!Identity.verify(unb64(k), inner.toByteArray(), unb64(env.getString("s")))) return
        val reg = Cloud.registryKey(from)
        if (reg != null && reg != k) return
        val o = JSONObject(inner)
        if (o.optString("from") != from || o.optString("id") != id || o.optString("to") != Store.settings.value.code) return
        val p = JSONObject(String(Crypto.open(msgKey(k, id), unb64(o.getString("c"))), Charsets.UTF_8))
        val kind = p.optString("kind", "text")
        var path = ""
        if (isFile(kind)) {
            val data = Cloud.download(p.getString("blob")) ?: return
            val f = File(mediaDir(), id + "_" + safe(p.optString("fname", "file")))
            f.writeBytes(Crypto.open(unb64(p.getString("fk")), data))
            path = f.absolutePath
        }
        Store.contact(from)?.let { if (it.key.isBlank()) Store.upsertContact(it.copy(key = k)) }
        val m = Msg(id, from, o.optString("n", from), false, kind, p.optString("body"), path, p.optString("fname"),
            p.optString("mime"), p.optLong("size"), System.currentTimeMillis(), 3, Hub.openChat == from)
        insert(m)
        onIncoming(m)
    }

    /* ---------------- offline relay (store-carry-forward) ---------------- */

    /** The relay confirmed (hash-locked receipt) that the recipient got this message. */
    fun markRelayDelivered(mid: String) {
        val m = findById(mid) ?: return
        if (m.out && m.status in listOf(0, 2, 4)) setStatus(mid, 5)
    }

    /** A message addressed to me arrived through other phones. [originKey] signed the bundle. */
    fun receiveRelay(inner: JSONObject, originKey: String) {
        val from = inner.optString("from")
        val mid = inner.optString("mid")
        if (from.length != 8 || mid.isEmpty() || from == Store.settings.value.code) return
        if (!Store.allowed(from) || findById(mid) != null) return
        val kind = inner.optString("kind", "text")
        val c = Store.contact(from)
        var body = inner.optString("body")
        if (c != null && c.key.isNotBlank() && c.key != originKey) {
            if (kind != "text") return                                   // never accept a safety status from a different key
            body = "⚠ " + L.t("relay_key_warn") + "\n" + body
        } else {
            if (c != null && c.key.isBlank()) Store.upsertContact(c.copy(key = originKey))
            Relay.rememberKey(from, originKey)
        }
        var path = ""
        if (isFile(kind)) {
            val data = runCatching { unb64(inner.optString("file")) }.getOrNull() ?: return
            if (data.size > Relay.MAX_FILE) return
            val f = File(mediaDir(), mid + "_" + safe(inner.optString("fname", "file")))
            f.writeBytes(data)
            path = f.absolutePath
        }
        val m = Msg(mid, from, inner.optString("n", from), false, kind, body, path, inner.optString("fname"),
            inner.optString("mime"), inner.optLong("size"), System.currentTimeMillis(), 3, Hub.openChat == from)
        insert(m)
        onIncoming(m)
    }

    /** Save what we learned about a peer (TOFU: first key is remembered). */
    fun learn(r: Remote, ip: String) {
        val c = Store.contact(r.code) ?: return
        Store.upsertContact(c.copy(ip = ip, key = if (c.key.isBlank()) r.key else c.key,
            phone = c.phone.ifBlank { r.phone }))
    }

    /* ---------------- receiving (called by CallService) ---------------- */

    fun receive(ch: SecureChannel, r: Remote) {
        val ip = ch.socket.inetAddress?.hostAddress ?: ""
        learn(r, ip)
        while (true) {
            val meta = ch.recvJson() ?: break
            val id = meta.getString("id")
            val kind = meta.optString("kind", "text")
            val size = meta.optLong("size")
            var path = ""
            if (isFile(kind)) {
                if (size < 0 || size > MAX_FILE) throw IllegalStateException("size")
                val f = File(mediaDir(), id + "_" + safe(meta.optString("fname", "file")))
                FileOutputStream(f).use { o ->
                    var got = 0L
                    while (got < size) {
                        val chunk = ch.recv() ?: throw IllegalStateException("eof")
                        o.write(chunk); got += chunk.size
                    }
                }
                path = f.absolutePath
            }
            val m = Msg(id, r.code, r.name, false, kind, meta.optString("body"), path,
                meta.optString("fname"), meta.optString("mime"), size, System.currentTimeMillis(), 3,
                Hub.openChat == r.code)
            val fresh = findById(id) == null
            insert(m)
            ch.sendJson(JSONObject().put("ok", id))
            if (fresh) onIncoming(m)
        }
    }

    private fun onIncoming(m: Msg) {
        when (m.kind) {
            "rollcall" -> Safety.onRollCall(app, m)
            "checkin" -> Safety.onCheckin(app, m)
            else -> if (Hub.openChat != m.peer) notifyMessage(m)
        }
    }

    fun preview(m: Msg): String = when (m.kind) {
        "text" -> m.body
        "image" -> "📷 " + L.t("photo")
        "video" -> "🎬 " + L.t("video")
        "rollcall" -> "📋 " + L.t("rollcall")
        "checkin" -> if (Safety.parse(m.body).optString("status") == "help") "🆘 " + L.t("need_help") else "✅ " + L.t("im_safe")
        "room" -> "🎙 " + L.t("room_invite")
        else -> "📎 " + m.fileName
    }

    private fun notifyMessage(m: Msg) {
        val name = Store.contact(m.peer)?.name ?: m.peerName
        val text = preview(m)
        val open = PendingIntent.getActivity(
            app, m.peer.hashCode(),
            Intent(app, ChatActivity::class.java).putExtra("code", m.peer).putExtra("name", name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(app, CH_MSG)
            .setSmallIcon(R.drawable.ic_call)
            .setContentTitle(name)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching { (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(m.peer.hashCode(), n) }
    }
}
