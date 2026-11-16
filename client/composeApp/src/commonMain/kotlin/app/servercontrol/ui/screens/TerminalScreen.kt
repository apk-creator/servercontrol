package app.servercontrol.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.servercontrol.AppState
import app.servercontrol.ui.AppDialog
import app.servercontrol.ui.SectionCard
import app.servercontrol.SecretField
import app.servercontrol.Section
import app.servercontrol.Session
import app.servercontrol.SshAuth
import app.servercontrol.SshHostKeyUnknown
import app.servercontrol.isDesktop
import app.servercontrol.normalizeKey
import app.servercontrol.sshPublicKeyOf
import app.servercontrol.terminal.TerminalInputState
import app.servercontrol.terminal.TerminalKeyBar
import app.servercontrol.terminal.TerminalPane
import app.servercontrol.terminal.terminalHasOwnSelection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch

/** Host part of the agent URL – the SSH server is the same machine. */
fun hostOf(url: String): String {
    val hp = url.substringAfter("://").substringBefore("/")
    return if (hp.startsWith("[")) hp.substringAfter("[").substringBefore("]") else hp.substringBeforeLast(":", hp)
}

@Composable
fun TerminalScreen(state: AppState, session: Session) {
    val term = state.terminal
    if (term != null && term.connected) {
        TerminalRunning(state)
    } else {
        // not connected: connection card + the SSH key cards (former "SSH-Schlüssel" menu)
        SshKeysScreen(state, session) {
            card("Verbindung") { SshConnectForm(state, session) }
        }
    }
}

@Composable
private fun TerminalRunning(state: AppState) {
    val term = state.terminal ?: return
    var font by remember { mutableFloatStateOf(if (isDesktop) 14f else 12f) }
    val mods = remember { TerminalInputState() }
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { kotlinx.coroutines.delay(1500); copied = false } }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (copied) "Kopiert" else term.title, Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.bodySmall, maxLines = 1)
            if (!terminalHasOwnSelection) {
                IconButton(onClick = { font = (font - 1f).coerceAtLeast(7f) }) { Icon(Icons.Default.Remove, "Schrift kleiner") }
                IconButton(onClick = { font = (font + 1f).coerceAtMost(28f) }) { Icon(Icons.Default.Add, "Schrift größer") }
            }
            IconButton(onClick = { clipboard.getText()?.text?.let { term.paste(it) } }) { Icon(Icons.Default.ContentPaste, "Einfügen") }
            IconButton(onClick = {
                clipboard.setText(AnnotatedString(term.backend.copyText()))
                copied = true
            }) { Icon(Icons.Default.ContentCopy, "Kopieren (Auswahl, sonst Bildschirm)") }
            IconButton(onClick = { term.backend.clear() }) { Icon(Icons.Default.Delete, "Terminal leeren") }
            IconButton(onClick = { term.close() }) { Icon(Icons.Default.LinkOff, "Trennen") }
        }
        TerminalPane(term, mods, font, Modifier.weight(1f).fillMaxWidth())
        TerminalKeyBar(term, mods)
    }
}

@Composable
private fun SshConnectForm(state: AppState, session: Session) {
    val profile = session.profile
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf(hostOf(session.api.baseUrl)) }
    var port by remember { mutableStateOf(profile.sshPort.toString()) }
    var user by remember { mutableStateOf(profile.sshUser) }
    var passphrase by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var hostKeyAsk by remember { mutableStateOf<Pair<SshHostKeyUnknown, CompletableDeferred<Boolean>>?>(null) }
    val hasKey = session.profile.sshPrivateKey.isNotBlank()
    // keys generated in the app are not passphrase-protected; imported ones may be
    val imported = hasKey && session.profile.sshPublicKey.isBlank()

    // SSH login is key-only: the private key lives encrypted in the profile
    fun connect() {
        if (busy || user.isBlank() || !hasKey) return
        busy = true
        error = null
        scope.launch {
            val term = state.terminalSession()
            val auth = SshAuth(user.trim(), password = null, privateKey = normalizeKey(session.profile.sshPrivateKey), passphrase = passphrase.ifEmpty { null })
            val p = port.toIntOrNull() ?: 22
            try {
                try {
                    term.connect(host.trim(), p, auth, session.profile.sshHostKey)
                } catch (e: SshHostKeyUnknown) {
                    val d = CompletableDeferred<Boolean>()
                    hostKeyAsk = e to d
                    val ok = d.await()
                    hostKeyAsk = null
                    if (!ok) throw Exception("Hostschlüssel nicht bestätigt.")
                    term.connect(host.trim(), p, auth, e.fingerprint)
                    state.updateSessionProfile(session.profile.copy(sshHostKey = e.fingerprint, sshHostKeyType = e.keyType))
                }
                val np = session.profile.copy(sshUser = user.trim(), sshPort = p)
                if (np != session.profile) state.updateSessionProfile(np)
                passphrase = ""
            } catch (e: Throwable) {
                val msg = e.message ?: e.toString()
                error = if (msg.contains("Auth", ignoreCase = true)) {
                    val fp = session.profile.sshKeyFingerprint.ifBlank { sshPublicKeyOf(session.profile.sshPrivateKey, passphrase.ifEmpty { null })?.second ?: "" }
                    "Anmeldung als „${user.trim()}“ abgelehnt. Der Schlüssel dieses Geräts" + (if (fp.isNotBlank()) " ($fp)" else "") +
                        " ist für diesen Benutzer nicht auf dem Server eingetragen – unten unter „Eingetragene Schlüssel auf dem Server“ mit demselben Benutzer eintragen." +
                        if (fp.isBlank()) " Der Schlüssel ließ sich nicht lesen (beschädigt eingefügt oder Passphrase nötig)." else ""
                } else msg
            } finally {
                busy = false
            }
        }
    }

    SectionCard("Verbindung") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (state.terminal != null && state.terminal?.connected == false) {
                Text("Die letzte Sitzung wurde beendet.", style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(host, { host = it }, Modifier.weight(1f), label = { Text("Host") }, singleLine = true)
                OutlinedTextField(port, { port = it.filter { c -> c.isDigit() }.take(5) }, Modifier.width(96.dp), label = { Text("Port") }, singleLine = true)
            }
            OutlinedTextField(user, { user = it.trim() }, Modifier.fillMaxWidth(), label = { Text("Benutzer") }, singleLine = true)
            if (!hasKey) {
                Text("Für dieses Gerät ist noch kein SSH-Schlüssel hinterlegt – unter „Schlüssel dieses Geräts“ anlegen.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            } else {
                Text(
                    "Anmeldung mit dem Schlüssel dieses Geräts" +
                        (session.profile.sshKeyFingerprint.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: " (importiert)"),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (imported) SecretField(passphrase, { passphrase = it }, "Passphrase (nur bei geschütztem Schlüssel)")
                Button(onClick = { connect() }, enabled = !busy && user.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Verbinden") }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (session.profile.sshHostKey.isNotBlank()) {
                Text("Hostschlüssel (${session.profile.sshHostKeyType}): ${session.profile.sshHostKey}", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { state.updateSessionProfile(session.profile.copy(sshHostKey = "", sshHostKeyType = "")) }) {
                    Text("Hostschlüssel vergessen")
                }
            }
        }
    }

    hostKeyAsk?.let { (e, d) ->
        val typeFile = when {
            e.keyType.contains("ed25519") -> "ed25519"
            e.keyType.contains("ecdsa") -> "ecdsa"
            else -> "rsa"
        }
        AppDialog(
            onDismissRequest = { d.complete(false) },
            title = { Text("SSH-Hostschlüssel bestätigen") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Erste SSH-Verbindung zu diesem Server. Vergleiche den Fingerprint mit der Ausgabe von")
                    Text("ssh-keygen -lf /etc/ssh/ssh_host_${typeFile}_key.pub", fontFamily = FontFamily.Monospace)
                    Text("${e.keyType}\n${e.fingerprint}", fontFamily = FontFamily.Monospace)
                }
            },
            confirmButton = { TextButton(onClick = { d.complete(true) }) { Text("Stimmt überein – vertrauen") } },
            dismissButton = { TextButton(onClick = { d.complete(false) }) { Text("Abbrechen") } },
        )
    }
}
