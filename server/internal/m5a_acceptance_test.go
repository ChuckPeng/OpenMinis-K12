package internal_test

import (
	"bytes"
	"context"
	"database/sql"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	neturl "net/url"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/ChuckPeng/OpenMinis-K12/server/internal/admin"
	"github.com/ChuckPeng/OpenMinis-K12/server/internal/auth"
	"github.com/ChuckPeng/OpenMinis-K12/server/internal/config"
	"github.com/ChuckPeng/OpenMinis-K12/server/internal/crypto"
	"github.com/ChuckPeng/OpenMinis-K12/server/internal/deviceapi"
	"github.com/ChuckPeng/OpenMinis-K12/server/internal/httpx"
	"github.com/ChuckPeng/OpenMinis-K12/server/internal/outbox"
	"github.com/ChuckPeng/OpenMinis-K12/server/internal/store"
	"github.com/ChuckPeng/OpenMinis-K12/server/internal/version"
	"github.com/ChuckPeng/OpenMinis-K12/server/internal/wecom"
)

func TestM5aVersionEmbedded(t *testing.T) {
	if version.Version != "1.0.0-m5a" {
		t.Fatalf("Version=%s", version.Version)
	}
	if version.VersionCode != "1000005" {
		t.Fatalf("VersionCode=%s", version.VersionCode)
	}
}

func TestM5aStoreRestartPersists(t *testing.T) {
	dir := t.TempDir()
	dbPath := filepath.Join(dir, "t.db")
	st, err := store.Open(dbPath)
	if err != nil {
		t.Fatal(err)
	}
	fam, err := st.EnsureFamily(context.Background(), "home")
	if err != nil {
		t.Fatal(err)
	}
	req := &store.UnlockRequest{DeviceID: "d1", QuestionHash: strings.Repeat("a", 64), Subject: "数学", TaskID: sql.NullString{String: "task-r4", Valid: true}}
	if err := st.CreateUnlockRequest(context.Background(), req); err != nil {
		t.Fatal(err)
	}
	if err := outbox.EnqueueJSON(context.Background(), st, "app1", "unlock_request", map[string]string{"x": "1"}, nil); err != nil {
		t.Fatal(err)
	}
	_ = fam
	_ = st.Close()

	st2, err := store.Open(dbPath)
	if err != nil {
		t.Fatal(err)
	}
	defer st2.Close()
	got, err := st2.GetUnlockByTaskID(context.Background(), "task-r4")
	if err != nil || got == nil {
		t.Fatalf("unlock missing after reopen: %v", err)
	}
	n, err := st2.CountOutboxPending(context.Background())
	if err != nil || n != 1 {
		t.Fatalf("outbox pending=%d err=%v", n, err)
	}
}

func TestM5aAdminAuthCSRFAndLock(t *testing.T) {
	h, cleanup := newTestAdmin(t)
	defer cleanup()

	// R6: unauthenticated
	res := httptest.NewRecorder()
	h.ServeHTTP(res, httptest.NewRequest(http.MethodGet, "/api/v1/admin/audit", nil))
	if res.Code != 401 {
		t.Fatalf("want 401 got %d", res.Code)
	}

	mustSetup(t, h, "admin", "correcthorse1")
	csrf, jar := mustLogin(t, h, "admin", "correcthorse1")

	// Missing CSRF on write
	req := httptest.NewRequest(http.MethodPost, "/api/v1/admin/logout", bytes.NewBufferString("{}"))
	req.Header.Set("Content-Type", "application/json")
	for _, c := range jar {
		req.AddCookie(c)
	}
	res = httptest.NewRecorder()
	h.ServeHTTP(res, req)
	if res.Code != 403 {
		t.Fatalf("csrf want 403 got %d", res.Code)
	}

	// Lock after 5 failures
	for i := 0; i < 5; i++ {
		r := httptest.NewRequest(http.MethodPost, "/api/v1/admin/login", bytes.NewBufferString(`{"username":"admin","password":"wrongpassword1"}`))
		r.Header.Set("Content-Type", "application/json")
		rr := httptest.NewRecorder()
		h.ServeHTTP(rr, r)
	}
	r := httptest.NewRequest(http.MethodPost, "/api/v1/admin/login", bytes.NewBufferString(`{"username":"admin","password":"wrongpassword1"}`))
	r.Header.Set("Content-Type", "application/json")
	rr := httptest.NewRecorder()
	h.ServeHTTP(rr, r)
	if rr.Code != 429 {
		t.Fatalf("want lock 429 got %d body=%s", rr.Code, rr.Body.String())
	}
	_ = csrf
}

func TestM5aDevicePortAdmin404(t *testing.T) {
	dir := t.TempDir()
	st, err := store.Open(filepath.Join(dir, "t.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	dev := (&deviceapi.Server{Store: st}).Handler()
	res := httptest.NewRecorder()
	dev.ServeHTTP(res, httptest.NewRequest(http.MethodGet, "/api/v1/admin/audit", nil))
	if res.Code != 404 {
		t.Fatalf("R7 want 404 got %d", res.Code)
	}
	res = httptest.NewRecorder()
	dev.ServeHTTP(res, httptest.NewRequest(http.MethodGet, "/metrics", nil))
	if res.Code != 404 {
		t.Fatalf("metrics on device port want 404 got %d", res.Code)
	}
}

func TestM5aJ12HarnessAndFallbackCode(t *testing.T) {
	h, cleanup := newTestAdmin(t)
	defer cleanup()
	mustSetup(t, h, "admin", "correcthorse1")
	csrf, jar := mustLogin(t, h, "admin", "correcthorse1")

	// Save wecom app + approver parent
	body := map[string]any{
		"corpid": "wwSampleCorp", "agentid": 1000002, "token": "tok",
		"secret": "sec", "encoding_aes_key": "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG",
		"api_base_url": "http://127.0.0.1:9", "enabled": true,
	}
	b, _ := json.Marshal(body)
	req := authed(http.MethodPut, "/api/v1/admin/wecom-apps", b, csrf, jar)
	res := httptest.NewRecorder()
	h.ServeHTTP(res, req)
	if res.Code != 200 {
		t.Fatalf("save wecom %d %s", res.Code, res.Body.String())
	}
	var apps []map[string]any
	_ = json.Unmarshal(res.Body.Bytes(), &apps)
	appID := apps[0]["id"].(string)
	cbURL, _ := apps[0]["callback_url"].(string)
	if !strings.Contains(cbURL, "/wecom/callback/") {
		t.Fatalf("callback_url=%q", cbURL)
	}

	pb, _ := json.Marshal(map[string]any{"label": "家长A", "wecom_userid": "parent_a", "can_approve": true})
	req = authed(http.MethodPost, "/api/v1/admin/parents", pb, csrf, jar)
	res = httptest.NewRecorder()
	h.ServeHTTP(res, req)
	if res.Code != 201 {
		t.Fatalf("parent %d %s", res.Code, res.Body.String())
	}

	// Mark buttons unavailable → fallback path (J14)
	req = authed(http.MethodPost, "/api/v1/admin/wecom-apps/"+appID+"/mark-button-callback", []byte(`{"ok":false}`), csrf, jar)
	res = httptest.NewRecorder()
	h.ServeHTTP(res, req)

	req = authed(http.MethodPost, "/api/v1/admin/j12/fallback-code", []byte(`{"subject":"数学"}`), csrf, jar)
	res = httptest.NewRecorder()
	h.ServeHTTP(res, req)
	if res.Code != 201 {
		t.Fatalf("fallback create %d %s", res.Code, res.Body.String())
	}
	var created map[string]any
	_ = json.Unmarshal(res.Body.Bytes(), &created)
	if created["use_buttons"] != false {
		t.Fatalf("expected use_buttons false, got %#v", created["use_buttons"])
	}
	code := created["response_code"].(string)
	taskID := created["task_id"].(string)
	if len(code) != 6 {
		t.Fatalf("code=%q", code)
	}

	dir := t.TempDir()
	// Use the same store via simulate API for button path after marking ok
	req = authed(http.MethodPost, "/api/v1/admin/wecom-apps/"+appID+"/mark-button-callback", []byte(`{"ok":true}`), csrf, jar)
	res = httptest.NewRecorder()
	h.ServeHTTP(res, req)

	req = authed(http.MethodPost, "/api/v1/admin/j12/simulate", mustJSON(map[string]any{
		"app_id": appID, "from_userid": "parent_a", "event_key": "approve_question", "task_id": taskID,
	}), csrf, jar)
	res = httptest.NewRecorder()
	h.ServeHTTP(res, req)
	if res.Code != 200 {
		t.Fatalf("simulate %d %s", res.Code, res.Body.String())
	}

	// Second request: approve via backup code path (text fallback)
	req = authed(http.MethodPost, "/api/v1/admin/j12/fallback-code", []byte(`{"subject":"英语"}`), csrf, jar)
	res = httptest.NewRecorder()
	h.ServeHTTP(res, req)
	_ = json.Unmarshal(res.Body.Bytes(), &created)
	code2 := created["response_code"].(string)

	// Direct store-level fallback (same as WeCom text handler)
	st := testStoreFromAdmin(t, h)
	appsList, _ := st.ListWecomApps(context.Background())
	if err := wecom.ApproveByBackupCode(context.Background(), st, &appsList[0], "parent_a", code2); err != nil {
		t.Fatal(err)
	}
	u, _ := st.GetUnlockByResponseCode(context.Background(), code2)
	// already approved so GetUnlockByResponseCode filters pending — fetch by id
	u2, _ := st.GetUnlockRequest(context.Background(), created["id"].(string))
	if u2 == nil || u2.Status != "approved" {
		t.Fatalf("backup approve status=%v u=%v", u2, u)
	}
	_ = dir
}

func TestM5aOutboxDropsExpiredUnlock(t *testing.T) {
	dir := t.TempDir()
	st, err := store.Open(filepath.Join(dir, "t.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	exp := time.Now().UTC().Add(-time.Minute)
	if err := outbox.EnqueueJSON(context.Background(), st, "a", "unlock_request", map[string]string{"k": "v"}, &exp); err != nil {
		t.Fatal(err)
	}
	calls := 0
	w := &outbox.Worker{Store: st, Sender: func(ctx context.Context, item store.OutboxItem) error {
		calls++
		return nil
	}, Every: time.Hour}
	w.Tick(context.Background())
	if calls != 0 {
		t.Fatalf("expired unlock must not send, calls=%d", calls)
	}
	n, _ := st.CountOutboxPending(context.Background())
	if n != 0 {
		t.Fatalf("pending=%d", n)
	}
}

func TestM5aWecomCallbackVerifyAndCard(t *testing.T) {
	dir := t.TempDir()
	st, err := store.Open(filepath.Join(dir, "t.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	master, err := crypto.LoadOrCreateMasterKey(filepath.Join(dir, "master.key"))
	if err != nil {
		t.Fatal(err)
	}
	fam, _ := st.EnsureFamily(context.Background(), "home")
	aesKey := "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG"
	encAES, _ := master.Seal([]byte(aesKey))
	app := &store.WecomApp{
		FamilyID: fam, CorpID: "wwSampleCorpId0001", Token: "k12PublicSampleToken",
		AESKeyEnc: encAES, CallbackKey: "cbkey1", Enabled: true, AgentID: 1,
	}
	if err := st.UpsertWecomApp(context.Background(), app); err != nil {
		t.Fatal(err)
	}
	_ = st.CreateParent(context.Background(), &store.Parent{FamilyID: fam, WecomUserID: "parent_a", Label: "家长A", CanApprove: true})
	req := &store.UnlockRequest{DeviceID: "d", QuestionHash: strings.Repeat("b", 64), Subject: "数学", TaskID: sql.NullString{String: "task-cb", Valid: true}}
	_ = st.CreateUnlockRequest(context.Background(), req)

	crypt, err := crypto.NewWXBizMsgCrypt(app.Token, aesKey, app.CorpID)
	if err != nil {
		t.Fatal(err)
	}
	ts, nonce := "1700000001", "n1"
	echoEnc, _ := crypt.EncryptPlain("1615088319")
	echoSig := crypt.Signature(ts, nonce, echoEnc)

	cb := &wecom.CallbackHandler{
		Store: st, Master: master,
		OnCard: func(ctx context.Context, a *store.WecomApp, ev wecom.CardEvent) error {
			return wecom.HandleCardEvent(ctx, st, a, ev)
		},
	}
	dev := (&deviceapi.Server{Store: st, Callback: cb}).Handler()

	q := neturl.Values{}
	q.Set("msg_signature", echoSig)
	q.Set("timestamp", ts)
	q.Set("nonce", nonce)
	q.Set("echostr", echoEnc)
	verifyURL := "/wecom/callback/cbkey1?" + q.Encode()
	res := httptest.NewRecorder()
	dev.ServeHTTP(res, httptest.NewRequest(http.MethodGet, verifyURL, nil))
	if res.Code != 200 || res.Body.String() != "1615088319" {
		t.Fatalf("verify url %d %q", res.Code, res.Body.String())
	}

	plain := `<xml><ToUserName><![CDATA[wwSampleCorpId0001]]></ToUserName><FromUserName><![CDATA[parent_a]]></FromUserName><CreateTime>1700000001</CreateTime><MsgType><![CDATA[event]]></MsgType><Event><![CDATA[template_card_event]]></Event><EventKey><![CDATA[approve_question]]></EventKey><TaskId><![CDATA[task-cb]]></TaskId></xml>`
	encXML, err := crypt.EncryptMsg(plain, ts, nonce)
	if err != nil {
		t.Fatal(err)
	}
	enc, _ := extractEncryptForTest(encXML)
	sig := crypt.Signature(ts, nonce, enc)
	q2 := neturl.Values{}
	q2.Set("msg_signature", sig)
	q2.Set("timestamp", ts)
	q2.Set("nonce", nonce)
	postURL := "/wecom/callback/cbkey1?" + q2.Encode()
	res = httptest.NewRecorder()
	dev.ServeHTTP(res, httptest.NewRequest(http.MethodPost, postURL, strings.NewReader(encXML)))
	if res.Code != 200 {
		t.Fatalf("post %d", res.Code)
	}
	// async handler
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		u, _ := st.GetUnlockByTaskID(context.Background(), "task-cb")
		if u != nil && u.Status == "approved" {
			return
		}
		time.Sleep(20 * time.Millisecond)
	}
	u, _ := st.GetUnlockByTaskID(context.Background(), "task-cb")
	t.Fatalf("card event not applied, status=%v", u)
}

func extractEncryptForTest(xmlBody string) (string, error) {
	const open = "<Encrypt><![CDATA["
	start := strings.Index(xmlBody, open)
	if start < 0 {
		return "", io.EOF
	}
	start += len(open)
	end := strings.Index(xmlBody[start:], "]]>")
	if end < 0 {
		return "", io.EOF
	}
	return xmlBody[start : start+end], nil
}

// --- helpers ---

type adminBundle struct {
	handler http.Handler
	store   *store.Store
}

var testAdminStore = map[http.Handler]*store.Store{}

func newTestAdmin(t *testing.T) (http.Handler, func()) {
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
	proxies, _ := httpx.ParseTrustedProxies([]string{"127.0.0.1/32"})
	cfg := config.Default()
	cfg.DataDir = dir
	cfg.PublicBaseURL = "https://example.test"
	cfg.TrustedProxies = []string{"127.0.0.1/32"}
	cfg.DeviceListen = "127.0.0.1:0"
	cfg.AdminListen = "127.0.0.1:0"
	s := &admin.Server{Cfg: cfg, Store: st, Master: master, Proxies: proxies}
	h := s.Handler()
	testAdminStore[h] = st
	return h, func() { _ = st.Close() }
}

func testStoreFromAdmin(t *testing.T, h http.Handler) *store.Store {
	st := testAdminStore[h]
	if st == nil {
		t.Fatal("store missing")
	}
	return st
}

func mustSetup(t *testing.T, h http.Handler, user, pass string) {
	t.Helper()
	body, _ := json.Marshal(map[string]string{"username": user, "password": pass})
	res := httptest.NewRecorder()
	h.ServeHTTP(res, httptest.NewRequest(http.MethodPost, "/api/v1/admin/setup", bytes.NewReader(body)))
	if res.Code != 204 {
		t.Fatalf("setup %d %s", res.Code, res.Body.String())
	}
}

func mustLogin(t *testing.T, h http.Handler, user, pass string) (csrf string, cookies []*http.Cookie) {
	t.Helper()
	body, _ := json.Marshal(map[string]string{"username": user, "password": pass})
	res := httptest.NewRecorder()
	h.ServeHTTP(res, httptest.NewRequest(http.MethodPost, "/api/v1/admin/login", bytes.NewReader(body)))
	if res.Code != 204 {
		t.Fatalf("login %d %s", res.Code, res.Body.String())
	}
	return res.Header().Get("X-CSRF-Token"), res.Result().Cookies()
}

func authed(method, path string, body []byte, csrf string, cookies []*http.Cookie) *http.Request {
	var rdr io.Reader
	if body != nil {
		rdr = bytes.NewReader(body)
	}
	req := httptest.NewRequest(method, path, rdr)
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-CSRF-Token", csrf)
	for _, c := range cookies {
		req.AddCookie(c)
	}
	return req
}

func mustJSON(v any) []byte {
	b, _ := json.Marshal(v)
	return b
}

func TestPasswordRules(t *testing.T) {
	if err := auth.ValidatePassword("short"); err == nil {
		t.Fatal("expected error")
	}
	if err := auth.ValidatePassword("alllettersonly"); err == nil {
		t.Fatal("expected digit")
	}
	hash, err := auth.HashPassword("correcthorse1")
	if err != nil {
		t.Fatal(err)
	}
	ok, err := auth.VerifyPassword(hash, "correcthorse1")
	if err != nil || !ok {
		t.Fatal("verify")
	}
}
