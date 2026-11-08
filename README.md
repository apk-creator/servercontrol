# ServerControl

Verwaltung **eines** Debian-13-Servers vom Android-Gerät und vom Desktop (Linux/Windows):

- Cockpit-Funktionen: Übersicht (CPU, RAM, Temperaturen, Last), Dienste (systemd), Container (Docker/Podman), Protokolle (journald), Speicher + SMART, Netzwerk + WireGuard-Peers, Prozesse, Updates (apt), Konten, Neustart/Herunterfahren, optional Root-Befehle
- **Kopia** – beliebig viele Repositories (je eine Kopia-Konfigurationsdatei): Status, Snapshots je Quelle, Snapshot jetzt, Wartung, Verifizieren, Wiederherstellen, Löschen
- **Nextcloud** – Status, Wartungsmodus, Einrichtungsprüfung, Benutzer (anlegen, sperren, Kontingent, Passwort), Apps, DB-Reparaturen, Dateiscan, Hintergrundjob-Modus
- **Immich** – Version/Speicher/Statistik, Aufträge (starten, pausieren, leeren), Benutzer, externe Bibliotheken
- **WireGuard automatisch**: nur während die App benutzt wird und nur außerhalb des Heimnetzes

```
 App (Android / Desktop)  ──HTTPS, Zertifikat gepinnt──▶  servercontrol-agent (Go, root, systemd)
   · Adressen verschlüsselt                                 · systemctl, journalctl, apt, smartctl
   · Passwörter aus KeePass                                 · kopia --config-file=…
   · WireGuard an/aus                                       · occ (Nextcloud), Immich-API (Schlüssel bleibt auf dem Server)
```

## 1. Agent auf dem Server installieren

Artefakt `servercontrol-agent` aus dem letzten GitHub-Actions-Lauf (oder Release) laden, auf den Server kopieren:

```sh
mkdir sc && tar -C sc -xzf servercontrol-agent.tar.gz && cd sc
sudo sh install.sh servercontrol-agent-linux-amd64
```

Der Installer legt `/etc/servercontrol/agent.json` an, fragt das Login-Passwort ab (min. 12 Zeichen – in KeePass speichern), erzeugt ein TLS-Zertifikat und zeigt dessen **Fingerprint** an. Den brauchst du bei der ersten Verbindung in der App.

Danach `/etc/servercontrol/agent.json` anpassen (Vorlage: `deploy/agent.example.json`) und `systemctl restart servercontrol-agent`.

| Schlüssel | Bedeutung |
|---|---|
| `listen` | Adressen, z. B. LAN-IP und WireGuard-IP des Servers. **Nicht** ins Internet freigeben. |
| `kopia.repos[]` | `id`, `name`, `configFile` (eigene Kopia-Config je Repo), optional `passwordFile` (chmod 600), `sources` |
| `nextcloud.occ` | Befehl für occ, z. B. `["runuser","-u","www-data","--","php","/var/www/nextcloud/occ"]` (Docker: `["docker","exec","-u","www-data","nextcloud","php","occ"]`) |
| `immich.url` / `apiKeyFile` | Immich-URL vom Server aus + Datei mit Admin-API-Schlüssel (Rechte: server.*, job.*, adminUser.*, library.*) |
| `allowShell` | `true` schaltet den Bereich „Befehl“ frei (Root-Shell!) – standardmäßig aus |

Weitere Befehle: `servercontrol-agent -passwd` (Passwort ändern), `-fingerprint` (Fingerprint anzeigen).

Kopia-Repositories lassen sich auch direkt in der App hinzufügen/bearbeiten (speichert nur die Definition im Agent).

## 2. App installieren

- **Android**: `ServerControl.apk` aus dem Actions-Artefakt installieren. Alle Builds sind mit demselben Schlüssel signiert, Updates lassen sich also drüberinstallieren.
- **Linux**: `.deb` installieren (`sudo apt install ./serverControl_*.deb`)
- **Windows**: `.msi` installieren

## 3. Server in der App anlegen

- **Adresse im Heimnetz**: z. B. `https://192.168.178.10:8443`
- **Adresse über VPN** (optional): z. B. `https://10.8.0.1:8443` – leer lassen, wenn die LAN-Adresse auch durch den Tunnel erreichbar ist
- **WireGuard-Tunnelname**

Bei der ersten Verbindung zeigt die App den Zertifikat-Fingerprint – mit `servercontrol-agent -fingerprint` vergleichen und bestätigen. Danach ist das Zertifikat gepinnt; ein geändertes Zertifikat führt zum Abbruch.

### WireGuard-Automatik

Beim Verbinden prüft die App, ob der Agent im Heimnetz erreichbar ist. Wenn nicht, schaltet sie den Tunnel ein. Verlässt du die App, wird der Tunnel wieder abgeschaltet – aber nur, wenn die App ihn selbst eingeschaltet hat (Android: 60 s nach Verlassen der App, Desktop: 5 min nach Minimieren oder sofort beim Schließen). Beim Zurückkehren wird er automatisch wieder aktiviert.

- **Android**: offizielle WireGuard-App. Dort *Einstellungen → „Fernsteuerung durch Apps erlauben“* einschalten und ServerControl die Berechtigung „WireGuard-Tunnel steuern“ geben (wird beim ersten Start abgefragt).
- **Linux**: Tunnel in NetworkManager importieren: `nmcli connection import type wireguard file wg-home.conf` und `nmcli connection modify wg-home connection.autoconnect no`. Tunnelname = Verbindungsname.
- **Windows**: Standard ist `wireguard.exe /installtunnelservice …` – das braucht Administratorrechte. Alternativ eigene Befehle im Profil eintragen (`{tunnel}` wird ersetzt).

## 4. KeePass

Die App speichert **keine Passwörter**.

- **Android**: Benutzer-/Passwortfelder sind native Autofill-Felder → KeePassDX / Keepass2Android füllen sie aus.
- **Desktop**: KeePassXC Auto-Type mit Standardsequenz `{USERNAME}{TAB}{PASSWORD}{ENTER}`. Fenstertitel zum Zuordnen: `ServerControl – Login <Profilname>`. Das Master-Passwort der App (verschlüsselt die Profile) kommt ebenfalls aus KeePass: Fenstertitel `ServerControl – Entsperren`, Sequenz `{PASSWORD}{ENTER}`.

## Sicherheit

- Profile (Adressen, Tunnelnamen, Fingerprints) liegen verschlüsselt: Android mit AES-256-GCM-Schlüssel im Android Keystore, Desktop mit AES-256-GCM aus dem Master-Passwort (PBKDF2-SHA256, 310 000 Iterationen). Android: keine Backups, keine Screenshots (FLAG_SECURE).
- Agent: nur TLS, Zertifikat in der App gepinnt, Passwort als PBKDF2-SHA256 (600 000 Iterationen), Sperre nach 5 Fehlversuchen für 15 min, Sitzungen laufen nach 30 min Inaktivität / 12 h ab. Befehle werden ohne Shell ausgeführt, Eingaben per Allowlist/Regex geprüft. Der Immich-API-Schlüssel und Kopia-Passwörter verlassen den Server nicht.
- Der Agent läuft als root – deshalb nur im LAN/WireGuard lauschen lassen.

## Entwicklung

- `agent/` – Go 1.24, nur Standardbibliothek: `cd agent && go build .`
- `client/` – Kotlin Multiplatform + Compose Multiplatform (Android + Desktop): `cd client && ./gradlew :composeApp:run` bzw. `:composeApp:assembleRelease`
- CI: `.github/workflows/build.yml` baut Agent, APK, .deb und .msi; ein Tag `v*` erzeugt ein Release.
- Signierung: Ohne Secrets wird `client/composeApp/signing/servercontrol.keystore` verwendet (privates Repo). Für einen eigenen Schlüssel die Secrets `SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD` setzen (Achtung: Schlüsselwechsel erfordert Neuinstallation).
