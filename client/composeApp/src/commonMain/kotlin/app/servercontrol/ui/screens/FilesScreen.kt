package app.servercontrol.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.servercontrol.BackHandler
import app.servercontrol.Bold
import app.servercontrol.data.AppJson
import app.servercontrol.net.ApiClient
import app.servercontrol.net.ApiException
import app.servercontrol.pickNamedFile
import app.servercontrol.saveFile
import app.servercontrol.ui.*
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class FileEntry(
    val name: String = "",
    val type: String = "file",
    val target: String = "",
    val size: Long = 0,
    val mode: String = "",
    val mtime: Long = 0,
    val owner: String = "",
    val group: String = "",
) {
    val isDir get() = type == "dir" || type == "dirlink"
}

@Serializable
data class FileList(val path: String = "/", val entries: List<FileEntry> = emptyList())

/** last folder, kept while the app runs */
private object FilesState {
    var path by mutableStateOf("/")
}

/** Percent-encodes a value for the query string (UTF-8, also umlauts and spaces). */
fun encQuery(s: String): String = buildString {
    for (b in s.encodeToByteArray()) {
        val c = b.toInt() and 0xff
        val ch = c.toChar()
        if (ch.isLetterOrDigit() && c < 128 || ch in "-._~/") append(ch) else append("%" + c.toString(16).uppercase().padStart(2, '0'))
    }
}

private fun join(dir: String, name: String) = if (dir == "/") "/$name" else "$dir/$name"
private fun parent(p: String) = p.substringBeforeLast('/').ifBlank { "/" }

@Composable
fun FilesScreen(api: ApiClient) {
    val host = rememberActionHost { api }
    val scope = rememberCoroutineScope()
    var path by remember { mutableStateOf(FilesState.path) }
    var tick by remember { mutableStateOf(0) }
    var filter by remember { mutableStateOf("") }
    var addMenu by remember { mutableStateOf(false) }
    var newFolder by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<FileEntry?>(null) }
    var viewing by remember { mutableStateOf<FileEntry?>(null) }
    var renaming by remember { mutableStateOf<FileEntry?>(null) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    fun go(p: String) { path = p; FilesState.path = p; filter = "" }
    BackHandler(path != "/") { go(parent(path)) }

    fun upload(overwrite: Boolean, name: String, data: ByteArray) {
        host.run("Hochladen", {
            api.postBytes("/api/files/upload?dir=${encQuery(path)}&name=${encQuery(name)}&overwrite=${if (overwrite) 1 else 0}", data, 30 * 60_000L)
            "$name (${formatBytes(data.size.toLong())}) nach $path hochgeladen."
        }, after = { tick++ })
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            SearchField(filter, { filter = it }, "In diesem Ordner suchen", Modifier.weight(1f))
            AddButton({ addMenu = true }, "Hochladen oder Ordner anlegen")
            RefreshButton { tick++ }
        }
        // path as clickable parts
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val parts = path.split('/').filter { it.isNotEmpty() }
            Text("/", Modifier.clickable { go("/") }.padding(4.dp), fontWeight = Bold)
            parts.forEachIndexed { i, part ->
                val target = "/" + parts.take(i + 1).joinToString("/")
                Text(part, Modifier.clickable { go(target) }.padding(4.dp), fontWeight = if (i == parts.lastIndex) Bold else null)
                if (i < parts.lastIndex) Text("/", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Loader(path to tick, { api.get<FileList>("/api/files/list?path=${encQuery(path)}") }) { list, _ ->
            // hidden files are always shown
            val shown = list.entries.filter { filter.isBlank() || it.name.contains(filter, true) }
            Column(Modifier.fillMaxSize()) {
            ListHeader("${shown.size} Einträge")
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(shown, key = { _, e -> e.name }) { i, e ->
                    StripedItem(
                        i,
                        leading = {
                            Icon(
                                when (e.type) {
                                    "dir" -> Icons.Default.Folder
                                    "dirlink" -> Icons.Default.FolderSpecial
                                    "link" -> Icons.Default.Link
                                    else -> Icons.AutoMirrored.Filled.InsertDriveFile
                                }, null, Modifier.size(22.dp),
                            )
                        },
                        headline = { Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supporting = {
                            Text(
                                listOfNotNull(
                                    if (e.isDir) null else formatBytes(e.size),
                                    formatTime(e.mtime).dropLast(3),
                                    "${e.owner}:${e.group}",
                                    e.mode,
                                    e.target.takeIf { it.isNotBlank() }?.let { "→ $it" },
                                ).joinToString(" · "),
                                fontSize = TextSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        },
                        // files open their actions with a tap; folders open on tap, so they keep the gear
                        trailing = if (e.isDir) ({ IconButton(onClick = { selected = e }) { Icon(Icons.Default.Settings, "Aktionen", Modifier.size(20.dp)) } }) else null,
                        onClick = { if (e.isDir) go(join(path, e.name)) else selected = e },
                    )
                }
            }
            }
        }
    }

    if (addMenu) AppDialog(
        onDismissRequest = { addMenu = false },
        title = { Text("Hinzufügen in $path") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    addMenu = false
                    scope.launch {
                        val (name, data) = pickNamedFile() ?: return@launch
                        // ask before overwriting an existing file
                        val exists = runCatching { api.get<FileList>("/api/files/list?path=${encQuery(path)}").entries.any { it.name == name } }.getOrDefault(false)
                        if (exists) confirm = Confirm("„$name“ ersetzen?", "In $path gibt es schon eine Datei mit diesem Namen.") { upload(true, name, data) }
                        else upload(false, name, data)
                    }
                }, Modifier.fillMaxWidth()) { Text("Datei hochladen") }
                Button(onClick = { addMenu = false; newFolder = true }, Modifier.fillMaxWidth()) { Text("Neuer Ordner") }
                Text("Hochgeladene Dateien und neue Ordner bekommen Besitzer und Gruppe des Ordners.", fontSize = TextSmall)
            }
        },
        confirmButton = { TextButton(onClick = { addMenu = false }) { Text("Abbrechen") } },
    )

    if (newFolder) NameDialog("Neuer Ordner", "", "Anlegen", { newFolder = false }) { name ->
        newFolder = false
        host.run("Ordner anlegen", {
            api.postRaw("/api/files/mkdir", buildJsonObject { put("path", join(path, name)) }.toString())
            "Ordner $name angelegt."
        }, after = { tick++ })
    }

    selected?.let { e ->
        val full = join(path, e.name)
        AppDialog(
            onDismissRequest = { selected = null },
            title = { Text(e.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    KeyValues(
                        "Pfad" to full,
                        if (e.isDir) null else "Größe" to formatBytes(e.size),
                        "Geändert" to formatTime(e.mtime),
                        "Besitzer" to "${e.owner}:${e.group}",
                        "Rechte" to e.mode,
                        e.target.takeIf { it.isNotBlank() }?.let { "Ziel" to it },
                    )
                    if (e.isDir) Button(onClick = { selected = null; go(full) }, Modifier.fillMaxWidth()) { Text("Öffnen") }
                    if (e.type == "file") {
                        if (e.size <= 512 * 1024) Button(onClick = { selected = null; viewing = e }, Modifier.fillMaxWidth()) { Text("Anzeigen") }
                        Button(onClick = {
                            selected = null
                            host.run("Herunterladen", {
                                val where = saveFile(e.name) { sink -> api.download("/api/files/download?path=${encQuery(full)}", sink) }
                                if (where == null) "Abgebrochen." else "${e.name} gespeichert: $where"
                            })
                        }, Modifier.fillMaxWidth()) { Text("Herunterladen") }
                    }
                    OutlinedButton(onClick = { selected = null; renaming = e }, Modifier.fillMaxWidth()) { Text("Umbenennen") }
                    OutlinedButton(onClick = {
                        selected = null
                        confirm = Confirm(
                            "„${e.name}“ löschen?",
                            if (e.type == "dir") "Der Ordner wird mit seinem gesamten Inhalt endgültig gelöscht." else "Die Datei wird endgültig gelöscht.",
                        ) {
                            host.run("Löschen", {
                                api.postRaw("/api/files/delete", buildJsonObject { put("path", full); put("recursive", e.type == "dir") }.toString())
                                "${e.name} gelöscht."
                            }, after = { tick++ })
                        }
                    }, Modifier.fillMaxWidth()) { Text("Löschen", color = StatusBad) }
                }
            },
            confirmButton = { TextButton(onClick = { selected = null }) { Text("Schließen") } },
        )
    }

    renaming?.let { e ->
        NameDialog("Umbenennen", e.name, "Umbenennen", { renaming = null }) { name ->
            renaming = null
            host.run("Umbenennen", {
                api.postRaw("/api/files/rename", buildJsonObject { put("from", join(path, e.name)); put("to", join(path, name)) }.toString())
                "${e.name} → $name"
            }, after = { tick++ })
        }
    }

    viewing?.let { e ->
        val full = join(path, e.name)
        AppDialog(
            onDismissRequest = { viewing = null },
            title = { Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                Loader(full, { api.getJson("/api/files/download?view=1&path=${encQuery(full)}") }) { v, _ ->
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        if (v["truncated"].bool == true) Text("Gekürzt – nur der Anfang wird angezeigt.", fontSize = TextSmall, color = StatusWarn)
                        MonoText(v["text"].str)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { viewing = null }) { Text("Schließen") } },
        )
    }

    ConfirmDialog(confirm) { confirm = null }
    ActionResultDialog(host, api)
}

@Composable
private fun NameDialog(title: String, initial: String, action: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    val valid = name.isNotBlank() && name != "." && name != ".." && !name.contains('/')
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onOk(name.trim()) }, enabled = valid) { Text(action) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}
