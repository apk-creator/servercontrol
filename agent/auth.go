package main

import (
	"crypto/pbkdf2"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"net"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"
)

const pbkdf2Iter = 600_000

func HashPassword(pw string) (string, error) {
	salt := make([]byte, 16)
	if _, err := rand.Read(salt); err != nil {
		return "", err
	}
	key, err := pbkdf2.Key(sha256.New, pw, salt, pbkdf2Iter, 32)
	if err != nil {
		return "", err
	}
	return fmt.Sprintf("pbkdf2-sha256$%d$%s$%s", pbkdf2Iter,
		base64.RawStdEncoding.EncodeToString(salt), base64.RawStdEncoding.EncodeToString(key)), nil
}

func VerifyPassword(encoded, pw string) bool {
	p := strings.Split(encoded, "$")
	if len(p) != 4 || p[0] != "pbkdf2-sha256" {
		return false
	}
	iter, err := strconv.Atoi(p[1])
	if err != nil || iter < 10_000 {
		return false
	}
	salt, err1 := base64.RawStdEncoding.DecodeString(p[2])
	want, err2 := base64.RawStdEncoding.DecodeString(p[3])
	if err1 != nil || err2 != nil {
		return false
	}
	got, err := pbkdf2.Key(sha256.New, pw, salt, iter, len(want))
	if err != nil {
		return false
	}
	return subtle.ConstantTimeCompare(got, want) == 1
}

type session struct {
	created  time.Time
	lastSeen time.Time
	deviceID string // set when the session belongs to a remembered device
}

type Auth struct {
	cfg      *ConfigStore
	mu       sync.Mutex
	sessions map[string]*session
	failures map[string][]time.Time // per client IP
}

func NewAuth(cfg *ConfigStore) *Auth {
	a := &Auth{cfg: cfg, sessions: map[string]*session{}, failures: map[string][]time.Time{}}
	go a.janitor()
	return a
}

func (a *Auth) janitor() {
	for range time.Tick(time.Minute) {
		a.mu.Lock()
		c := a.cfg.Get()
		for k, s := range a.sessions {
			if a.expired(s, c) {
				delete(a.sessions, k)
			}
		}
		a.mu.Unlock()
	}
}

func (a *Auth) expired(s *session, c Config) bool {
	now := time.Now()
	return now.Sub(s.lastSeen) > time.Duration(c.SessionIdleMinutes)*time.Minute ||
		now.Sub(s.created) > time.Duration(c.SessionMaxHours)*time.Hour
}

func clientIP(r *http.Request) string {
	h, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return h
}

var errLocked = errors.New("too many failed attempts, try again later")

// locked reports whether ip is currently locked out (5 failures / 15 min).
func (a *Auth) locked(ip string) bool {
	a.mu.Lock()
	defer a.mu.Unlock()
	recent := a.failures[ip][:0]
	for _, t := range a.failures[ip] {
		if time.Since(t) < 15*time.Minute {
			recent = append(recent, t)
		}
	}
	a.failures[ip] = recent
	return len(recent) >= 5
}

func (a *Auth) fail(ip string) {
	a.mu.Lock()
	a.failures[ip] = append(a.failures[ip], time.Now())
	a.mu.Unlock()
	time.Sleep(500 * time.Millisecond)
}

// newSession creates a session token (optionally bound to a remembered device).
func (a *Auth) newSession(ip, deviceID string) (string, error) {
	tok := make([]byte, 32)
	if _, err := rand.Read(tok); err != nil {
		return "", err
	}
	t := hex.EncodeToString(tok)
	a.mu.Lock()
	delete(a.failures, ip)
	a.sessions[t] = &session{created: time.Now(), lastSeen: time.Now(), deviceID: deviceID}
	a.mu.Unlock()
	return t, nil
}

// Login checks credentials with a per-IP lockout (5 failures / 15 min).
func (a *Auth) Login(r *http.Request, user, pw string) (string, error) {
	ip := clientIP(r)
	if a.locked(ip) {
		return "", errLocked
	}
	c := a.cfg.Get()
	userOK := subtle.ConstantTimeCompare([]byte(user), []byte(c.User)) == 1
	pwOK := VerifyPassword(c.PasswordHash, pw) // always run to keep timing uniform
	if !userOK || !pwOK {
		a.fail(ip)
		return "", errors.New("invalid credentials")
	}
	return a.newSession(ip, "")
}

// bindDevice marks a session as belonging to a device.
func (a *Auth) bindDevice(token, deviceID string) {
	a.mu.Lock()
	if s, ok := a.sessions[token]; ok {
		s.deviceID = deviceID
	}
	a.mu.Unlock()
}

// sessionDevice returns the device of the session behind the request ("" if none).
func (a *Auth) sessionDevice(r *http.Request) string {
	a.mu.Lock()
	defer a.mu.Unlock()
	if s, ok := a.sessions[bearer(r)]; ok {
		return s.deviceID
	}
	return ""
}

// dropDevice ends all sessions of a revoked device.
func (a *Auth) dropDevice(deviceID string) {
	a.mu.Lock()
	for k, s := range a.sessions {
		if s.deviceID == deviceID {
			delete(a.sessions, k)
		}
	}
	a.mu.Unlock()
}

func (a *Auth) Logout(token string) {
	a.mu.Lock()
	delete(a.sessions, token)
	a.mu.Unlock()
}

func bearer(r *http.Request) string {
	h := r.Header.Get("Authorization")
	if strings.HasPrefix(h, "Bearer ") {
		return strings.TrimPrefix(h, "Bearer ")
	}
	return ""
}

func (a *Auth) Middleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		tok := bearer(r)
		a.mu.Lock()
		s, ok := a.sessions[tok]
		if ok && a.expired(s, a.cfg.Get()) {
			delete(a.sessions, tok)
			ok = false
		}
		if ok {
			s.lastSeen = time.Now()
		}
		a.mu.Unlock()
		if !ok {
			writeErr(w, http.StatusUnauthorized, "not authenticated")
			return
		}
		next.ServeHTTP(w, r)
	})
}
