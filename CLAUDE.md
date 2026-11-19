# ServerControl – Hinweise für Claude

Verwaltung eines Debian-13-Servers. Zwei Teile:
- `agent/` – Go 1.24, **nur Standardbibliothek**, läuft als root (systemd) auf dem Server, HTTPS-API.
- `client/` – Kotlin Multiplatform + Compose Multiplatform (Android + Desktop Linux/Windows).

Antworten, Commit-Messages und UI-Texte auf **Deutsch**. Code-Kommentare auf Englisch (wie bestehend).

## Sparsam arbeiten (wichtig)
- **Nicht lesen**, außer die Aufgabe betrifft sie direkt: `client/termlib/` (gepatchte ConnectBot-Kopie, siehe `termlib/README.md`), `README.md` (Nutzerdoku), `client/gradle/`, Icons/Ressourcen.
- Große Dateien (> 500 Zeilen, z. B. `SystemScreens.kt`, `KopiaWebUi.kt`, `drives.go`, `kopia.go`) mit `grep -n` gezielt durchsuchen und nur den relevanten Bereich lesen.
- Build-/Testausgaben immer kürzen (Befehle unten). Keine vollständigen Gradle-Logs in den Kontext holen.
- Nicht auf GitHub-Actions-Läufe warten oder deren Logs abrufen; der Nutzer baut lokal und meldet Fehler mit den relevanten Zeilen.
- Vor größeren Änderungen kurz planen (welche Dateien, welche Endpunkte), dann umsetzen.

## Bauen und Testen
```sh
# Agent
cd agent && go vet ./... && go test ./... 2>&1 | tail -20
cd agent && go build . 2>&1 | head -20

# Client (Kompilieren prüfen, ohne Paket)
cd client && ./gradlew -q :composeApp:compileKotlinDesktop 2>&1 | grep -E '^e:|error' | head -30
cd client && ./gradlew -q :composeApp:compileReleaseKotlinAndroid 2>&1 | grep -E '^e:|error' | head -30
cd client && ./gradlew :composeApp:run            # Desktop starten
cd client && ./gradlew :composeApp:assembleRelease # APK (ohne Secrets: Debug-Schlüssel)
```
Nach Änderungen an `commonMain` beide Targets kompilieren (Desktop **und** Android).

## Architektur – wo was hingehört
- **Neuer Agent-Endpunkt:** Route in `agent/main.go` registrieren (`priv.HandleFunc("METHOD /api/<bereich>/...", handler)`; `pub` nur für Login/Ping), Handler in die passende Datei (`system.go`, `kopia.go`, `nextcloud.go`, `immich.go`, `drives.go`, …). Befehle **ohne Shell** über `run(...)` bzw. `Jobs.Start(...)` aus `util.go` ausführen (Antworten mit `writeJSON`/`writeErr`/`sendResult`), Eingaben per Allowlist/Regex prüfen (Muster: `regexp.MustCompile` als Paketvariable in der jeweiligen Datei). Test in `*_test.go` ergänzen, wenn Logik nicht trivial ist.
- **Client-Aufruf:** `net/ApiClient.kt` (`getJson`, `postRaw`, `putRaw`); Profile/Login-Datenklassen in `data/Models.kt`; Antworten meist als `JsonElement` mit Helfern aus `ui/JsonHelpers.kt`.
- **Navigation/Zustand:** `AppState.kt` (`Screen`, `Section`), Seitenmenü `SideMenu.kt`, Hilfetexte zum „?“ in `Help.kt`.
- **Bildschirme:** `ui/screens/<Bereich>Screen.kt`; gemeinsame Bausteine in `ui/Components.kt`, Diagramme `ui/Charts.kt`.
- **Plattformcode:** `expect/actual` über `Platform.kt` → `androidMain/…/AndroidPlatform.kt`, `desktopMain/…/DesktopPlatform.kt`. SSH: `SshJsch.kt`/`SshKeyGen.kt` je Plattform; Terminal Android = termlib, Desktop = eigener Emulator (`terminal/`).

## Regeln
- `appVersion` in `client/composeApp/build.gradle.kts` **nur erhöhen, wenn der Nutzer es verlangt** – ein Push auf `main` mit neuer Version erstellt automatisch Tag und Release.
- Sicherheit nicht aufweichen: Zertifikat-Pinning, keine gespeicherten Passwörter, Geheimnisse bleiben auf dem Server (`/etc/servercontrol/`, chmod 600), Agent nur im LAN.
- Keine neuen Go-Abhängigkeiten (nur Stdlib). Neue Gradle-Abhängigkeiten nur nach Rückfrage.
- Signier-Secrets nie in Dateien schreiben.
