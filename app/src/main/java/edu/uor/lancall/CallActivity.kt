package edu.uor.lancall

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

class CallActivity : ComponentActivity() {

    companion object { const val ACTION_ACCEPT = "edu.uor.lancall.ACCEPT" }

    private var pendingVideoAnswer = false

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
            val camOk = res[Manifest.permission.CAMERA] == true || has(Manifest.permission.CAMERA)
            CallService.instance?.accept(pendingVideoAnswer && camOk)
        }

    private fun has(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        RtcEngine.init(applicationContext)
        showOverLockScreen()
        volumeControlStream = AudioManager.STREAM_VOICE_CALL
        handle(intent)
        setContent { UoRTheme { CallScreen(onAccept = ::acceptCall) } }
        lifecycleScope.launch { Hub.call.collect { if (it.phase == Phase.IDLE) finish() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent?) {
        if (i?.action == ACTION_ACCEPT) acceptCall(Hub.call.value.wantVideo)
    }

    /** Answer (optionally with video). Asks for mic/camera first if needed. */
    fun acceptCall(video: Boolean) {
        val need = mutableListOf<String>()
        if (!has(Manifest.permission.RECORD_AUDIO)) need += Manifest.permission.RECORD_AUDIO
        if (video && !has(Manifest.permission.CAMERA)) need += Manifest.permission.CAMERA
        if (need.isEmpty()) CallService.instance?.accept(video)
        else { pendingVideoAnswer = video; permLauncher.launch(need.toTypedArray()) }
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true); setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}

/** A WebRTC video surface inside Compose. */
@Composable
fun VideoView(sink: ProxySink, mirror: Boolean, fill: Boolean, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { c: Context ->
            SurfaceViewRenderer(c).apply {
                init(RtcEngine.egl.eglBaseContext, null)
                setEnableHardwareScaler(true)
                setScalingType(if (fill) RendererCommon.ScalingType.SCALE_ASPECT_FILL else RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                if (!fill) setZOrderMediaOverlay(true)
                sink.target = this
            }
        },
        update = { it.setMirror(mirror) },
        onRelease = { if (sink.target === it) sink.target = null; it.release() }
    )
}

@Composable
fun CallScreen(onAccept: (Boolean) -> Unit) {
    val c by Hub.call.collectAsState()
    val remoteVideo by Hub.remoteVideo.collectAsState()
    val svc = CallService.instance
    val ctx = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var front by remember { mutableStateOf(true) }
    var controls by remember { mutableStateOf(true) }
    LaunchedEffect(c.phase) { while (c.phase == Phase.ACTIVE) { now = System.currentTimeMillis(); delay(500) } }

    val camPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) CallService.instance?.setCamera(true)
    }
    val screenLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        if (r.resultCode == Activity.RESULT_OK && data != null) CallService.instance?.startScreenShare(data)
    }
    // remote support (I am being helped): Android's own screen-capture consent, then my chosen mode
    var pendingMode by remember { mutableStateOf("") }
    var askAccess by remember { mutableStateOf(false) }
    val supportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        if (r.resultCode == Activity.RESULT_OK && data != null) {
            CallService.instance?.startScreenShare(data)
            CallService.instance?.setSupportMode(pendingMode)
        }
    }
    fun allow(mode: String) {
        if (mode == "control" && !RemoteAccessService.isEnabled(ctx)) { askAccess = true; return }
        pendingMode = mode
        val mpm = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        supportLauncher.launch(mpm.createScreenCaptureIntent())
    }

    val peer = c.peer
    val status = when (c.phase) {
        Phase.ACTIVE -> if (c.message == "connecting_media") tr("connecting_media")
            else DateUtils.formatElapsedTime(((now - c.startedAt) / 1000).coerceAtLeast(0))
        Phase.INCOMING -> if (c.supportReq) tr("rs_incoming").format(peer?.name ?: "") else if (c.wantVideo) tr("incoming_video") else tr("incoming")
        Phase.OUTGOING -> tr(c.message.ifEmpty { "connecting" })
        Phase.ENDED -> tr(c.message)
        Phase.IDLE -> ""
    }
    val (secText, secColor) = when (c.security) {
        1 -> ("🔒 " + tr("sec_verified")) to Green
        2 -> ("⚠ " + tr("sec_changed")) to Red
        else -> ("🔒 " + tr("sec_encrypted")) to Color.White.copy(alpha = .7f)
    }
    val helperView = c.supportRole == "helper" && (c.supportMode == "view" || c.supportMode == "control")
    val showRemote = c.phase == Phase.ACTIVE && (remoteVideo || helperView)
    val showLocal = (c.video || c.sharing) && (c.phase == Phase.ACTIVE || c.phase == Phase.OUTGOING)

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Brand, Color(0xFF0B1B33))))
        .clickable(enabled = showRemote) { controls = !controls }) {

        if (showRemote) key(helperView) {
            VideoView(RtcEngine.remoteSink, mirror = false, fill = !helperView, modifier = Modifier.fillMaxSize())
        }
        if (helperView && c.supportMode == "control") ControlSurface(Modifier.fillMaxSize())

        // local preview: full screen while ringing, small window during the call
        if (showLocal) {
            val small = showRemote
            key(small) {
                VideoView(RtcEngine.localSink, mirror = front && !c.sharing, fill = !small,
                    modifier = if (small) Modifier.align(Alignment.TopEnd).padding(top = 40.dp, end = 12.dp)
                        .size(110.dp, 160.dp).clip(RoundedCornerShape(14.dp))
                    else Modifier.fillMaxSize())
            }
        }

        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {

            if (!showRemote) {
                Spacer(Modifier.height(if (showLocal) 20.dp else 56.dp))
                if (!showLocal) Box(Modifier.size(116.dp).clip(CircleShape).background(avatarColor(peer?.code ?: "")),
                    contentAlignment = Alignment.Center) {
                    Text(initials(peer?.name ?: "?"), color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(16.dp))
            }
            if (!showRemote || controls) {
                Text(peer?.name ?: "", color = Color.White, fontSize = if (showRemote) 20.sp else 30.sp, fontWeight = FontWeight.Bold)
                Text(Store.formatCode(peer?.code ?: "") + (if (c.internet) " • 🌐 " + tr("via_internet") else " • 📶 " + tr("via_wifi")),
                    color = Color.White.copy(alpha = .75f), fontSize = 13.sp)
                Text(status, color = if (c.phase == Phase.ENDED) Gold else Color.White, fontSize = 18.sp)
                Text(secText, color = secColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                if (c.sharing && c.supportRole != "target") Text("🔴 " + tr("sharing_screen"), color = Red, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 6.dp).background(Color.White, RoundedCornerShape(8.dp)).padding(6.dp))
                if (c.supportRole == "helper" && c.phase == Phase.ACTIVE && c.supportMode.isEmpty())
                    Text(tr("rs_waiting").format(peer?.name ?: ""), color = Gold, modifier = Modifier.padding(top = 10.dp))
                if (c.supportRole == "helper" && c.supportMode == "off")
                    Text(tr("rs_stop") + " ✓", color = Gold, modifier = Modifier.padding(top = 10.dp))
            }

            if (c.phase == Phase.ACTIVE && c.supportRole == "target") {
                Spacer(Modifier.height(18.dp))
                if (c.supportMode.isEmpty()) Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("🛟 " + tr("rs_incoming").format(peer?.name ?: ""), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text(tr("rs_consent_text"), fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
                        Button(onClick = { allow("view") }, modifier = Modifier.fillMaxWidth()) { Text("👁 " + tr("rs_allow_view")) }
                        Button(onClick = { allow("control") }, modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Green)) { Text("🖐 " + tr("rs_allow_control")) }
                        TextButton(onClick = { svc?.hangup() }, modifier = Modifier.fillMaxWidth()) { Text(tr("decline")) }
                    }
                } else if (c.supportMode == "view" || c.supportMode == "control") Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFC62828)), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("🔴 " + tr(if (c.supportMode == "control") "rs_banner_control" else "rs_banner_view") + " " + (peer?.name ?: ""),
                            color = Color.White, fontWeight = FontWeight.Bold)
                        if (c.supportMode == "view" && RemoteAccessService.instance == null)
                            Text(tr("rs_view_only_note"), color = Color.White, fontSize = 12.sp)
                        Button(onClick = { svc?.setSupportMode("off") }, modifier = Modifier.padding(top = 8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White)) { Text(tr("rs_stop"), color = Color(0xFFC62828)) }
                    }
                }
            }
            if (helperView && c.supportMode == "control") HelperToolbar()

            Spacer(Modifier.weight(1f))

            when (c.phase) {
                Phase.INCOMING -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    RoundButton(Icons.Filled.CallEnd, tr("decline"), Red) { svc?.decline() }
                    if (c.wantVideo) RoundButton(Icons.Filled.Call, tr("answer_audio"), Color(0xFF2E7D32)) { onAccept(false) }
                    RoundButton(if (c.wantVideo) Icons.Filled.Videocam else if (c.supportReq) Icons.Filled.SupportAgent else Icons.Filled.Call,
                        tr("answer"), Green) { onAccept(c.wantVideo) }
                }
                Phase.OUTGOING, Phase.ACTIVE -> if (!showRemote || controls) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (c.phase == Phase.ACTIVE) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            ToggleButton(if (c.muted) Icons.Filled.MicOff else Icons.Filled.Mic, tr("mute"), c.muted) { svc?.setMuted(!c.muted) }
                            ToggleButton(Icons.Filled.VolumeUp, tr("speaker"), c.speaker) { svc?.setSpeaker(!c.speaker) }
                            if (!c.supportReq) ToggleButton(if (c.video) Icons.Filled.Videocam else Icons.Filled.VideocamOff, tr("camera"), c.video) {
                                if (c.video) svc?.setCamera(false)
                                else if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) svc?.setCamera(true)
                                else camPerm.launch(Manifest.permission.CAMERA)
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        if (!c.supportReq) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            ToggleButton(Icons.Filled.Cameraswitch, tr("flip"), false) { svc?.switchCamera(); front = !front }
                            ToggleButton(if (c.sharing) Icons.Filled.StopScreenShare else Icons.Filled.ScreenShare, tr("share_screen"), c.sharing) {
                                if (c.sharing) svc?.stopScreenShare()
                                else {
                                    val mpm = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                                    screenLauncher.launch(mpm.createScreenCaptureIntent())
                                }
                            }
                        }
                        Spacer(Modifier.height(24.dp))
                    }
                    RoundButton(Icons.Filled.CallEnd, tr("end"), Red) { svc?.hangup() }
                }
                else -> Spacer(Modifier.height(90.dp))
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (askAccess) AccessDialog(onDismiss = { askAccess = false }, onViewOnly = { allow("view") })
}

/** Explains the one-time Accessibility switch needed before a helper can control this phone. */
@Composable
fun AccessDialog(onDismiss: () -> Unit, onViewOnly: () -> Unit) {
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("rs_access_title")) },
        text = { Text(tr("rs_access_needed")) },
        confirmButton = { TextButton(onClick = {
            runCatching { ctx.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            onDismiss()
        }) { Text(tr("open")) } },
        dismissButton = { TextButton(onClick = { onDismiss(); onViewOnly() }) { Text(tr("rs_allow_view")) } }
    )
}

/** Helper side: touches on the remote screen become taps / long-presses / swipes on the other device. */
@Composable
fun ControlSurface(modifier: Modifier) {
    var size by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    fun norm(o: androidx.compose.ui.geometry.Offset): Pair<Double, Double> {
        val fw = RtcEngine.remoteSink.frameW.coerceAtLeast(1).toFloat(); val fh = RtcEngine.remoteSink.frameH.coerceAtLeast(1).toFloat()
        val scale = minOf(size.width / fw, size.height / fh)
        val w = fw * scale; val h = fh * scale
        val ox = (size.width - w) / 2; val oy = (size.height - h) / 2
        return ((o.x - ox) / w).toDouble().coerceIn(0.0, 1.0) to ((o.y - oy) / h).toDouble().coerceIn(0.0, 1.0)
    }
    fun send(o: org.json.JSONObject) { CallService.instance?.sendControl(o) }
    var start by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    var cur by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    var t0 by remember { mutableLongStateOf(0L) }
    Box(modifier
        .onSizeChanged { size = it }
        .pointerInput(Unit) {
            detectTapGestures(
                onTap = { p -> val (x, y) = norm(p); send(org.json.JSONObject().put("t", "tap").put("x", x).put("y", y)) },
                onLongPress = { p -> val (x, y) = norm(p); send(org.json.JSONObject().put("t", "long").put("x", x).put("y", y)) })
        }
        .pointerInput(Unit) {
            detectDragGestures(
                onDragStart = { p -> start = p; cur = p; t0 = System.currentTimeMillis() },
                onDrag = { change, amount -> change.consume(); cur += amount },
                onDragEnd = {
                    val (x1, y1) = norm(start); val (x2, y2) = norm(cur)
                    send(org.json.JSONObject().put("t", "swipe").put("x1", x1).put("y1", y1).put("x2", x2).put("y2", y2)
                        .put("ms", (System.currentTimeMillis() - t0).coerceIn(80, 2000)))
                })
        })
}

@Composable
fun HelperToolbar() {
    var text by remember { mutableStateOf("") }
    fun key(k: String) = CallService.instance?.sendControl(org.json.JSONObject().put("t", "key").put("k", k))
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).background(Color.Black.copy(alpha = .55f), RoundedCornerShape(14.dp)).padding(8.dp)) {
        Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = { key("back") }) { Text("◀", color = Color.White, fontSize = 20.sp) }
            TextButton(onClick = { key("home") }) { Text("⌂", color = Color.White, fontSize = 20.sp) }
            TextButton(onClick = { key("recents") }) { Text("▢", color = Color.White, fontSize = 20.sp) }
            TextButton(onClick = { key("backspace") }) { Text("⌫", color = Color.White, fontSize = 20.sp) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(text, { text = it }, placeholder = { Text(tr("rs_type")) }, singleLine = true, modifier = Modifier.weight(1f),
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White))
            TextButton(onClick = {
                if (text.isNotEmpty()) { CallService.instance?.sendControl(org.json.JSONObject().put("t", "text").put("s", text)); text = "" }
            }) { Text("⏎", color = Color.White, fontSize = 20.sp) }
        }
    }
}

@Composable
fun RoundButton(icon: ImageVector, label: String, color: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FloatingActionButton(onClick = onClick, containerColor = color, shape = CircleShape, modifier = Modifier.size(72.dp)) {
            Icon(icon, label, tint = Color.White, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(label, color = Color.White, fontSize = 13.sp)
    }
}

@Composable
fun ToggleButton(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(76.dp)) {
        FilledIconButton(onClick = onClick, modifier = Modifier.size(58.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = if (active) Color.White else Color.White.copy(alpha = .18f),
                contentColor = if (active) Brand else Color.White)) {
            Icon(icon, label, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(label, color = Color.White, fontSize = 12.sp, maxLines = 1)
    }
}
