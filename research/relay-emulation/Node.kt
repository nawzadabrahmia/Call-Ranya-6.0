import edu.uor.lancall.*
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.io.File
import kotlin.concurrent.thread

fun main(args: Array<String>) {
    val (name, code, ip) = Triple(args[0], args[1], args[2])
    val dir = File("/tmp/dtn/$name").apply { deleteRecursively(); mkdirs() }
    android.content.Context.FILES = dir
    val f = Store::class.java.getDeclaredField("sp"); f.isAccessible = true; f.set(null, MemPrefs())
    Store.settings.value = Settings(registered = true, code = code, name = "Person $name", phone = "", relay = true, research = true)
    Identity.init()
    Messenger.init(android.content.Context())
    val ss = ServerSocket(); ss.reuseAddress = true; ss.bind(InetSocketAddress(ip, Net.SIGNAL_PORT))
    thread(isDaemon = true) {
        while (true) {
            val s = ss.accept()
            thread {
                val ch = SecureChannel(s)
                try { val (t, _, eph) = ch.readHello()!!; if (t == Relay.TYPE) { ch.accept(t, eph); s.soTimeout = 20000; Relay.serve(ch) } }
                catch (e: Exception) { println("$name server error $e") } finally { ch.close() }
            }
        }
    }
    val sync = Relay::class.java.getDeclaredMethod("syncWith", String::class.java, String::class.java).apply { isAccessible = true }
    println("READY $name ${Identity.publicB64}")
    while (true) {
        val line = readLine() ?: break
        val p = line.split(" ", limit = 3)
        try {
            when (p[0]) {
                "contact" -> Store.upsertContact(Contact(p[1], "Person " + p[1].take(1), key = p[2], verified = true, team = true))
                "send" -> { Messenger.sendControl(p[1], "x", "text", p[2]); Thread.sleep(4000) }
                "checkin" -> { Messenger.sendControl(p[1], "x", "checkin", JSONObject().put("status", "safe").put("note", p[2]).toString()); Thread.sleep(4000) }
                "sos" -> Relay.broadcastSos(p.drop(1).joinToString(" "), null)
                "ann" -> Relay.postAnnouncement("Water", p.drop(1).joinToString(" "), 24)
                "sync" -> sync.invoke(Relay, p[1], "peer-" + p[1])
                "maintain" -> Relay.maintain()
                "csv" -> { val c = Relay.exportCsv(); println("CSV ${c.readLines().size} lines; events: " + c.readLines().drop(2).map { it.split(",")[2] }.groupingBy { it }.eachCount()) }
            }
            Relay.refresh()
            val st = Relay.stats.value
            val msgs = Messenger.conversations().flatMap { Messenger.messages(it.peer) }
                .joinToString("; ") { (if (it.out) "OUT->" else "IN<-") + it.peer + " [" + it.kind + "] '" + it.body.take(40) + "' status=" + it.status }
            val board = Relay.board.value.joinToString("; ") { it.k + ":" + it.name + ":'" + (it.title + " " + it.text).trim() + "' hops=" + it.hops + " trusted=" + it.trusted }
            println("$name> $line\n   held=${st.held} enc=${st.encounters} fwd=${st.forwarded} rcv=${st.received} toMe=${st.deliveredToMe} myDelivered=${st.myDelivered}\n   msgs: $msgs\n   board: $board")
        } catch (e: Exception) { println("$name ERROR on '$line': $e"); e.printStackTrace(System.out) }
        println("DONE")
    }
}
