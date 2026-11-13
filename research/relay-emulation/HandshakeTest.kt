import edu.uor.lancall.*
import android.content.SharedPreferences
import org.json.JSONObject
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

fun serverOnce(ss: ServerSocket, onDone: (String) -> Unit) = thread {
    val s = ss.accept()
    val ch = SecureChannel(s)
    try {
        val (type, r, eph) = ch.readHello()!!
        ch.accept(type, eph)
        val o = ch.recvJson()!!
        ch.sendJson(JSONObject().put("echo", o.getString("x")))
        onDone("server ok strong=${ch.strong} type=$type code='${r.code}'")
    } catch (e: Exception) { onDone("server error: $e") } finally { ch.close() }
}

fun main() {
    val f = Store::class.java.getDeclaredField("sp"); f.isAccessible = true; f.set(null, MemPrefs())
    Store.settings.value = Settings(registered = true, code = "11112222", name = "Nawzad", phone = "07500000000")
    Identity.init()
    println("identity ok, fingerprint=" + Identity.fingerprint())
    val ss = ServerSocket(0)

    for (type in listOf("msg", "call", Relay.TYPE)) {
        var res = ""
        val t = serverOnce(ss) { res = it }
        val ch = SecureChannel(Socket("127.0.0.1", ss.localPort))
        val err = ch.connectHandshake(type)
        ch.sendJson(JSONObject().put("x", "hello-$type"))
        val back = ch.recvJson()
        t.join()
        println("[$type] client err=$err strong=${ch.strong} echo=${back?.optString("echo")} | $res")
        ch.close()
    }

    // ---- old (v5) client without x=3 talking to a v6 server ----
    fun oldClient(): String {
        val sock = Socket("127.0.0.1", ss.localPort)
        val inp = java.io.DataInputStream(sock.getInputStream()); val out = java.io.DataOutputStream(sock.getOutputStream())
        fun send(b: ByteArray) { out.writeInt(b.size); out.write(b); out.flush() }
        fun recv(): ByteArray { val n = inp.readInt(); return ByteArray(n).also { inp.readFully(it) } }
        val eph = Crypto.ephemeral(); val ephPub = eph.public.encoded
        send(JSONObject().put("v", 2).put("t", "msg").put("code", "11112222").put("name", "old").put("phone", "")
            .put("id", Identity.publicB64).put("eph", b64(ephPub)).put("sig", b64(Identity.sign(ephPub + "msg".toByteArray()))).toString().toByteArray())
        val h = JSONObject(String(recv()))
        if (h.has("err")) return "refused: " + h.getString("err")
        val theirEph = unb64(h.getString("eph"))
        val sess = Session(Crypto.hkdf(Crypto.ecdh(eph.private, theirEph), "UoRCall-v2".toByteArray(), ephPub + theirEph, 128), true)
        send(sess.tx.seal(JSONObject().put("x", "from-v5").toString().toByteArray()))
        val back = sess.rx.open(recv())?.let { String(it) }
        sock.close()
        return "old client got: $back"
    }
    run {
        var res = ""; val t = serverOnce(ss) { res = it }
        val r = runCatching { oldClient() }.getOrElse { "exception $it" }
        t.join(); println("[v5 client -> v6 server] $r | $res")
    }

    // ---- downgrade protection: contact pinned at proto 3, then an old-style hello arrives ----
    Store.contacts.value = listOf(Contact("11112222", "Nawzad", key = Identity.publicB64, proto = 3))
    run {
        var res = ""; val t = serverOnce(ss) { res = it }
        val r = runCatching { oldClient() }.getOrElse { "client sees: ${it.javaClass.simpleName}" }
        t.join(); println("[downgrade attempt] $r | $res")
    }
}
