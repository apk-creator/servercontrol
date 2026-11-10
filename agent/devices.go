package main

import (
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"errors"
	"net/http"
	"regexp"
	"sort"
	"strings"
	"time"
)

// Remembered devices: after one login with the password an app installation receives a
// long-lived refresh token. With it the app silently gets a new session whenever the old
// one expired (app in the background, agent restarted after an update). Only the SHA-256
// of the token is stored; devices can be revoked in the app; changing the password revokes all.

const (
	maxDevices       = 20
	deviceUnusedDays = 180
)

var deviceIDRe = regexp.MustCompile(`^[0-9a-f]{16}$`)

func randomHex(n int) (string, error) {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return hex.EncodeToString(b), nil
}

func hashToken(t string) string {
	sum := sha256.Sum256([]byte(t))
	return hex.EncodeToString(sum[:])
}

func cleanDeviceName(n string) string {
	n = strings.TrimSpace(strings.Map(func(r rune) rune {
		if r < 32 {
			return -1
		}
		return r
	}, n))
	if len([]rune(n)) > 60 {
		n = string([]rune(n)[:60])
	}
	if n == "" {
		n = "Gerät"
	}
	return n
}

// registerDevice creates a device entry and returns (id, refreshToken).
func (s *Server) registerDevice(name string) (string, string, error) {
	id, err := randomHex(8)
	if err != nil {
		return "", "", err
	}
	tok, err := randomHex(32)
	if err != nil {
		return "", "", err
	}
	now := time.Now()
	err = s.cfg.Update(func(c *Config) error {
		kept := c.Devices[:0]
		for _, d := range c.Devices {
			if now.Sub(d.LastUsed) < deviceUnusedDays*24*time.Hour {
				kept = append(kept, d)
			}
		}
		c.Devices = kept
		// keep the list bounded: drop the least recently used
		sort.Slice(c.Devices, func(i, j int) bool { return c.Devices[i].LastUsed.After(c.Devices[j].LastUsed) })
		if len(c.Devices) >= maxDevices {
			c.Devices = c.Devices[:maxDevices-1]
		}
		c.Devices = append(c.Devices, Device{ID: id, Name: cleanDeviceName(name), TokenHash: hashToken(tok), Created: now, LastUsed: now})
		return nil
	})
	if err != nil {
		return "", "", err
	}
	logf("device registered: %s (%s)", cleanDeviceName(name), id)
	return id, tok, nil
}

var errUnknownDevice = errors.New("Gerät nicht (mehr) angemeldet – bitte mit Passwort anmelden")

// POST /api/refresh {deviceId, refreshToken} – public, same lockout as the password login
func (s *Server) handleRefresh(w http.ResponseWriter, r *http.Request) {
	var req struct {
		DeviceID     string `json:"deviceId"`
		RefreshToken string `json:"refreshToken"`
	}
	if err := readJSON(r, &req); err != nil || !deviceIDRe.MatchString(req.DeviceID) {
		writeErr(w, 400, "invalid request")
		return
	}
	ip := clientIP(r)
	if s.auth.locked(ip) {
		// not 401: the app must not forget its device because of a temporary lock
		writeErr(w, 429, errLocked.Error())
		return
	}
	var found *Device
	c := s.cfg.Get()
	for i := range c.Devices {
		d := &c.Devices[i]
		if d.ID == req.DeviceID {
			found = d
		}
	}
	ok := found != nil && subtle.ConstantTimeCompare([]byte(hashToken(req.RefreshToken)), []byte(found.TokenHash)) == 1 &&
		time.Since(found.LastUsed) < deviceUnusedDays*24*time.Hour
	if !ok {
		s.auth.fail(ip)
		logf("refresh failed from %s (device %s)", ip, req.DeviceID)
		writeErr(w, 401, errUnknownDevice.Error())
		return
	}
	tok, err := s.auth.newSession(ip, found.ID)
	if err != nil {
		writeErr(w, 500, err.Error())
		return
	}
	// remember the last use (at most once per hour to avoid constant config writes)
	if time.Since(found.LastUsed) > time.Hour {
		_ = s.cfg.Update(func(c *Config) error {
			for i := range c.Devices {
				if c.Devices[i].ID == found.ID {
					c.Devices[i].LastUsed = time.Now()
				}
			}
			return nil
		})
	}
	writeJSON(w, 200, s.loginResponse(tok, "", ""))
}

func (s *Server) loginResponse(token, deviceID, refreshToken string) map[string]any {
	c := s.cfg.Get()
	resp := map[string]any{
		"token": token, "idleMinutes": c.SessionIdleMinutes,
		"modules": map[string]bool{"nextcloud": c.Nextcloud.Enabled, "immich": c.Immich.Enabled, "shell": c.AllowShell},
	}
	if refreshToken != "" {
		resp["deviceId"] = deviceID
		resp["refreshToken"] = refreshToken
	}
	return resp
}

// GET /api/devices
func (s *Server) handleDevices(w http.ResponseWriter, r *http.Request) {
	cur := s.auth.sessionDevice(r)
	type item struct {
		ID       string    `json:"id"`
		Name     string    `json:"name"`
		Created  time.Time `json:"created"`
		LastUsed time.Time `json:"lastUsed"`
		Current  bool      `json:"current"`
	}
	out := []item{}
	for _, d := range s.cfg.Get().Devices {
		out = append(out, item{d.ID, d.Name, d.Created, d.LastUsed, d.ID == cur})
	}
	sort.Slice(out, func(i, j int) bool { return out[i].LastUsed.After(out[j].LastUsed) })
	writeJSON(w, 200, out)
}

func (s *Server) revokeDevice(id string) bool {
	removed := false
	_ = s.cfg.Update(func(c *Config) error {
		kept := c.Devices[:0]
		for _, d := range c.Devices {
			if d.ID == id {
				removed = true
				continue
			}
			kept = append(kept, d)
		}
		c.Devices = kept
		return nil
	})
	if removed {
		s.auth.dropDevice(id)
		logf("device revoked: %s", id)
	}
	return removed
}

// POST /api/devices/{id}/revoke
func (s *Server) handleDeviceRevoke(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if !deviceIDRe.MatchString(id) || !s.revokeDevice(id) {
		writeErr(w, 404, "Gerät nicht gefunden")
		return
	}
	writeJSON(w, 200, map[string]bool{"ok": true})
}
