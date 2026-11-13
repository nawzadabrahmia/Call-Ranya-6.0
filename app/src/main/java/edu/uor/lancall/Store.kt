package edu.uor.lancall

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

/* ---------------- Models ---------------- */

data class Peer(
    val code: String,
    val name: String,
    val ip: String,
    val phone: String = "",
    val lastSeen: Long = 0L,
    val status: Int = 0,          // 0 free, 1 busy, 2 do-not-disturb
    val visible: Boolean = true    // false = found by exact code/phone only
)

data class Contact(
    val code: String,
    val name: String,
    val phone: String = "",
    val ip: String = "",
    val key: String = "",          // identity public key (Base64) – for end-to-end verification
    val verified: Boolean = false, // key confirmed by scanning QR face-to-face
    val blocked: Boolean = false,
    val team: Boolean = false,     // in the safety/roll-call "team" group
    val proto: Int = 0             // highest handshake version completed with this key (3 = pinned, no downgrade)
)

data class HistoryItem(
    val type: String,              // out, in, missed, declined
    val code: String,
    val name: String,
    val ip: String,
    val time: Long,
    val duration: Long
)

enum class Phase { IDLE, OUTGOING, INCOMING, ACTIVE, ENDED }

/** 0 = new/unverified (encrypted), 1 = verified, 2 = key changed (warning) */
data class CallUi(
    val phase: Phase = Phase.IDLE,
    val peer: Peer? = null,
    val incoming: Boolean = false,
    val startedAt: Long = 0L,
    val muted: Boolean = false,
    val speaker: Boolean = false,
    val message: String = "",
    val security: Int = 0,
    val video: Boolean = false,      // my camera is on
    val sharing: Boolean = false,    // I'm sharing my screen
    val internet: Boolean = false,   // this call goes over the internet
    val wantVideo: Boolean = false,  // the caller asked for a video call
    val supportReq: Boolean = false, // this call is a remote-support session
    val supportRole: String = "",    // "helper" (I help) or "target" (I am being helped)
    val supportMode: String = ""     // "", "view", "control", "off"
)

data class Settings(
    val registered: Boolean = false,
    val code: String = "",
    val name: String = "",
    val phone: String = "",
    val lang: String = "en",       // en (primary/default), ckb, ar
    val theme: String = "system",  // system, light, dark
    val visible: Boolean = false,  // show me in "Available now" list
    val sharePhone: Boolean = false,
    val whoCanCall: String = "everyone", // everyone, contacts
    val dnd: Boolean = false,
    val autoStart: Boolean = true,
    val alwaysAwake: Boolean = true,
    val org: String = "",
    val internet: Boolean = true,     // allow internet calls when Supabase is configured
    val shareLocation: Boolean = false, // attach GPS position to safety check-ins
    val relay: Boolean = true,        // carry encrypted messages/SOS for others (offline relay network)
    val relayStation: Boolean = false,// relay station: more storage (phone kept at a clinic, school, checkpoint…)
    val research: Boolean = false     // record anonymous relay statistics for the study (opt-in)
)

/* ---------------- Live state ---------------- */

object Hub {
    val call = MutableStateFlow(CallUi())
    val peers = MutableStateFlow<List<Peer>>(emptyList())       // visible people
    val online = MutableStateFlow<Map<String, Peer>>(emptyMap()) // every phone we know is reachable (incl. hidden contacts)
    val myIp = MutableStateFlow("")
    val codeConflict = MutableStateFlow(false)
    val serviceRunning = MutableStateFlow(false)
    val cloud = MutableStateFlow(0)            // 0 off, 1 connecting, 2 online
    val remoteVideo = MutableStateFlow(false)  // receiving video frames
    val chatVersion = MutableStateFlow(0)   // bumped whenever messages change
    @Volatile var openChat: String? = null  // code of the chat currently on screen

    fun isBusy(): Boolean = call.value.phase.let { it != Phase.IDLE && it != Phase.ENDED }
    fun bumpChats() { chatVersion.value = chatVersion.value + 1 }
}

/* ---------------- Persistent storage ---------------- */

object Store {
    private lateinit var sp: SharedPreferences
    val settings = MutableStateFlow(Settings())
    val contacts = MutableStateFlow<List<Contact>>(emptyList())
    val history = MutableStateFlow<List<HistoryItem>>(emptyList())

    fun newCode(): String {
        val r = SecureRandom()
        return (10_000_000 + r.nextInt(90_000_000)).toString()
    }

    fun formatCode(c: String) = if (c.length == 8) c.substring(0, 4) + " " + c.substring(4) else c

    /** Compare phone numbers by their last 10 digits (07501234567 == +9647501234567). */
    fun phoneKey(p: String): String = p.filter(Char::isDigit).takeLast(10)

    fun init(ctx: Context) {
        if (::sp.isInitialized) return
        sp = ctx.getSharedPreferences("uorcall", Context.MODE_PRIVATE)
        val oldCode = sp.getString("code", "") ?: ""
        val registered = sp.getBoolean("registered", false) && oldCode.length == 8
        settings.value = Settings(
            registered = registered,
            code = if (oldCode.length == 8) oldCode else newCode(),
            name = sp.getString("name", "") ?: "",
            phone = sp.getString("phone", "") ?: "",
            lang = (sp.getString("lang", "en") ?: "en").let { if (it in listOf("en", "ckb", "ar")) it else "en" },
            theme = sp.getString("theme", "system") ?: "system",
            visible = sp.getBoolean("visible", false),
            sharePhone = sp.getBoolean("sharePhone", false),
            whoCanCall = sp.getString("who", "everyone") ?: "everyone",
            dnd = sp.getBoolean("dnd", false),
            autoStart = sp.getBoolean("autoStart", true),
            alwaysAwake = sp.getBoolean("awake", true),
            org = sp.getString("org", "") ?: "",
            internet = sp.getBoolean("internet", true),
            shareLocation = sp.getBoolean("shareLoc", false),
            relay = sp.getBoolean("relay", true),
            relayStation = sp.getBoolean("relayStation", false),
            research = sp.getBoolean("research", false)
        )
        contacts.value = loadContacts()
        history.value = loadHistory()
    }

    fun save(s: Settings) {
        settings.value = s
        sp.edit()
            .putBoolean("registered", s.registered)
            .putString("code", s.code)
            .putString("name", s.name)
            .putString("phone", s.phone)
            .putString("lang", s.lang)
            .putString("theme", s.theme)
            .putBoolean("visible", s.visible)
            .putBoolean("sharePhone", s.sharePhone)
            .putString("who", s.whoCanCall)
            .putBoolean("dnd", s.dnd)
            .putBoolean("autoStart", s.autoStart)
            .putBoolean("awake", s.alwaysAwake)
            .putString("org", s.org)
            .putBoolean("internet", s.internet)
            .putBoolean("shareLoc", s.shareLocation)
            .putBoolean("relay", s.relay)
            .putBoolean("relayStation", s.relayStation)
            .putBoolean("research", s.research)
            .apply()
    }

    fun prefs(): SharedPreferences = sp

    /* ---- contacts ---- */
    private fun loadContacts(): List<Contact> = try {
        val a = JSONArray(sp.getString("contacts2", "[]"))
        (0 until a.length()).map { contactFromJson(a.getJSONObject(it)) }.filter { it.code.isNotEmpty() }
    } catch (e: Exception) { emptyList() }

    fun contactToJson(c: Contact): JSONObject = JSONObject()
        .put("c", c.code).put("n", c.name).put("p", c.phone).put("ip", c.ip)
        .put("k", c.key).put("v", c.verified).put("b", c.blocked).put("t", c.team).put("pv", c.proto)

    fun contactFromJson(o: JSONObject) = Contact(
        code = o.optString("c"), name = o.optString("n"), phone = o.optString("p"),
        ip = o.optString("ip"), key = o.optString("k"),
        verified = o.optBoolean("v"), blocked = o.optBoolean("b"), team = o.optBoolean("t"),
        proto = o.optInt("pv", 0)
    )

    @Synchronized
    fun saveContacts(list: List<Contact>) {
        contacts.value = list.sortedBy { it.name.lowercase() }
        val a = JSONArray()
        contacts.value.forEach { a.put(contactToJson(it)) }
        sp.edit().putString("contacts2", a.toString()).apply()
    }

    fun contact(code: String): Contact? = contacts.value.firstOrNull { it.code == code }

    /** Add or merge a contact (keeps existing key/verified/blocked unless new info is stronger). */
    @Synchronized
    fun upsertContact(c: Contact) {
        val old = contact(c.code)
        val merged = if (old == null) c else old.copy(
            name = c.name.ifBlank { old.name },
            phone = c.phone.ifBlank { old.phone },
            ip = c.ip.ifBlank { old.ip },
            key = c.key.ifBlank { old.key },
            verified = c.verified || (old.verified && (c.key.isBlank() || c.key == old.key)),
            team = c.team || old.team,
            proto = if (c.key.isBlank() || c.key == old.key) maxOf(c.proto, old.proto) else c.proto
        )
        saveContacts(contacts.value.filterNot { it.code == c.code } + merged)
    }

    /** Remember that this contact (with this key) completed the x=3 handshake → refuse later downgrades. */
    @Synchronized
    fun markProto(code: String, key: String, v: Int) {
        val c = contact(code) ?: return
        if (c.key != key || c.proto >= v) return
        saveContacts(contacts.value.filterNot { it.code == code } + c.copy(proto = v))
    }

    /** Key of a non-blocked contact that I trust for announcements (verified by QR or in my team). */
    fun trustedKey(key: String): Boolean =
        key == Identity.publicB64 || contacts.value.any { it.key == key && !it.blocked && (it.verified || it.team) }

    fun blockedKey(key: String): Boolean = contacts.value.any { it.key == key && it.blocked }

    fun contactByKey(key: String): Contact? = contacts.value.firstOrNull { it.key == key && key.isNotEmpty() }

    fun removeContact(code: String) = saveContacts(contacts.value.filterNot { it.code == code })

    fun setBlocked(code: String, name: String, blocked: Boolean) {
        val old = contact(code) ?: Contact(code, name)
        saveContacts(contacts.value.filterNot { it.code == code } + old.copy(blocked = blocked))
    }

    fun isBlocked(code: String) = contact(code)?.blocked == true

    fun setTeam(code: String, on: Boolean) {
        val old = contact(code) ?: return
        saveContacts(contacts.value.filterNot { it.code == code } + old.copy(team = on))
    }

    fun team(): List<Contact> = contacts.value.filter { it.team && !it.blocked }

    /** Privacy rules shared by calls, messages and rooms. */
    fun allowed(code: String): Boolean {
        if (isBlocked(code)) return false
        if (settings.value.whoCanCall == "contacts" && contact(code) == null) return false
        return true
    }

    /* ---- history ---- */
    private fun loadHistory(): List<HistoryItem> = try {
        val a = JSONArray(sp.getString("history", "[]"))
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            HistoryItem(o.optString("type"), o.optString("code"), o.optString("name"),
                o.optString("ip"), o.optLong("time"), o.optLong("dur"))
        }
    } catch (e: Exception) { emptyList() }

    @Synchronized
    fun addHistory(h: HistoryItem) {
        val list = (listOf(h) + history.value).take(300)
        history.value = list
        persistHistory(list)
    }

    @Synchronized
    fun clearHistory() { history.value = emptyList(); persistHistory(emptyList()) }

    private fun persistHistory(list: List<HistoryItem>) {
        val a = JSONArray()
        list.forEach {
            a.put(JSONObject().put("type", it.type).put("code", it.code).put("name", it.name)
                .put("ip", it.ip).put("time", it.time).put("dur", it.duration))
        }
        sp.edit().putString("history", a.toString()).apply()
    }
}
