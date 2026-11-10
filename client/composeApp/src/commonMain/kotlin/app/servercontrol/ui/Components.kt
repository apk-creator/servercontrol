package app.servercontrol.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import androidx.compose.foundation.layout.widthIn
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

/** ISO-8601 time ("2026-11-09T01:00:03.12+01:00" or "…Z") → epoch millis, null if unreadable. */
fun isoToMs(s: String): Long? {
    val m = Regex("""^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2}):(\d{2})(\.\d+)?(Z|[+-]\d{2}:?\d{2})?""").find(s.trim()) ?: return null
    val g = m.groupValues
    val y = g[1].toLong(); val mo = g[2].toLong(); val d = g[3].toLong()
    // days from civil (Howard Hinnant)
    val yy = if (mo <= 2) y - 1 else y
    val era = (if (yy >= 0) yy else yy - 399) / 400
    val yoe = yy - era * 400
    val doy = (153 * (if (mo > 2) mo - 3 else mo + 9) + 2) / 5 + d - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    val days = era * 146097 + doe - 719468
    var ms = ((days * 24 + g[4].toLong()) * 60 + g[5].toLong()) * 60_000 + g[6].toLong() * 1000
    g[7].takeIf { it.length > 1 }?.let { ms += (it.drop(1) + "000").take(3).toLong() }
    val z = g[8]
    if (z.isNotEmpty() && z != "Z") {
        val digits = z.drop(1).replace(":", "")
        val off = (digits.take(2).toLong() * 60 + digits.drop(2).toLong()) * 60_000
        ms -= if (z[0] == '+') off else -off
    }
    return ms
}

fun pct(used: Long, total: Long): Float = if (total <= 0) 0f else (used.toFloat() / total).coerceIn(0f, 1f)

/** The app uses exactly two font sizes. */
val TextBig = 15.sp
val TextSmall = 12.sp
val LineBig = 20.sp
val LineSmall = 16.sp

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
    val reload: () -> Unit = { tick++ }
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

/** Open/closed state of the cards, kept while the app runs (key = card title). */
object CardState {
    val collapsed = androidx.compose.runtime.mutableStateMapOf<String, Boolean>()
}

/**
 * Card with a title row: title, optional [actions] (e.g. refresh) and a small arrow
 * to collapse (↑) or expand (↓) the card.
 */
@Composable
fun SectionCard(
    title: String? = null,
    modifier: Modifier = Modifier,
    collapsible: Boolean = true,
    stateKey: String? = null,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val key = stateKey ?: title ?: ""
    val closed = collapsible && title != null && CardState.collapsed[key] == true
    Card(modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = if (closed) 4.dp else 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (title != null) {
                Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    actions()
                    if (collapsible) {
                        IconButton(onClick = { CardState.collapsed[key] = !closed }, modifier = Modifier.size(36.dp)) {
                            Icon(
                                if (closed) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                                if (closed) "Ausklappen" else "Einklappen",
                                Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
            if (!closed) {
                Column(Modifier.padding(end = 8.dp, top = if (title == null) 8.dp else 0.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
            }
        }
    }
}

/** Refresh symbol (instead of a "Neu laden" button). */
@Composable
fun RefreshButton(onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) { Icon(Icons.Default.Refresh, "Neu laden", Modifier.size(22.dp)) }
}

@Composable
fun AddButton(onClick: () -> Unit, description: String = "Hinzufügen") {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) { Icon(Icons.Default.Add, description, Modifier.size(24.dp)) }
}

/** Header row above a list: text on the left, symbols on the right. */
@Composable
fun ListHeader(text: String, actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), fontSize = TextSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        actions()
    }
}

/** Background of row [i] in lists and tables: rows alternate (A B A B) for easier reading. */
@Composable
fun stripe(i: Int): Color = if (i % 2 == 1) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f) else Color.Transparent

/** List row with alternating background. */
@Composable
fun StripedItem(
    index: Int,
    headline: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    supporting: (@Composable () -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    androidx.compose.material3.ListItem(
        headlineContent = headline,
        supportingContent = supporting,
        leadingContent = leading,
        trailingContent = trailing,
        colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = stripe(index)),
        modifier = modifier.then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    )
}

/** Key/value row; [index] ≥ 0 gives it the alternating background. */
@Composable
fun KeyValue(k: String, v: String, index: Int = -1, valueColor: Color = Color.Unspecified, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth()
            .background(if (index >= 0) stripe(index) else Color.Transparent, androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 4.dp, vertical = 3.dp),
    ) {
        Text(k, Modifier.width(128.dp).padding(end = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        Text(v, Modifier.weight(1f), color = valueColor)
    }
}

/** Table of key/value rows with alternating background; null entries are skipped. */
@Composable
fun KeyValues(vararg rows: Pair<String, String>?) {
    Column { rows.filterNotNull().forEachIndexed { i, (k, v) -> KeyValue(k, v, i) } }
}

@Composable
fun UsageBar(label: String, used: Long, total: Long, detail: String) {
    val p = pct(used, total)
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) { Text(label, Modifier.weight(1f)); Text(detail, fontSize = TextSmall) }
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
            Text(text, fontFamily = FontFamily.Monospace, fontSize = TextSmall, lineHeight = LineSmall)
        }
    }
}

// ---------------- confirm & actions ----------------

class Confirm(val title: String, val text: String, val action: () -> Unit)

@Composable
fun ConfirmDialog(c: Confirm?, onDismiss: () -> Unit) {
    if (c == null) return
    AppDialog(
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
        AppDialog(onDismissRequest = {}, confirmButton = {}, title = { Text("Bitte warten …") },
            text = { LinearProgressIndicator(Modifier.fillMaxWidth()) })
    }
    when (val r = host.result) {
        null -> {}
        is ActionResult.Message -> AppDialog(
            onDismissRequest = { host.result = null },
            title = { Text(r.title, color = if (r.ok) Color.Unspecified else StatusBad) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { MonoText(r.text) } },
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
    AppDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (j?.finished == null) { CircularProgressIndicator(Modifier.width(20.dp)); Spacer(Modifier.width(10.dp)) }
                Text(title)
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState(), reverseScrolling = false)) {
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

// ---------------- dialogs ----------------

/**
 * Dialog with the same parameters as Material's AlertDialog, but using the whole screen
 * width on phones (Galaxy S24: ~384 dp) and as much height as the content needs.
 */
@Composable
fun AppDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismissRequest,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        androidx.compose.material3.Surface(
            modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp).widthIn(max = 640.dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp)) {
                title?.let {
                    androidx.compose.material3.ProvideTextStyle(MaterialTheme.typography.titleLarge) { it() }
                    Spacer(Modifier.heightIn(min = 10.dp))
                }
                text?.let {
                    Box(Modifier.weight(1f, fill = false)) {
                        androidx.compose.material3.ProvideTextStyle(MaterialTheme.typography.bodyMedium) { it() }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    dismissButton?.invoke()
                    confirmButton()
                }
            }
        }
    }
}
