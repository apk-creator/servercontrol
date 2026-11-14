package app.servercontrol.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.servercontrol.AppState
import app.servercontrol.Bold
import app.servercontrol.ButtonRow
import app.servercontrol.Session
import app.servercontrol.SmallButton
import app.servercontrol.SshKeyAlgo
import app.servercontrol.data.AppJson
import app.servercontrol.generateSshKey
import app.servercontrol.isDesktop
import app.servercontrol.ui.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * SSH keys: generate a key pair for this device (like ConnectBot), use it in the terminal
 * and register the public key in ~/.ssh/authorized_keys on the server via the agent.
 */
@Composable
fun SshKeysScreen(state: AppState, session: Session) {
    val api = session.api
    val profile = session.profile
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val host = rememberActionHost { api }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    var user by remember { mutableStateOf(profile.sshUser) }
    var refresh by remember { mutableStateOf(0) }
    var generating by remember { mutableStateOf(false) }
    var algo by remember { mutableStateOf(SshKeyAlgo.ED25519) }
    var bits by remember { mutableStateOf(0) }
    val today = formatTime(currentTimeMs()).take(8)
    var comment by remember { mutableStateOf("servercontrol-${if (isDesktop) "desktop" else "android"}-$today") }
    var addKey by remember { mutableStateOf(false) }
    var importKey by remember { mutableStateOf(false) }
    var genError by remember { mutableStateOf<String?>(null) }

    fun register(key: String) {
        val u = user.trim()
        val body = buildJsonObject { put("user", u); put("key", key) }.toString()
        host.run("Schlüssel eintragen", {
            AppJson.parseToJsonElement(api.postRaw("/api/ssh/keys", body))["message"].str
        }, after = {
            refresh++
            if (u.isNotBlank() && u != session.profile.sshUser) state.updateSessionProfile(session.profile.copy(sshUser = u))
        })
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        // ---------- key of this device ----------
        SectionCard("Schlüssel dieses Geräts") {
            val p = session.profile
            if (p.sshPrivateKey.isBlank()) {
                Text("Noch kein Schlüssel. Erzeuge hier einen eigenen Schlüssel für dieses Gerät – " +
                    "der private Teil bleibt verschlüsselt in der App, nur der öffentliche kommt auf den Server.", fontSize = TextSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SshKeyAlgo.entries.forEach { a ->
                        FilterPill(algo == a, {
                            algo = a
                            bits = when (a) { SshKeyAlgo.ED25519 -> 0; SshKeyAlgo.ECDSA -> 256; SshKeyAlgo.RSA -> 4096 }
                        }, if (a == SshKeyAlgo.ED25519) "Ed25519 (empfohlen)" else a.label)
                    }
                }
                val sizes = when (algo) { SshKeyAlgo.ECDSA -> listOf(256, 384, 521); SshKeyAlgo.RSA -> listOf(3072, 4096); else -> emptyList() }
                if (sizes.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    sizes.forEach { b -> FilterPill(bits == b, { bits = b }, "$b Bit") }
                }
                OutlinedTextField(comment, { comment = it.replace(" ", "-") }, Modifier.fillMaxWidth(), label = { Text("Kommentar (erscheint auf dem Server)") }, singleLine = true)
                Button(enabled = !generating, onClick = {
                    generating = true
                    genError = null
                    scope.launch {
                        try {
                            val k = generateSshKey(algo, bits, comment.trim())
                            state.updateSessionProfile(
                                session.profile.copy(sshPrivateKey = k.privateKey, sshPublicKey = k.publicKey, sshKeyFingerprint = k.fingerprint)
                            )
                        } catch (e: Throwable) {
                            genError = "Erzeugen fehlgeschlagen: ${e.message ?: e}"
                        } finally {
                            generating = false
                        }
                    }
                }) { Text(if (generating) "Erzeuge …" else "Schlüssel erzeugen") }
                if (generating) LinearProgressIndicator(Modifier.fillMaxWidth())
                genError?.let { Text(it, color = StatusBad) }
                TextButton(onClick = { importKey = true }) { Text("Stattdessen vorhandenen privaten Schlüssel einfügen …") }
            } else {
                if (p.sshPublicKey.isNotBlank()) {
                    KeyValues("Typ" to p.sshPublicKey.substringBefore(' '), "Fingerprint" to p.sshKeyFingerprint)
                    Text("Öffentlicher Schlüssel:", fontSize = TextSmall)
                    MonoText(p.sshPublicKey)
                    ButtonRow {
                        SmallButton("Kopieren", { clipboard.setText(AnnotatedString(p.sshPublicKey)) })
                        Button(enabled = user.isNotBlank(), onClick = { register(p.sshPublicKey) }) { Text("Auf dem Server eintragen") }
                    }
                } else {
                    Text("Ein importierter privater Schlüssel ist hinterlegt (im Terminal-Dialog eingefügt).", fontSize = TextSmall)
                }
                Text("Das Terminal meldet sich damit automatisch an (Benutzer: ${user.ifBlank { "–" }}).", fontSize = TextSmall)
                ButtonRow {
                    SmallButton("Schlüssel löschen", {
                        confirm = Confirm("Schlüssel dieses Geräts löschen?",
                            "Der private Schlüssel wird aus der App entfernt. Trage ihn auf dem Server ebenfalls aus (unten), sonst bleibt der Eintrag dort bestehen.") {
                            state.updateSessionProfile(session.profile.copy(sshPrivateKey = "", sshPublicKey = "", sshKeyFingerprint = ""))
                        }
                    })
                }
            }
        }

        // ---------- keys on the server ----------
        SectionCard("Eingetragene Schlüssel auf dem Server") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(user, { user = it.trim() }, Modifier.weight(1f), label = { Text("SSH-Benutzer") }, singleLine = true)
                Spacer(Modifier.width(8.dp))
                SmallButton("Laden", { refresh++ }, enabled = user.isNotBlank())
            }
            if (user.isNotBlank()) {
                Loader(user to refresh, { api.getJson("/api/ssh/keys?user=${user.trim()}") }) { d, reload ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(d["file"].str, fontSize = TextSmall)
                        val pw = d["passwordAuthentication"].str
                        if (pw.isNotBlank()) {
                            Text("Passwort-Anmeldung per SSH: ${if (pw == "yes") "an" else "aus"}", fontSize = TextSmall, fontWeight = Bold)
                            val keyCount = d["keys"].arr.size
                            val mineRegistered = d["keys"].arr.any { it["fingerprint"].str == session.profile.sshKeyFingerprint }
                            fun toggle(enable: Boolean) {
                                val body = buildJsonObject { put("enabled", enable); put("user", user.trim()) }.toString()
                                host.run("Passwort-Anmeldung", {
                                    AppJson.parseToJsonElement(api.postRaw("/api/ssh/password-auth", body))["message"].str
                                }, reload)
                            }
                            if (pw == "yes" && keyCount > 0) SmallButton("Passwort-Anmeldung abschalten", {
                                confirm = Confirm("Passwort-Anmeldung per SSH abschalten?",
                                    "Danach kommen nur noch Geräte mit eingetragenem Schlüssel per SSH auf den Server (alle Benutzer)." +
                                        (if (!mineRegistered) "\n\nACHTUNG: Der Schlüssel dieses Geräts ist für ${user.trim()} nicht eingetragen!" else "") +
                                        "\n\nVorher am besten einmal per Schlüssel anmelden und die Sitzung offen lassen. Notfallzugang bleibt Cockpit (Port 9090) und diese App.") {
                                    toggle(false)
                                }
                            })
                            if (pw == "no") SmallButton("Passwort-Anmeldung wieder einschalten", { toggle(true) })
                        }
                        if (d["keys"].arr.isEmpty()) Text("Noch keine Schlüssel eingetragen.", fontSize = TextSmall)
                        d["keys"].arr.forEachIndexed { i, k ->
                            val fp = k["fingerprint"].str
                            val mine = fp == session.profile.sshKeyFingerprint
                            Row(Modifier.fillMaxWidth().background(stripe(i)).padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(k["comment"].str.ifBlank { "(ohne Kommentar)" } + if (mine) "  · dieses Gerät" else "", fontWeight = Bold)
                                    Text("${k["type"].str}  $fp", fontSize = TextSmall)
                                }
                                TextButton(onClick = {
                                    confirm = Confirm("Schlüssel austragen?",
                                        "${k["comment"].str}\n$fp\n\n" + (if (mine) "Das ist der Schlüssel dieses Geräts – das Terminal braucht danach wieder das Passwort. " else "") +
                                            (if (pw == "no" && d["keys"].arr.size == 1) "ACHTUNG: Das ist der letzte Schlüssel und die Passwort-Anmeldung ist aus – danach ist kein SSH-Login mehr möglich!" else "")) {
                                        val body = buildJsonObject { put("user", user.trim()); put("fingerprint", fp) }.toString()
                                        host.run("Schlüssel austragen", { api.postRaw("/api/ssh/keys/remove", body); "Schlüssel entfernt." }, reload)
                                    }
                                }) { Text("Austragen") }
                            }
                        }
                        ButtonRow { SmallButton("Schlüssel eines anderen Geräts hinzufügen …", { addKey = true }) }
                    }
                }
            }
        }

        SectionCard("Hinweis") {
            Text("Erzeuge für jedes Gerät einen eigenen Schlüssel und trage ihn ein. Die Passwort-Anmeldung erst abschalten, " +
                "wenn sich alle Geräte per Schlüssel anmelden – und dabei eine zweite Sitzung offen lassen.", fontSize = TextSmall)
        }
    }

    if (addKey) {
        var text by remember { mutableStateOf("") }
        AppDialog(
            onDismissRequest = { addKey = false },
            title = { Text("Öffentlichen Schlüssel hinzufügen") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Inhalt der .pub-Datei, z. B. „ssh-ed25519 AAAA… name@pc“", fontSize = TextSmall)
                    OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), minLines = 3, maxLines = 6)
                }
            },
            confirmButton = {
                TextButton(enabled = text.isNotBlank() && user.isNotBlank(), onClick = { register(text.trim()); addKey = false }) { Text("Eintragen") }
            },
            dismissButton = { TextButton(onClick = { addKey = false }) { Text("Abbrechen") } },
        )
    }
    if (importKey) {
        var text by remember { mutableStateOf("") }
        AppDialog(
            onDismissRequest = { importKey = false },
            title = { Text("Privaten Schlüssel einfügen") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Inhalt der privaten Schlüsseldatei (OpenSSH oder PEM). Er wird verschlüsselt im Profil gespeichert.", fontSize = TextSmall)
                    OutlinedTextField(
                        text, { text = it }, Modifier.fillMaxWidth(), minLines = 1, maxLines = 1,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        placeholder = { Text("-----BEGIN OPENSSH PRIVATE KEY-----") },
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = text.contains("PRIVATE KEY"), onClick = {
                    state.updateSessionProfile(session.profile.copy(sshPrivateKey = text.trim() + "\n", sshPublicKey = "", sshKeyFingerprint = ""))
                    importKey = false
                }) { Text("Übernehmen") }
            },
            dismissButton = { TextButton(onClick = { importKey = false }) { Text("Abbrechen") } },
        )
    }
    ConfirmDialog(confirm) { confirm = null }
    ActionResultDialog(host, api)
}
