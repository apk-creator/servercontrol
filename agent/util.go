package main

import (
	"bytes"
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/hex"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"math/big"
	"net"
	"net/http"
	"os"
	"os/exec"
	"strings"
	"sync"
	"time"
)

// ---------- HTTP helpers ----------

func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(v)
}

func writeErr(w http.ResponseWriter, code int, msg string) {
	writeJSON(w, code, map[string]string{"error": msg})
}

func readJSON(r *http.Request, v any) error {
	dec := json.NewDecoder(http.MaxBytesReader(nil, r.Body, 1<<20))
	dec.DisallowUnknownFields()
	return dec.Decode(v)
}

// ---------- command execution ----------

type CmdResult struct {
	Cmd      string `json:"cmd"`
	ExitCode int    `json:"exitCode"`
	Stdout   string `json:"stdout"`
	Stderr   string `json:"stderr"`
}

// run executes a command without a shell (no injection possible through args).
func run(ctx context.Context, timeout time.Duration, env []string, name string, args ...string) CmdResult {
	ctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	cmd := exec.CommandContext(ctx, name, args...)
	cmd.Env = append(os.Environ(), env...)
	cmd.Env = append(cmd.Env, "LC_ALL=C", "DEBIAN_FRONTEND=noninteractive")
	var out, errb bytes.Buffer
	cmd.Stdout, cmd.Stderr = &out, &errb
	err := cmd.Run()
	res := CmdResult{Cmd: name + " " + strings.Join(args, " "), Stdout: out.String(), Stderr: errb.String()}
	if err != nil {
		if ee, ok := err.(*exec.ExitError); ok {
			res.ExitCode = ee.ExitCode()
		} else {
			res.ExitCode = -1
			res.Stderr += err.Error()
		}
	}
	return res
}

func sendResult(w http.ResponseWriter, res CmdResult) {
	code := http.StatusOK
	if res.ExitCode != 0 {
		code = http.StatusBadGateway
	}
	writeJSON(w, code, res)
}

// ---------- background jobs (long-running tasks) ----------

type JobView struct {
	ID       string     `json:"id"`
	Title    string     `json:"title"`
	Started  time.Time  `json:"started"`
	Finished *time.Time `json:"finished,omitempty"`
	ExitCode *int       `json:"exitCode,omitempty"`
	Output   string     `json:"output"`
}

type Job struct {
	ID       string
	Title    string
	Started  time.Time
	Finished *time.Time
	ExitCode *int
	mu       sync.Mutex
	buf      bytes.Buffer
}

type lockedWriter struct{ j *Job }

func (l lockedWriter) Write(p []byte) (int, error) {
	l.j.mu.Lock()
	defer l.j.mu.Unlock()
	// cap output at 2 MB, keep the tail
	if l.j.buf.Len()+len(p) > 2<<20 {
		b := l.j.buf.Bytes()
		keep := b[len(b)/2:]
		l.j.buf.Reset()
		l.j.buf.WriteString("…(gekürzt)…\n")
		l.j.buf.Write(keep)
	}
	return l.j.buf.Write(p)
}

type Jobs struct {
	mu   sync.Mutex
	jobs map[string]*Job
}

func NewJobs() *Jobs { return &Jobs{jobs: map[string]*Job{}} }

func (js *Jobs) Start(title string, timeout time.Duration, env []string, name string, args ...string) *Job {
	idb := make([]byte, 8)
	_, _ = rand.Read(idb)
	j := &Job{ID: hex.EncodeToString(idb), Title: title, Started: time.Now()}
	js.mu.Lock()
	// drop finished jobs older than 24h
	for k, o := range js.jobs {
		if v := o.snapshot(); v.Finished != nil && time.Since(*v.Finished) > 24*time.Hour {
			delete(js.jobs, k)
		}
	}
	js.jobs[j.ID] = j
	js.mu.Unlock()
	go func() {
		ctx, cancel := context.WithTimeout(context.Background(), timeout)
		defer cancel()
		cmd := exec.CommandContext(ctx, name, args...)
		cmd.Env = append(os.Environ(), env...)
		cmd.Env = append(cmd.Env, "LC_ALL=C", "DEBIAN_FRONTEND=noninteractive")
		cmd.Stdout, cmd.Stderr = lockedWriter{j}, lockedWriter{j}
		fmt.Fprintf(lockedWriter{j}, "$ %s %s\n", name, strings.Join(args, " "))
		err := cmd.Run()
		code := 0
		if err != nil {
			code = -1
			if ee, ok := err.(*exec.ExitError); ok {
				code = ee.ExitCode()
			} else {
				fmt.Fprintf(lockedWriter{j}, "\n%s\n", err)
			}
		}
		now := time.Now()
		j.mu.Lock()
		j.Finished, j.ExitCode = &now, &code
		j.mu.Unlock()
		logf("job %s (%s) finished with %d", j.ID, title, code)
	}()
	logf("job %s started: %s", j.ID, title)
	return j
}

func (j *Job) snapshot() JobView {
	j.mu.Lock()
	defer j.mu.Unlock()
	return JobView{ID: j.ID, Title: j.Title, Started: j.Started, Finished: j.Finished, ExitCode: j.ExitCode, Output: j.buf.String()}
}

func (js *Jobs) handleList(w http.ResponseWriter, r *http.Request) {
	js.mu.Lock()
	out := make([]JobView, 0, len(js.jobs))
	for _, j := range js.jobs {
		s := j.snapshot()
		s.Output = "" // list without output
		out = append(out, s)
	}
	js.mu.Unlock()
	writeJSON(w, 200, out)
}

func (js *Jobs) handleGet(w http.ResponseWriter, r *http.Request) {
	js.mu.Lock()
	j, ok := js.jobs[r.PathValue("id")]
	js.mu.Unlock()
	if !ok {
		writeErr(w, 404, "job not found")
		return
	}
	writeJSON(w, 200, j.snapshot())
}

func jobStarted(w http.ResponseWriter, j *Job) {
	writeJSON(w, http.StatusAccepted, map[string]string{"jobId": j.ID, "title": j.Title})
}

// ---------- TLS ----------

// ensureCert creates a self-signed ECDSA certificate on first start.
// The client pins its SHA-256 fingerprint (trust on first use).
func ensureCert(certPath, keyPath string) error {
	if _, err := os.Stat(certPath); err == nil {
		return nil
	}
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return err
	}
	host, _ := os.Hostname()
	serial, _ := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 120))
	tmpl := x509.Certificate{
		SerialNumber: serial,
		Subject:      pkix.Name{CommonName: "ServerControl " + host},
		NotBefore:    time.Now().Add(-time.Hour),
		NotAfter:     time.Now().AddDate(10, 0, 0),
		KeyUsage:     x509.KeyUsageDigitalSignature,
		ExtKeyUsage:  []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		DNSNames:     []string{host, "localhost"},
		IPAddresses:  []net.IP{net.ParseIP("127.0.0.1")},
	}
	der, err := x509.CreateCertificate(rand.Reader, &tmpl, &tmpl, &key.PublicKey, key)
	if err != nil {
		return err
	}
	kb, err := x509.MarshalECPrivateKey(key)
	if err != nil {
		return err
	}
	if err := os.WriteFile(keyPath, pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: kb}), 0o600); err != nil {
		return err
	}
	return os.WriteFile(certPath, pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der}), 0o644)
}

func certFingerprint(certPath string) (string, error) {
	b, err := os.ReadFile(certPath)
	if err != nil {
		return "", err
	}
	blk, _ := pem.Decode(b)
	if blk == nil {
		return "", fmt.Errorf("no PEM in %s", certPath)
	}
	sum := sha256.Sum256(blk.Bytes)
	parts := make([]string, len(sum))
	for i, c := range sum {
		parts[i] = fmt.Sprintf("%02X", c)
	}
	return strings.Join(parts, ":"), nil
}

func logf(format string, a ...any) {
	fmt.Fprintf(os.Stderr, time.Now().Format("2006-01-02 15:04:05 ")+format+"\n", a...)
}
