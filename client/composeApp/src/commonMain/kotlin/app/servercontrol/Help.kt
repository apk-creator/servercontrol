package app.servercontrol

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.servercontrol.ui.TextSmall

private const val COMMON =
    "Allgemein: Reiter und Karten lassen sich umordnen – lange auf den Reiter bzw. die Kartenüberschrift drücken und ziehen. " +
        "Die Pfeile rechts klappen Karten ein und aus, das Kreissymbol lädt neu, das Zahnrad öffnet weitere Aktionen. " +
        "Das Seitenmenü sortierst du genauso durch langes Drücken."

/** What each menu can do – shown under "?" → Hinweis. */
fun helpFor(section: Section): List<Pair<String, String>> = when (section) {
    Section.OVERVIEW -> listOf(
        "Neustart / Herunterfahren" to "Sofort, in 1 oder in 5 Minuten. Ein geplanter Vorgang erscheint darunter und lässt sich abbrechen. Nach dem Herunterfahren geht Einschalten aus der Ferne nur per Wake-on-LAN.",
        "System" to "Hostname, Betriebssystem, Kernel, CPU, Laufzeit und Temperaturen (min / Ø / max). Ein Tipp auf eine Temperaturzeile zeigt die einzelnen Sensoren. Schlafende Platten werden dafür nicht geweckt; für sie gilt der letzte gemessene Wert.",
        "Auslastung" to "CPU (Tipp: alle Kerne), Arbeitsspeicher, Swap und Festplatten-I/O.",
        "Laufwerke" to "Jedes Laufwerk in einer Zeile: Zustand (aktiv, idle, Standby), SMART-Status und Belegung. Grauer SMART-Status = letzter bekannter Wert, weil die Platte schläft. Tipp öffnet System → Speicher.",
        "Verlauf" to "Alles seit dem Start des Agenten, höchstens 24 Stunden. Die Diagramme sind auf den höchsten Wert des Zeitraums skaliert („Spitze“).",
    )
    Section.SYSTEM -> listOf(
        "Dienste" to "Alle systemd-Dienste mit Suche und Filter (alle, aktiv, fehlgeschlagen). Antippen: Start, Stopp, Neustart, Konfiguration neu laden, Autostart an/aus, Fehler zurücksetzen, Logs.",
        "Prozesse" to "Laufende Prozesse, sortiert nach CPU oder RAM. Antippen zum Beenden (oder Erzwingen).",
        "Container" to "Docker-/Podman-Container: Status, Starten, Stoppen, Neustarten, Protokoll.",
        "Speicher → Laufwerke" to "NVMe vor SATA. Je Laufwerk Zustand, SMART und Belegung der eingehängten Dateisysteme. Antippen öffnet die Laufwerksseite mit allen Details: Partitionen anlegen und löschen, Partitionstabelle, Formatieren (ext4, xfs, btrfs, exFAT, FAT32, NTFS), Umbenennen, Ein- und Aushängen (wahlweise dauerhaft über die fstab mit nofail). Löschende Aktionen verlangen die Eingabe des Gerätenamens; Laufwerke mit dem laufenden System sind dafür gesperrt. Ein Tipp auf SMART zeigt die Werte und startet Selbsttests; schlafende Platten werden nur auf Wunsch geweckt.",
        "Speicher → Auto-Standby" to "Leerlaufzeit je HDD, verwaltet von hd-idle. 10–30 Minuten sind ein guter Wert; zu kurze Zeiten verschleißen Motor und Mechanik. Das Mondsymbol schickt eine Platte sofort schlafen. Andere Dienste, die per hdparm eigene Standby-Zeiten setzen, werden beim Speichern abgeschaltet.",
        "Netzwerk" to "Schnittstellen mit Adressen und Verkehr sowie die WireGuard-Peers des Servers mit letztem Handshake.",
        "Protokolle" to "journald, neueste Einträge oben. Filter nach Dienst (z. B. „kopia“ findet alle Kopia-Dienste), Suchtext, Fehler/Warnungen und „seit Boot“ greifen von selbst.",
    )
    Section.TERMINAL -> listOf(
        "Verbindung" to "SSH-Terminal zum Server. Die Anmeldung läuft nur per Schlüssel. Beim ersten Verbinden den Hostschlüssel mit „ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub“ auf dem Server vergleichen. Die Sitzung bleibt im Hintergrund verbunden.",
        "Im Terminal" to "Text markieren, dann im ⋮-Menü neben der Markierung „Kopieren“. Oben: Schrift kleiner/größer, Einfügen, Leeren, Trennen.",
        "Schlüssel dieses Geräts" to "Eigenen Schlüssel erzeugen (Ed25519 empfohlen) oder einfügen. Der private Teil bleibt verschlüsselt in der App, nur der öffentliche kommt auf den Server. Über das Zahnrad lässt sich der private Schlüssel exportieren (z. B. vor einer Neuinstallation).",
        "Eingetragene Schlüssel" to "Schlüssel in ~/.ssh/authorized_keys des Benutzers verwalten und die Passwort-Anmeldung von SSH abschalten. Erst abschalten, wenn sich alle Geräte per Schlüssel anmelden – und dabei eine zweite Sitzung offen lassen.",
    )
    Section.FILES -> listOf(
        "Dateien" to "Dateimanager mit vollen Rechten auf dem Server, versteckte Dateien werden immer angezeigt. Ordner antippen öffnet sie, das Zahnrad zeigt ihre Aktionen. Dateien antippen zeigt Ansehen, Herunterladen, Umbenennen und Löschen. „+“ lädt Dateien hoch oder legt Ordner an. Systemordner lassen sich nicht als Ganzes löschen.",
    )
    Section.UPDATES -> listOf(
        "Updates" to "App, Agent, Nextcloud, Immich und Server auf einer Seite. Geprüft wird bei der Anmeldung und täglich im Hintergrund (mit Benachrichtigung). Das Kreissymbol prüft neu und aktualisiert dabei die Paketlisten.",
        "App" to "Das Update lädt im Hintergrund weiter. Ist die App beim Abschluss nicht sichtbar, startet ein Tipp auf „Update bereit“ die Installation.",
        "Agent" to "Der Agent lädt das Release von GitHub, prüft die SHA-256-Prüfsumme, ersetzt sich selbst und startet neu. Erst den Agent, dann die App aktualisieren.",
        "Server" to "apt-Updates; Sicherheitsupdates sind rot. Full-Upgrade kann auch Pakete entfernen oder neu installieren.",
    )
    Section.ACCOUNTS -> listOf("Konten" to "Benutzerkonten des Servers (UID, Home, Shell) und die letzten Anmeldungen.")
    Section.KOPIA -> listOf(
        "Repositories" to "Ein Reiter je Repository. Oben: Snapshot starten, Verifizieren (liest 1 % zurück, weckt die Platte), Wartung (kurz oder vollständig), Neu laden; das Zahnrad bearbeitet das Repository oder fügt ein neues hinzu (auf dem Server suchen, verbinden/neu anlegen, Config-Datei).",
        "Snapshots" to "Quellen mit letztem und nächstem Snapshot. Antippen öffnet den Verlauf mit Anheften, Beschreibung, Durchsuchen, Wiederherstellen und Löschen.",
        "Richtlinien" to "Alle Richtlinien wie in der Kopia-Weboberfläche: Aufbewahrung, Dateien, Fehlerbehandlung, Komprimierung, Zeitplan, Hochladen, Aktionen, Protokollierung. Leere Felder werden geerbt.",
        "Aufgaben" to "Laufende und letzte Aufgaben des Kopia-Servers mit Protokoll und Abbrechen.",
        "Repository / Wartung" to "Pfad, Laufwerk und Belegung. „Zeitplan“ stellt Intervall und Uhrzeit der automatischen Wartung ein. Laufen die Wartungen über eigene Skripte (z. B. nach dem Snapshot), hier „aus“ lassen. Die Wartungsinfo wird zwischengespeichert, weil das Lesen die Platte weckt; „Neu lesen“ holt sie frisch.",
        "Server" to "Beschreibung des Repositorys. „Abgleichen“ liest neue Snapshots und Richtlinien aus dem Repository ein (z. B. nach Änderungen per Kommandozeile) und weckt dafür die Platte.",
        "Abfrage" to "Alle Daten kommen vom laufenden Kopia-Server, damit die Platten schlafen können. Ohne Server gibt es nur die Snapshot-Liste über die Kommandozeile (weckt die Platte).",
        "Benachrichtigungen" to "Profile (E-Mail, Pushover, Webhook), über die der Kopia-Server Snapshots, Wartung und Fehler meldet.",
    )
    Section.NEXTCLOUD -> listOf(
        "Status" to "Version, Wartungsmodus, Systemprüfungen.",
        "Benutzer" to "Anlegen, sperren, löschen, Kontingent, Passwort, Dateien scannen.",
        "Apps" to "Apps aktivieren, deaktivieren und aktualisieren.",
        "Wartung" to "Wartungsfenster: Beginn in Ortszeit für aufwendige Hintergrundjobs (Aufräumen, Vorschaubilder); Nextcloud gibt 4 Stunden vor, alternativ jederzeit. Nextcloud speichert die Zeit in UTC, mit der Sommerzeit verschiebt sich die Ortszeit um eine Stunde. Außerdem Datenbank-Reparaturen, Dateiscan, Papierkorb und Versionen aufräumen, Modus der Hintergrundjobs.",
        "Einrichtung" to "Pfad zu occ und Aufruf (z. B. sudo -u www-data php). Die Zugangsdaten bleiben auf dem Server.",
    )
    Section.IMMICH -> listOf(
        "Immich" to "Status, Aufträge (starten, pausieren, leeren), Benutzer und externe Bibliotheken. Einrichtung: Adresse und API-Schlüssel – beides bleibt auf dem Server.",
    )
    Section.SHELL -> listOf("Befehl" to "Befehle laufen als root auf dem Server. Nur aktiv, wenn allowShell in der Agent-Konfiguration gesetzt ist. Lange Befehle im Hintergrund starten; sie erscheinen dann unter Agent → Aufgaben.")
    Section.AGENT -> listOf(
        "Agent" to "Versionen und verbundene Module. Updates stehen unter „Updates“.",
        "Angemeldete Geräte" to "Diese Geräte bleiben angemeldet, auch wenn die App lange im Hintergrund war. „Abmelden“ widerruft ein Gerät – ein verlorenes Handy sofort hier abmelden. „Abmelden“ im Seitenmenü entfernt das eigene Gerät.",
        "Aufgaben" to "Länger laufende Aktionen aus der App (apt, Kopia, Nextcloud, Formatieren …) mit Ausgabe. Sie bleiben 24 Stunden sichtbar; ein Neustart des Agenten leert die Liste.",
        "Update-Quelle" to "GitHub-Repository mit den Releases. Das Repository ist öffentlich, ein Token ist nicht nötig (nur bei einem privaten Fork: Fine-grained Token mit „Contents: Read-only“).",
    )
}

/** "?" in the title bar: drop-down "Hinweis" with the functions of the current menu */
@Composable
fun HelpButton(section: Section) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) { Icon(Icons.AutoMirrored.Filled.HelpOutline, "Hinweis") }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        Column(Modifier.widthIn(max = 380.dp).heightIn(max = 520.dp).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("Hinweis", fontWeight = FontWeight.SemiBold)
            helpFor(section).forEach { (title, text) ->
                Text(title, Modifier.padding(top = 10.dp), fontWeight = FontWeight.SemiBold, fontSize = TextSmall)
                Text(text, fontSize = TextSmall)
            }
            HorizontalDivider(Modifier.padding(vertical = 10.dp))
            Text(COMMON, fontSize = TextSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
