// ServerControl agent – small REST service for one Debian 13 server.
// Runs as root under systemd, TLS only, password + session token auth.
package main

import (
	"bufio"
	"crypto/tls"
	"encoding/json"
	"flag"
	"fmt"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

// set at build time: -ldflags "-X main.version=1.2.3"
var version = "0.0.0-dev"

type Server struct {
	cfg  *ConfigStore
	auth *Auth
	jobs *Jobs
}

func main() {
	cfgPath := flag.String("config", "/etc/servercontrol/agent.json", "config file")
	initCfg := flag.Bool("init", false, "create a default config, set the password and generate a TLS certificate")
	passwd := flag.Bool("passwd", false, "set a new login password")
	fp := flag.Bool("fingerprint", false, "print the TLS certificate fingerprint (to compare in the app)")
	showVer := flag.Bool("version", false, "print version")
	flag.Parse()

	switch {
	case *showVer:
		fmt.Println(version)
		return
	case *initCfg:
		must(doInit(*cfgPath))
		return
	case *passwd:
		must(doPasswd(*cfgPath))
		return
	case *fp:
		c, err := LoadConfig(*cfgPath)
		must(err)
		f, err := certFingerprint(c.Get().TLSCert)
		must(err)
		fmt.Println(f)
		return
	}

	store, err := LoadConfig(*cfgPath)
	must(err)
	cfg := store.Get()
	must(ensureCert(cfg.TLSCert, cfg.TLSKey))
	fpr, _ := certFingerprint(cfg.TLSCert)
	logf("ServerControl agent %s – certificate fingerprint %s", version, fpr)

	s := &Server{cfg: store, auth: NewAuth(store), jobs: NewJobs()}
	metrics.Start()
	handler := securityHeaders(s.routes())

	cert, err := tls.LoadX509KeyPair(cfg.TLSCert, cfg.TLSKey)
	must(err)
	tlsCfg := &tls.Config{MinVersion: tls.VersionTLS12, Certificates: []tls.Certificate{cert}}

	var wg sync.WaitGroup
	for _, addr := range cfg.Listen {
		srv := &http.Server{
			Addr: addr, Handler: handler, TLSConfig: tlsCfg,
			ReadHeaderTimeout: 10 * time.Second, IdleTimeout: 120 * time.Second,
		}
		wg.Add(1)
		go func() {
			defer wg.Done()
			logf("listening on https://%s", addr)
			if err := srv.ListenAndServeTLS("", ""); err != nil {
				logf("listener %s: %v", addr, err)
			}
		}()
	}
	wg.Wait()
	os.Exit(1)
}

func (s *Server) routes() http.Handler {
	pub := http.NewServeMux()
	priv := http.NewServeMux()

	pub.HandleFunc("GET /api/ping", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, 200, map[string]string{"app": "servercontrol-agent", "version": version})
	})
	pub.HandleFunc("POST /api/login", func(w http.ResponseWriter, r *http.Request) {
		var req struct {
			User     string `json:"user"`
			Password string `json:"password"`
			Device   string `json:"device"` // optional: remember this app installation
		}
		if err := readJSON(r, &req); err != nil {
			writeErr(w, 400, "invalid request")
			return
		}
		tok, err := s.auth.Login(r, req.User, req.Password)
		if err != nil {
			logf("login failed from %s: %v", clientIP(r), err)
			writeErr(w, 401, err.Error())
			return
		}
		logf("login ok from %s", clientIP(r))
		var devID, refresh string
		if strings.TrimSpace(req.Device) != "" {
			if devID, refresh, err = s.registerDevice(req.Device); err != nil {
				logf("device registration failed: %v", err)
			} else {
				s.auth.bindDevice(tok, devID)
			}
		}
		writeJSON(w, 200, s.loginResponse(tok, devID, refresh))
	})
	pub.HandleFunc("POST /api/refresh", s.handleRefresh)

	priv.HandleFunc("POST /api/logout", func(w http.ResponseWriter, r *http.Request) {
		// explicit sign-out also forgets the device (next login needs the password again)
		if d := s.auth.sessionDevice(r); d != "" && r.URL.Query().Get("forget") == "1" {
			s.revokeDevice(d)
		}
		s.auth.Logout(bearer(r))
		writeJSON(w, 200, map[string]bool{"ok": true})
	})
	// system (Cockpit-like)
	priv.HandleFunc("GET /api/system/overview", handleOverview)
	priv.HandleFunc("GET /api/system/services", handleServices)
	priv.HandleFunc("GET /api/system/services/{unit}", handleServiceStatus)
	priv.HandleFunc("POST /api/system/services/{unit}/{action}", handleServiceAction)
	priv.HandleFunc("GET /api/system/logs", handleLogs)
	priv.HandleFunc("GET /api/system/storage", handleStorage)
	priv.HandleFunc("GET /api/system/smart", handleSmart)
	priv.HandleFunc("POST /api/system/smart/test", handleSmartTest)
	priv.HandleFunc("GET /api/system/network", handleNetwork)
	priv.HandleFunc("GET /api/system/processes", handleProcesses)
	priv.HandleFunc("POST /api/system/processes/kill", handleKill)
	priv.HandleFunc("GET /api/system/accounts", handleAccounts)
	priv.HandleFunc("GET /api/system/updates", s.handleUpdatesList)
	priv.HandleFunc("POST /api/system/updates/refresh", s.handleUpdatesRefresh)
	priv.HandleFunc("POST /api/system/updates/upgrade", s.handleUpdatesUpgrade)
	priv.HandleFunc("POST /api/system/power/{action}", handlePower)
	priv.HandleFunc("GET /api/system/history", handleHistory)
	priv.HandleFunc("GET /api/files/list", handleFilesList)
	priv.HandleFunc("GET /api/files/download", handleFilesDownload)
	priv.HandleFunc("POST /api/files/upload", handleFilesUpload)
	priv.HandleFunc("POST /api/files/mkdir", handleFilesMkdir)
	priv.HandleFunc("POST /api/files/rename", handleFilesRename)
	priv.HandleFunc("POST /api/files/delete", handleFilesDelete)
	priv.HandleFunc("POST /api/system/shell", s.handleShell)
	priv.HandleFunc("GET /api/containers", handleContainers)
	priv.HandleFunc("GET /api/containers/{name}/logs", handleContainerLogs)
	priv.HandleFunc("POST /api/containers/{name}/{action}", handleContainerAction)
	// remembered devices
	priv.HandleFunc("GET /api/devices", s.handleDevices)
	priv.HandleFunc("POST /api/devices/{id}/revoke", s.handleDeviceRevoke)
	// jobs
	priv.HandleFunc("GET /api/jobs", s.jobs.handleList)
	priv.HandleFunc("GET /api/jobs/{id}", s.jobs.handleGet)
	// kopia
	priv.HandleFunc("GET /api/kopia/repos", s.handleKopiaRepos)
	priv.HandleFunc("POST /api/kopia/repos", s.handleKopiaRepoSave)
	priv.HandleFunc("DELETE /api/kopia/repos/{repo}", s.handleKopiaRepoDelete)
	priv.HandleFunc("GET /api/kopia/repos/{repo}/status", s.handleKopiaStatus)
	priv.HandleFunc("GET /api/kopia/repos/{repo}/snapshots", s.handleKopiaSnapshots)
	priv.HandleFunc("GET /api/kopia/repos/{repo}/policies", s.handleKopiaPolicies)
	priv.HandleFunc("/api/kopia/repos/{repo}/srv/{path...}", s.handleKopiaProxy)
	priv.HandleFunc("GET /api/kopia/repos/{repo}/schedule", s.handleKopiaSchedule)
	priv.HandleFunc("POST /api/kopia/repos/{repo}/schedule", s.handleKopiaScheduleSet)
	priv.HandleFunc("POST /api/kopia/repos/{repo}/maintenance-schedule", s.handleKopiaMaintSet)
	priv.HandleFunc("POST /api/kopia/repos/{repo}/{action}", s.handleKopiaAction)
	// nextcloud
	priv.HandleFunc("GET /api/nextcloud/status", s.handleNcStatus)
	priv.HandleFunc("GET /api/nextcloud/apps", s.handleNcApps)
	priv.HandleFunc("GET /api/nextcloud/users", s.handleNcUsers)
	priv.HandleFunc("GET /api/nextcloud/setupchecks", s.handleNcSetupChecks)
	priv.HandleFunc("GET /api/nextcloud/updatecheck", s.handleNcUpdateCheck)
	priv.HandleFunc("GET /api/nextcloud/background", s.handleNcBackgroundMode)
	priv.HandleFunc("GET /api/nextcloud/maintenance-window", s.handleNcMaintWindow)
	priv.HandleFunc("PUT /api/nextcloud/maintenance-window", s.handleNcMaintWindowSet)
	priv.HandleFunc("POST /api/nextcloud/action/{action}", s.handleNcAction)
	priv.HandleFunc("GET /api/kopia/discover", s.handleKopiaDiscover)
	priv.HandleFunc("POST /api/kopia/connect", s.handleKopiaConnect)
	priv.HandleFunc("POST /api/kopia/repos/{repo}/password", s.handleKopiaPassword)
	// ssh authorized_keys
	priv.HandleFunc("GET /api/ssh/keys", handleSSHKeysList)
	priv.HandleFunc("POST /api/ssh/keys", handleSSHKeyAdd)
	priv.HandleFunc("POST /api/ssh/keys/remove", handleSSHKeyRemove)
	priv.HandleFunc("POST /api/ssh/password-auth", handleSSHPasswordAuth)
	priv.HandleFunc("GET /api/agent/release/asset", s.handleReleaseAsset)
	// configuration from the app
	priv.HandleFunc("GET /api/config", s.handleConfigGet)
	priv.HandleFunc("POST /api/config/nextcloud/test", s.handleNcConfigTest)
	priv.HandleFunc("PUT /api/config/nextcloud", s.handleNcConfigSave)
	priv.HandleFunc("POST /api/config/immich/test", s.handleImmichConfigTest)
	priv.HandleFunc("PUT /api/config/immich", s.handleImmichConfigSave)
	priv.HandleFunc("PUT /api/config/update", s.handleUpdateConfigSave)
	// self-update
	priv.HandleFunc("GET /api/agent/update/check", s.handleUpdateCheck)
	priv.HandleFunc("POST /api/agent/update/install", s.handleUpdateInstall)
	priv.HandleFunc("GET /api/agent/update/status", s.handleUpdateStatus)
	priv.HandleFunc("POST /api/agent/update/upload", s.handleUpdateUpload)
	priv.HandleFunc("GET /api/immich-compose", s.handleImmichCompose)
	priv.HandleFunc("POST /api/immich-compose/update", s.handleImmichComposeUpdate)
	// immich (allowlisted proxy)
	priv.HandleFunc("/api/immich/{path...}", s.handleImmich)

	pub.Handle("/api/", s.auth.Middleware(priv))
	return pub
}

func securityHeaders(h http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("Strict-Transport-Security", "max-age=31536000")
		defer func() {
			if rec := recover(); rec != nil {
				logf("panic: %v", rec)
				writeErr(w, 500, "internal error")
			}
		}()
		h.ServeHTTP(w, r)
	})
}

// ---------- CLI helpers ----------

var stdin = bufio.NewReader(os.Stdin)

func readSecret(prompt string) (string, error) {
	fmt.Fprint(os.Stderr, prompt)
	stty := func(arg string) {
		c := exec.Command("stty", arg)
		c.Stdin = os.Stdin
		_ = c.Run()
	}
	stty("-echo")
	defer func() { stty("echo"); fmt.Fprintln(os.Stderr) }()
	line, err := stdin.ReadString('\n')
	return strings.TrimRight(line, "\r\n"), err
}

func askPassword() (string, error) {
	p1, err := readSecret("Neues Passwort (min. 12 Zeichen): ")
	if err != nil {
		return "", err
	}
	if len(p1) < 12 {
		return "", fmt.Errorf("Passwort zu kurz")
	}
	p2, err := readSecret("Wiederholen: ")
	if err != nil {
		return "", err
	}
	if p1 != p2 {
		return "", fmt.Errorf("Passwörter stimmen nicht überein")
	}
	return HashPassword(p1)
}

func doInit(path string) error {
	if _, err := os.Stat(path); err == nil {
		return fmt.Errorf("%s existiert bereits (Passwort ändern: -passwd)", path)
	}
	c := defaultConfig(filepath.Dir(path))
	h, err := askPassword()
	if err != nil {
		return err
	}
	c.PasswordHash = h
	if err := writeConfig(path, c); err != nil {
		return err
	}
	if err := ensureCert(c.TLSCert, c.TLSKey); err != nil {
		return err
	}
	f, _ := certFingerprint(c.TLSCert)
	fmt.Printf("Konfiguration angelegt: %s\nBenutzer: %s\nZertifikat-Fingerprint (in der App vergleichen):\n%s\n", path, c.User, f)
	return nil
}

func doPasswd(path string) error {
	b, err := os.ReadFile(path)
	if err != nil {
		return err
	}
	var c Config
	if err := json.Unmarshal(b, &c); err != nil {
		return err
	}
	h, err := askPassword()
	if err != nil {
		return err
	}
	c.PasswordHash = h
	c.Devices = nil // all remembered devices must sign in again
	if err := writeConfig(path, c); err != nil {
		return err
	}
	fmt.Println("Passwort gesetzt, alle angemeldeten Geräte abgemeldet. Agent neu starten: systemctl restart servercontrol-agent")
	return nil
}

func must(err error) {
	if err != nil {
		fmt.Fprintln(os.Stderr, "Fehler:", err)
		os.Exit(1)
	}
}
