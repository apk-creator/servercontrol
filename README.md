# ServerControl

Verwaltung **eines** Debian-13-Servers vom Android-Gerät und vom Desktop (Linux/Windows):

- Cockpit-Funktionen: Übersicht (CPU, RAM, Temperaturen, Last), Dienste (systemd), Container (Docker/Podman), Protokolle (journald), Speicher + SMART, Netzwerk, Prozesse, Updates (apt), Konten, Neustart/Herunterfahren, optional Root-Befehle
- **Kopia** – beliebig viele Repositories (je eine Kopia-Konfigurationsdatei): Status, Snapshots je Quelle, Snapshot jetzt, Wartung, Verifizieren, Wiederherstellen, Löschen
- **Nextcloud** – Status, Wartungsmodus, Einrichtungsprüfung, Benutzer (anlegen, sperren, Kontingent, Passwort), Apps, DB-Reparaturen, Dateiscan, Hintergrundjob-Modus
- **Immich** – Version/Speicher/Statistik, Aufträge (starten, pausieren, leeren), Benutzer, externe Bibliotheken
- **SSH-Terminal** (xterm-kompatibel: bash, nano, htop, less, vim, mc), Hostschlüssel gepinnt, Anmeldung per Passwort oder privatem Schlüssel
- **Einrichtung aus der App**: Nextcloud (occ), Immich (URL + API-Schlüssel), Kopia (vorhandene Konfigurationen finden, Repositories verbinden oder neu anlegen)
- **Agent-Update aus der App**: neuestes GitHub-Release oder hochgeladene Datei

```
 App (Android / Desktop)  ──HTTPS, Zertifikat gepinnt──▶  servercontrol-agent (Go, root, systemd)
   · Adressen verschlüsselt                                 · systemctl, journalctl, apt, smartctl
   · keine Passwörter gespeichert                           · kopia --config-file=…
                                                            · occ (Nextcloud), Immich-API (Schlüssel bleibt auf dem Server)
```

## 1. Agent auf dem Server installieren

Artefakt `servercontrol-agent` aus dem letzten GitHub-Actions-Lauf (oder Release) laden, auf den Server kopieren:

```sh
mkdir sc && tar -C sc -xzf servercontrol-agent.tar.gz && cd sc
sudo sh install.sh servercontrol-agent-linux-amd64
```

Der Installer legt `/etc/servercontrol/agent.json` an, fragt das Login-Passwort ab (min. 12 Zeichen), erzeugt ein TLS-Zertifikat und zeigt dessen **Fingerprint** an. Den brauchst du bei der ersten Verbindung in der App.

Danach `/etc/servercontrol/agent.json` anpassen (Vorlage: `deploy/agent.example.json`) und `systemctl restart servercontrol-agent`.

| Schlüssel | Bedeutung |
|---|---|
| `listen` | Adresse(n), z. B. `192.168.1.10:8443` oder `:8443`. **Nicht** ins Internet freigeben; Port 8443 in nftables nur fürs LAN öffnen. |
| `kopia.repos[]` | `id`, `name`, `configFile` (eigene Kopia-Config je Repo), optional `passwordFile` (chmod 600), `sources` |
| `nextcloud.occ` | Befehl für occ, z. B. `["runuser","-u","www-data","--","php","/var/www/nextcloud/occ"]` (Docker: `["docker","exec","-u","www-data","nextcloud","php","occ"]`) |
| `immich.url` / `apiKeyFile` | Immich-URL vom Server aus + Datei mit Admin-API-Schlüssel (Rechte: server.*, job.*, adminUser.*, library.*) |
| `allowShell` | `true` schaltet den Bereich „Befehl“ frei (Root-Shell!) – standardmäßig aus |

Weitere Befehle: `servercontrol-agent -passwd` (Passwort ändern), `-fingerprint` (Fingerprint anzeigen).

Nextcloud, Immich und Kopia lassen sich komplett in der App einrichten (Bereiche *Nextcloud*, *Immich*, *Kopia*) – die Konfigurationsdatei musst du dafür nicht anfassen. Geheimnisse (Immich-API-Schlüssel, Kopia-Passwörter, GitHub-Token) landen nur auf dem Server in `/etc/servercontrol/` (chmod 600).

### Agent aktualisieren

*Agent → Nach Updates suchen → Jetzt installieren.* Der Agent lädt `servercontrol-agent.tar.gz` aus dem neuesten Release, prüft die neue Datei, ersetzt sich selbst (alte Version bleibt als `.bak`) und startet neu. Für dieses private Repository braucht der Server dafür ein GitHub-Token: *github.com → Settings → Developer settings → Fine-grained tokens*, nur Repository `servercontrol`, Berechtigung **Contents: Read-only**. Alternativ *Datei hochladen …* mit dem Archiv aus dem Release.

**App aktualisieren:** auf derselben Seite unter *App aktualisieren*. Die App lädt ihr Paket (APK, MSI bzw. DEB) über den Agent aus dem neuesten Release – sie braucht dafür kein eigenes Token. Android fragt beim ersten Mal, ob ServerControl Apps installieren darf; unter Windows startet der Installer und die App beendet sich.

Die allererste Umstellung von 1.0.0 auf eine Version mit Update-Funktion geht noch manuell: neues Archiv entpacken und `sudo sh install.sh servercontrol-agent-linux-amd64` erneut ausführen (die Konfiguration bleibt erhalten).

## 2. App installieren

- **Android**: `ServerControl.apk` aus dem Actions-Artefakt installieren. Alle Builds sind mit demselben Schlüssel signiert, Updates lassen sich also drüberinstallieren.
- **Linux**: `.deb` installieren (`sudo apt install ./serverControl_*.deb`)
- **Windows**: `.msi` installieren

## 3. Server in der App anlegen

- **Adresse des Agenten**: z. B. `https://192.168.1.10:8443`. Von unterwegs vorher das VPN ins Heimnetz verbinden.

Bei der ersten Verbindung zeigt die App den Zertifikat-Fingerprint – mit `servercontrol-agent -fingerprint` vergleichen und bestätigen. Danach ist das Zertifikat gepinnt; ein geändertes Zertifikat führt zum Abbruch.

## 4. SSH-Terminal

Bereich *Terminal*: Host ist die Adresse des Agenten, Port und Benutzer werden im Profil gemerkt, das Passwort nie. Beim ersten Verbinden den Hostschlüssel mit `ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub` vergleichen. Optional privaten Schlüssel einfügen (wird verschlüsselt im Profil gespeichert, das Passwortfeld dient dann als Passphrase).

- **Android:** Das Terminal ist die Terminal-Komponente von [ConnectBot](https://github.com/connectbot/termlib) (libvterm): lange drücken markiert, Griffe mit Lupe, Menü mit Kopieren/Einfügen/Alles auswählen, Zwei-Finger-Zoom für die Schrift. Tippen in die Eingabezeile setzt den Cursor.
- **Desktop:** eigener Emulator; mit der Maus markieren, Strg+Umschalt+C / Strg+Umschalt+V.
- **Einfügen:** mehrzeiliger Text landet komplett in der Eingabezeile und wird erst mit Enter ausgeführt.
- **Werkzeugleiste:** Einfügen, Kopieren (Auswahl, ohne Auswahl der sichtbare Bildschirm), Leeren (Mülleimer: Verlauf weg, Shell zeichnet die Eingabezeile oben neu), Trennen.
- Sobald etwas eingetippt wird, springt die aktuelle Eingabezeile ganz nach oben (nicht in Vollbild-Programmen wie nano/htop).
- **Hintergrund (Android):** solange ein Terminal verbunden ist, hält ein Dienst die Verbindung offen; in der Benachrichtigungsleiste steht „Verbunden“ mit `>_`. *Trennen* direkt in der Benachrichtigung.
- Das Seitenmenü lässt sich umsortieren: Eintrag lange drücken und an die gewünschte Stelle ziehen.
- Unter der Ausgabe eine Leiste mit Strg, Alt, Esc, Tab, Pfeiltasten usw.; Schriftgröße, Einfügen und Kopieren oben rechts.

## 5. SSH-Schlüssel

Bereich *SSH-Schlüssel* (Erzeugung wie bei ConnectBot, Ed25519 empfohlen, alternativ ECDSA oder RSA):

1. *Schlüssel erzeugen* – der private Schlüssel bleibt verschlüsselt in der App, das Terminal meldet sich danach automatisch damit an.
2. *Auf dem Server eintragen* – der Agent schreibt den öffentlichen Schlüssel in `~<benutzer>/.ssh/authorized_keys` (richtige Rechte, vorherige Version als `authorized_keys.servercontrol-backup`).
3. Unter *Eingetragene Schlüssel* lassen sich Schlüssel anderer Geräte (z. B. PC) hinzufügen oder austragen.

Für jedes Gerät einen eigenen Schlüssel verwenden. Das Terminal der App meldet sich nur noch per Schlüssel an. Wenn sich alle Geräte per Schlüssel anmelden, lässt sich unter *Eingetragene Schlüssel* die Passwort-Anmeldung per SSH für den ganzen Server abschalten (Datei `/etc/ssh/sshd_config.d/00-servercontrol.conf`, wird vor dem Neuladen mit `sshd -t` geprüft) und bei Bedarf wieder einschalten.

## 6. Passwörter

Die App speichert keine Passwörter. Auf Android sind alle Benutzer- und Passwortfelder native Autofill-Felder; am Desktop springt Tab ins nächste Feld und Enter meldet an. Passwortfelder sind verdeckt und lassen sich mit dem Augen-Symbol einblenden. Das Master-Passwort der Desktop-App verschlüsselt die gespeicherten Profile.

## Sicherheit

- Profile (Adressen, Fingerprints, SSH-Daten) liegen verschlüsselt: Android mit AES-256-GCM-Schlüssel im Android Keystore, Desktop mit AES-256-GCM aus dem Master-Passwort (PBKDF2-SHA256, 310 000 Iterationen). Android: keine Backups, keine Screenshots (FLAG_SECURE).
- Agent: nur TLS, Zertifikat in der App gepinnt, Passwort als PBKDF2-SHA256 (600 000 Iterationen), Sperre nach 5 Fehlversuchen für 15 min, Sitzungen laufen nach 30 min Inaktivität / 12 h ab. Befehle werden ohne Shell ausgeführt, Eingaben per Allowlist/Regex geprüft. Der Immich-API-Schlüssel und Kopia-Passwörter verlassen den Server nicht.
- Der Agent läuft als root – deshalb nur im LAN erreichbar machen.

## Entwicklung

- `agent/` – Go 1.24, nur Standardbibliothek: `cd agent && go build .`
- `client/` – Kotlin Multiplatform + Compose Multiplatform (Android + Desktop); Android-Terminal: `org.connectbot:termlib` (Apache-2.0): `cd client && ./gradlew :composeApp:run` bzw. `:composeApp:assembleRelease`
- CI: `.github/workflows/build.yml` baut Agent, APK, .deb und .msi. **Release automatisch:** Wird auf `main` die Version in `client/composeApp/build.gradle.kts` (`appVersion`) erhöht, legt die CI nach erfolgreichem Build Tag `v<version>` und das Release mit allen Dateien selbst an. (Ein manuell erstelltes Tag `v*` funktioniert weiterhin.)
- Signierung: Ohne Secrets wird `client/composeApp/signing/servercontrol.keystore` verwendet (privates Repo). Für einen eigenen Schlüssel die Secrets `SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD` setzen (Achtung: Schlüsselwechsel erfordert Neuinstallation).
