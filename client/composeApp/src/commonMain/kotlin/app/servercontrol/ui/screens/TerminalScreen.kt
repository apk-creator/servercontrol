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
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
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
import app.servercontrol.CredentialFields
import app.servercontrol.Session
import app.servercontrol.SshAuth
import app.servercontrol.SshHostKeyUnknown
import app.servercontrol.isDesktop
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
        SshConnectForm(state, session)
    }
}

@Composable
private fun TerminalRunning(state: AppState) {
    val term = state.terminal ?: return
    var font by remember { mutableFloatStateOf(if (isDesktop) 14f else 12f) }
    val mods = remember { TerminalInputState() }
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(term.title, Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.bodySmall, maxLines = 1)
            if (!terminalHasOwnSelection) {
                IconButton(onClick = { font = (font - 1f).coerceAtLeast(7f) }) { Icon(Icons.Default.Remove, "Schrift kleiner") }
                IconButton(onClick = { font = (font + 1f).coerceAtMost(28f) }) { Icon(Icons.Default.Add, "Schrift größer") }
            }
            IconButton(onClick = { clipboard.getText()?.text?.let { term.paste(it) } }) { Icon(Icons.Default.ContentPaste, "Einfügen") }
            if (!terminalHasOwnSelection) {
                IconButton(onClick = { clipboard.setText(AnnotatedString(term.backend.screenText() ?: "")) }) {
                    Icon(Icons.Default.ContentCopy, "Bildschirm kopieren")
                }
            }
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
    var password by remember { mutableStateOf("") }
    var useKey by remember { mutableStateOf(profile.sshPrivateKey.isNotBlank()) }
    var key by remember { mutableStateOf(profile.sshPrivateKey) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var hostKeyAsk by remember { mutableStateOf<Pair<SshHostKeyUnknown, CompletableDeferred<Boolean>>?>(null) }

    fun connect() {
        if (busy || user.isBlank()) return
        busy = true
        error = null
        scope.launch {
            val term = state.terminalSession()
            val auth = SshAuth(
                user.trim(),
                password = if (useKey) null else password,
                privateKey = if (useKey) key else null,
                passphrase = if (useKey) password else null,
            )
            val p = port.toIntOrNull() ?: 22
            try {
                var pinned = session.profile.sshHostKey
                try {
                    term.connect(host.trim(), p, auth, pinned)
                } catch (e: SshHostKeyUnknown) {
                    val d = CompletableDeferred<Boolean>()
                    hostKeyAsk = e to d
                    val ok = d.await()
                    hostKeyAsk = null
                    if (!ok) throw Exception("Hostschlüssel nicht bestätigt.")
                    pinned = e.fingerprint
                    term.connect(host.trim(), p, auth, pinned)
                    state.updateSessionProfile(session.profile.copy(sshHostKey = e.fingerprint, sshHostKeyType = e.keyType))
                }
                // remember user/port/key (never the password)
                val np = session.profile.copy(sshUser = user.trim(), sshPort = p, sshPrivateKey = if (useKey) key.trim() else "")
                if (np != session.profile) state.updateSessionProfile(np)
                password = ""
            } catch (e: Throwable) {
                error = e.message ?: e.toString()
            } finally {
                busy = false
            }
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 520.dp).padding(20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("SSH-Terminal", style = MaterialTheme.typography.titleLarge)
            if (state.terminal != null && state.terminal?.connected == false) {
                Text("Die letzte Sitzung wurde beendet.", style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(host, { host = it }, Modifier.weight(1f), label = { Text("Host") }, singleLine = true)
                OutlinedTextField(port, { port = it.filter { c -> c.isDigit() }.take(5) }, Modifier.width(96.dp), label = { Text("Port") }, singleLine = true)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(useKey, { useKey = it })
                Spacer(Modifier.width(8.dp))
                Text("Mit privatem Schlüssel anmelden")
            }
            if (useKey) {
                var showKey by remember { mutableStateOf(false) }
                OutlinedTextField(
                    key, { key = it }, Modifier.fillMaxWidth(), label = { Text("Privater Schlüssel (OpenSSH/PEM)") },
                    minLines = if (showKey) 3 else 1, maxLines = if (showKey) 6 else 1,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility, if (showKey) "Verbergen" else "Anzeigen")
                        }
                    },
                    supportingText = { Text("Wird verschlüsselt im Profil gespeichert. Das Feld „Passwort“ dient dann als Passphrase.") },
                )
            }
            if (useKey) Text("Das Passwortfeld dient als Passphrase des Schlüssels.", style = MaterialTheme.typography.bodySmall)
            CredentialFields(user, { user = it }, password, { password = it }, onSubmit = { connect() })
            Button(onClick = { connect() }, enabled = !busy && user.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Verbinden") }
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
        AlertDialog(
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
