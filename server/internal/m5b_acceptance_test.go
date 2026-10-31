package internal_test

import (
	"bytes"
	"compress/gzip"
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"crypto/sha256"
	"database/sql"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/ChuckPeng/OpenMinis-K12/server/internal/admin"
	"github.com/ChuckPeng/OpenMinis-K12/server/internal/config"
	"github.com/ChuckPeng/OpenMinis-K12/server/internal/crypto"
	"github.com/ChuckPeng/OpenMinis-K12/server/internal/deviceapi"
	"github.com/ChuckPeng/OpenMinis-K12/server/internal/httpx"
	"github.com/ChuckPeng/OpenMinis-K12/server/internal/store"
	"github.com/ChuckPeng/OpenMinis-K12/server/internal/version"
)

type m5bTestHarness struct {
	Store        *store.Store
	Master       crypto.MasterKey
	Notifier     *deviceapi.Notifier
	DeviceServer *deviceapi.Server
	AdminServer  *admin.Server
	ServerPub    ed25519.PublicKey
	ServerPriv   ed25519.PrivateKey
	FamilyID     string
	AdminCSRF    string
	AdminCookies []*http.Cookie
}

func newM5bTestHarness(t *testing.T) (*m5bTestHarness, func()) {
	t.Helper()
	dir := t.TempDir()
	_ = os.Setenv("OPENMINIS_K12_ALLOW_EMPTY_TRUSTED_PROXIES", "1")
	dbPath := filepath.Join(dir, "m5b.db")

	st, err := store.Open(dbPath)
	if err != nil {
		t.Fatal(err)
	}

	master, err := crypto.LoadOrCreateMasterKey(filepath.Join(dir, "master.key"))
	if err != nil {
		t.Fatal(err)
	}

	famID, err := st.EnsureFamily(context.Background(), "home")
	if err != nil {
		t.Fatal(err)
	}

	pub, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}

	proxies, _ := httpx.ParseTrustedProxies([]string{"127.0.0.1/32"})
	cfg := config.Default()
	cfg.DataDir = dir
	cfg.PublicBaseURL = "https://k12.example.com"
	cfg.TrustedProxies = []string{"127.0.0.1/32"}

	notifier := deviceapi.NewNotifier()
	devSrv := &deviceapi.Server{
		Cfg:         cfg,
		Store:       st,
		Master:      master,
		SigningPriv: priv,
		SigningPub:  pub,
		Notifier:    notifier,
	}

	admSrv := &admin.Server{
		Cfg:      cfg,
		Store:    st,
		Master:   master,
		Proxies:  proxies,
		Notifier: notifier,
	}

	// Setup admin credentials
	mustSetup(t, admSrv.Handler(), "admin", "correcthorse1")
	csrf, jar := mustLogin(t, admSrv.Handler(), "admin", "correcthorse1")

	h := &m5bTestHarness{
		Store:        st,
		Master:       master,
		Notifier:     notifier,
		DeviceServer: devSrv,
		AdminServer:  admSrv,
		ServerPub:    pub,
		ServerPriv:   priv,
		FamilyID:     famID,
		AdminCSRF:    csrf,
		AdminCookies: jar,
	}

	return h, func() {
		_ = st.Close()
	}
}

func (h *m5bTestHarness) adminRequest(t *testing.T, method, path string, body interface{}) *httptest.ResponseRecorder {
	t.Helper()
	var bodyReader *bytes.Buffer
	if body != nil {
		b, err := json.Marshal(body)
		if err != nil {
			t.Fatal(err)
		}
		bodyReader = bytes.NewBuffer(b)
	} else {
		bodyReader = bytes.NewBuffer(nil)
	}

	req := httptest.NewRequest(method, path, bodyReader)
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-CSRF-Token", h.AdminCSRF)
	for _, c := range h.AdminCookies {
		req.AddCookie(c)
	}

	rec := httptest.NewRecorder()
	h.AdminServer.Handler().ServeHTTP(rec, req)
	return rec
}

func (h *m5bTestHarness) signedDeviceRequest(
	t *testing.T,
	method, path string,
	body []byte,
	deviceID string,
	devicePriv ed25519.PrivateKey,
	extraHeaders map[string]string,
) *httptest.ResponseRecorder {
	t.Helper()
	nowMs := fmt.Sprintf("%d", time.Now().UnixMilli())
	nonceBytes := make([]byte, 16)
	_, _ = rand.Read(nonceBytes)
	nonce := hex.EncodeToString(nonceBytes)

	var bodyHash string
	if len(body) > 0 {
		hash := sha256.Sum256(body)
		bodyHash = hex.EncodeToString(hash[:])
	}

	signStr := crypto.BuildDeviceSignString(method, path, nowMs, nonce, bodyHash)
	sig := crypto.SignGrant(devicePriv, []byte(signStr))

	req := httptest.NewRequest(method, path, bytes.NewReader(body))
	req.Header.Set("X-K12-Device", deviceID)
	req.Header.Set("X-K12-Ts", nowMs)
	req.Header.Set("X-K12-Nonce", nonce)
	req.Header.Set("X-K12-Sig", sig)
	for k, v := range extraHeaders {
		req.Header.Set(k, v)
	}

	rec := httptest.NewRecorder()
	h.DeviceServer.Handler().ServeHTTP(rec, req)
	return rec
}

func gzipBytes(data []byte) []byte {
	var buf bytes.Buffer
	zw := gzip.NewWriter(&buf)
	_, _ = zw.Write(data)
	_ = zw.Close()
	return buf.Bytes()
}

// --- Test 1: Versioning ---

func TestM5bVersionAndCode(t *testing.T) {
	if version.Version != "1.0.0-m5b" {
		t.Fatalf("expected version 1.0.0-m5b, got %s", version.Version)
	}
	if version.VersionCode != "1000006" {
		t.Fatalf("expected versionCode 1000006, got %s", version.VersionCode)
	}
}

// --- Test 2: WP-B-1 Pairing, Approval & Revocation (J1, J9) ---

func TestM5b_WP_B1_PairingAndDeviceLifecycle(t *testing.T) {
	h, cleanup := newM5bTestHarness(t)
	defer cleanup()

	// 1. Admin generates pair code
	rec := h.adminRequest(t, http.MethodPost, "/api/v1/admin/pair-codes", map[string]interface{}{
		"child_id": "child-test-1",
	})
	if rec.Code != http.StatusOK {
		t.Fatalf("create pair code failed: %d %s", rec.Code, rec.Body.String())
	}
	var pairCodeResp struct {
		Code      string `json:"code"`
		PairCode  string `json:"pair_code"`
		ExpiresAt string `json:"expires_at"`
	}
	_ = json.Unmarshal(rec.Body.Bytes(), &pairCodeResp)
	code := pairCodeResp.Code
	if code == "" {
		code = pairCodeResp.PairCode
	}
	if code == "" {
		t.Fatal("empty pair code returned")
	}

	// 2. Tablet pairs: POST /api/v1/device/pair
	devPub, devPriv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	devPubB64 := base64.StdEncoding.EncodeToString(devPub)

	pairReqBody, _ := json.Marshal(map[string]interface{}{
		"pair_code":     code,
		"device_pubkey": devPubB64,
		"device_label":  "平板 A",
		"child_id":      "child-test-1",
	})
	req := httptest.NewRequest(http.MethodPost, "/api/v1/device/pair", bytes.NewReader(pairReqBody))
	rec = httptest.NewRecorder()
	h.DeviceServer.Handler().ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("pair device failed: %d %s", rec.Code, rec.Body.String())
	}
	var pairResp struct {
		DeviceID          string `json:"device_id"`
		Status            string `json:"status"`
		WrappedCodeSecret string `json:"wrapped_code_secret"`
	}
	_ = json.Unmarshal(rec.Body.Bytes(), &pairResp)
	if pairResp.DeviceID == "" || pairResp.Status != "pending_confirm" {
		t.Fatalf("unexpected pair status: %+v", pairResp)
	}

	// 3. Pending device cannot call business API (e.g. heartbeat returns 401/403)
	rec = h.signedDeviceRequest(t, http.MethodPost, "/api/v1/device/heartbeat", []byte(`{}`), pairResp.DeviceID, devPriv, nil)
	if rec.Code != http.StatusUnauthorized && rec.Code != http.StatusForbidden {
		t.Fatalf("pending device should get 401 or 403 on heartbeat, got %d", rec.Code)
	}

	// 4. Pending device can poll pair status
	rec = h.signedDeviceRequest(t, http.MethodGet, "/api/v1/device/pair/status?wait=0", nil, pairResp.DeviceID, devPriv, nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("poll status failed: %d", rec.Code)
	}

	// 5. Admin approves device
	rec = h.adminRequest(t, http.MethodPost, fmt.Sprintf("/api/v1/admin/devices/%s/approve", pairResp.DeviceID), nil)
	if rec.Code != http.StatusOK && rec.Code != http.StatusNoContent {
		t.Fatalf("approve device failed: %d %s", rec.Code, rec.Body.String())
	}

	// 6. Device is now active -> heartbeat succeeds
	rec = h.signedDeviceRequest(t, http.MethodPost, "/api/v1/device/heartbeat", []byte(`{"app_version":"1.0.0-m5b"}`), pairResp.DeviceID, devPriv, nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("heartbeat on active device failed: %d %s", rec.Code, rec.Body.String())
	}

	// 7. J9: Admin revokes device -> device signatures immediately invalid
	rec = h.adminRequest(t, http.MethodPost, fmt.Sprintf("/api/v1/admin/devices/%s/revoke", pairResp.DeviceID), nil)
	if rec.Code != http.StatusOK && rec.Code != http.StatusNoContent {
		t.Fatalf("revoke device failed: %d %s", rec.Code, rec.Body.String())
	}

	rec = h.signedDeviceRequest(t, http.MethodPost, "/api/v1/device/heartbeat", []byte(`{}`), pairResp.DeviceID, devPriv, nil)
	if rec.Code != http.StatusUnauthorized && rec.Code != http.StatusForbidden {
		t.Fatalf("revoked device should get 401 or 403, got %d", rec.Code)
	}
}

// --- Test 3: WP-B-2 Remote Reveal & Unlock Flow (J2-J8, J10, J13) ---

func TestM5b_WP_B2_RemoteUnlockAndAck(t *testing.T) {
	h, cleanup := newM5bTestHarness(t)
	defer cleanup()

	// Create and activate device with sealed codeSecret
	devPub, devPriv, _ := ed25519.GenerateKey(rand.Reader)
	devID := "dev-unlock-test"
	codeSec := make([]byte, 32)
	_, _ = rand.Read(codeSec)
	codeSecEnc, err := h.Master.Seal(codeSec)
	if err != nil {
		t.Fatal(err)
	}

	_ = h.Store.CreateDevice(context.Background(), &store.Device{
		ID:            devID,
		FamilyID:      h.FamilyID,
		ChildID:       "child-1",
		Label:         "平板 测试",
		Pubkey:        base64.StdEncoding.EncodeToString(devPub),
		CodeSecretEnc: codeSecEnc,
		Status:        "active",
		CreatedAt:     time.Now().UTC().Format(time.RFC3339),
	})

	// 1. Tablet creates unlock request
	reqPayload, _ := json.Marshal(map[string]interface{}{
		"client_request_id": "req-client-1",
		"subject":           "数学",
		"question_hash":     "qhash12345",
		"hints_used":        3,
		"scope":             "question",
	})
	rec := h.signedDeviceRequest(t, http.MethodPost, "/api/v1/device/unlock-requests", reqPayload, devID, devPriv, nil)
	if rec.Code != http.StatusOK && rec.Code != http.StatusCreated {
		t.Fatalf("create unlock request failed: %d %s", rec.Code, rec.Body.String())
	}
	var unlockResp struct {
		ID        string `json:"id"`
		RequestID string `json:"request_id"`
		Status    string `json:"status"`
	}
	_ = json.Unmarshal(rec.Body.Bytes(), &unlockResp)
	reqID := unlockResp.ID
	if reqID == "" {
		reqID = unlockResp.RequestID
	}
	if reqID == "" {
		t.Fatalf("unexpected unlock response: %+v", unlockResp)
	}

	// 2. J10: Concurrent 2nd request should be rejected (only 1 pending allowed)
	rec = h.signedDeviceRequest(t, http.MethodPost, "/api/v1/device/unlock-requests", reqPayload, devID, devPriv, nil)
	if rec.Code != http.StatusConflict {
		t.Fatalf("concurrent pending unlock should get 409, got %d", rec.Code)
	}

	// 3. Admin approves request -> grant is generated with server Ed25519 signature
	rec = h.adminRequest(t, http.MethodPost, fmt.Sprintf("/api/v1/admin/unlock-requests/%s/decide", reqID), map[string]interface{}{
		"decision": "approve",
		"scope":    "question",
	})
	if rec.Code != http.StatusOK {
		t.Fatalf("admin approve unlock failed: %d %s", rec.Code, rec.Body.String())
	}

	// 4. Tablet polls unlock request status -> receives approved grant
	rec = h.signedDeviceRequest(t, http.MethodGet, fmt.Sprintf("/api/v1/device/unlock-requests/%s?wait=0", reqID), nil, devID, devPriv, nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("poll unlock request failed: %d %s", rec.Code, rec.Body.String())
	}
	var pollResp struct {
		Status string `json:"status"`
		Grant  *struct {
			Payload   crypto.GrantPayload `json:"payload"`
			Signature string              `json:"signature"`
		} `json:"grant"`
	}
	_ = json.Unmarshal(rec.Body.Bytes(), &pollResp)
	if pollResp.Status != "approved" || pollResp.Grant == nil {
		t.Fatalf("expected approved grant, got: %+v", pollResp)
	}

	// 5. Verify server's grant signature
	canonical, err := crypto.CanonicalGrantPayload(pollResp.Grant.Payload)
	if err != nil {
		t.Fatal(err)
	}
	if !crypto.VerifyGrant(h.ServerPub, canonical, pollResp.Grant.Signature) {
		t.Fatal("grant signature failed verification with server public key")
	}

	// 6. J8: Device ACKs the unlock request
	ackBody, _ := json.Marshal(map[string]interface{}{
		"method":   "grant",
		"grant_id": pollResp.Grant.Payload.GrantID,
	})
	rec = h.signedDeviceRequest(t, http.MethodPost, fmt.Sprintf("/api/v1/device/unlock-requests/%s/ack", reqID), ackBody, devID, devPriv, nil)
	if rec.Code != http.StatusOK && rec.Code != http.StatusNoContent {
		t.Fatalf("ack unlock request failed: %d %s", rec.Code, rec.Body.String())
	}
}

// --- Test 4: WP-B-3 Content Safety Alerts & Deduping (H1-H6) ---

func TestM5b_WP_B3_ContentSafetyAlerts(t *testing.T) {
	h, cleanup := newM5bTestHarness(t)
	defer cleanup()

	devPub, devPriv, _ := ed25519.GenerateKey(rand.Reader)
	devID := "dev-safety-test"
	_ = h.Store.CreateDevice(context.Background(), &store.Device{
		ID:        devID,
		FamilyID:  h.FamilyID,
		ChildID:   "child-1",
		Label:     "平板 安全",
		Pubkey:    base64.StdEncoding.EncodeToString(devPub),
		Status:    "active",
		CreatedAt: time.Now().UTC().Format(time.RFC3339),
	})

	// 1. Post emergency alert: snippet is encrypted at rest (H5)
	nowMs := time.Now().UnixMilli()
	alertPayload, _ := json.Marshal(map[string]interface{}{
		"alert_id":    "alert-001",
		"level":       "emergency",
		"category":    "self_harm",
		"session_id":  "sess-001",
		"occurred_at": nowMs,
		"dedupe_key":  "sess-001:self_harm",
		"snippet":     "敏感上下文测试片段两句话",
	})
	rec := h.signedDeviceRequest(t, http.MethodPost, "/api/v1/device/alerts", alertPayload, devID, devPriv, nil)
	if rec.Code != http.StatusAccepted {
		t.Fatalf("post alert failed: %d %s", rec.Code, rec.Body.String())
	}

	// 2. Database check: snippet in DB must NOT match plaintext (encrypted via MasterKey)
	var encryptedSnippet sql.NullString
	err := h.Store.DB.QueryRowContext(context.Background(), "SELECT snippet_enc FROM alerts WHERE id = 'alert-001'").Scan(&encryptedSnippet)
	if err != nil {
		t.Fatal(err)
	}
	if !encryptedSnippet.Valid || encryptedSnippet.String == "敏感上下文测试片段两句话" {
		t.Fatalf("snippet in DB should be encrypted, got: %s", encryptedSnippet.String)
	}

	// 3. Admin reads alert snippet -> snippet is decrypted for parent audit
	rec = h.adminRequest(t, http.MethodGet, "/api/v1/admin/alerts/alert-001/snippet", nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("admin get alert snippet failed: %d %s", rec.Code, rec.Body.String())
	}
	if !strings.Contains(rec.Body.String(), "敏感上下文测试片段两句话") {
		t.Fatalf("admin response should contain decrypted snippet, got %s", rec.Body.String())
	}

	// 4. H3: Deduplication within 30 min window
	rec = h.signedDeviceRequest(t, http.MethodPost, "/api/v1/device/alerts", alertPayload, devID, devPriv, nil)
	if rec.Code != http.StatusAccepted {
		t.Fatalf("deduped alert should still return 202, got %d", rec.Code)
	}
	// Count of alerts in DB should still be 1
	var count int
	_ = h.Store.DB.QueryRowContext(context.Background(), "SELECT COUNT(*) FROM alerts WHERE dedupe_key = 'sess-001:self_harm'").Scan(&count)
	if count != 1 {
		t.Fatalf("expected 1 alert due to deduplication, got %d", count)
	}
}

// --- Test 5: WP-B-4 Study Events, Idempotency & Aggregation (S8, S11, S14) ---

func TestM5b_WP_B4_EventsIdempotencyAndDailyAggregate(t *testing.T) {
	h, cleanup := newM5bTestHarness(t)
	defer cleanup()

	devPub, devPriv, _ := ed25519.GenerateKey(rand.Reader)
	devID := "dev-events-test"
	_ = h.Store.CreateDevice(context.Background(), &store.Device{
		ID:        devID,
		FamilyID:  h.FamilyID,
		ChildID:   "child-ev-1",
		Label:     "平板 事件",
		Pubkey:    base64.StdEncoding.EncodeToString(devPub),
		Status:    "active",
		CreatedAt: time.Now().UTC().Format(time.RFC3339),
	})

	nowMs := time.Now().UnixMilli()
	events := []map[string]interface{}{
		{
			"event_id":    "ev-01",
			"type":        "question_start",
			"session_id":  "sess-1",
			"child_id":    "child-ev-1",
			"device_id":   devID,
			"occurred_at": nowMs,
			"subject":     "数学",
			"payload":     map[string]any{"question_hash": "q1"},
		},
		{
			"event_id":    "ev-02",
			"type":        "hint_used",
			"session_id":  "sess-1",
			"child_id":    "child-ev-1",
			"device_id":   devID,
			"occurred_at": nowMs + 60000,
			"subject":     "数学",
			"payload":     map[string]any{"hints_used": 1},
		},
	}
	batchPayload, _ := json.Marshal(map[string]interface{}{"events": events})
	gzipped := gzipBytes(batchPayload)

	headers := map[string]string{
		"Content-Type":     "application/json",
		"Content-Encoding": "gzip",
	}

	// 1. Post batch 1st time
	rec := h.signedDeviceRequest(t, http.MethodPost, "/api/v1/device/events", gzipped, devID, devPriv, headers)
	if rec.Code != http.StatusOK && rec.Code != http.StatusAccepted {
		t.Fatalf("events post 1 failed: %d %s", rec.Code, rec.Body.String())
	}

	// 2. S8: Post same batch 2nd and 3rd time (idempotency check)
	h.signedDeviceRequest(t, http.MethodPost, "/api/v1/device/events", gzipped, devID, devPriv, headers)
	h.signedDeviceRequest(t, http.MethodPost, "/api/v1/device/events", gzipped, devID, devPriv, headers)

	var evCount int
	_ = h.Store.DB.QueryRowContext(context.Background(), "SELECT COUNT(*) FROM study_event WHERE child_id = 'child-ev-1'").Scan(&evCount)
	if evCount != 2 {
		t.Fatalf("S8 failure: duplicate events inserted, expected 2 got %d", evCount)
	}

	// 3. S11: Verify daily aggregate was created
	var aggQuestions int
	err := h.Store.DB.QueryRowContext(context.Background(), "SELECT questions_started FROM daily_aggregate WHERE child_id = 'child-ev-1'").Scan(&aggQuestions)
	if err != nil || aggQuestions != 1 {
		t.Fatalf("daily aggregate questions_started expected 1, got %d (err: %v)", aggQuestions, err)
	}

	// 4. S14/P5: GET /api/v1/device/reports must NEVER return parent_advice_json
	// Insert dummy report with parent_advice_json
	_, _ = h.Store.DB.ExecContext(context.Background(), `
		INSERT INTO report (id, family_id, child_id, period_type, period_start, period_end, summary_json, parent_advice_json, created_at)
		VALUES ('rep-1', ?, 'child-ev-1', 'daily', '2026-10-30', '2026-10-30', '{"summary":"良好"}', '{"advice":"千万不要看答案"}', CURRENT_TIMESTAMP)
	`, h.FamilyID)

	rec = h.signedDeviceRequest(t, http.MethodGet, "/api/v1/device/reports", nil, devID, devPriv, nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("get device reports failed: %d %s", rec.Code, rec.Body.String())
	}
	if strings.Contains(rec.Body.String(), "parent_advice_json") || strings.Contains(rec.Body.String(), "千万不要看答案") {
		t.Fatalf("P5/S14 failure: device reports leaked parent_advice_json: %s", rec.Body.String())
	}
}

// --- Test 6: WP-B-6 Device Backup & Retention (§19.6) ---

func TestM5b_WP_B6_BackupRetentionAndMagicBytes(t *testing.T) {
	h, cleanup := newM5bTestHarness(t)
	defer cleanup()

	devPub, devPriv, _ := ed25519.GenerateKey(rand.Reader)
	devID := "dev-backup-test"
	_ = h.Store.CreateDevice(context.Background(), &store.Device{
		ID:        devID,
		FamilyID:  h.FamilyID,
		ChildID:   "child-bk-1",
		Label:     "平板 备份",
		Pubkey:    base64.StdEncoding.EncodeToString(devPub),
		Status:    "active",
		CreatedAt: time.Now().UTC().Format(time.RFC3339),
	})

	// 1. Non-K12B payload is rejected
	badPayload := []byte("INVALID_MAGIC_HEADER_TEST")
	rec := h.signedDeviceRequest(t, http.MethodPut, "/api/v1/device/backups", badPayload, devID, devPriv, map[string]string{
		"Content-Type": "application/octet-stream",
	})
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("bad magic should get 400, got %d", rec.Code)
	}

	// 2. Valid K12B payload accepted
	validPayload := append([]byte{0x4B, 0x31, 0x32, 0x42}, []byte("valid-backup-ciphertext-bytes-here")...)
	hash := sha256.Sum256(validPayload)
	hashHex := hex.EncodeToString(hash[:])
	rec = h.signedDeviceRequest(t, http.MethodPut, "/api/v1/device/backups", validPayload, devID, devPriv, map[string]string{
		"Content-Type":         "application/octet-stream",
		"X-K12-Content-Sha256": hashHex,
		"X-K12-Kdf-Params":     `{"kdf":"pbkdf2"}`,
	})
	if rec.Code != http.StatusNoContent {
		t.Fatalf("valid backup should get 204, got %d %s", rec.Code, rec.Body.String())
	}

	// 3. Retrieve latest backup
	rec = h.signedDeviceRequest(t, http.MethodGet, "/api/v1/device/backups/latest", nil, devID, devPriv, nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("get latest backup failed: %d", rec.Code)
	}
	if !bytes.Equal(rec.Body.Bytes(), validPayload) {
		t.Fatal("retrieved backup does not match uploaded payload")
	}
}

// --- Test 7: Report Signed Links (S16, S17) ---

func TestM5b_SignedReportLinks(t *testing.T) {
	h, cleanup := newM5bTestHarness(t)
	defer cleanup()

	// 1. Admin generates report and signed link
	rec := h.adminRequest(t, http.MethodPost, "/api/v1/admin/reports/generate", map[string]interface{}{
		"child_id": "child-rep-1",
		"date":     "2026-10-30",
	})
	if rec.Code != http.StatusOK {
		t.Fatalf("generate briefing failed: %d %s", rec.Code, rec.Body.String())
	}
	var genResp struct {
		ReportID  string `json:"report_id"`
		SignedURL string `json:"signed_url"`
		LinkID    string `json:"link_id"`
		Exp       int64  `json:"exp"`
		Sig       string `json:"sig"`
	}
	_ = json.Unmarshal(rec.Body.Bytes(), &genResp)
	if genResp.SignedURL == "" || genResp.Sig == "" {
		t.Fatalf("invalid report link response: %+v", genResp)
	}

	// 2. Access signed link via public route
	req := httptest.NewRequest(http.MethodGet, fmt.Sprintf("/r/%s?exp=%d&sig=%s", genResp.LinkID, genResp.Exp, genResp.Sig), nil)
	rec = httptest.NewRecorder()
	h.DeviceServer.Handler().ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("public access to signed report failed: %d %s", rec.Code, rec.Body.String())
	}
	html := rec.Body.String()
	if !strings.Contains(html, "noindex") || !strings.Contains(html, "no-referrer") {
		t.Fatal("signed report HTML missing required security headers (noindex, no-referrer)")
	}

	// 3. Tampered signature rejected (renders expired/invalid page with 404)
	req = httptest.NewRequest(http.MethodGet, fmt.Sprintf("/r/%s?exp=%d&sig=%s", genResp.LinkID, genResp.Exp, genResp.Sig+"bad"), nil)
	rec = httptest.NewRecorder()
	h.DeviceServer.Handler().ServeHTTP(rec, req)
	if rec.Code != http.StatusNotFound && rec.Code != http.StatusForbidden {
		t.Fatalf("tampered sig should get 404 or 403, got %d", rec.Code)
	}

	// 4. Expired signature rejected
	req = httptest.NewRequest(http.MethodGet, fmt.Sprintf("/r/%s?exp=%d&sig=%s", genResp.LinkID, time.Now().Add(-time.Hour).Unix(), genResp.Sig), nil)
	rec = httptest.NewRecorder()
	h.DeviceServer.Handler().ServeHTTP(rec, req)
	if rec.Code != http.StatusNotFound && rec.Code != http.StatusForbidden {
		t.Fatalf("expired link should get 404 or 403, got %d", rec.Code)
	}

	// 5. Revoked link rejected
	_ = h.adminRequest(t, http.MethodPost, fmt.Sprintf("/api/v1/admin/reports/%s/links/%s/revoke", genResp.ReportID, genResp.LinkID), nil)
	req = httptest.NewRequest(http.MethodGet, fmt.Sprintf("/r/%s?exp=%d&sig=%s", genResp.LinkID, genResp.Exp, genResp.Sig), nil)
	rec = httptest.NewRecorder()
	h.DeviceServer.Handler().ServeHTTP(rec, req)
	if rec.Code != http.StatusNotFound {
		t.Fatalf("revoked link should get 404, got %d", rec.Code)
	}
}
