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

// Login checks credentials with a per-IP lockout (5 failures / 15 min).
func (a *Auth) Login(r *http.Request, user, pw string) (string, error) {
	ip := clientIP(r)
	a.mu.Lock()
	recent := a.failures[ip][:0]
	for _, t := range a.failures[ip] {
		if time.Since(t) < 15*time.Minute {
			recent = append(recent, t)
		}
	}
	a.failures[ip] = recent
	if len(recent) >= 5 {
		a.mu.Unlock()
		return "", errLocked
	}
	a.mu.Unlock()

	c := a.cfg.Get()
	userOK := subtle.ConstantTimeCompare([]byte(user), []byte(c.User)) == 1
	pwOK := VerifyPassword(c.PasswordHash, pw) // always run to keep timing uniform
	if !userOK || !pwOK {
		a.mu.Lock()
		a.failures[ip] = append(a.failures[ip], time.Now())
		a.mu.Unlock()
		time.Sleep(500 * time.Millisecond)
		return "", errors.New("invalid credentials")
	}
	tok := make([]byte, 32)
	if _, err := rand.Read(tok); err != nil {
		return "", err
	}
	t := hex.EncodeToString(tok)
	a.mu.Lock()
	delete(a.failures, ip)
	a.sessions[t] = &session{created: time.Now(), lastSeen: time.Now()}
	a.mu.Unlock()
	return t, nil
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
