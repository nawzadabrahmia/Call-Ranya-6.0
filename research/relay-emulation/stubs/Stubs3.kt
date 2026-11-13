package edu.uor.lancall
import org.json.JSONArray
import org.json.JSONObject
object R { object drawable { const val ic_call = 0 } }
object BuildConfig { const val VERSION_NAME = "6.0" }
class ChatActivity : android.app.Activity()
class RelayActivity : android.app.Activity()
object Cloud {
    val enabled get() = false
    var onOnline: (() -> Unit)? = null
    suspend fun registryKey(code: String): String? = null
    fun insertInbox(id: String, to: String, env: String): Boolean = false
    fun sendBlocking(to: String, call: String, body: JSONObject) {}
    fun fetchInbox(sinceIso: String): JSONArray? = null
    fun upload(name: String, bytes: ByteArray): Boolean = false
    fun download(name: String): ByteArray? = null
}
