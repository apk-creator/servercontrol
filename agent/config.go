package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"time"
)

// KopiaRepo describes one Kopia repository the agent can control.
// Each repository uses its own Kopia config file, so any number of
// repositories can be managed side by side.
type KopiaRepo struct {
	ID           string   `json:"id"`
	Name         string   `json:"name"`
	ConfigFile   string   `json:"configFile"`
	PasswordFile string   `json:"passwordFile,omitempty"` // optional, file containing the repo password (0600)
	Sources      []string `json:"sources,omitempty"`      // optional, default snapshot sources
}

type KopiaConfig struct {
	Binary string      `json:"binary"`
	Repos  []KopiaRepo `json:"repos"`
}

type NextcloudConfig struct {
	Enabled bool     `json:"enabled"`
	OCC     []string `json:"occ"` // full command prefix, e.g. runuser -u www-data -- php /var/www/nextcloud/occ
}

type ImmichConfig struct {
	Enabled    bool   `json:"enabled"`
	URL        string `json:"url"`
	APIKeyFile string `json:"apiKeyFile"`
}

type UpdateConfig struct {
	Repo      string `json:"repo"`      // GitHub owner/repo with releases
	TokenFile string `json:"tokenFile"` // optional token for private repos (read-only)
}

type Config struct {
	Listen             []string        `json:"listen"`
	TLSCert            string          `json:"tlsCert"`
	TLSKey             string          `json:"tlsKey"`
	User               string          `json:"user"`
	PasswordHash       string          `json:"passwordHash"`
	SessionIdleMinutes int             `json:"sessionIdleMinutes"`
	SessionMaxHours    int             `json:"sessionMaxHours"`
	AllowShell         bool            `json:"allowShell"`
	Kopia              KopiaConfig     `json:"kopia"`
	Nextcloud          NextcloudConfig `json:"nextcloud"`
	Immich             ImmichConfig    `json:"immich"`
	Update             UpdateConfig    `json:"update"`
	Devices            []Device        `json:"devices,omitempty"`
}

// Device is an app installation that stays signed in (refresh token, stored only as SHA-256).
type Device struct {
	ID        string    `json:"id"`
	Name      string    `json:"name"`
	TokenHash string    `json:"tokenHash"`
	Created   time.Time `json:"created"`
	LastUsed  time.Time `json:"lastUsed"`
}

type ConfigStore struct {
	mu   sync.RWMutex
	path string
	cfg  Config
}

func defaultConfig(dir string) Config {
	return Config{
		Listen:             []string{":8443"},
		TLSCert:            filepath.Join(dir, "tls.crt"),
		TLSKey:             filepath.Join(dir, "tls.key"),
		User:               "admin",
		SessionIdleMinutes: 30,
		SessionMaxHours:    12,
		Kopia:              KopiaConfig{Binary: "kopia", Repos: []KopiaRepo{}},
		Nextcloud: NextcloudConfig{
			Enabled: false,
			OCC:     []string{"runuser", "-u", "www-data", "--", "php", "/var/www/nextcloud/occ"},
		},
		Immich: ImmichConfig{Enabled: false, URL: "http://127.0.0.1:2283", APIKeyFile: filepath.Join(dir, "immich.key")},
		Update: UpdateConfig{Repo: "apk-creator/servercontrol", TokenFile: filepath.Join(dir, "github.token")},
	}
}

func LoadConfig(path string) (*ConfigStore, error) {
	b, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	var c Config
	if err := json.Unmarshal(b, &c); err != nil {
		return nil, fmt.Errorf("config %s: %w", path, err)
	}
	if c.SessionIdleMinutes <= 0 {
		c.SessionIdleMinutes = 30
	}
	if c.SessionMaxHours <= 0 {
		c.SessionMaxHours = 12
	}
	if c.Kopia.Binary == "" {
		c.Kopia.Binary = "kopia"
	}
	dir := filepath.Dir(path)
	if c.Immich.APIKeyFile == "" {
		c.Immich.APIKeyFile = filepath.Join(dir, "immich.key")
	}
	if c.Update.Repo == "" {
		c.Update.Repo = "apk-creator/servercontrol"
	}
	if c.Update.TokenFile == "" {
		c.Update.TokenFile = filepath.Join(dir, "github.token")
	}
	if len(c.Nextcloud.OCC) == 0 {
		c.Nextcloud.OCC = defaultConfig(dir).Nextcloud.OCC
	}
	if c.PasswordHash == "" {
		return nil, errors.New("no password set – run: servercontrol-agent -passwd")
	}
	return &ConfigStore{path: path, cfg: c}, nil
}

// Dir is the directory of the config file (/etc/servercontrol).
func (s *ConfigStore) Dir() string { return filepath.Dir(s.path) }

func (s *ConfigStore) Get() Config {
	s.mu.RLock()
	defer s.mu.RUnlock()
	c := s.cfg
	c.Kopia.Repos = append([]KopiaRepo(nil), s.cfg.Kopia.Repos...)
	c.Nextcloud.OCC = append([]string(nil), s.cfg.Nextcloud.OCC...)
	c.Devices = append([]Device(nil), s.cfg.Devices...)
	return c
}

// Update applies fn to the config and persists it atomically.
func (s *ConfigStore) Update(fn func(*Config) error) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	c := s.cfg
	c.Kopia.Repos = append([]KopiaRepo(nil), s.cfg.Kopia.Repos...)
	c.Nextcloud.OCC = append([]string(nil), s.cfg.Nextcloud.OCC...)
	c.Devices = append([]Device(nil), s.cfg.Devices...)
	if err := fn(&c); err != nil {
		return err
	}
	if err := writeConfig(s.path, c); err != nil {
		return err
	}
	s.cfg = c
	return nil
}

func writeConfig(path string, c Config) error {
	b, err := json.MarshalIndent(c, "", "  ")
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return err
	}
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, append(b, '\n'), 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}

// writeSecret stores a secret (API key, password, token) in a root-only file.
func writeSecret(path, value string) error {
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return err
	}
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, []byte(value+"\n"), 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}
