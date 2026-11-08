package app.servercontrol.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.servercontrol.data.JobInfo
import app.servercontrol.data.JobStarted
import app.servercontrol.net.ApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// ---------------- formatting ----------------

fun formatBytes(b: Long?): String {
    if (b == null || b < 0) return "–"
    val units = listOf("B", "KB", "MB", "GB", "TB", "PB")
    var v = b.toDouble()
    var i = 0
    while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
    return if (i == 0) "$b B" else "${(v * 10).roundToInt() / 10.0} ${units[i]}"
}

fun formatDuration(s: Long): String {
    val d = s / 86400; val h = (s % 86400) / 3600; val m = (s % 3600) / 60
    return buildString {
        if (d > 0) append("${d} T ")
        if (d > 0 || h > 0) append("${h} Std ")
        append("${m} Min")
    }
}

fun pct(used: Long, total: Long): Float = if (total <= 0) 0f else (used.toFloat() / total).coerceIn(0f, 1f)

val StatusOk = Color(0xFF2E7D32)
val StatusWarn = Color(0xFFF9A825)
val StatusBad = Color(0xFFC62828)

// ---------------- loading ----------------

/**
 * Loads data with [load] and shows loading/error states. [content] gets the data and a reload function.
 */
@Composable
fun <T> Loader(
    key: Any?,
    load: suspend () -> T,
    content: @Composable (data: T, reload: () -> Unit) -> Unit,
) {
    var tick by remember { mutableIntStateOf(0) }
    var data by remember(key) { mutableStateOf<T?>(null) }
    var err by remember(key) { mutableStateOf<String?>(null) }
    var loading by remember(key) { mutableStateOf(true) }
    LaunchedEffect(key, tick) {
        loading = true
        err = null
        try {
            data = load()
        } catch (e: Throwable) {
            err = e.message ?: e.toString()
        }
        loading = false
    }
    val reload = { tick++ }
    Column(Modifier.fillMaxSize()) {
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        err?.let { ErrorCard(it, reload) }
        val d = data
        if (d != null) content(d, reload)
        else if (loading) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    }
}

@Composable
fun ErrorCard(msg: String, retry: (() -> Unit)? = null) {
    Card(
        Modifier.fillMaxWidth().padding(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(msg, Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
            if (retry != null) IconButton(onClick = retry) { Icon(Icons.Default.Refresh, "Erneut") }
        }
    }
}

@Composable
fun SectionCard(title: String? = null, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
fun KeyValue(k: String, v: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(k, Modifier.width(140.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(v, Modifier.weight(1f))
    }
}

@Composable
fun UsageBar(label: String, used: Long, total: Long, detail: String) {
    val p = pct(used, total)
    Column {
        Row { Text(label, Modifier.weight(1f)); Text(detail, style = MaterialTheme.typography.bodySmall) }
        LinearProgressIndicator(
            progress = { p },
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            color = when { p > 0.9f -> StatusBad; p > 0.75f -> StatusWarn; else -> MaterialTheme.colorScheme.primary },
        )
    }
}

@Composable
fun MonoText(text: String, modifier: Modifier = Modifier) {
    SelectionContainer {
        Box(modifier.horizontalScroll(rememberScrollState())) {
            Text(text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 15.sp)
        }
    }
}

// ---------------- confirm & actions ----------------

class Confirm(val title: String, val text: String, val action: () -> Unit)

@Composable
fun ConfirmDialog(c: Confirm?, onDismiss: () -> Unit) {
    if (c == null) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(c.title) },
        text = { Text(c.text) },
        confirmButton = { TextButton(onClick = { onDismiss(); c.action() }) { Text("Ausführen") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

/** Result of a quick command or a started background job, shown in a dialog. */
sealed interface ActionResult {
    data class Message(val title: String, val text: String, val ok: Boolean) : ActionResult
    data class JobRef(val id: String, val title: String) : ActionResult
}

class ActionHost(private val scope: CoroutineScope, private val api: () -> ApiClient) {
    var result by mutableStateOf<ActionResult?>(null)
    var running by mutableStateOf(false)

    /** POST to [path]; 202 responses become a live job view. */
    fun post(title: String, path: String, body: String? = null, after: () -> Unit = {}) {
        scope.launch {
            running = true
            result = try {
                val text = api().postRaw(path, body)
                val started = runCatching { app.servercontrol.data.AppJson.decodeFromString<JobStarted>(text) }.getOrNull()
                if (started != null && started.jobId.isNotBlank()) ActionResult.JobRef(started.jobId, started.title)
                else ActionResult.Message(title, summarize(text), true)
            } catch (e: Throwable) {
                ActionResult.Message(title, e.message ?: e.toString(), false)
            }
            running = false
            after()
        }
    }

    fun run(title: String, block: suspend () -> String, after: () -> Unit = {}) {
        scope.launch {
            running = true
            result = try {
                ActionResult.Message(title, block(), true)
            } catch (e: Throwable) {
                ActionResult.Message(title, e.message ?: e.toString(), false)
            }
            running = false
            after()
        }
    }

    private fun summarize(text: String): String = runCatching {
        val r = app.servercontrol.data.AppJson.decodeFromString<app.servercontrol.data.CmdResult>(text)
        (r.stdout + "\n" + r.stderr).trim().ifBlank { "OK" }
    }.getOrDefault(text.take(2000))
}

@Composable
fun rememberActionHost(api: () -> ApiClient): ActionHost {
    val scope = rememberCoroutineScope()
    return remember { ActionHost(scope, api) }
}

@Composable
fun ActionResultDialog(host: ActionHost, api: ApiClient) {
    if (host.running) {
        AlertDialog(onDismissRequest = {}, confirmButton = {}, title = { Text("Bitte warten …") },
            text = { LinearProgressIndicator(Modifier.fillMaxWidth()) })
    }
    when (val r = host.result) {
        null -> {}
        is ActionResult.Message -> AlertDialog(
            onDismissRequest = { host.result = null },
            title = { Text(r.title, color = if (r.ok) Color.Unspecified else StatusBad) },
            text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) { MonoText(r.text) } },
            confirmButton = { TextButton(onClick = { host.result = null }) { Text("OK") } },
        )
        is ActionResult.JobRef -> JobDialog(api, r.id, r.title) { host.result = null }
    }
}

/** Live view of a background job on the server (apt upgrade, kopia snapshot, occ …). */
@Composable
fun JobDialog(api: ApiClient, id: String, title: String, onDismiss: () -> Unit) {
    var job by remember(id) { mutableStateOf<JobInfo?>(null) }
    var err by remember(id) { mutableStateOf<String?>(null) }
    LaunchedEffect(id) {
        while (true) {
            try {
                job = api.get<JobInfo>("/api/jobs/$id")
                err = null
                if (job?.finished != null) break
            } catch (e: Throwable) {
                err = e.message
            }
            delay(1500)
        }
    }
    val j = job
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (j?.finished == null) { CircularProgressIndicator(Modifier.width(20.dp)); Spacer(Modifier.width(10.dp)) }
                Text(title)
            }
        },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState(), reverseScrolling = false)) {
                err?.let { Text(it, color = StatusBad) }
                if (j?.finished != null) {
                    Text(
                        if (j.exitCode == 0) "Erfolgreich abgeschlossen" else "Fehlgeschlagen (Exit ${j.exitCode})",
                        color = if (j.exitCode == 0) StatusOk else StatusBad, fontWeight = FontWeight.SemiBold,
                    )
                }
                MonoText(j?.output?.takeLast(20_000) ?: "")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(if (j?.finished == null) "Im Hintergrund" else "Schließen") } },
    )
}

@Composable
fun StatusDot(color: Color) {
    Box(Modifier.width(10.dp).heightIn(min = 10.dp, max = 10.dp).padding(0.dp)) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) { drawCircle(color) }
    }
}
