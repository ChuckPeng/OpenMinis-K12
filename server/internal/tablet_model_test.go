package internal_test

import (
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/ChuckPeng/OpenMinis-K12/server/internal/store"
)

const syntheticTabletKey = "synthetic-tablet-key-9f3a"

func TestTabletModelEncryptionWriteOnlyAndVersion(t *testing.T) {
	h, cleanup := newM5bTestHarness(t)
	defer cleanup()

	// No default vendor before the parent saves anything.
	rec := h.adminRequest(t, http.MethodGet, "/api/v1/admin/tablet-model", nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("get empty: %d %s", rec.Code, rec.Body.String())
	}
	var empty map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &empty); err != nil {
		t.Fatal(err)
	}
	if empty["provider_type"] != "" || empty["scope"] != "family" {
		t.Fatalf("unexpected empty config: %+v", empty)
	}
	if _, ok := empty["api_key"]; ok {
		t.Fatal("admin view must not contain api_key")
	}
	if versionNum(empty["version"]) != 0 {
		t.Fatalf("version want 0 got %v", empty["version"])
	}

	// URL syntax only: an unknown host is accepted. A non-URL is not.
	bad := h.adminRequest(t, http.MethodPut, "/api/v1/admin/tablet-model", map[string]any{
		"provider_type": "openAI",
		"base_url":      "notaurl",
		"model_name":    "chat-model",
		"api_key":       syntheticTabletKey,
	})
	if bad.Code != http.StatusBadRequest {
		t.Fatalf("bad url want 400 got %d %s", bad.Code, bad.Body.String())
	}
	ftp := h.adminRequest(t, http.MethodPut, "/api/v1/admin/tablet-model", map[string]any{
		"provider_type": "openAI",
		"base_url":      "ftp://example.invalid/v1",
		"model_name":    "chat-model",
		"api_key":       syntheticTabletKey,
	})
	if ftp.Code != http.StatusBadRequest {
		t.Fatalf("ftp want 400 got %d", ftp.Code)
	}

	first := h.adminRequest(t, http.MethodPut, "/api/v1/admin/tablet-model", map[string]any{
		"provider_type":     "openAI",
		"base_url":          "https://example.invalid/v1",
		"model_name":        "chat-model",
		"vision_model_name": "vision-model",
		"api_key":           syntheticTabletKey,
		"budget_cents":      1,
	})
	if first.Code != http.StatusOK {
		t.Fatalf("put: %d %s", first.Code, first.Body.String())
	}
	if strings.Contains(first.Body.String(), syntheticTabletKey) || strings.Contains(first.Body.String(), "budget_cents") {
		t.Fatalf("response leaked key or budget: %s", first.Body.String())
	}
	var saved map[string]any
	_ = json.Unmarshal(first.Body.Bytes(), &saved)
	if saved["api_key_set"] != true || versionNum(saved["version"]) != 1 {
		t.Fatalf("saved view: %+v", saved)
	}
	if saved["provider_type"] != "openAI" || saved["base_url"] != "https://example.invalid/v1" {
		t.Fatalf("fields: %+v", saved)
	}
	if _, ok := saved["api_key"]; ok {
		t.Fatal("api_key echoed")
	}

	var enc string
	if err := h.Store.DB.QueryRowContext(context.Background(), `SELECT api_key_enc FROM tablet_model_config WHERE family_id=?`, h.FamilyID).Scan(&enc); err != nil {
		t.Fatal(err)
	}
	if enc == "" || enc == syntheticTabletKey || strings.Contains(enc, syntheticTabletKey) {
		t.Fatal("key must be ciphertext at rest")
	}
	plain, err := h.Master.Open(enc)
	if err != nil || string(plain) != syntheticTabletKey {
		t.Fatalf("decrypt mismatch: %v", err)
	}

	// Write-only: omitting the key keeps the stored secret and bumps version.
	second := h.adminRequest(t, http.MethodPut, "/api/v1/admin/tablet-model", map[string]any{
		"provider_type":     "anthropic",
		"base_url":          "https://example.invalid/v1",
		"model_name":        "chat-model-b",
		"vision_model_name": "",
		"api_key":           "",
	})
	if second.Code != http.StatusOK {
		t.Fatalf("second put: %d %s", second.Code, second.Body.String())
	}
	var again map[string]any
	_ = json.Unmarshal(second.Body.Bytes(), &again)
	if versionNum(again["version"]) != 2 || again["provider_type"] != "anthropic" || again["api_key_set"] != true {
		t.Fatalf("second view: %+v", again)
	}
	var enc2 string
	_ = h.Store.DB.QueryRowContext(context.Background(), `SELECT api_key_enc FROM tablet_model_config WHERE family_id=?`, h.FamilyID).Scan(&enc2)
	plain2, err := h.Master.Open(enc2)
	if err != nil || string(plain2) != syntheticTabletKey {
		t.Fatal("omitted key must keep the previous secret")
	}

	aud, err := h.Store.ListAudit(context.Background(), 20)
	if err != nil {
		t.Fatal(err)
	}
	sawSave := false
	for _, row := range aud {
		if strings.Contains(row.Target, syntheticTabletKey) || strings.Contains(row.Actor, syntheticTabletKey) || strings.Contains(row.Action, syntheticTabletKey) {
			t.Fatalf("audit leaked key: %+v", row)
		}
		if row.Action == "tablet_model_save" {
			sawSave = true
		}
	}
	if !sawSave {
		t.Fatal("missing tablet_model_save audit")
	}
}

func TestTabletModelAuthCSRFHeartbeatAndRevoked(t *testing.T) {
	h, cleanup := newM5bTestHarness(t)
	defer cleanup()

	// Unauthenticated.
	req := httptest.NewRequest(http.MethodGet, "/api/v1/admin/tablet-model", nil)
	rec := httptest.NewRecorder()
	h.AdminServer.Handler().ServeHTTP(rec, req)
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("anon want 401 got %d", rec.Code)
	}

	// Missing CSRF.
	body := []byte(`{"provider_type":"openAI","base_url":"https://example.invalid/v1","model_name":"m","api_key":"` + syntheticTabletKey + `"}`)
	req = httptest.NewRequest(http.MethodPut, "/api/v1/admin/tablet-model", strings.NewReader(string(body)))
	req.Header.Set("Content-Type", "application/json")
	for _, c := range h.AdminCookies {
		req.AddCookie(c)
	}
	rec = httptest.NewRecorder()
	h.AdminServer.Handler().ServeHTTP(rec, req)
	if rec.Code != http.StatusForbidden {
		t.Fatalf("csrf want 403 got %d %s", rec.Code, rec.Body.String())
	}
	if strings.Contains(rec.Body.String(), syntheticTabletKey) {
		t.Fatal("error body leaked key")
	}

	ok := h.adminRequest(t, http.MethodPut, "/api/v1/admin/tablet-model", map[string]any{
		"provider_type":     "openAI",
		"base_url":          "https://example.invalid/v1",
		"model_name":        "chat-model",
		"vision_model_name": "vision-model",
		"api_key":           syntheticTabletKey,
	})
	if ok.Code != http.StatusOK {
		t.Fatalf("put: %d %s", ok.Code, ok.Body.String())
	}

	devPub, devPriv, _ := ed25519.GenerateKey(rand.Reader)
	devID := "dev-model-a"
	_ = h.Store.CreateDevice(context.Background(), &store.Device{
		ID:            devID,
		FamilyID:      h.FamilyID,
		ChildID:       "child-model-a",
		Label:         "tablet-a",
		Pubkey:        base64.StdEncoding.EncodeToString(devPub),
		CodeSecretEnc: "enc",
		Status:        "active",
		CreatedAt:     time.Now().UTC().Format(time.RFC3339),
	})

	hb := h.signedDeviceRequest(t, http.MethodPost, "/api/v1/device/heartbeat", []byte(`{"app_version":"1.0.0-rc.2","config_version_applied":0}`), devID, devPriv, nil)
	if hb.Code != http.StatusOK {
		t.Fatalf("heartbeat: %d %s", hb.Code, hb.Body.String())
	}
	if strings.Contains(hb.Body.String(), syntheticTabletKey) || strings.Contains(hb.Body.String(), "api_key") {
		t.Fatalf("heartbeat leaked key: %s", hb.Body.String())
	}
	var hbBody map[string]any
	_ = json.Unmarshal(hb.Body.Bytes(), &hbBody)
	if versionNum(hbBody["tablet_model_version"]) != 1 {
		t.Fatalf("heartbeat version: %+v", hbBody)
	}

	// Unknown device.
	_, otherPriv, _ := ed25519.GenerateKey(rand.Reader)
	unknown := h.signedDeviceRequest(t, http.MethodGet, "/api/v1/device/tablet-model", nil, "dev-missing", otherPriv, nil)
	if unknown.Code != http.StatusUnauthorized && unknown.Code != http.StatusForbidden {
		t.Fatalf("unknown want 401/403 got %d", unknown.Code)
	}

	// Pending device.
	pendPub, pendPriv, _ := ed25519.GenerateKey(rand.Reader)
	_ = h.Store.CreateDevice(context.Background(), &store.Device{
		ID:            "dev-model-pending",
		FamilyID:      h.FamilyID,
		ChildID:       "child-model-pending",
		Label:         "tablet-pending",
		Pubkey:        base64.StdEncoding.EncodeToString(pendPub),
		CodeSecretEnc: "enc",
		Status:        "pending_confirm",
		CreatedAt:     time.Now().UTC().Format(time.RFC3339),
	})
	pending := h.signedDeviceRequest(t, http.MethodGet, "/api/v1/device/tablet-model", nil, "dev-model-pending", pendPriv, nil)
	if pending.Code != http.StatusUnauthorized && pending.Code != http.StatusForbidden {
		t.Fatalf("pending want 401/403 got %d %s", pending.Code, pending.Body.String())
	}

	fetch := h.signedDeviceRequest(t, http.MethodGet, "/api/v1/device/tablet-model", nil, devID, devPriv, nil)
	if fetch.Code != http.StatusOK {
		t.Fatalf("fetch: %d %s", fetch.Code, fetch.Body.String())
	}
	if !strings.Contains(fetch.Body.String(), syntheticTabletKey) {
		t.Fatal("paired device must receive the key")
	}
	var delivered map[string]any
	_ = json.Unmarshal(fetch.Body.Bytes(), &delivered)
	if delivered["provider_type"] != "openAI" || delivered["model_name"] != "chat-model" || delivered["vision_model_name"] != "vision-model" {
		t.Fatalf("delivered: %+v", delivered)
	}
	if delivered["cleared"] == true {
		t.Fatal("active config marked cleared")
	}

	aud, _ := h.Store.ListAudit(context.Background(), 30)
	saw := false
	for _, row := range aud {
		blob := row.Actor + row.Action + row.Target
		if strings.Contains(blob, syntheticTabletKey) {
			t.Fatalf("audit leaked key: %+v", row)
		}
		if row.Action == "tablet_model_deliver" && strings.Contains(row.Target, devID) && strings.Contains(row.Target, ":v1") {
			saw = true
		}
	}
	if !saw {
		t.Fatal("missing delivery audit")
	}

	admin := h.adminRequest(t, http.MethodGet, "/api/v1/admin/tablet-model", nil)
	if strings.Contains(admin.Body.String(), syntheticTabletKey) {
		t.Fatal("admin get leaked key")
	}
	var view map[string]any
	_ = json.Unmarshal(admin.Body.Bytes(), &view)
	dels, _ := view["deliveries"].([]any)
	found := false
	for _, raw := range dels {
		item, _ := raw.(map[string]any)
		if item["device_id"] == devID && versionNum(item["version"]) == 1 && item["delivered_at"] != "" {
			found = true
		}
	}
	if !found {
		t.Fatalf("missing last delivery: %+v", view["deliveries"])
	}

	rev := h.adminRequest(t, http.MethodPost, "/api/v1/admin/devices/"+devID+"/revoke", map[string]any{})
	if rev.Code != http.StatusNoContent && rev.Code != http.StatusOK {
		t.Fatalf("revoke: %d %s", rev.Code, rev.Body.String())
	}
	denied := h.signedDeviceRequest(t, http.MethodGet, "/api/v1/device/tablet-model", nil, devID, devPriv, nil)
	if denied.Code != http.StatusUnauthorized && denied.Code != http.StatusForbidden {
		t.Fatalf("revoked want 401/403 got %d %s", denied.Code, denied.Body.String())
	}
	if strings.Contains(denied.Body.String(), syntheticTabletKey) {
		t.Fatal("revoked response leaked key")
	}

	cleared := h.adminRequest(t, http.MethodDelete, "/api/v1/admin/tablet-model", nil)
	if cleared.Code != http.StatusOK {
		t.Fatalf("clear: %d %s", cleared.Code, cleared.Body.String())
	}
	if strings.Contains(cleared.Body.String(), syntheticTabletKey) {
		t.Fatal("clear leaked key")
	}
	var clearedView map[string]any
	_ = json.Unmarshal(cleared.Body.Bytes(), &clearedView)
	if versionNum(clearedView["version"]) != 2 || clearedView["cleared"] != true || clearedView["api_key_set"] != false {
		t.Fatalf("cleared view: %+v", clearedView)
	}
	var enc string
	_ = h.Store.DB.QueryRowContext(context.Background(), `SELECT api_key_enc FROM tablet_model_config WHERE family_id=?`, h.FamilyID).Scan(&enc)
	if enc != "" {
		t.Fatal("clear must wipe ciphertext")
	}

	// A new active device sees the cleared version and no key.
	devPub2, devPriv2, _ := ed25519.GenerateKey(rand.Reader)
	_ = h.Store.CreateDevice(context.Background(), &store.Device{
		ID:            "dev-model-b",
		FamilyID:      h.FamilyID,
		ChildID:       "child-model-b",
		Label:         "tablet-b",
		Pubkey:        base64.StdEncoding.EncodeToString(devPub2),
		CodeSecretEnc: "enc",
		Status:        "active",
		CreatedAt:     time.Now().UTC().Format(time.RFC3339),
	})
	hb2 := h.signedDeviceRequest(t, http.MethodPost, "/api/v1/device/heartbeat", []byte(`{"app_version":"1.0.0-rc.2","config_version_applied":0}`), "dev-model-b", devPriv2, nil)
	var hb2Body map[string]any
	_ = json.Unmarshal(hb2.Body.Bytes(), &hb2Body)
	if versionNum(hb2Body["tablet_model_version"]) != 2 {
		t.Fatalf("cleared heartbeat version: %+v", hb2Body)
	}
	fetch2 := h.signedDeviceRequest(t, http.MethodGet, "/api/v1/device/tablet-model", nil, "dev-model-b", devPriv2, nil)
	if fetch2.Code != http.StatusOK {
		t.Fatalf("fetch cleared: %d %s", fetch2.Code, fetch2.Body.String())
	}
	if strings.Contains(fetch2.Body.String(), syntheticTabletKey) || strings.Contains(fetch2.Body.String(), `"api_key"`) {
		t.Fatalf("cleared fetch leaked key: %s", fetch2.Body.String())
	}
	var clearedDev map[string]any
	_ = json.Unmarshal(fetch2.Body.Bytes(), &clearedDev)
	if clearedDev["cleared"] != true || versionNum(clearedDev["version"]) != 2 {
		t.Fatalf("cleared device body: %+v", clearedDev)
	}
}

func versionNum(v any) int {
	switch n := v.(type) {
	case float64:
		return int(n)
	case json.Number:
		i, _ := n.Int64()
		return int(i)
	default:
		return -1
	}
}
