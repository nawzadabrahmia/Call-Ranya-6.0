package edu.uor.lancall

import android.util.Base64
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.Socket
import java.nio.ByteBuffer
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyStore
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
fun unb64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)

/**
 * Long-term identity of this phone (ECDSA P-256).
 * The public key travels in QR codes so contacts can be verified face to face.
 */
object Identity {
    private lateinit var kp: KeyPair

    /**
     * Loads (or creates) the identity key pair.
     * Since 6.0 the private key is stored wrapped (AES-256-GCM) by a key that lives in the Android Keystore,
     * so a copied preferences file or backup no longer reveals it. Old plaintext keys are migrated once.
     */
    fun init() {
        if (::kp.isInitialized) return
        val sp = Store.prefs()
        val pub = sp.getString("id_pub", null)
        val prv = sp.getString("id_prv", null)
        val wrapped = sp.getString("id_prv_w", null)
        val kf = KeyFactory.getInstance("EC")
        var loaded: KeyPair? = null
        if (pub != null && wrapped != null) loaded = runCatching {
            KeyPair(kf.generatePublic(X509EncodedKeySpec(unb64(pub))),
                kf.generatePrivate(PKCS8EncodedKeySpec(KeyWrap.unwrap(unb64(wrapped)))))
        }.getOrNull()
        if (loaded == null && pub != null && prv != null) loaded = runCatching {
            KeyPair(kf.generatePublic(X509EncodedKeySpec(unb64(pub))), kf.generatePrivate(PKCS8EncodedKeySpec(unb64(prv))))
        }.getOrNull()
        val fresh = loaded == null
        kp = loaded ?: KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1")); generateKeyPair()
        }
        if (fresh || prv != null || wrapped == null) protect()
    }

    /** Store the private key wrapped by the Keystore; fall back to the old format only if the Keystore fails. */
    private fun protect() {
        val sp = Store.prefs()
        val raw = kp.private.encoded
        val w = runCatching { KeyWrap.wrap(raw).takeIf { KeyWrap.unwrap(it).contentEquals(raw) } }.getOrNull()
        val e = sp.edit().putString("id_pub", b64(kp.public.encoded))
        if (w != null) e.putString("id_prv_w", b64(w)).remove("id_prv") else e.putString("id_prv", b64(raw))
        e.commit()
    }

    val publicBytes: ByteArray get() = kp.public.encoded
    val publicB64: String get() = b64(kp.public.encoded)

    /** Static ECDH with another phone's identity key (used for internet messages). */
    fun agree(theirPub: ByteArray): ByteArray = Crypto.ecdh(kp.private, theirPub)

    fun sign(data: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").run { initSign(kp.private); update(data); sign() }

    fun verify(pub: ByteArray, data: ByteArray, sig: ByteArray): Boolean = try {
        Signature.getInstance("SHA256withECDSA").run { initVerify(Crypto.pub(pub)); update(data); verify(sig) }
    } catch (e: Exception) { false }

    /** Safety number shown to users (96 bits), e.g. "4F2A 91C0 7B3E D455 9A01 33CF" */
    fun fingerprint(pub: ByteArray = publicBytes): String {
        val h = MessageDigest.getInstance("SHA-256").digest(pub)
        return h.take(12).joinToString("") { "%02X".format(it) }.chunked(4).joinToString(" ")
    }
}

/** AES-256-GCM key kept inside the Android Keystore (never leaves it); used to wrap the identity private key. */
object KeyWrap {
    private const val ALIAS = "callranya_identity_wrap"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
        return g.generateKey()
    }

    fun wrap(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val iv = c.iv
        return byteArrayOf(iv.size.toByte()) + iv + c.doFinal(plain)
    }

    fun unwrap(d: ByteArray): ByteArray {
        val n = d[0].toInt()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, d, 1, n))
        return c.doFinal(d, 1 + n, d.size - 1 - n)
    }
}

object Crypto {
    private val rnd = SecureRandom()

    fun pub(bytes: ByteArray): PublicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))

    fun ephemeral(): KeyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1")); generateKeyPair()
    }

    fun ecdh(my: PrivateKey, their: ByteArray): ByteArray =
        KeyAgreement.getInstance("ECDH").run { init(my); doPhase(pub(their), true); generateSecret() }

    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, len: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = ByteArray(len)
        var t = ByteArray(0); var pos = 0; var i = 1
        while (pos < len) {
            mac.update(t); mac.update(info); mac.update(i.toByte())
            t = mac.doFinal()
            val n = minOf(t.size, len - pos)
            System.arraycopy(t, 0, out, pos, n); pos += n; i++
        }
        return out
    }

    fun randomBytes(n: Int) = ByteArray(n).also { rnd.nextBytes(it) }

    fun sha256(vararg parts: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").run { parts.forEach { update(it) }; digest() }

    fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    fun signWith(k: PrivateKey, data: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").run { initSign(k); update(data); sign() }

    /** One-shot AES-256-GCM: returns nonce(12) || ciphertext. */
    fun seal(key: ByteArray, plain: ByteArray): ByteArray {
        val nonce = randomBytes(12)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        return nonce + c.doFinal(plain)
    }

    fun open(key: ByteArray, data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, data, 0, 12))
        return c.doFinal(data, 12, data.size - 12)
    }
}

/** AES-256-GCM in one direction, with a counter nonce (never repeats, rejects replays). */
class GcmStream(key: ByteArray, private val dir: Byte) {
    private val k = SecretKeySpec(key, "AES")
    private var sendCtr = 0L
    private var lastRecv = -1L

    @Synchronized
    fun seal(plain: ByteArray): ByteArray {
        val nonce = nonce(++sendCtr)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, k, GCMParameterSpec(128, nonce))
        val ct = c.doFinal(plain)
        return ByteBuffer.allocate(8 + ct.size).putLong(sendCtr).put(ct).array()
    }

    /** strict = true for TCP (in order); false for UDP audio (allows reordering, drops old). */
    @Synchronized
    fun open(msg: ByteArray, strict: Boolean = true): ByteArray? {
        if (msg.size < 8 + 16) return null
        val ctr = ByteBuffer.wrap(msg, 0, 8).long
        if (strict && ctr != lastRecv + 1 && lastRecv != -1L) return null
        if (!strict && ctr <= lastRecv) return null
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, nonce(ctr)))
            c.doFinal(msg, 8, msg.size - 8).also { lastRecv = ctr }
        } catch (e: Exception) { null }
    }

    private fun nonce(ctr: Long) = ByteBuffer.allocate(12).put(dir).put(0).put(0).put(0).putLong(ctr).array()
}

/** Keys for one secured connection. Initiator and responder mirror each other. */
class Session(okm: ByteArray, private val initiator: Boolean) {
    private val a = okm.copyOfRange(0, 32)
    private val b = okm.copyOfRange(32, 64)
    private val c = okm.copyOfRange(64, 96)
    private val d = okm.copyOfRange(96, 128)
    val tx = GcmStream(if (initiator) a else b, if (initiator) 1 else 2)
    val rx = GcmStream(if (initiator) b else a, if (initiator) 2 else 1)
    fun audioTx() = GcmStream(if (initiator) c else d, if (initiator) 3 else 4)
    fun audioRx() = GcmStream(if (initiator) d else c, if (initiator) 4 else 3)
}

/** What we learned about the other phone during the handshake. */
data class Remote(
    val code: String,
    val name: String,
    val phone: String,
    val key: String,
    val security: Int   // 0 new/unverified, 1 verified, 2 KEY CHANGED
)

/**
 * Length-prefixed frames over TCP. After [handshake] every frame is AES-GCM encrypted.
 *
 * Handshake (v2, unchanged so older phones and the Windows app still connect):
 *   each side sends {code,name,phone,id,eph,sig}; sig = ECDSA(identity, eph || type).
 *   Session keys = HKDF(ECDH(eph_a, eph_b)). Forward secrecy: ephemeral keys are thrown away.
 *
 * Extension x=3 (Call Ranya 6.0, used when BOTH sides send "x":3):
 *   after the keys are derived each side sends, encrypted, an ECDSA signature over the whole transcript
 *   (type, both codes, both identity keys, both ephemeral keys, own role). This binds the identities to the
 *   session (SIGMA-style) and stops identity-misbinding attacks. Once a contact has completed x=3 its
 *   protocol version is pinned, so a later downgrade to v2 is refused.
 *
 * Relay sessions (type "relay") are anonymous: a throw-away key is used instead of the identity key and no
 *   code/name/phone is sent, because relayed bundles are signed and encrypted individually.
 */
class SecureChannel(val socket: Socket) {
    private val inp = DataInputStream(BufferedInputStream(socket.getInputStream(), 65536))
    private val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 65536))
    private var session: Session? = null
    var remote: Remote? = null
        private set

    private var anonKp: KeyPair? = null
    private var theirId: ByteArray = ByteArray(0)
    private var theirX = 0

    val audioSession: Session? get() = session

    /** True when the stronger x=3 handshake was completed with the other side. */
    var strong = false
        private set

    @Synchronized
    fun send(data: ByteArray) {
        val frame = session?.tx?.seal(data) ?: data
        out.writeInt(frame.size); out.write(frame); out.flush()
    }

    fun recv(): ByteArray? {
        val n = try { inp.readInt() } catch (e: EOFException) { return null }
        if (n < 0 || n > 2 * 1024 * 1024) throw IllegalStateException("bad frame")
        val b = ByteArray(n); inp.readFully(b)
        val s = session ?: return b
        return s.rx.open(b) ?: throw SecurityException("decrypt failed")
    }

    fun sendJson(o: JSONObject) = send(o.toString().toByteArray(Charsets.UTF_8))
    fun recvJson(): JSONObject? = recv()?.let { JSONObject(String(it, Charsets.UTF_8)) }

    fun close() { runCatching { socket.close() } }

    private fun anonymous(type: String) = type == Relay.TYPE

    private fun anon(): KeyPair = anonKp ?: Crypto.ephemeral().also { anonKp = it }

    private fun myId(type: String): ByteArray = if (anonymous(type)) anon().public.encoded else Identity.publicBytes

    private fun mySign(type: String, data: ByteArray): ByteArray =
        if (anonymous(type)) Crypto.signWith(anon().private, data) else Identity.sign(data)

    private fun myCode(type: String) = if (anonymous(type)) "" else Store.settings.value.code

    private fun hello(type: String, eph: KeyPair): JSONObject {
        val me = Store.settings.value
        val ephPub = eph.public.encoded
        val anon = anonymous(type)
        return JSONObject()
            .put("v", 2).put("x", 3).put("t", type)
            .put("code", myCode(type)).put("name", if (anon) "" else me.name)
            .put("phone", if (!anon && me.sharePhone) me.phone else "")
            .put("id", b64(myId(type))).put("eph", b64(ephPub))
            .put("sig", b64(mySign(type, ephPub + type.toByteArray())))
    }

    private fun parse(o: JSONObject, type: String): Pair<Remote, ByteArray> {
        val id = unb64(o.getString("id"))
        val eph = unb64(o.getString("eph"))
        val sig = unb64(o.getString("sig"))
        if (!Identity.verify(id, eph + type.toByteArray(), sig)) throw SecurityException("bad signature")
        val code = if (anonymous(type)) "" else o.getString("code")
        val key = b64(id)
        theirId = id
        theirX = o.optInt("x", 0)
        val c = if (code.isEmpty()) null else Store.contact(code)
        // version pinning: a contact that already spoke x=3 with the same key must not be downgraded
        if (c != null && c.proto >= 3 && c.key == key && theirX < 3) throw SecurityException("downgrade")
        val sec = when {
            c == null || c.key.isBlank() -> 0
            c.key != key -> 2
            c.verified -> 1
            else -> 0
        }
        return Remote(code, o.optString("name", code), o.optString("phone"), key, sec) to eph
    }

    private fun derive(eph: KeyPair, theirEph: ByteArray, initiator: Boolean) {
        val secret = Crypto.ecdh(eph.private, theirEph)
        val mine = eph.public.encoded
        val info = if (initiator) mine + theirEph else theirEph + mine
        session = Session(Crypto.hkdf(secret, "UoRCall-v2".toByteArray(), info, 128), initiator)
    }

    private fun lp(b: ByteArray) = ByteBuffer.allocate(4).putInt(b.size).array() + b

    private fun transcript(type: String, iCode: String, iId: ByteArray, iEph: ByteArray,
                           rCode: String, rId: ByteArray, rEph: ByteArray): ByteArray =
        Crypto.sha256("CR-T3|$type|$iCode|$rCode|".toByteArray(), lp(iId), lp(iEph), lp(rId), lp(rEph))

    /** x=3: exchange encrypted transcript signatures (key confirmation + identity binding). */
    private fun finish(type: String, initiator: Boolean, myEph: ByteArray, theirEph: ByteArray) {
        if (theirX < 3) return
        val theirCode = remote?.code ?: ""
        val th = if (initiator) transcript(type, myCode(type), myId(type), myEph, theirCode, theirId, theirEph)
                 else transcript(type, theirCode, theirId, theirEph, myCode(type), myId(type), myEph)
        val myRole = if (initiator) "I" else "R"
        val theirRole = if (initiator) "R" else "I"
        sendJson(JSONObject().put("fin", b64(mySign(type, myRole.toByteArray() + th))))
        val o = recvJson() ?: throw SecurityException("no fin")
        if (!Identity.verify(theirId, theirRole.toByteArray() + th, unb64(o.optString("fin")))) throw SecurityException("bad transcript")
        strong = true
        if (theirCode.isNotEmpty()) Store.markProto(theirCode, remote?.key ?: "", 3)
    }

    /** Client side. Returns error string from the other side (e.g. "busy") or null on success. */
    fun connectHandshake(type: String): String? {
        val eph = Crypto.ephemeral()
        sendJson(hello(type, eph))
        val o = recvJson() ?: return "closed"
        if (o.has("err")) return o.getString("err")
        val (r, theirEph) = parse(o, type)
        remote = r
        derive(eph, theirEph, initiator = true)
        finish(type, true, eph.public.encoded, theirEph)
        return null
    }

    /**
     * Server side, step 1: read the hello. Caller then decides (busy/blocked...) and calls
     * [accept] or [refuse].
     */
    fun readHello(): Triple<String, Remote, ByteArray>? {
        val o = recvJson() ?: return null
        if (o.optInt("v") != 2) return null
        val type = o.optString("t")
        val (r, theirEph) = parse(o, type)
        remote = r
        return Triple(type, r, theirEph)
    }

    fun accept(type: String, theirEph: ByteArray) {
        val eph = Crypto.ephemeral()
        sendJson(hello(type, eph))
        derive(eph, theirEph, initiator = false)
        finish(type, false, eph.public.encoded, theirEph)
    }

    fun refuse(err: String) { runCatching { sendJson(JSONObject().put("err", err)) }; close() }
}
