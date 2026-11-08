package app.servercontrol

import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.servercontrol.data.ServerProfile
import app.servercontrol.net.Route
import app.servercontrol.ui.screens.*
import kotlinx.coroutines.launch
import kotlin.random.Random

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(), content = content)
}

@Composable
fun App(state: AppState) {
    LaunchedEffect(Unit) { state.loadVault() }
    AppTheme {
        Surface(Modifier.fillMaxSize()) {
            when (val s = state.screen) {
                Screen.Profiles -> ProfilesScreen(state)
                is Screen.Login -> LoginScreen(state, s.profile)
                Screen.Main -> MainScreen(state)
            }
            state.pendingCert?.let { (fp, d) ->
                AlertDialog(
                    onDismissRequest = { d.complete(false) },
                    title = { Text("Zertifikat bestätigen") },
                    text = {
                        Column {
                            Text("Erste Verbindung zu diesem Server. Vergleiche den Fingerprint mit der Ausgabe von\n`servercontrol-agent -fingerprint` auf dem Server:")
                            Spacer(Modifier.height(8.dp))
                            Text(fp, fontFamily = FontFamily.Monospace)
                        }
                    },
                    confirmButton = { TextButton(onClick = { d.complete(true) }) { Text("Stimmt überein – vertrauen") } },
                    dismissButton = { TextButton(onClick = { d.complete(false) }) { Text("Abbrechen") } },
                )
            }
        }
    }
}

// ======================= Profiles =======================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilesScreen(state: AppState) {
    var editing by remember { mutableStateOf<ServerProfile?>(null) }
    Scaffold(
        topBar = { TopAppBar(title = { Text("ServerControl") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                editing = ServerProfile(id = Random.nextLong().toULong().toString(16), name = "", lanUrl = "https://")
            }) { Icon(Icons.Default.Add, "Server hinzufügen") }
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            if (state.vault.profiles.isEmpty()) item {
                Text("Noch kein Server angelegt. Tippe auf +.", Modifier.padding(24.dp))
            }
            items(state.vault.profiles, key = { it.id }) { p ->
                ListItem(
                    headlineContent = { Text(p.name) },
                    supportingContent = {
                        Text(buildString {
                            append(p.lanUrl)
                            if (p.wgTunnel.isNotBlank()) append("  ·  WireGuard: ${p.wgTunnel}")
                            if (p.certFingerprint.isBlank()) append("  ·  Zertifikat noch nicht bestätigt")
                        })
                    },
                    leadingContent = { Icon(Icons.Default.Dns, null) },
                    trailingContent = { IconButton(onClick = { editing = p }) { Icon(Icons.Default.Edit, "Bearbeiten") } },
                    modifier = Modifier.clickable { state.error = null; state.screen = Screen.Login(p) },
                )
                HorizontalDivider()
            }
        }
    }
    editing?.let { p ->
        ProfileEditor(state, p, onDone = { editing = null })
    }
}

@Composable
fun ProfileEditor(state: AppState, initial: ServerProfile, onDone: () -> Unit) {
    var p by remember { mutableStateOf(initial) }
    val isNew = state.vault.profiles.none { it.id == initial.id }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(if (isNew) "Server hinzufügen" else "Server bearbeiten") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(p.name, { p = p.copy(name = it) }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(p.lanUrl, { p = p.copy(lanUrl = it.trim()) }, label = { Text("Adresse im Heimnetz") },
                    placeholder = { Text("https://192.168.178.10:8443") }, singleLine = true)
                OutlinedTextField(p.vpnUrl, { p = p.copy(vpnUrl = it.trim()) }, label = { Text("Adresse über VPN (optional)") },
                    placeholder = { Text("https://10.8.0.1:8443") }, singleLine = true)
                OutlinedTextField(p.wgTunnel, { p = p.copy(wgTunnel = it.trim()) }, label = { Text("WireGuard-Tunnelname") },
                    supportingText = { Text(state.vpn.description) }, singleLine = true)
                if (isDesktop) {
                    OutlinedTextField(p.vpnUpCommand, { p = p.copy(vpnUpCommand = it) }, label = { Text("Befehl Tunnel an (leer = Standard)") }, singleLine = true)
                    OutlinedTextField(p.vpnDownCommand, { p = p.copy(vpnDownCommand = it) }, label = { Text("Befehl Tunnel aus (leer = Standard)") }, singleLine = true)
                }
                Text(
                    if (p.certFingerprint.isBlank()) "Zertifikat: wird bei der ersten Verbindung bestätigt."
                    else "Zertifikat: ${p.certFingerprint.take(23)}…",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (p.certFingerprint.isNotBlank()) {
                    TextButton(onClick = { p = p.copy(certFingerprint = "") }) { Text("Fingerprint zurücksetzen") }
                }
                if (!isNew) {
                    TextButton(onClick = { state.deleteProfile(initial); onDone() }) { Text("Server entfernen", color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = p.name.isNotBlank() && p.lanUrl.startsWith("https://") && p.lanUrl.length > 9 &&
                    (p.vpnUrl.isBlank() || p.vpnUrl.startsWith("https://")),
                onClick = { state.saveProfile(p); onDone() },
            ) { Text("Speichern") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Abbrechen") } },
    )
}

// ======================= Login =======================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(state: AppState, profile: ServerProfile) {
    var user by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    val submit = {
        if (state.busy == null && user.isNotBlank() && pw.isNotBlank()) {
            state.login(profile, user, pw)
            pw = ""
        }
        Unit
    }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(profile.name) },
            navigationIcon = { IconButton(onClick = { state.screen = Screen.Profiles }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") } },
        )
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 420.dp).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Anmelden bei ${profile.name}", style = MaterialTheme.typography.titleLarge)
                Text("Zugangsdaten aus KeePass (Autofill bzw. Auto-Type).", style = MaterialTheme.typography.bodySmall)
                CredentialFields(user, { user = it }, pw, { pw = it }, onSubmit = submit)
                Button(onClick = submit, enabled = state.busy == null, modifier = Modifier.fillMaxWidth()) { Text("Verbinden") }
                state.busy?.let {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(it)
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

// ======================= Main =======================

fun Section.icon(): ImageVector = when (this) {
    Section.OVERVIEW -> Icons.Default.Dashboard
    Section.SERVICES -> Icons.Default.SettingsApplications
    Section.CONTAINERS -> Icons.Default.ViewInAr
    Section.LOGS -> Icons.AutoMirrored.Filled.List
    Section.STORAGE -> Icons.Default.Storage
    Section.NETWORK -> Icons.Default.Lan
    Section.PROCESSES -> Icons.Default.Memory
    Section.UPDATES -> Icons.Default.SystemUpdate
    Section.ACCOUNTS -> Icons.Default.People
    Section.KOPIA -> Icons.Default.Backup
    Section.NEXTCLOUD -> Icons.Default.Cloud
    Section.IMMICH -> Icons.Default.PhotoLibrary
    Section.JOBS -> Icons.Default.Pending
    Section.SHELL -> Icons.Default.Terminal
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(state: AppState) {
    val session = state.session ?: return
    val sections = Section.entries.filter { it.module == null || session.modules[it.module] == true }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    BoxWithConstraints {
        val wide = maxWidth > 840.dp
        val nav: @Composable () -> Unit = {
            ModalDrawerSheet(Modifier.widthIn(max = 280.dp)) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(session.profile.name, Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
                    sections.forEach { s ->
                        NavigationDrawerItem(
                            label = { Text(s.title) },
                            icon = { Icon(s.icon(), null) },
                            selected = state.section == s,
                            onClick = { state.section = s; scope.launch { drawer.close() } },
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                }
            }
        }
        val content: @Composable () -> Unit = {
            Scaffold(topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(state.section.title)
                            Text(
                                session.profile.name + " · " + if (session.route == Route.VPN) "über WireGuard" else "Heimnetz",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    },
                    navigationIcon = {
                        if (!wide) IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Default.Menu, "Menü") }
                    },
                    actions = {
                        if (session.route == Route.VPN) Icon(Icons.Default.VpnLock, "VPN aktiv", Modifier.padding(end = 8.dp))
                        IconButton(onClick = { state.logout() }) { Icon(Icons.AutoMirrored.Filled.Logout, "Abmelden") }
                    },
                )
            }) { pad ->
                Box(Modifier.padding(pad).fillMaxSize()) {
                    if (state.paused || state.busy != null) {
                        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            CircularProgressIndicator()
                            Text(state.busy ?: "Pausiert", Modifier.padding(12.dp))
                        }
                    } else {
                        SectionContent(state, session)
                    }
                }
            }
        }
        if (wide) {
            PermanentNavigationDrawer(drawerContent = nav, content = content)
        } else {
            ModalNavigationDrawer(drawerState = drawer, drawerContent = nav, content = content)
        }
    }
}

@Composable
fun SectionContent(state: AppState, session: Session) {
    val api = session.api
    when (state.section) {
        Section.OVERVIEW -> OverviewScreen(api) { state.section = it }
        Section.SERVICES -> ServicesScreen(api)
        Section.CONTAINERS -> ContainersScreen(api)
        Section.LOGS -> LogsScreen(api)
        Section.STORAGE -> StorageScreen(api)
        Section.NETWORK -> NetworkScreen(api)
        Section.PROCESSES -> ProcessesScreen(api)
        Section.UPDATES -> UpdatesScreen(api)
        Section.ACCOUNTS -> AccountsScreen(api)
        Section.KOPIA -> KopiaScreen(api)
        Section.NEXTCLOUD -> NextcloudScreen(api)
        Section.IMMICH -> ImmichScreen(api)
        Section.JOBS -> JobsScreen(api)
        Section.SHELL -> ShellScreen(api)
    }
}

@Composable
fun SmallButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    OutlinedButton(onClick = onClick, enabled = enabled, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) { Text(text) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ButtonRow(content: @Composable () -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) { content() }
}

val Bold = FontWeight.SemiBold
