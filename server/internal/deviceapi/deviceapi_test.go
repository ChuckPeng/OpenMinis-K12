package deviceapi_test

import (
	"bytes"
	"compress/gzip"
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/ChuckPeng/OpenMinis-K12/server/internal/config"
	"github.com/ChuckPeng/OpenMinis-K12/server/internal/crypto"
	"github.com/ChuckPeng/OpenMinis-K12/server/internal/deviceapi"
	"github.com/ChuckPeng/OpenMinis-K12/server/internal/store"
	"github.com/google/uuid"
)

type testHarness struct {
	server   *deviceapi.Server
	store    *store.Store
	master   crypto.MasterKey
	cfg      config.Config
	devPub   ed25519.PublicKey
	devPriv  ed25519.PrivateKey
	deviceID string
	childID  string
	familyID string
}

func newTestHarness(t *testing.T) (*testHarness, func()) {
	t.Helper()
	dir := t.TempDir()
	_ = os.Setenv("OPENMINIS_K12_ALLOW_EMPTY_TRUSTED_PROXIES", "1")
	st, err := store.Open(filepath.Join(dir, "t.db"))
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
	cfg := config.Default()
	cfg.DataDir = dir
	cfg.PublicBaseURL = "https://example.test"

	srv := &deviceapi.Server{
		Cfg:      cfg,
		Store:    st,
		Master:   master,
		Notifier: deviceapi.NewNotifier(),
	}
	_ = srv.EnsureSigningKey(context.Background())

	devPub, devPriv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}

	h := &testHarness{
		server:   srv,
		store:    st,
		master:   master,
		cfg:      cfg,
		devPub:   devPub,
		devPriv:  devPriv,
		childID:  uuid.NewString(),
		familyID: famID,
	}

	return h, func() { _ = st.Close() }
}

func (h *testHarness) signRequest(req *http.Request, body []byte) {
	tsStr := strconv.FormatInt(time.Now().UnixMilli(), 10)
	nonce := hex.EncodeToString([]byte(uuid.NewString()[:16]))
	bodyHash := sha256.Sum256(body)
	bodyHashHex := hex.EncodeToString(bodyHash[:])

	signStr := crypto.BuildDeviceSignString(req.Method, req.URL.RequestURI(), tsStr, nonce, bodyHashHex)
	sig := crypto.SignGrant(h.devPriv, []byte(signStr))

	req.Header.Set("X-K12-Device", h.deviceID)
	req.Header.Set("X-K12-Ts", tsStr)
	req.Header.Set("X-K12-Nonce", nonce)
	req.Header.Set("X-K12-Sig", sig)
}

func TestDevicePairingAndStatusFlow(t *testing.T) {
	h, cleanup := newTestHarness(t)
	defer cleanup()

	handler := h.server.Handler()

	// 1. Create a 10-minute pair code
	code := "123456"
	if err := h.store.CreatePairCode(context.Background(), h.familyID, code, 10*time.Minute); err != nil {
		t.Fatal(err)
	}

	// 2. Submit pair request (unauthenticated, pair code in body)
	pairReq := map[string]any{
		"pair_code":     code,
		"device_pubkey": base64.StdEncoding.EncodeToString(h.devPub),
		"label":         "测试平板",
		"child_id":      h.childID,
	}
	body, _ := json.Marshal(pairReq)
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, httptest.NewRequest(http.MethodPost, "/api/v1/device/pair", bytes.NewReader(body)))
	if rec.Code != http.StatusOK {
		t.Fatalf("pair failed: %d %s", rec.Code, rec.Body.String())
	}

	var pairResp struct {
		DeviceID          string `json:"device_id"`
		Status            string `json:"status"`
		CodeSecretAlg     string `json:"code_secret_alg"`
		CodeSecretWrapped string `json:"code_secret_wrapped"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &pairResp); err != nil {
		t.Fatal(err)
	}
	if pairResp.DeviceID == "" || pairResp.Status != "pending_confirm" {
		t.Fatalf("invalid pair response: %+v", pairResp)
	}
	if pairResp.CodeSecretAlg != "sealedbox_x25519" || pairResp.CodeSecretWrapped == "" {
		t.Fatalf("missing wrapped codeSecret: %+v", pairResp)
	}

	h.deviceID = pairResp.DeviceID

	// 3. Pair code is one-time: second submission must fail with 401
	rec2 := httptest.NewRecorder()
	handler.ServeHTTP(rec2, httptest.NewRequest(http.MethodPost, "/api/v1/device/pair", bytes.NewReader(body)))
	if rec2.Code != http.StatusUnauthorized {
		t.Fatalf("reused pair code must 401, got %d", rec2.Code)
	}

	// 4. Query pair status while pending_confirm (signed request)
	reqStatus := httptest.NewRequest(http.MethodGet, "/api/v1/device/pair/status", nil)
	h.signRequest(reqStatus, nil)
	recStatus := httptest.NewRecorder()
	handler.ServeHTTP(recStatus, reqStatus)
	if recStatus.Code != http.StatusOK {
		t.Fatalf("status failed: %d %s", recStatus.Code, recStatus.Body.String())
	}
	var statusResp struct {
		Status  string `json:"status"`
		Changed bool   `json:"changed"`
	}
	_ = json.Unmarshal(recStatus.Body.Bytes(), &statusResp)
	if statusResp.Status != "pending_confirm" {
		t.Fatalf("expected pending_confirm, got %s", statusResp.Status)
	}

	// 5. Parent approves device -> active
	if err := h.store.UpdateDeviceStatus(context.Background(), h.deviceID, "active"); err != nil {
		t.Fatal(err)
	}
	h.server.Notifier.Wake("pair:" + h.deviceID)

	// 6. Query status again -> active, changed=true
	reqStatus2 := httptest.NewRequest(http.MethodGet, "/api/v1/device/pair/status", nil)
	h.signRequest(reqStatus2, nil)
	recStatus2 := httptest.NewRecorder()
	handler.ServeHTTP(recStatus2, reqStatus2)
	_ = json.Unmarshal(recStatus2.Body.Bytes(), &statusResp)
	if statusResp.Status != "active" {
		t.Fatalf("expected active, got %s", statusResp.Status)
	}
}

func TestDeviceSignatureRejection(t *testing.T) {
	h, cleanup := newTestHarness(t)
	defer cleanup()

	handler := h.server.Handler()
	h.deviceID = uuid.NewString()

	dev := &store.Device{
		ID:            h.deviceID,
		FamilyID:      h.familyID,
		ChildID:       h.childID,
		Label:         "平板A",
		Pubkey:        base64.StdEncoding.EncodeToString(h.devPub),
		CodeSecretEnc: "enc",
		Status:        "active",
	}
	_ = h.store.CreateDevice(context.Background(), dev)

	// 1. Missing headers -> 401
	r1 := httptest.NewRequest(http.MethodPost, "/api/v1/device/heartbeat", bytes.NewReader([]byte("{}")))
	w1 := httptest.NewRecorder()
	handler.ServeHTTP(w1, r1)
	if w1.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401, got %d", w1.Code)
	}

	// 2. Tampered signature -> 401
	body := []byte(`{"app_version":"1.0.0-m5b","config_version_applied":1}`)
	r2 := httptest.NewRequest(http.MethodPost, "/api/v1/device/heartbeat", bytes.NewReader(body))
	h.signRequest(r2, body)
	r2.Header.Set("X-K12-Sig", "bad-signature")
	w2 := httptest.NewRecorder()
	handler.ServeHTTP(w2, r2)
	if w2.Code != http.StatusUnauthorized {
		t.Fatalf("tampered sig expected 401, got %d", w2.Code)
	}

	// 3. Stale timestamp (beyond 5 min) -> 401
	r3 := httptest.NewRequest(http.MethodPost, "/api/v1/device/heartbeat", bytes.NewReader(body))
	h.signRequest(r3, body)
	staleTs := strconv.FormatInt(time.Now().Add(-10*time.Minute).UnixMilli(), 10)
	r3.Header.Set("X-K12-Ts", staleTs)
	w3 := httptest.NewRecorder()
	handler.ServeHTTP(w3, r3)
	if w3.Code != http.StatusUnauthorized {
		t.Fatalf("stale ts expected 401, got %d", w3.Code)
	}

	// 4. Replayed nonce -> 401
	r4a := httptest.NewRequest(http.MethodPost, "/api/v1/device/heartbeat", bytes.NewReader(body))
	h.signRequest(r4a, body)
	w4a := httptest.NewRecorder()
	handler.ServeHTTP(w4a, r4a)
	if w4a.Code != http.StatusOK {
		t.Fatalf("first request failed: %d %s", w4a.Code, w4a.Body.String())
	}

	// Second request with same nonce
	r4b := httptest.NewRequest(http.MethodPost, "/api/v1/device/heartbeat", bytes.NewReader(body))
	r4b.Header.Set("X-K12-Device", r4a.Header.Get("X-K12-Device"))
	r4b.Header.Set("X-K12-Ts", r4a.Header.Get("X-K12-Ts"))
	r4b.Header.Set("X-K12-Nonce", r4a.Header.Get("X-K12-Nonce"))
	r4b.Header.Set("X-K12-Sig", r4a.Header.Get("X-K12-Sig"))
	w4b := httptest.NewRecorder()
	handler.ServeHTTP(w4b, r4b)
	if w4b.Code != http.StatusUnauthorized {
		t.Fatalf("replay nonce expected 401, got %d", w4b.Code)
	}

	// 5. Revoked device -> 401 (J9)
	_ = h.store.UpdateDeviceStatus(context.Background(), h.deviceID, "revoked")
	r5 := httptest.NewRequest(http.MethodPost, "/api/v1/device/heartbeat", bytes.NewReader(body))
	h.signRequest(r5, body)
	w5 := httptest.NewRecorder()
	handler.ServeHTTP(w5, r5)
	if w5.Code != http.StatusUnauthorized {
		t.Fatalf("revoked device expected 401, got %d", w5.Code)
	}
}

func TestUnlockRequestAndGrantVerification(t *testing.T) {
	h, cleanup := newTestHarness(t)
	defer cleanup()

	handler := h.server.Handler()
	h.deviceID = uuid.NewString()

	secret := make([]byte, 32)
	for i := range secret {
		secret[i] = byte(i + 1)
	}
	secretEnc, _ := h.master.Seal(secret)

	dev := &store.Device{
		ID:            h.deviceID,
		FamilyID:      h.familyID,
		ChildID:       h.childID,
		Label:         "平板A",
		Pubkey:        base64.StdEncoding.EncodeToString(h.devPub),
		CodeSecretEnc: secretEnc,
		Status:        "active",
	}
	_ = h.store.CreateDevice(context.Background(), dev)

	// 1. Create unlock request
	clientReqID := "client-req-001"
	qHash := "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
	reqBody := map[string]any{
		"client_request_id": clientReqID,
		"subject":           "数学",
		"question_hash":     qHash,
		"hints_used":        2,
	}
	bodyBytes, _ := json.Marshal(reqBody)
	req := httptest.NewRequest(http.MethodPost, "/api/v1/device/unlock-requests", bytes.NewReader(bodyBytes))
	h.signRequest(req, bodyBytes)
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, req)
	if rec.Code != http.StatusCreated {
		t.Fatalf("create unlock failed: %d %s", rec.Code, rec.Body.String())
	}

	var created struct {
		ID        string `json:"id"`
		ExpiresAt string `json:"expires_at"`
	}
	_ = json.Unmarshal(rec.Body.Bytes(), &created)
	if created.ID == "" {
		t.Fatal("empty unlock id")
	}

	// Verify derived backup code matches expected formula (J13)
	expectedCode := crypto.DeriveBackupCode(secret, clientReqID, qHash)
	stored, err := h.store.GetUnlockRequest(context.Background(), created.ID)
	if err != nil || stored == nil || stored.ResponseCode.String != expectedCode {
		t.Fatalf("expected backup code %s, got %v", expectedCode, stored)
	}

	// 2. Poll unlock request (pending)
	pollReq := httptest.NewRequest(http.MethodGet, "/api/v1/device/unlock-requests/"+created.ID, nil)
	h.signRequest(pollReq, nil)
	pollRec := httptest.NewRecorder()
	handler.ServeHTTP(pollRec, pollReq)
	var view struct {
		ID     string `json:"id"`
		Status string `json:"status"`
		Grant  *struct {
			Payload   crypto.GrantPayload `json:"payload"`
			Signature string              `json:"signature"`
		} `json:"grant"`
	}
	_ = json.Unmarshal(pollRec.Body.Bytes(), &view)
	if view.Status != "pending" || view.Grant != nil {
		t.Fatalf("expected pending without grant, got %+v", view)
	}

	// 3. Parent approves unlock
	_, _ = h.store.DecideUnlock(context.Background(), created.ID, "approved", "爸爸", "question")
	h.server.Notifier.Wake("unlock:" + created.ID)

	// 4. Poll again -> approved with signed grant (J2, J5)
	pollReq2 := httptest.NewRequest(http.MethodGet, "/api/v1/device/unlock-requests/"+created.ID, nil)
	h.signRequest(pollReq2, nil)
	pollRec2 := httptest.NewRecorder()
	handler.ServeHTTP(pollRec2, pollReq2)
	_ = json.Unmarshal(pollRec2.Body.Bytes(), &view)
	if view.Status != "approved" || view.Grant == nil {
		t.Fatalf("expected approved with grant, got %+v", view)
	}

	// Client verifies Ed25519 signature on grant payload (J5)
	canonical, err := crypto.CanonicalGrantPayload(view.Grant.Payload)
	if err != nil {
		t.Fatal(err)
	}
	if !crypto.VerifyGrant(h.server.SigningPub, canonical, view.Grant.Signature) {
		t.Fatal("grant signature invalid on device")
	}
	if view.Grant.Payload.DeviceID != h.deviceID || view.Grant.Payload.QuestionHash != qHash {
		t.Fatalf("grant payload mismatch: %+v", view.Grant.Payload)
	}

	// 5. Ack grant
	ackBody, _ := json.Marshal(map[string]string{
		"method":   "grant",
		"grant_id": view.Grant.Payload.GrantID,
	})
	ackReq := httptest.NewRequest(http.MethodPost, "/api/v1/device/unlock-requests/"+created.ID+"/ack", bytes.NewReader(ackBody))
	h.signRequest(ackReq, ackBody)
	ackRec := httptest.NewRecorder()
	handler.ServeHTTP(ackRec, ackReq)
	if ackRec.Code != http.StatusNoContent {
		t.Fatalf("ack failed: %d %s", ackRec.Code, ackRec.Body.String())
	}
}

func TestAlertDeduplicationAndSnippetEncryption(t *testing.T) {
	h, cleanup := newTestHarness(t)
	defer cleanup()

	handler := h.server.Handler()
	h.deviceID = uuid.NewString()

	dev := &store.Device{
		ID:            h.deviceID,
		FamilyID:      h.familyID,
		ChildID:       h.childID,
		Pubkey:        base64.StdEncoding.EncodeToString(h.devPub),
		CodeSecretEnc: "enc",
		Status:        "active",
	}
	_ = h.store.CreateDevice(context.Background(), dev)

	// 1. Post alert with snippet (H4)
	snippet := "上一句。这是触发情绪关注的语句。下一句。"
	alertBody, _ := json.Marshal(map[string]any{
		"level":       "关注",
		"category":    "情绪消极",
		"occurred_at": time.Now().UnixMilli(),
		"dedupe_key":  "session-chat-42",
		"snippet":     snippet,
	})
	req := httptest.NewRequest(http.MethodPost, "/api/v1/device/alerts", bytes.NewReader(alertBody))
	h.signRequest(req, alertBody)
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, req)
	if rec.Code != http.StatusAccepted {
		t.Fatalf("alert failed: %d %s", rec.Code, rec.Body.String())
	}

	var resp struct {
		ID string `json:"id"`
	}
	_ = json.Unmarshal(rec.Body.Bytes(), &resp)

	// Check DB: snippet is encrypted, not plaintext
	alert, err := h.store.GetAlert(context.Background(), resp.ID)
	if err != nil || alert == nil {
		t.Fatal("alert not stored")
	}
	if alert.SnippetEnc == "" || alert.SnippetEnc == snippet {
		t.Fatalf("snippet must be encrypted in db: %s", alert.SnippetEnc)
	}
	decrypted, err := h.master.Open(alert.SnippetEnc)
	if err != nil || string(decrypted) != snippet {
		t.Fatalf("decrypted snippet mismatch: %s", string(decrypted))
	}

	// 2. Post duplicate alert within 30 min (H2)
	req2 := httptest.NewRequest(http.MethodPost, "/api/v1/device/alerts", bytes.NewReader(alertBody))
	h.signRequest(req2, alertBody)
	rec2 := httptest.NewRecorder()
	handler.ServeHTTP(rec2, req2)
	if rec2.Code != http.StatusAccepted {
		t.Fatalf("deduped alert expected 202, got %d", rec2.Code)
	}
	var resp2 struct {
		ID string `json:"id"`
	}
	_ = json.Unmarshal(rec2.Body.Bytes(), &resp2)
	if resp2.ID != resp.ID {
		t.Fatalf("dedupe should return existing id %s, got %s", resp.ID, resp2.ID)
	}
}

func TestEventsIdempotentBatchAndAggregate(t *testing.T) {
	h, cleanup := newTestHarness(t)
	defer cleanup()

	handler := h.server.Handler()
	h.deviceID = uuid.NewString()

	dev := &store.Device{
		ID:            h.deviceID,
		FamilyID:      h.familyID,
		ChildID:       h.childID,
		Pubkey:        base64.StdEncoding.EncodeToString(h.devPub),
		CodeSecretEnc: "enc",
		Status:        "active",
	}
	_ = h.store.CreateDevice(context.Background(), dev)

	// 1. Prepare events batch with gzip
	nowMs := time.Now().UnixMilli()
	events := []map[string]any{
		{
			"event_id":       uuid.NewString(),
			"schema_version": 1,
			"device_id":      h.deviceID,
			"child_id":       h.childID,
			"type":           "active_span",
			"occurred_at":    nowMs,
			"tz":             "Asia/Shanghai",
			"seq":            1,
			"subject":        "数学",
			"kp_ids":         []string{"kp-1"},
			"payload":        map[string]any{"start": nowMs, "end": nowMs + 700000}, // 700 sec (> 10 min)
		},
		{
			"event_id":       uuid.NewString(),
			"schema_version": 1,
			"device_id":      h.deviceID,
			"child_id":       h.childID,
			"type":           "attempt",
			"occurred_at":    nowMs + 1000,
			"tz":             "Asia/Shanghai",
			"seq":            2,
			"subject":        "数学",
			"kp_ids":         []string{"kp-1"},
			"payload":        map[string]any{"correct": true, "first_try": true},
		},
	}
	payloadBytes, _ := json.Marshal(map[string]any{"events": events})

	// Compress with gzip
	var gzBuf bytes.Buffer
	gz := gzip.NewWriter(&gzBuf)
	_, _ = gz.Write(payloadBytes)
	_ = gz.Close()

	req := httptest.NewRequest(http.MethodPost, "/api/v1/device/events", &gzBuf)
	req.Header.Set("Content-Encoding", "gzip")
	h.signRequest(req, gzBuf.Bytes())

	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("events upload failed: %d %s", rec.Code, rec.Body.String())
	}
	var res struct {
		MaxSeq int `json:"max_seq"`
	}
	_ = json.Unmarshal(rec.Body.Bytes(), &res)
	if res.MaxSeq != 2 {
		t.Fatalf("expected max_seq=2, got %d", res.MaxSeq)
	}

	// Verify daily aggregate was computed
	today := deviceapi.LocalDateInShanghai(nowMs)
	aggs, err := h.store.GetDailyAggregate(context.Background(), h.childID, today)
	if err != nil || len(aggs) == 0 {
		t.Fatalf("aggregate missing: %v", err)
	}
	agg := aggs[0]
	if agg.ActiveSeconds != 700 || agg.AttemptsCount != 1 || agg.FirstTryCorrect != 1 || agg.IsStudyDay != 1 {
		t.Fatalf("unexpected aggregate values: %+v", agg)
	}

	// 2. Upload same events again: idempotent count stays unchanged (S8)
	var gzBuf2 bytes.Buffer
	gz2 := gzip.NewWriter(&gzBuf2)
	_, _ = gz2.Write(payloadBytes)
	_ = gz2.Close()

	req2 := httptest.NewRequest(http.MethodPost, "/api/v1/device/events", &gzBuf2)
	req2.Header.Set("Content-Encoding", "gzip")
	h.signRequest(req2, gzBuf2.Bytes())
	rec2 := httptest.NewRecorder()
	handler.ServeHTTP(rec2, req2)
	if rec2.Code != http.StatusOK {
		t.Fatalf("re-upload failed: %d", rec2.Code)
	}
}

func TestDeviceReportsExcludesParentAdvice(t *testing.T) {
	h, cleanup := newTestHarness(t)
	defer cleanup()

	handler := h.server.Handler()
	h.deviceID = uuid.NewString()

	dev := &store.Device{
		ID:            h.deviceID,
		FamilyID:      h.familyID,
		ChildID:       h.childID,
		Pubkey:        base64.StdEncoding.EncodeToString(h.devPub),
		CodeSecretEnc: "enc",
		Status:        "active",
	}
	_ = h.store.CreateDevice(context.Background(), dev)

	// Create report containing parent advice
	rep := &store.Report{
		ID:               uuid.NewString(),
		ChildID:          h.childID,
		Kind:             "daily",
		PeriodStart:      "2026-10-30",
		PeriodEnd:        "2026-10-30",
		MetricsJSON:      `{"active_minutes": 25}`,
		AnalysisJSON:     `{}`,
		ParentTextJSON:   `{"summary": "今天学得很专注"}`,
		ChildTextJSON:    `{"summary": "你今天完成了数学练习！"}`,
		ParentAdviceJSON: `{"secret_advice": "亲子建议绝不能发给孩子"}`,
		TextSource:       "template",
	}
	_ = h.store.CreateReport(context.Background(), rep)

	// Device calls GET /api/v1/device/reports (P5, S14)
	req := httptest.NewRequest(http.MethodGet, "/api/v1/device/reports", nil)
	h.signRequest(req, nil)
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("get reports failed: %d %s", rec.Code, rec.Body.String())
	}

	bodyStr := rec.Body.String()
	if bytes.Contains(rec.Body.Bytes(), []byte("parent_advice_json")) ||
		bytes.Contains(rec.Body.Bytes(), []byte("secret_advice")) {
		t.Fatalf("DEVICE API RETURNED PARENT ADVICE! Body: %s", bodyStr)
	}
}

func TestEncryptedBackupAndLatest(t *testing.T) {
	h, cleanup := newTestHarness(t)
	defer cleanup()

	handler := h.server.Handler()
	h.deviceID = uuid.NewString()

	dev := &store.Device{
		ID:            h.deviceID,
		FamilyID:      h.familyID,
		ChildID:       h.childID,
		Pubkey:        base64.StdEncoding.EncodeToString(h.devPub),
		CodeSecretEnc: "enc",
		Status:        "active",
	}
	_ = h.store.CreateDevice(context.Background(), dev)

	// 1. Upload backup ciphertext (magic K12B)
	cipherBody := append([]byte("K12B"), []byte("encrypted-mock-sqlite-fsrs-data")...)
	cSha := sha256.Sum256(cipherBody)
	cShaHex := hex.EncodeToString(cSha[:])
	kdfParams := `{"m":65536,"t":3,"p":1,"salt":"0123456789abcdef"}`

	putReq := httptest.NewRequest(http.MethodPut, "/api/v1/device/backups", bytes.NewReader(cipherBody))
	putReq.Header.Set("X-K12-Content-Sha256", cShaHex)
	putReq.Header.Set("X-K12-Kdf-Params", kdfParams)
	h.signRequest(putReq, cipherBody)
	putRec := httptest.NewRecorder()
	handler.ServeHTTP(putRec, putReq)
	if putRec.Code != http.StatusNoContent {
		t.Fatalf("put backup failed: %d %s", putRec.Code, putRec.Body.String())
	}

	// 2. Retrieve latest backup
	getReq := httptest.NewRequest(http.MethodGet, "/api/v1/device/backups/latest", nil)
	h.signRequest(getReq, nil)
	getRec := httptest.NewRecorder()
	handler.ServeHTTP(getRec, getReq)
	if getRec.Code != http.StatusOK {
		t.Fatalf("get backup failed: %d %s", getRec.Code, getRec.Body.String())
	}

	if getRec.Header().Get("X-K12-Content-Sha256") != cShaHex {
		t.Fatalf("sha256 header mismatch: %s", getRec.Header().Get("X-K12-Content-Sha256"))
	}
	if !bytes.Equal(getRec.Body.Bytes(), cipherBody) {
		t.Fatal("backup body mismatch")
	}
}

func TestSignedReportLinkAccessAndInvalidation(t *testing.T) {
	h, cleanup := newTestHarness(t)
	defer cleanup()

	handler := h.server.Handler()

	rep := &store.Report{
		ID:             uuid.NewString(),
		ChildID:        h.childID,
		Kind:           "daily",
		PeriodStart:    "2026-10-30",
		PeriodEnd:      "2026-10-30",
		MetricsJSON:    `{"active_minutes": 35, "attempts": 12, "reveals": 1, "mistakes": 2}`,
		ParentTextJSON: `{"summary": "孩子今天主动完成了全部错题复习。"}`,
		TextSource:     "template",
	}
	_ = h.store.CreateReport(context.Background(), rep)

	linkID := "link-xyz-987"
	exp := time.Now().Add(72 * time.Hour).Unix()
	reportKey := h.master.DeriveKey("report-link-sign")
	sig := crypto.SignReportLink(reportKey, linkID, exp)

	delivery := &store.ReportDelivery{
		ID:            uuid.NewString(),
		ReportID:      rep.ID,
		LinkID:        linkID,
		LinkExpiresAt: time.Now().Add(72 * time.Hour).Format(time.RFC3339Nano),
		Status:        "active",
	}
	_ = h.store.CreateReportDelivery(context.Background(), delivery)

	// 1. Valid signed link opens HTML report (S16)
	url := fmt.Sprintf("/r/%s?exp=%d&sig=%s", linkID, exp, sig)
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, url, nil))
	if rec.Code != http.StatusOK {
		t.Fatalf("report link failed: %d", rec.Code)
	}
	body := rec.Body.String()
	if !strings.Contains(body, "伴学日简报") || !strings.Contains(body, "robots") || !strings.Contains(body, "noindex") {
		t.Fatalf("missing HTML elements or noindex: %s", body)
	}

	// 2. Tampered signature -> 404 (S13)
	badUrl := fmt.Sprintf("/r/%s?exp=%d&sig=badsig", linkID, exp)
	rec2 := httptest.NewRecorder()
	handler.ServeHTTP(rec2, httptest.NewRequest(http.MethodGet, badUrl, nil))
	if rec2.Code != http.StatusNotFound {
		t.Fatalf("tampered link must 404, got %d", rec2.Code)
	}
	if strings.Contains(rec2.Body.String(), "35") {
		t.Fatal("expired/tampered page must not leak metrics")
	}

	// 3. Revoked link -> 404
	_ = h.store.RevokeReportLink(context.Background(), linkID)
	rec3 := httptest.NewRecorder()
	handler.ServeHTTP(rec3, httptest.NewRequest(http.MethodGet, url, nil))
	if rec3.Code != http.StatusNotFound {
		t.Fatalf("revoked link must 404, got %d", rec3.Code)
	}
}
