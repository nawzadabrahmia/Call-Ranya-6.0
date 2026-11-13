package edu.uor.lancall

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID

/**
 * Always-running foreground service:
 *  - presence (Discovery) and the secure TCP server on 45455
 *  - incoming calls (rings + wakes the screen, answer from the lock screen)
 *  - incoming messages/files (Messenger)
 *  - the call itself (encrypted signalling + encrypted voice)
 */
class CallService : Service() {

    companion object {
        const val CH_SERVICE = "service"
        const val CH_CALL = "incoming_call"
        const val CH_MISSED = "missed"
        const val NOTIF_SERVICE = 1
        const val NOTIF_CALL = 2
        const val NOTIF_MISSED = 3
        const val ACTION_DECLINE = "edu.uor.lancall.DECLINE"
        const val ACTION_HANGUP = "edu.uor.lancall.HANGUP"
        const val ACTION_STOP = "edu.uor.lancall.STOP"
        const val RING_TIMEOUT_MS = 45_000L

        @Volatile var instance: CallService? = null
            private set

        fun start(ctx: Context) {
            if (!Store.settings.value.registered) return
            try { ContextCompat.startForegroundService(ctx, Intent(ctx, CallService::class.java)) } catch (_: Exception) { }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private lateinit var ringer: Ringer

    @Volatile private var server: ServerSocket? = null
    @Volatile private var link: Link? = null
    @Volatile private var engine: RtcEngine? = null
    private var timeoutJob: Job? = null

    private var cpuLock: PowerManager.WakeLock? = null
    private var proxLock: PowerManager.WakeLock? = null
    private var screenLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wifiLowLatency: WifiManager.WifiLock? = null
    private var mcLock: WifiManager.MulticastLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        Store.init(this); Identity.init(); Messenger.init(this)
        if (!goForeground(false)) { stopSelf(); return }
        ringer = Ringer(this)
        acquireLocks()
        Discovery.start()
        Relay.start()
        startServer()
        Cloud.onMessage = { from, call, o, _, sec -> onCloud(from, call, o, sec) }
        Cloud.start()
        Hub.serviceRunning.value = true
        scope.launch {
            combine(Hub.myIp, Store.settings, Hub.cloud) { _, _, _ -> }.collect { if (!Hub.isBusy()) notifyService() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DECLINE -> decline()
            ACTION_HANGUP -> hangup()
            ACTION_STOP -> { stopEverything(); return START_NOT_STICKY }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        Hub.serviceRunning.value = false
        Discovery.stop()
        Relay.stop()
        runCatching { server?.close() }
        runCatching { engine?.close() }
        Cloud.stop()
        if (::ringer.isInitialized) ringer.stop()
        releaseLocks()
        scope.cancel()
        super.onDestroy()
    }

    private fun stopEverything() {
        if (Hub.isBusy()) hangup()
        Store.save(Store.settings.value.copy(autoStart = false))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /* ======================= Public API used by the UI ======================= */

    fun announceNow() = Discovery.announceNow()

    private fun errKey(err: String) = when (err) { "busy" -> "busy"; "dnd" -> "dnd_on"; else -> "unavailable" }

    private fun newEngine(caller: Boolean, l: () -> Link?): RtcEngine =
        RtcEngine(this, caller,
            signal = { o -> l()?.send(o) },
            onConnected = {
                Hub.call.update { it.copy(message = "connected") }
                notifyService()
            },
            onFailed = { endCall("lost", true) },
            onData = { s -> onControl(s) })

    /* ---------------- remote support ---------------- */

    /** Control events from the helper – applied ONLY if I allowed "view & control" in this session. */
    private fun onControl(s: String) {
        val c = Hub.call.value
        if (c.phase != Phase.ACTIVE || c.supportRole != "target" || c.supportMode != "control") return
        val o = runCatching { JSONObject(s) }.getOrNull() ?: return
        RemoteAccessService.instance?.handle(o)
    }

    /** Helper side: send a tap / swipe / key / text to the device I'm helping. */
    fun sendControl(o: JSONObject) {
        val c = Hub.call.value
        if (c.supportRole == "helper" && c.supportMode == "control") engine?.sendData(o.toString())
    }

    /** Target side: the user chose view / control / off. Screen sharing must already be running for view/control. */
    fun setSupportMode(mode: String) {
        val c = Hub.call.value
        if (c.supportRole != "target" || c.phase != Phase.ACTIVE) return
        val m = if (mode == "control" && RemoteAccessService.instance == null) "view" else mode
        Hub.call.update { it.copy(supportMode = m) }
        link?.send(JSONObject().put("c", "support").put("mode", m))
        if (m == "off") stopScreenShare()
        notifyService()
    }

    /** Call someone: local Wi-Fi first, internet as fallback. */
    fun dial(target: Peer, video: Boolean = false, support: Boolean = false) {
        synchronized(lock) {
            if (Hub.isBusy()) return
            Hub.call.value = CallUi(phase = Phase.OUTGOING, peer = target, incoming = false, message = "connecting",
                video = video, wantVideo = video, speaker = video || support,
                supportReq = support, supportRole = if (support) "helper" else "")
        }
        Rooms.leave()
        RtcEngine.init(applicationContext)
        val eng = newEngine(true) { link }
        engine = eng
        if (video) { eng.setCamera(true); eng.setSpeaker(true) }
        goForeground(true, camera = video)
        scope.launch {
            val lanIp = Hub.online.value[target.code]?.ip
                ?: (if (target.code.length == 8) Discovery.resolve(target.code, 1500)?.ip else null)
                ?: target.ip.ifEmpty { null }
                ?: Store.contact(target.code)?.ip?.ifEmpty { null }
            if (lanIp != null && tryLan(target, lanIp, video, support)) return@launch
            if (Hub.call.value.phase != Phase.OUTGOING) return@launch
            if (Cloud.enabled) dialInternet(target, video, support)
            else endCall("unreachable", false, busyTone = true)
        }
    }

    /** @return true if we reached the phone on the LAN (the call continues here), false to try the internet. */
    private fun tryLan(target: Peer, ip: String, video: Boolean, support: Boolean): Boolean {
        val s = Socket()
        try { s.connect(InetSocketAddress(ip, Net.SIGNAL_PORT), 2500) } catch (e: Exception) { runCatching { s.close() }; return false }
        val c = SecureChannel(s)
        var l: LanLink? = null
        try {
            s.tcpNoDelay = true
            val err = c.connectHandshake("call")
            if (err != null) { c.close(); endCall(errKey(err), false, busyTone = true); return true }
            if (Hub.call.value.phase != Phase.OUTGOING) { c.close(); return true }
            val r = c.remote!!
            Messenger.learn(r, ip)
            val link0 = LanLink(c)
            l = link0
            link = link0
            Hub.call.update {
                it.copy(security = r.security, internet = false,
                    peer = Peer(r.code, Store.contact(r.code)?.name ?: r.name, ip, r.phone))
            }
            link0.send(JSONObject().put("c", "invite").put("video", video).put("support", support))
            startRingTimeout()
            while (true) {
                val o = c.recvJson() ?: break
                onLinkMessage(link0, o)
            }
        } catch (_: Exception) {
        }
        if (l != null && link === l) endCall(if (Hub.call.value.phase == Phase.OUTGOING) "unreachable" else "ended", false)
        return true
    }

    private suspend fun dialInternet(target: Peer, video: Boolean, support: Boolean) {
        Hub.call.update { it.copy(internet = true, message = "calling_internet") }
        val u = Cloud.lookup(target.code)
        if (u == null) { endCall("unreachable", false, busyTone = true); return }
        if (Hub.call.value.phase != Phase.OUTGOING) return
        val c = Store.contact(u.code)
        val sec = when { c == null || c.key.isBlank() -> 0; c.key != u.key -> 2; c.verified -> 1; else -> 0 }
        val l = CloudLink(u.code, UUID.randomUUID().toString())
        link = l
        Hub.call.update { cur ->
            val p = cur.peer
            cur.copy(security = sec, peer = p?.copy(name = c?.name ?: u.name.ifBlank { p.name }, ip = ""))
        }
        startRingTimeout()
        // the doorbell is repeated a few times in case the first message is lost
        repeat(4) {
            if (link !== l || Hub.call.value.phase != Phase.OUTGOING || Hub.call.value.message == "ringing") return
            l.send(JSONObject().put("c", "call").put("video", video).put("support", support))
            delay(4000)
        }
    }

    private fun startRingTimeout() {
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(RING_TIMEOUT_MS)
            val ph = Hub.call.value.phase
            if (ph == Phase.OUTGOING) endCall("no_answer", true, busyTone = true)
            else if (ph == Phase.INCOMING) endCall("missed", true)
        }
    }

    /** Messages from the other phone, the same for LAN and internet. */
    private fun onLinkMessage(l: Link, o: JSONObject) {
        if (link !== l) return
        val ph = Hub.call.value.phase
        when (o.optString("c")) {
            "ringing" -> if (ph == Phase.OUTGOING) {
                Hub.call.update { it.copy(message = "ringing") }
                ringer.startRingback()
            }
            "accept" -> if (ph == Phase.OUTGOING) {
                timeoutJob?.cancel()
                ringer.stop()
                Hub.call.update { it.copy(phase = Phase.ACTIVE, startedAt = System.currentTimeMillis(), message = "connecting_media") }
                engine?.start()
                if (!Hub.call.value.speaker) acquireProximity()
                notifyService(); Discovery.announceNow()
            }
            "reject" -> endCall("declined", false, busyTone = true)
            "busy" -> endCall("busy", false, busyTone = true)
            "dnd" -> endCall("dnd_on", false, busyTone = true)
            "unavailable" -> endCall("unavailable", false, busyTone = true)
            "hangup" -> endCall(if (ph == Phase.INCOMING) "missed" else "ended", false)
            "rtc" -> engine?.onSignal(o)
            "cam" -> Hub.remoteVideo.value = o.optBoolean("on")
            "support" -> if (Hub.call.value.supportRole == "helper") Hub.call.update { it.copy(supportMode = o.optString("mode")) }
        }
    }

    fun accept(withVideo: Boolean = Hub.call.value.wantVideo) {
        if (Hub.call.value.phase != Phase.INCOMING) return
        val l = link ?: return
        timeoutJob?.cancel()
        ringer.stop()
        releaseScreen()
        cancelCallNotification()
        Rooms.leave()
        RtcEngine.init(applicationContext)
        val eng = newEngine(false) { link }
        engine = eng
        val camOk = withVideo && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        goForeground(true, camera = camOk)   // called from the visible call screen → mic/camera allowed
        eng.start()
        if (camOk) eng.setCamera(true)
        eng.setSpeaker(camOk)
        Hub.call.update { it.copy(phase = Phase.ACTIVE, startedAt = System.currentTimeMillis(), message = "connecting_media",
            video = camOk, speaker = camOk) }
        if (!camOk) acquireProximity()
        l.send(JSONObject().put("c", "accept"))
        if (camOk) l.send(JSONObject().put("c", "cam").put("on", true))
        notifyService(); Discovery.announceNow()
    }

    fun decline() {
        if (Hub.call.value.phase != Phase.INCOMING) return
        endCall("declined", true, send = "reject")
    }

    fun hangup() {
        when (Hub.call.value.phase) {
            Phase.INCOMING -> decline()
            Phase.OUTGOING, Phase.ACTIVE -> endCall("ended", true)
            else -> {}
        }
    }

    fun setMuted(m: Boolean) { engine?.setMuted(m); Hub.call.update { it.copy(muted = m) } }

    fun setSpeaker(on: Boolean) {
        engine?.setSpeaker(on)
        if (on) releaseProximity() else if (!Hub.call.value.video) acquireProximity()
        Hub.call.update { it.copy(speaker = on) }
    }

    fun setCamera(on: Boolean) {
        val eng = engine ?: return
        if (on) goForeground(true, camera = true, screen = Hub.call.value.sharing)
        val now = eng.setCamera(on)
        if (now) { releaseProximity(); if (!Hub.call.value.speaker) setSpeaker(true) }
        Hub.call.update { it.copy(video = now) }
        link?.send(JSONObject().put("c", "cam").put("on", now || Hub.call.value.sharing))
        if (!now) goForeground(true, camera = false, screen = Hub.call.value.sharing)
    }

    fun switchCamera() { engine?.switchCamera() }

    /** Voice rooms need the microphone foreground type while open. */
    fun roomMode(on: Boolean) { if (!Hub.isBusy()) goForeground(on) }

    /** Screen sharing, after the user agreed in Android's own consent dialog. */
    fun startScreenShare(data: Intent) {
        val eng = engine ?: return
        if (Hub.call.value.phase != Phase.ACTIVE) return
        goForeground(true, camera = Hub.call.value.video, screen = true)   // must happen before the projection starts
        val (rw, rh) = realScreen()
        val scale = 1280.0 / maxOf(rw, rh).coerceAtLeast(1)
        val w = (rw * minOf(1.0, scale)).toInt() and 0xFFFE
        val h = (rh * minOf(1.0, scale)).toInt() and 0xFFFE
        val ok = eng.startScreen(data, w, h) { scope.launch { stopScreenShare() } }
        if (ok) {
            Hub.call.update { it.copy(sharing = true) }
            link?.send(JSONObject().put("c", "cam").put("on", true))
        } else goForeground(true, camera = Hub.call.value.video)
    }

    private fun realScreen(): Pair<Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        return if (Build.VERSION.SDK_INT >= 30) wm.currentWindowMetrics.bounds.let { it.width() to it.height() }
        else android.util.DisplayMetrics().also { @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(it) }.let { it.widthPixels to it.heightPixels }
    }

    fun stopScreenShare() {
        if (!Hub.call.value.sharing) return
        engine?.stopScreen()
        Hub.call.update { it.copy(sharing = false) }
        link?.send(JSONObject().put("c", "cam").put("on", Hub.call.value.video))
        goForeground(true, camera = Hub.call.value.video)
    }

    /* ======================= Server (LAN) ======================= */

    private fun startServer() = scope.launch {
        while (isActive) {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(Net.SIGNAL_PORT))
                server = ss
                while (isActive) {
                    val client = ss.accept()
                    scope.launch { handleIncoming(client) }
                }
            } catch (_: Exception) {
                runCatching { server?.close() }
                if (!isActive) break
                delay(2000)
            }
        }
    }

    private fun allowed(code: String) = Store.allowed(code)

    private fun handleIncoming(s: Socket) {
        val ch = SecureChannel(s)
        var myLink: LanLink? = null
        try {
            s.tcpNoDelay = true
            s.soTimeout = 15_000
            val (type, r, eph) = ch.readHello() ?: run { ch.close(); return }
            val ip = s.inetAddress?.hostAddress ?: ""
            if (type == Relay.TYPE) {
                // anonymous store-carry-forward exchange; bundles are verified one by one
                if (!Relay.enabled) { ch.refuse("unavailable"); return }
                ch.accept(type, eph)
                s.soTimeout = 20_000
                Relay.serve(ch)
                ch.close(); return
            }
            if (!allowed(r.code)) { ch.refuse("unavailable"); return }
            when (type) {
                "msg" -> {
                    ch.accept(type, eph)
                    s.soTimeout = 60_000
                    Messenger.receive(ch, r)
                    ch.close(); return
                }
                "call" -> {}
                else -> { ch.close(); return }
            }
            val caller = Peer(r.code, Store.contact(r.code)?.name ?: r.name, ip, r.phone)
            if (Store.settings.value.dnd) { ch.refuse("dnd"); missed(caller); return }
            synchronized(lock) {
                if (Hub.isBusy()) { ch.refuse("busy"); missed(caller); return }
                Hub.call.value = CallUi(phase = Phase.INCOMING, peer = caller, incoming = true,
                    message = "incoming", security = r.security)
            }
            ch.accept(type, eph)
            Messenger.learn(r, ip)
            val inv = ch.recvJson()
            val l = LanLink(ch)
            myLink = l
            link = l
            val sup = inv?.optBoolean("support") == true
            Hub.call.update { it.copy(wantVideo = inv?.optBoolean("video") == true && !sup,
                supportReq = sup, supportRole = if (sup) "target" else "") }
            l.send(JSONObject().put("c", "ringing"))
            ring(caller)
            s.soTimeout = 0
            while (true) {
                val o = ch.recvJson() ?: break
                onLinkMessage(l, o)
            }
        } catch (_: Exception) {
            if (myLink == null && Hub.call.value.phase == Phase.INCOMING && link == null) endCall("missed", false)
        } finally {
            if (myLink != null && link === myLink) endCall(if (Hub.call.value.phase == Phase.INCOMING) "missed" else "ended", false)
            else if (myLink == null) ch.close()
        }
    }

    private fun missed(p: Peer) {
        Store.addHistory(HistoryItem("missed", p.code, p.name, p.ip, System.currentTimeMillis(), 0))
        showMissedNotification(p)
    }

    private fun ring(caller: Peer) {
        wakeScreen()
        ringer.startRinging()
        showIncomingNotification(caller, Hub.call.value.wantVideo)
        openCallScreen()
        startRingTimeout()
    }

    /* ======================= Incoming (internet) ======================= */

    private fun onCloud(from: String, call: String, o: JSONObject, sec: Int) {
        val cur = link
        if (o.optString("c") == "inbox") { scope.launch { Messenger.fetchInbox() }; return }
        if (o.optString("c") != "call") {
            if (cur is CloudLink && cur.to == from && cur.call == call) onLinkMessage(cur, o)
            return
        }
        if (cur is CloudLink && cur.call == call) return            // repeated doorbell
        val reply = CloudLink(from, call)
        if (!allowed(from)) { reply.send(JSONObject().put("c", "unavailable")); return }
        val caller = Peer(from, Store.contact(from)?.name ?: o.optString("n", from), "")
        if (Store.settings.value.dnd) { reply.send(JSONObject().put("c", "dnd")); missed(caller); return }
        synchronized(lock) {
            if (Hub.isBusy()) { reply.send(JSONObject().put("c", "busy")); missed(caller); return }
            Hub.call.value = CallUi(phase = Phase.INCOMING, peer = caller, incoming = true, message = "incoming",
                security = sec, internet = true, wantVideo = o.optBoolean("video") && !o.optBoolean("support"),
                supportReq = o.optBoolean("support"), supportRole = if (o.optBoolean("support")) "target" else "")
            link = reply
        }
        reply.send(JSONObject().put("c", "ringing"))
        ring(caller)
    }

    /* ======================= Call lifecycle ======================= */

    private fun endCall(reason: String, notifyPeer: Boolean, busyTone: Boolean = false, send: String = "hangup") {
        val snap = synchronized(lock) {
            val cur = Hub.call.value
            if (cur.phase == Phase.IDLE || cur.phase == Phase.ENDED) return
            Hub.call.value = cur.copy(phase = Phase.ENDED, message = reason, sharing = false)
            cur
        }
        timeoutJob?.cancel()
        ringer.stop()
        val eng = engine; engine = null
        runCatching { eng?.close() }
        releaseProximity()
        releaseScreen()
        cancelCallNotification()

        val l = link
        link = null
        if (l != null) {
            if (notifyPeer) l.send(JSONObject().put("c", send))
            l.close()
        }

        snap.peer?.let { p ->
            val wasActive = snap.phase == Phase.ACTIVE
            val dur = if (wasActive) (System.currentTimeMillis() - snap.startedAt) / 1000 else 0L
            val type = when {
                !snap.incoming -> "out"
                wasActive -> "in"
                reason == "declined" -> "declined"
                else -> "missed"
            }
            Store.addHistory(HistoryItem(type, p.code, p.name, p.ip, System.currentTimeMillis(), dur))
            if (type == "missed") showMissedNotification(p)
        }

        if (busyTone) ringer.playBusy()
        goForeground(false)
        scope.launch {
            delay(2200)
            synchronized(lock) { if (Hub.call.value.phase == Phase.ENDED) Hub.call.value = CallUi() }
            notifyService()
            Discovery.announceNow()
        }
    }

    /* ======================= Notifications ======================= */

    private fun nm() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun pi(req: Int, intent: Intent, activity: Boolean): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return if (activity) PendingIntent.getActivity(this, req, intent, flags)
        else PendingIntent.getService(this, req, intent, flags)
    }

    private fun buildServiceNotification(): Notification {
        val s = Store.settings.value
        val c = Hub.call.value
        val ip = Hub.myIp.value.ifEmpty { L.t("no_wifi") }
        val open = pi(1, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), true)
        val b = NotificationCompat.Builder(this, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_call)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
        if (c.phase == Phase.ACTIVE || c.phase == Phase.OUTGOING) {
            val callScreen = pi(4, Intent(this, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), true)
            b.setContentTitle(
                if (c.supportRole == "target" && (c.supportMode == "view" || c.supportMode == "control"))
                    "🔴 " + L.t(if (c.supportMode == "control") "rs_banner_control" else "rs_banner_view") + " " + (c.peer?.name ?: "")
                else (if (c.phase == Phase.ACTIVE) L.t("in_call_with") else L.t("calling_x")).format(c.peer?.name ?: ""))
                .setContentText("🔒 " + L.t("encrypted") + if (c.internet) " • 🌐" else " • 📶")
                .setContentIntent(callScreen)
                .setUsesChronometer(c.phase == Phase.ACTIVE)
                .setWhen(c.startedAt)
                .setShowWhen(c.phase == Phase.ACTIVE)
                .addAction(0, L.t("hang_up"), pi(5, Intent(this, CallService::class.java).setAction(ACTION_HANGUP), false))
        } else {
            b.setContentTitle((if (s.dnd) L.t("notif_dnd") else L.t("notif_ready")).format(Store.formatCode(s.code)))
                .setContentText("${s.name} • $ip" + (if (s.visible) " • " + L.t("visible") else " • " + L.t("hidden")) +
                        (if (Hub.cloud.value == 2) " • 🌐" else ""))
        }
        return b.build()
    }

    private fun notifyService() { runCatching { nm().notify(NOTIF_SERVICE, buildServiceNotification()) } }

    private fun goForeground(inCall: Boolean, camera: Boolean = false, screen: Boolean = false): Boolean {
        val n = buildServiceNotification()
        fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                var t = 0
                if (Build.VERSION.SDK_INT >= 34) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                if (Build.VERSION.SDK_INT >= 30 && inCall && granted(Manifest.permission.RECORD_AUDIO))
                    t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                if (Build.VERSION.SDK_INT >= 30 && camera && granted(Manifest.permission.CAMERA))
                    t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                if (screen) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                if (t == 0) startForeground(NOTIF_SERVICE, n)
                else try { startForeground(NOTIF_SERVICE, n, t) } catch (e: Exception) {
                    if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_SERVICE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                    else startForeground(NOTIF_SERVICE, n)
                }
            } else {
                startForeground(NOTIF_SERVICE, n)
            }
            true
        } catch (e: Exception) { false }
    }

    /** Android's official call-style notification: rings over the lock screen, answer without unlocking. */
    private fun showIncomingNotification(p: Peer, video: Boolean) {
        val full = pi(10, Intent(this, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), true)
        val answer = pi(11, Intent(this, CallActivity::class.java).setAction(CallActivity.ACTION_ACCEPT)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), true)
        val decline = pi(12, Intent(this, CallService::class.java).setAction(ACTION_DECLINE), false)
        val person = Person.Builder().setName(p.name).setImportant(true).build()
        val n = NotificationCompat.Builder(this, CH_CALL)
            .setSmallIcon(R.drawable.ic_call)
            .setContentTitle(p.name)
            .setContentText((if (video) "🎥 " + L.t("incoming_video") else L.t("incoming")) + " • " + Store.formatCode(p.code))
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(person, decline, answer).setIsVideo(video))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(full, true)
            .setContentIntent(full)
            .build()
        runCatching { nm().notify(NOTIF_CALL, n) }
    }

    private fun cancelCallNotification() { runCatching { nm().cancel(NOTIF_CALL) } }

    private fun showMissedNotification(p: Peer) {
        val open = pi(20, Intent(this, MainActivity::class.java).putExtra("tab", 2), true)
        val n = NotificationCompat.Builder(this, CH_MISSED)
            .setSmallIcon(R.drawable.ic_call)
            .setContentTitle(L.t("missed_call"))
            .setContentText("${p.name} • ${Store.formatCode(p.code)}")
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching { nm().notify(NOTIF_MISSED, n) }
    }

    private fun openCallScreen() {
        runCatching { startActivity(Intent(this, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /* ======================= Locks ======================= */

    /** Turns the screen on for an incoming call even on phones that ignore full-screen intents. */
    private fun wakeScreen() {
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            screenLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "uorcall:ring"
            ).apply { setReferenceCounted(false); acquire(RING_TIMEOUT_MS + 5000) }
        }
    }

    private fun releaseScreen() {
        runCatching { if (screenLock?.isHeld == true) screenLock?.release() }
        screenLock = null
    }

    private fun acquireLocks() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (Store.settings.value.alwaysAwake) {
            cpuLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "uorcall:cpu").apply { setReferenceCounted(false); acquire() }
        }
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        runCatching {
            @Suppress("DEPRECATION")
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "uorcall:wifi").apply {
                setReferenceCounted(false); acquire()
            }
        }
        if (Build.VERSION.SDK_INT >= 29) runCatching {
            wifiLowLatency = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "uorcall:wifi-ll").apply {
                setReferenceCounted(false); acquire()
            }
        }
        runCatching { mcLock = wm.createMulticastLock("uorcall:mc").apply { setReferenceCounted(false); acquire() } }
    }

    private fun releaseLocks() {
        runCatching { cpuLock?.release() }
        runCatching { wifiLock?.release() }
        runCatching { wifiLowLatency?.release() }
        runCatching { mcLock?.release() }
        releaseProximity(); releaseScreen()
    }

    private fun acquireProximity() {
        if (proxLock?.isHeld == true) return
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
                proxLock = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "uorcall:prox").apply {
                    setReferenceCounted(false); acquire(4 * 60 * 60 * 1000L)
                }
            }
        }
    }

    private fun releaseProximity() {
        runCatching { if (proxLock?.isHeld == true) proxLock?.release() }
        proxLock = null
    }
}
