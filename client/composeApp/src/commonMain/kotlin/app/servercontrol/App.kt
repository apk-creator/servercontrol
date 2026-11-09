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
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.servercontrol.data.ServerProfile
import app.servercontrol.ui.screens.*
import kotlinx.coroutines.launch
import kotlin.random.Random

// Monochrome design: black / grey / white. Only status indicators keep colour.
private val MonoLight = lightColorScheme(
    primary = Color(0xFF111111), onPrimary = Color.White,
    primaryContainer = Color(0xFFE0E0E0), onPrimaryContainer = Color(0xFF111111),
    inversePrimary = Color(0xFFDADADA),
    secondary = Color(0xFF424242), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE6E6E6), onSecondaryContainer = Color(0xFF111111),
    tertiary = Color(0xFF616161), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFEDEDED), onTertiaryContainer = Color(0xFF111111),
    background = Color.White, onBackground = Color(0xFF111111),
    surface = Color.White, onSurface = Color(0xFF111111),
    surfaceVariant = Color(0xFFEEEEEE), onSurfaceVariant = Color(0xFF424242),
    surfaceTint = Color(0xFF111111),
    inverseSurface = Color(0xFF212121), inverseOnSurface = Color(0xFFF5F5F5),
    outline = Color(0xFF8E8E8E), outlineVariant = Color(0xFFD6D6D6),
    surfaceBright = Color.White, surfaceDim = Color(0xFFE0E0E0),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF7F7F7),
    surfaceContainer = Color(0xFFF2F2F2), surfaceContainerHigh = Color(0xFFECECEC),
    surfaceContainerHighest = Color(0xFFE6E6E6),
)

private val MonoDark = darkColorScheme(
    primary = Color(0xFFEDEDED), onPrimary = Color(0xFF111111),
    primaryContainer = Color(0xFF3A3A3A), onPrimaryContainer = Color.White,
    inversePrimary = Color(0xFF212121),
    secondary = Color(0xFFBDBDBD), onSecondary = Color(0xFF111111),
    secondaryContainer = Color(0xFF2E2E2E), onSecondaryContainer = Color(0xFFEDEDED),
    tertiary = Color(0xFF9E9E9E), onTertiary = Color(0xFF111111),
    tertiaryContainer = Color(0xFF333333), onTertiaryContainer = Color(0xFFEDEDED),
    background = Color(0xFF0E0E0E), onBackground = Color(0xFFEDEDED),
    surface = Color(0xFF0E0E0E), onSurface = Color(0xFFEDEDED),
    surfaceVariant = Color(0xFF2A2A2A), onSurfaceVariant = Color(0xFFBDBDBD),
    surfaceTint = Color(0xFFEDEDED),
    inverseSurface = Color(0xFFEDEDED), inverseOnSurface = Color(0xFF111111),
    outline = Color(0xFF7A7A7A), outlineVariant = Color(0xFF3A3A3A),
    surfaceBright = Color(0xFF2C2C2C), surfaceDim = Color(0xFF0E0E0E),
    surfaceContainerLowest = Color(0xFF080808), surfaceContainerLow = Color(0xFF151515),
    surfaceContainer = Color(0xFF1B1B1B), surfaceContainerHigh = Color(0xFF232323),
    surfaceContainerHighest = Color(0xFF2B2B2B),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) MonoDark else MonoLight, content = content)
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
                OutlinedTextField(p.lanUrl, { p = p.copy(lanUrl = it.trim()) }, label = { Text("Adresse des Agenten") },
                    placeholder = { Text("https://192.168.178.179:8443") }, singleLine = true,
                    supportingText = { Text("Von unterwegs vorher das VPN der Fritzbox verbinden.") })
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
                enabled = p.name.isNotBlank() && p.lanUrl.startsWith("https://") && p.lanUrl.length > 9,
                onClick = { state.saveProfile(p); onDone() },
            ) { Text("Speichern") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Abbrechen") } },
    )
}

// ======================= Login =======================

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
        Box(Modifier.padding(pad).consumeWindowInsets(pad).imePadding().fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 420.dp).padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Anmelden bei ${profile.name}", style = MaterialTheme.typography.titleLarge)
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
    Section.TERMINAL -> Icons.Default.Terminal
    Section.AGENT -> Icons.Default.Settings
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
    Section.SHELL -> Icons.Default.Code
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
                            Text(session.profile.name, style = MaterialTheme.typography.bodySmall)
                        }
                    },
                    navigationIcon = {
                        if (!wide) IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Default.Menu, "Menü") }
                    },
                    actions = {
                        IconButton(onClick = { state.logout() }) { Icon(Icons.AutoMirrored.Filled.Logout, "Abmelden") }
                    },
                )
            }) { pad ->
                // keyboard pushes the content up (Android 15 draws edge-to-edge)
                Box(Modifier.padding(pad).consumeWindowInsets(pad).imePadding().fillMaxSize()) {
                    if (state.busy != null) {
                        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            CircularProgressIndicator()
                            Text(state.busy ?: "", Modifier.padding(12.dp))
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
        Section.TERMINAL -> TerminalScreen(state, session)
        Section.AGENT -> AgentScreen(api)
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
