package deviceapi

import (
	"bytes"
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"io"
	"math"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/ChuckPeng/OpenMinis-K12/server/internal/config"
	"github.com/ChuckPeng/OpenMinis-K12/server/internal/crypto"
	"github.com/ChuckPeng/OpenMinis-K12/server/internal/httpx"
	"github.com/ChuckPeng/OpenMinis-K12/server/internal/store"
)

// Server is the device-facing HTTP port (default 8080).
// Admin paths return 404 (R7).
type Server struct {
	Cfg         config.Config
	Store       *store.Store
	Master      crypto.MasterKey
	SigningPriv ed25519.PrivateKey
	SigningPub  ed25519.PublicKey
	Callback    http.Handler
	Notifier    *Notifier
}

type deviceCtxKey struct{}

func withDevice(ctx context.Context, d *store.Device) context.Context {
	return context.WithValue(ctx, deviceCtxKey{}, d)
}

func getContextDevice(r *http.Request) *store.Device {
	v := r.Context().Value(deviceCtxKey{})
	if v == nil {
		return nil
	}
	return v.(*store.Device)
}

// EnsureSigningKey loads or generates the server Ed25519 keypair for signing grants.
func (s *Server) EnsureSigningKey(ctx context.Context) error {
	if s.SigningPriv != nil && s.SigningPub != nil {
		return nil
	}
	// Try loading from settings
	var privEnc string
	err := s.Store.DB.QueryRowContext(ctx, `SELECT value FROM settings WHERE key='server_ed25519_priv_enc'`).Scan(&privEnc)
	if err == nil && privEnc != "" {
		privBytes, err := s.Master.Open(privEnc)
		if err == nil && len(privBytes) == ed25519.PrivateKeySize {
			s.SigningPriv = ed25519.PrivateKey(privBytes)
			s.SigningPub = s.SigningPriv.Public().(ed25519.PublicKey)
			return nil
		}
	}

	// Generate new key
	pub, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		return err
	}
	enc, err := s.Master.Seal(priv)
	if err != nil {
		return err
	}
	_, _ = s.Store.DB.ExecContext(ctx, `
INSERT OR REPLACE INTO settings (key, value) VALUES ('server_ed25519_priv_enc', ?)`, enc)
	s.SigningPriv = priv
	s.SigningPub = pub
	return nil
}

// Handler mounts health endpoints, WeCom callback, report links, and device API.
func (s *Server) Handler() http.Handler {
	if s.Notifier == nil {
		s.Notifier = NewNotifier()
	}
	_ = s.EnsureSigningKey(context.Background())

	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, _ *http.Request) {
		httpx.WriteText(w, http.StatusOK, "ok")
	})
	mux.HandleFunc("/readyz", func(w http.ResponseWriter, r *http.Request) {
		if err := s.Store.Ping(r.Context()); err != nil {
			httpx.WriteText(w, http.StatusServiceUnavailable, "unavailable")
			return
		}
		httpx.WriteText(w, http.StatusOK, "ok")
	})

	if s.Callback != nil {
		mux.Handle("/wecom/", s.Callback)
	}

	// Public signed report link
	mux.HandleFunc("/r/", s.getSignedReport)

	// Device API endpoints
	mux.HandleFunc("/api/v1/device/pair", s.pairDevice)
	mux.HandleFunc("/api/v1/device/pair/status", s.withDeviceAuth(true, s.getPairStatus))

	mux.HandleFunc("/api/v1/device/unlock-requests", func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodPost {
			s.withDeviceAuth(false, s.createUnlockRequest)(w, r)
			return
		}
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
	})

	mux.HandleFunc("/api/v1/device/unlock-requests/", func(w http.ResponseWriter, r *http.Request) {
		if strings.HasSuffix(r.URL.Path, "/ack") {
			s.withDeviceAuth(false, s.ackUnlockRequest)(w, r)
			return
		}
		s.withDeviceAuth(false, s.waitUnlockRequest)(w, r)
	})

	mux.HandleFunc("/api/v1/device/alerts", s.withDeviceAuth(false, s.postAlert))
	mux.HandleFunc("/api/v1/device/events", s.withDeviceAuth(false, s.postEvents))
	mux.HandleFunc("/api/v1/device/reports", s.withDeviceAuth(false, s.listChildReports))
	mux.HandleFunc("/api/v1/device/heartbeat", s.withDeviceAuth(false, s.heartbeat))
	mux.HandleFunc("/api/v1/device/backups", s.withDeviceAuth(false, s.putBackup))
	mux.HandleFunc("/api/v1/device/backups/latest", s.withDeviceAuth(false, s.getLatestBackup))
	mux.HandleFunc("/api/v1/device/app-update", s.withDeviceAuth(false, s.getAppUpdate))
	mux.HandleFunc("/api/v1/device/diagnostics", s.withDeviceAuth(false, s.postDiagnostics))

	// R7: Management paths on device port must return 404
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		if strings.HasPrefix(r.URL.Path, "/api/v1/admin") || r.URL.Path == "/metrics" {
			http.NotFound(w, r)
			return
		}
		http.NotFound(w, r)
	})

	return mux
}

// withDeviceAuth checks device signature headers (X-K12-Device, X-K12-Ts, X-K12-Nonce, X-K12-Sig).
func (s *Server) withDeviceAuth(allowPending bool, next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		deviceID := r.Header.Get("X-K12-Device")
		tsStr := r.Header.Get("X-K12-Ts")
		nonce := r.Header.Get("X-K12-Nonce")
		sigStr := r.Header.Get("X-K12-Sig")

		if deviceID == "" || tsStr == "" || nonce == "" || sigStr == "" {
			httpx.WriteError(w, http.StatusUnauthorized, "missing_auth_headers", "required X-K12 headers missing")
			return
		}

		// Verify timestamp window: ±5 minutes
		ts, err := strconv.ParseInt(tsStr, 10, 64)
		if err != nil {
			httpx.WriteError(w, http.StatusUnauthorized, "invalid_timestamp", "invalid X-K12-Ts")
			return
		}
		nowMs := time.Now().UnixMilli()
		if math.Abs(float64(nowMs-ts)) > 5*60*1000 {
			httpx.WriteError(w, http.StatusUnauthorized, "timestamp_out_of_bounds", "X-K12-Ts exceeds 5 minute window")
			return
		}

		ctx := r.Context()

		// Replay protection: check and record nonce
		ok, err := s.Store.CheckAndRecordNonce(ctx, deviceID, nonce, time.Now())
		if err != nil || !ok {
			httpx.WriteError(w, http.StatusUnauthorized, "replay_detected", "nonce already seen or invalid")
			return
		}

		// Retrieve device
		dev, err := s.Store.GetDevice(ctx, deviceID)
		if err != nil || dev == nil {
			httpx.WriteError(w, http.StatusUnauthorized, "unknown_device", "device not found")
			return
		}

		// Check device status
		if dev.Status == "revoked" {
			httpx.WriteError(w, http.StatusUnauthorized, "device_revoked", "device has been revoked")
			return
		}
		if dev.Status == "pending_confirm" && !allowPending {
			httpx.WriteError(w, http.StatusUnauthorized, "device_pending", "device pending parent confirmation")
			return
		}

		// Read and restore body for hashing
		var bodyBytes []byte
		if r.Body != nil {
			bodyBytes, err = io.ReadAll(r.Body)
			if err != nil {
				httpx.WriteError(w, http.StatusBadRequest, "bad_request", "failed to read body")
				return
			}
			r.Body = io.NopCloser(bytes.NewBuffer(bodyBytes))
		}

		// Compute body SHA-256
		bodySHA := sha256.Sum256(bodyBytes)
		bodyHex := hex.EncodeToString(bodySHA[:])

		// Path with query string
		pathWithQuery := r.URL.RequestURI()
		signString := crypto.BuildDeviceSignString(r.Method, pathWithQuery, tsStr, nonce, bodyHex)

		// Decode device public key
		pubBytes, err := base64.StdEncoding.DecodeString(dev.Pubkey)
		if err != nil {
			pubBytes, err = hex.DecodeString(dev.Pubkey)
			if err != nil {
				pubBytes = []byte(dev.Pubkey)
			}
		}

		// Verify signature
		if !crypto.VerifyDeviceSignature(pubBytes, signString, sigStr) {
			httpx.WriteError(w, http.StatusUnauthorized, "bad_signature", "device signature verification failed")
			return
		}

		// Pass device in context
		reqWithCtx := r.WithContext(withDevice(ctx, dev))
		next(w, reqWithCtx)
	}
}
