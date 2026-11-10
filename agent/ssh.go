package main

import (
	"bufio"
	"bytes"
	"crypto/sha256"
	"encoding/base64"
	"encoding/binary"
	"errors"
	"net/http"
	"os"
	"os/user"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"time"
)

// Management of ~/.ssh/authorized_keys for a local user (public keys only).

var keyTypeRe = regexp.MustCompile(`^(ssh-ed25519|ssh-rsa|ecdsa-sha2-nistp(256|384|521)|sk-ssh-ed25519@openssh\.com|sk-ecdsa-sha2-nistp256@openssh\.com)$`)
var sshUserRe = regexp.MustCompile(`^[a-z_][a-z0-9_.-]{0,31}$`)

type authKey struct {
	Type        string `json:"type"`
	Comment     string `json:"comment"`
	Fingerprint string `json:"fingerprint"`
	Options     string `json:"options,omitempty"`
}

// parseKeyLine finds "<type> <base64> [comment]" in an authorized_keys line (options may precede it).
func parseKeyLine(line string) (authKey, error) {
	f := strings.Fields(line)
	for i, tok := range f {
		if !keyTypeRe.MatchString(tok) || i+1 >= len(f) {
			continue
		}
		blob, err := base64.StdEncoding.DecodeString(f[i+1])
		if err != nil || len(blob) < 4 {
			return authKey{}, errors.New("Schlüssel ist kein gültiges Base64")
		}
		n := binary.BigEndian.Uint32(blob[:4])
		if int(n)+4 > len(blob) || string(blob[4:4+n]) != tok {
			return authKey{}, errors.New("Schlüsseltyp passt nicht zum Inhalt")
		}
		sum := sha256.Sum256(blob)
		return authKey{
			Type:        tok,
			Comment:     strings.Join(f[i+2:], " "),
			Fingerprint: "SHA256:" + base64.RawStdEncoding.EncodeToString(sum[:]),
			Options:     strings.Join(f[:i], " "),
		}, nil
	}
	return authKey{}, errors.New("kein OpenSSH-Schlüssel erkannt (z. B. „ssh-ed25519 AAAA… kommentar“)")
}

func lookupUser(name string) (*user.User, error) {
	if !sshUserRe.MatchString(name) {
		return nil, errors.New("ungültiger Benutzername")
	}
	return user.Lookup(name)
}

func authKeysPath(u *user.User) string { return filepath.Join(u.HomeDir, ".ssh", "authorized_keys") }

func readAuthKeys(u *user.User) ([]string, error) {
	b, err := os.ReadFile(authKeysPath(u))
	if errors.Is(err, os.ErrNotExist) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	var lines []string
	sc := bufio.NewScanner(bytes.NewReader(b))
	sc.Buffer(make([]byte, 64*1024), 1<<20)
	for sc.Scan() {
		lines = append(lines, sc.Text())
	}
	return lines, sc.Err()
}

// writeAuthKeys writes the file atomically with the ownership and permissions sshd expects.
func writeAuthKeys(u *user.User, lines []string) error {
	uid, _ := strconv.Atoi(u.Uid)
	gid, _ := strconv.Atoi(u.Gid)
	dir := filepath.Join(u.HomeDir, ".ssh")
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return err
	}
	_ = os.Chown(dir, uid, gid)
	_ = os.Chmod(dir, 0o700)
	path := authKeysPath(u)
	if _, err := os.Stat(path); err == nil {
		backup := path + ".servercontrol-backup" // previous version
		if old, err := os.ReadFile(path); err == nil {
			_ = os.WriteFile(backup, old, 0o600)
			_ = os.Chown(backup, uid, gid)
		}
	}
	content := strings.Join(lines, "\n")
	if content != "" && !strings.HasSuffix(content, "\n") {
		content += "\n"
	}
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, []byte(content), 0o600); err != nil {
		return err
	}
	_ = os.Chown(tmp, uid, gid)
	return os.Rename(tmp, path)
}

func passwordAuthStatus(r *http.Request) string {
	res := run(r.Context(), 10*time.Second, nil, "sshd", "-T")
	for _, l := range strings.Split(res.Stdout, "\n") {
		if strings.HasPrefix(l, "passwordauthentication ") {
			return strings.TrimPrefix(l, "passwordauthentication ")
		}
	}
	return ""
}

// GET /api/ssh/keys?user=NAME
func handleSSHKeysList(w http.ResponseWriter, r *http.Request) {
	u, err := lookupUser(r.URL.Query().Get("user"))
	if err != nil {
		writeErr(w, 404, "Benutzer nicht gefunden")
		return
	}
	lines, err := readAuthKeys(u)
	if err != nil {
		writeErr(w, 500, err.Error())
		return
	}
	keys := []authKey{}
	for _, l := range lines {
		t := strings.TrimSpace(l)
		if t == "" || strings.HasPrefix(t, "#") {
			continue
		}
		if k, err := parseKeyLine(t); err == nil {
			keys = append(keys, k)
		}
	}
	writeJSON(w, 200, map[string]any{"user": u.Username, "file": authKeysPath(u), "keys": keys, "passwordAuthentication": passwordAuthStatus(r)})
}

// POST /api/ssh/keys {user, key}
func handleSSHKeyAdd(w http.ResponseWriter, r *http.Request) {
	var req struct {
		User string `json:"user"`
		Key  string `json:"key"`
	}
	if err := readJSON(r, &req); err != nil {
		writeErr(w, 400, err.Error())
		return
	}
	line := strings.TrimSpace(strings.ReplaceAll(req.Key, "\n", " "))
	k, err := parseKeyLine(line)
	if err != nil {
		writeErr(w, 400, err.Error())
		return
	}
	if k.Options != "" {
		writeErr(w, 400, "Optionen vor dem Schlüssel werden nicht übernommen – bitte nur „typ schlüssel kommentar“")
		return
	}
	u, err := lookupUser(req.User)
	if err != nil {
		writeErr(w, 404, "Benutzer nicht gefunden")
		return
	}
	lines, err := readAuthKeys(u)
	if err != nil {
		writeErr(w, 500, err.Error())
		return
	}
	for _, l := range lines {
		if e, err := parseKeyLine(l); err == nil && e.Fingerprint == k.Fingerprint {
			writeJSON(w, 200, map[string]any{"ok": true, "added": false, "message": "Schlüssel ist bereits eingetragen", "key": k})
			return
		}
	}
	if err := writeAuthKeys(u, append(lines, line)); err != nil {
		writeErr(w, 500, err.Error())
		return
	}
	logf("ssh key added for %s: %s %s", u.Username, k.Type, k.Fingerprint)
	writeJSON(w, 200, map[string]any{"ok": true, "added": true, "message": "Schlüssel für " + u.Username + " eingetragen", "key": k})
}

// POST /api/ssh/keys/remove {user, fingerprint}
func handleSSHKeyRemove(w http.ResponseWriter, r *http.Request) {
	var req struct {
		User        string `json:"user"`
		Fingerprint string `json:"fingerprint"`
	}
	if err := readJSON(r, &req); err != nil || !strings.HasPrefix(req.Fingerprint, "SHA256:") {
		writeErr(w, 400, "ungültige Anfrage")
		return
	}
	u, err := lookupUser(req.User)
	if err != nil {
		writeErr(w, 404, "Benutzer nicht gefunden")
		return
	}
	lines, err := readAuthKeys(u)
	if err != nil {
		writeErr(w, 500, err.Error())
		return
	}
	kept := lines[:0:0]
	removed := 0
	for _, l := range lines {
		if e, err := parseKeyLine(l); err == nil && e.Fingerprint == req.Fingerprint {
			removed++
			continue
		}
		kept = append(kept, l)
	}
	if removed == 0 {
		writeErr(w, 404, "Schlüssel nicht gefunden")
		return
	}
	if err := writeAuthKeys(u, kept); err != nil {
		writeErr(w, 500, err.Error())
		return
	}
	logf("ssh key removed for %s: %s", u.Username, req.Fingerprint)
	writeJSON(w, 200, map[string]any{"ok": true, "message": "Schlüssel entfernt"})
}

// ---------------- password login for SSH ----------------

const sshdDropIn = "/etc/ssh/sshd_config.d/00-servercontrol.conf"

// POST /api/ssh/password-auth {enabled, user}
// Disabling is only allowed if the given user already has at least one key in authorized_keys.
// sshd uses the first value it reads; the drop-in is read before the main config (Debian default),
// "00-" makes it win against other drop-ins.
func handleSSHPasswordAuth(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Enabled bool   `json:"enabled"`
		User    string `json:"user"`
	}
	if err := readJSON(r, &req); err != nil {
		writeErr(w, 400, err.Error())
		return
	}
	if !req.Enabled {
		u, err := lookupUser(req.User)
		if err != nil {
			writeErr(w, 404, "Benutzer nicht gefunden")
			return
		}
		lines, _ := readAuthKeys(u)
		n := 0
		for _, l := range lines {
			if _, err := parseKeyLine(l); err == nil {
				n++
			}
		}
		if n == 0 {
			writeErr(w, 409, "Für "+u.Username+" ist noch kein Schlüssel eingetragen – Passwort-Anmeldung bleibt an")
			return
		}
	}
	old, oldErr := os.ReadFile(sshdDropIn)
	if req.Enabled {
		_ = os.Remove(sshdDropIn)
	} else {
		content := "# managed by ServerControl\nPasswordAuthentication no\nKbdInteractiveAuthentication no\n"
		if err := os.WriteFile(sshdDropIn, []byte(content), 0o644); err != nil {
			writeErr(w, 500, err.Error())
			return
		}
	}
	restore := func() {
		if oldErr == nil {
			_ = os.WriteFile(sshdDropIn, old, 0o644)
		} else {
			_ = os.Remove(sshdDropIn)
		}
	}
	if t := run(r.Context(), 15*time.Second, nil, "sshd", "-t"); t.ExitCode != 0 {
		restore()
		writeErr(w, 500, "sshd-Konfiguration ungültig, nichts geändert: "+strings.TrimSpace(t.Stderr))
		return
	}
	if rl := run(r.Context(), 30*time.Second, nil, "systemctl", "reload-or-restart", "ssh.service"); rl.ExitCode != 0 {
		restore()
		_ = run(r.Context(), 30*time.Second, nil, "systemctl", "reload-or-restart", "ssh.service")
		writeErr(w, 500, "SSH-Dienst konnte nicht neu geladen werden: "+strings.TrimSpace(rl.Stderr))
		return
	}
	now := passwordAuthStatus(r)
	logf("ssh password authentication set to %v (sshd now: %s)", req.Enabled, now)
	msg := "Passwort-Anmeldung per SSH ist jetzt " + map[bool]string{true: "an", false: "aus"}[now == "yes"] + "."
	if (now == "yes") != req.Enabled {
		msg += " Achtung: Eine andere Einstellung in /etc/ssh/sshd_config hat Vorrang – bitte prüfen."
	}
	writeJSON(w, 200, map[string]any{"ok": true, "passwordAuthentication": now, "message": msg})
}
