package app.servercontrol

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.servercontrol.data.ServerProfile
import app.servercontrol.ui.AppDialog
import app.servercontrol.ui.MonoText
import app.servercontrol.ui.TextSmall

/** shown on the sign-in page when the server has no agent yet */
@Composable
fun AgentMissingCard(state: AppState, profile: ServerProfile) {
    var open by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Kein Agent gefunden", style = MaterialTheme.typography.titleMedium)
            Text(
                "Die App kann den Agent ${APP_VERSION} selbst installieren: Sie lädt ihn aus dem GitHub-Release und richtet ihn per SSH mit sudo ein. " +
                    "Danach ist das Zertifikat bereits bestätigt und die App meldet sich an.",
                fontSize = TextSmall,
            )
            Button(onClick = { open = true }, enabled = !state.installing) { Text("Agent installieren") }
        }
    }
    if (open || state.installing || state.installLog != null || state.installHostKey != null) {
        AgentInstallDialog(state, profile) { open = false; state.clearInstallLog() }
    }
}

@Composable
private fun AgentInstallDialog(state: AppState, profile: ServerProfile, onClose: () -> Unit) {
    var sshUser by remember { mutableStateOf(profile.sshUser) }
    var sshPw by remember { mutableStateOf("") }
    var sudoPw by remember { mutableStateOf("") }
    var agentPw by remember { mutableStateOf("") }
    var agentPw2 by remember { mutableStateOf("") }
    var addKey by remember { mutableStateOf(profile.sshPublicKey.isNotBlank()) }
    val hasKey = profile.sshPrivateKey.isNotBlank()
    val valid = sshUser.isNotBlank() && agentPw.length >= 12 && agentPw == agentPw2 && (hasKey || sshPw.isNotBlank())
    val hostKey = state.installHostKey
    val start = { trusted: String? ->
        state.installAgent(profile, sshUser.trim(), sshPw, sudoPw, agentPw, addKey, trusted)
    }
    AppDialog(
        onDismissRequest = { if (!state.installing) onClose() },
        title = { Text("Agent installieren") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (hostKey != null) {
                    Text("Erste SSH-Verbindung zu ${state.hostOf(profile).first}. Stimmt der Hostschlüssel?")
                    MonoText("${hostKey.second}\n${hostKey.first}")
                    Text("Vergleichen auf dem Server: ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub", fontSize = TextSmall)
                    return@Column
                }
                val log = state.installLog
                if (log != null) {
                    MonoText(log)
                    if (state.installing) LinearProgressIndicator(Modifier.fillMaxWidth())
                    return@Column
                }
                OutlinedTextField(sshUser, { sshUser = it }, Modifier.fillMaxWidth(), label = { Text("SSH-Benutzer (mit sudo-Recht)") }, singleLine = true)
                if (hasKey) Text("Anmeldung mit dem Schlüssel dieser App. Nur falls er auf dem Server noch nicht eingetragen ist, das SSH-Passwort angeben.", fontSize = TextSmall)
                SecretField(sshPw, { sshPw = it }, if (hasKey) "SSH-Passwort (optional)" else "SSH-Passwort")
                SecretField(sudoPw, { sudoPw = it }, "sudo-Passwort (leer = wie SSH-Passwort)")
                Text("Neues Agent-Passwort für die Anmeldung in der App (mindestens 12 Zeichen):", fontSize = TextSmall)
                SecretField(agentPw, { agentPw = it }, "Agent-Passwort")
                SecretField(agentPw2, { agentPw2 = it }, "Agent-Passwort wiederholen")
                if (agentPw2.isNotEmpty() && agentPw != agentPw2) Text("Die Passwörter stimmen nicht überein.", color = MaterialTheme.colorScheme.error, fontSize = TextSmall)
                if (profile.sshPublicKey.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(addKey, { addKey = it })
                    Text("App-Schlüssel für das Terminal eintragen", fontSize = TextSmall)
                }
                Text("Passwörter werden nicht gespeichert. Das Agent-Passwort liegt nur kurz in einem privaten Ordner auf dem Server und wird sofort gelöscht.", fontSize = TextSmall)
            }
        },
        confirmButton = {
            when {
                hostKey != null -> TextButton(onClick = {
                    state.confirmInstallHostKey(profile, hostKey.first)
                    start(hostKey.first)
                }) { Text("Vertrauen und installieren") }
                state.installLog != null -> TextButton(enabled = !state.installing, onClick = onClose) { Text("Schließen") }
                else -> TextButton(enabled = valid, onClick = { start(null) }) { Text("Installieren") }
            }
        },
        dismissButton = {
            if (!state.installing && state.installLog == null) TextButton(onClick = onClose) { Text("Abbrechen") }
        },
    )
}

/** after sign-in: the agent is older than the app */
@Composable
fun AgentOutdatedDialog(state: AppState) {
    val v = state.agentOutdated ?: return
    AppDialog(
        onDismissRequest = { state.agentOutdated = null },
        title = { Text("Agent aktualisieren?") },
        text = { Text("Auf dem Server läuft der Agent $v, die App ist $APP_VERSION. Einige Funktionen der App brauchen den neuen Agent.") },
        confirmButton = {
            TextButton(onClick = {
                state.agentOutdated = null
                state.startAgentUpdate()
                state.navigate(Section.UPDATES)
            }) { Text("Jetzt aktualisieren") }
        },
        dismissButton = { TextButton(onClick = { state.agentOutdated = null }) { Text("Später") } },
    )
}
