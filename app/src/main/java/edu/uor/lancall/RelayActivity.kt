package edu.uor.lancall

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider

/** Community board: SOS alerts and announcements that reached this phone, relay status, research export. */
class RelayActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this); Identity.init(); Messenger.init(this)
        Relay.maintain()
        setContent { UoRTheme { Surface(color = MaterialTheme.colorScheme.background) { RelayScreen(::finish) } } }
    }
}

@Composable
fun RelayScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val s by Store.settings.collectAsState()
    val st by Relay.stats.collectAsState()
    val board by Relay.board.collectAsState()
    var showPost by remember { mutableStateOf(false) }
    var confirmSos by remember { mutableStateOf(false) }
    var sosNote by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { while (true) { Relay.refresh(); kotlinx.coroutines.delay(5000) } }

    val sos = board.filter { it.k == Relay.SOS }
    val ann = board.filter { it.k == Relay.ANN }.sortedByDescending { it.trusted }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(Brand).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, null, tint = Color.White) }
            Column(Modifier.weight(1f)) {
                Text(tr("camp_board"), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text(tr("camp_board_sub"), color = Color.White.copy(alpha = .8f), fontSize = 12.sp)
            }
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
            /* ---- relay status ---- */
            item {
                Card(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusDot(if (s.relay) Green else Red)
                            Spacer(Modifier.width(8.dp))
                            Text(tr("relay_title") + ": " + (if (s.relay) tr("on") else tr("off")), fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(tr("relay_explain"), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Text("📦 " + tr("carrying_n").format(st.held) + " (" + Formatter.formatShortFileSize(ctx, st.heldBytes) + ")", fontSize = 14.sp)
                        Text("🤝 " + tr("encounters_n").format(st.encounters) +
                                (if (st.lastEncounter > 0) " • " + DateUtils.getRelativeTimeSpanString(st.lastEncounter) else ""), fontSize = 14.sp)
                        Text("➡ " + tr("relay_counts").format(st.forwarded, st.received), fontSize = 14.sp)
                        Text("✉ " + tr("relay_delivered").format(st.deliveredToMe, st.myDelivered), fontSize = 14.sp)
                        if (!s.relay) TextButton(onClick = { Store.save(s.copy(relay = true)); Relay.kick() }) { Text(tr("turn_on")) }
                    }
                }
            }
            /* ---- actions ---- */
            item {
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { confirmSos = true }, modifier = Modifier.weight(1f).height(52.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Red)) { Text("🆘 " + tr("sos_everyone"), fontSize = 13.sp) }
                    Button(onClick = { showPost = true }, modifier = Modifier.weight(1f).height(52.dp)) {
                        Text("📢 " + tr("post_ann"), fontSize = 13.sp)
                    }
                }
            }
            /* ---- SOS ---- */
            item { SectionTitle("🆘 " + tr("sos_alerts") + " (${sos.size})") }
            if (sos.isEmpty()) item { Text(tr("no_sos"), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp)) }
            items(sos, key = { "s" + it.id }) { b -> BoardCard(b) }
            /* ---- announcements ---- */
            item { SectionTitle("📢 " + tr("announcements") + " (${ann.size})") }
            if (ann.isEmpty()) item { Text(tr("no_ann"), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp)) }
            items(ann, key = { "a" + it.id }) { b -> BoardCard(b) }
            /* ---- research ---- */
            item { SectionTitle("🔬 " + tr("research_title")) }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        SwitchRow(tr("research_on"), tr("research_sub"), s.research) { Store.save(s.copy(research = it)) }
                        Text(tr("study_id").format(Relay.studyId()), fontWeight = FontWeight.Bold)
                        Text(tr("events_n").format(st.events), fontSize = 13.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                            OutlinedButton(onClick = {
                                runCatching {
                                    val f = Relay.exportCsv()
                                    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
                                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/csv")
                                        .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), tr("export_csv")))
                                }.onFailure { Toast.makeText(ctx, it.message ?: "error", Toast.LENGTH_LONG).show() }
                            }) { Text(tr("export_csv")) }
                            TextButton(onClick = { Relay.clearLog() }) { Text(tr("clear_log")) }
                        }
                    }
                }
            }
        }
    }

    if (confirmSos) AlertDialog(
        onDismissRequest = { confirmSos = false },
        title = { Text("🆘 " + tr("sos_everyone")) },
        text = {
            Column {
                Text(tr("sos_everyone_q"))
                OutlinedTextField(sosNote, { sosNote = it.take(200) }, label = { Text(tr("note_optional")) }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val loc = if (s.shareLocation) Safety.location(ctx) else null
                Relay.broadcastSos(sosNote.trim(), loc)
                sosNote = ""; confirmSos = false
                Toast.makeText(ctx, L.t("sos_sent_everyone"), Toast.LENGTH_LONG).show()
            }) { Text(tr("send"), color = Red) }
        },
        dismissButton = { TextButton(onClick = { confirmSos = false }) { Text(tr("cancel")) } }
    )
    if (showPost) PostAnnouncementDialog { showPost = false }
}

@Composable
fun BoardCard(b: BoardItem) {
    val ctx = LocalContext.current
    val sos = b.k == Relay.SOS
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = if (sos) Red.copy(alpha = .12f) else MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (sos) "🆘" else "📢", fontSize = 22.sp)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(b.name.ifBlank { Store.formatCode(b.code) } + (if (b.mine) " (" + tr("me") + ")" else ""), fontWeight = FontWeight.Bold)
                    Text((if (b.trusted) "✅ " + tr("verified_sender") else "⚠ " + tr("unverified_sender")) +
                            " • " + Store.formatCode(b.code), fontSize = 12.sp,
                        color = if (b.trusted) Green else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { Relay.deleteBoardItem(b.id) }) { Icon(Icons.Filled.Close, null) }
            }
            if (b.title.isNotBlank()) Text(b.title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.padding(top = 4.dp))
            if (b.text.isNotBlank()) Text(b.text, fontSize = 15.sp, modifier = Modifier.padding(top = 2.dp))
            else if (sos) Text(tr("need_help"), fontSize = 15.sp)
            Text(tr("received_ago").format(DateUtils.getRelativeTimeSpanString(b.rx)) + " • " + tr("hops_n").format(b.hops) +
                    " • " + tr("age_min").format(b.ageAtRx / 60_000), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (b.lat != null && b.lon != null) TextButton(onClick = {
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:${b.lat},${b.lon}?q=${b.lat},${b.lon}"))) }
                }) { Text("📍 " + tr("map")) }
                if (!b.mine && b.code.length == 8) TextButton(onClick = {
                    ctx.startActivity(Intent(ctx, ChatActivity::class.java).putExtra("code", b.code).putExtra("name", b.name))
                }) { Text("💬 " + tr("message")) }
            }
        }
    }
}

@Composable
fun PostAnnouncementDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var title by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var hours by remember { mutableIntStateOf(24) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("📢 " + tr("post_ann")) },
        text = {
            Column {
                Text(tr("ann_explain"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(title, { title = it.take(100) }, label = { Text(tr("ann_title")) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(text, { text = it.take(1500) }, label = { Text(tr("ann_text")) }, minLines = 3,
                    modifier = Modifier.fillMaxWidth())
                Text(tr("ann_keep"), fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(6, 24, 48, 72).forEach { h ->
                        FilterChip(selected = hours == h, onClick = { hours = h }, label = { Text(tr("hours_n").format(h)) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (title.isNotBlank()) {
                    Relay.postAnnouncement(title.trim(), text.trim(), hours)
                    Toast.makeText(ctx, L.t("ann_posted"), Toast.LENGTH_SHORT).show()
                    onDismiss()
                }
            }) { Text(tr("post")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("cancel")) } }
    )
}
