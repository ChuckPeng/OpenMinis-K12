package deviceapi

import (
	"compress/gzip"
	"context"
	"crypto/rand"
	"crypto/sha256"
	"database/sql"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"github.com/ChuckPeng/OpenMinis-K12/server/internal/crypto"
	"github.com/ChuckPeng/OpenMinis-K12/server/internal/httpx"
	"github.com/ChuckPeng/OpenMinis-K12/server/internal/outbox"
	"github.com/ChuckPeng/OpenMinis-K12/server/internal/store"
	"github.com/ChuckPeng/OpenMinis-K12/server/internal/wecom"
	"github.com/google/uuid"
)

// Default device configuration per §19.4 / conventions.
const defaultDeviceConfigJSON = `{
  "stage": "小学低年级",
  "daily_limit_minutes": 40,
  "weekend_extra_minutes": 30,
  "break_reminder_minutes": 25,
  "late_night_start": "21:00",
  "late_night_end": "06:00",
  "reveal_policy": {"require_parent_unlock": true},
  "turn_limit": 60,
  "image_limit": 20,
  "companion_limit_minutes": 15,
  "study_day_min_minutes": 10,
  "hotline_text": "",
  "hotline_number": ""
}`

func (s *Server) pairDevice(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	var req struct {
		PairCode     string `json:"pair_code"`
		DevicePubkey string `json:"device_pubkey"`
		Label        string `json:"label"`
		ChildID      string `json:"child_id"`
	}
	if err := httpx.DecodeJSON(r, &req); err != nil {
		httpx.WriteError(w, http.StatusBadRequest, "bad_request", "invalid json")
		return
	}
	if req.PairCode == "" || req.DevicePubkey == "" || req.ChildID == "" {
		httpx.WriteError(w, http.StatusBadRequest, "bad_request", "missing required fields")
		return
	}

	ctx := r.Context()
	familyID, err := s.Store.EnsureFamily(ctx, "home")
	if err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, "internal_error", "failed to find family")
		return
	}

	// Validate pair code (10-minute one-time code)
	ok, err := s.Store.ValidateAndConsumePairCode(ctx, familyID, req.PairCode)
	if err != nil || !ok {
		httpx.WriteError(w, http.StatusUnauthorized, "invalid_pair_code", "invalid, expired, or already used pair code")
		return
	}

	// Check if child_id already has an active device
	existing, err := s.Store.GetDeviceByChildID(ctx, req.ChildID)
	if err == nil && existing != nil && existing.Status == "active" {
		httpx.WriteError(w, http.StatusConflict, "device_conflict", "child already has an active device; replace confirmation required")
		return
	}

	// Generate 32-byte codeSecret
	codeSecret := make([]byte, 32)
	if _, err := io.ReadFull(rand.Reader, codeSecret); err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, "internal_error", "failed to generate secret")
		return
	}

	// Encrypt codeSecret with MasterKey for DB storage
	codeSecretEnc, err := s.Master.Seal(codeSecret)
	if err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, "internal_error", "failed to encrypt secret")
		return
	}

	// Decode device public key (base64 or hex)
	pubKeyBytes, err := base64.StdEncoding.DecodeString(req.DevicePubkey)
	if err != nil {
		pubKeyBytes, err = hex.DecodeString(req.DevicePubkey)
		if err != nil {
			pubKeyBytes = []byte(req.DevicePubkey)
		}
	}

	// Wrap codeSecret with device public key using anonymous sealed box
	alg, wrapped, err := crypto.SealCodeSecret(pubKeyBytes, codeSecret)
	if err != nil {
		httpx.WriteError(w, http.StatusBadRequest, "bad_pubkey", fmt.Sprintf("failed to wrap code secret: %v", err))
		return
	}

	// Register device in pending_confirm state
	devID := uuid.NewString()
	label := req.Label
	if label == "" {
		label = "学生平板"
	}
	dev := &store.Device{
		ID:            devID,
		FamilyID:      familyID,
		ChildID:       req.ChildID,
		Label:         label,
		Pubkey:        req.DevicePubkey,
		CodeSecretEnc: codeSecretEnc,
		Status:        "pending_confirm",
	}
	if err := s.Store.CreateDevice(ctx, dev); err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, "internal_error", "failed to create device")
		return
	}

	// Ensure child record exists and bind device
	_, _ = s.Store.EnsureChild(ctx, req.ChildID, familyID, label, "小学低年级")
	_ = s.Store.BindChildDevice(ctx, req.ChildID, devID)
	_, _ = s.Store.SetDeviceConfig(ctx, devID, defaultDeviceConfigJSON, "system")

	// Send WeCom confirmation card to parents (outbox)
	_ = outbox.EnqueueJSON(ctx, s.Store, "", "device_pairing", map[string]any{
		"kind":      "device_pairing",
		"device_id": devID,
		"label":     label,
		"child_id":  req.ChildID,
		"text":      fmt.Sprintf("新设备配对申请：%s（孩子：%s）。请在管理后台或企微确认。", label, req.ChildID),
	}, nil)

	httpx.WriteJSON(w, http.StatusOK, map[string]any{
		"device_id":           devID,
		"status":              "pending_confirm",
		"code_secret_alg":     alg,
		"code_secret_wrapped": wrapped,
	})
}

func (s *Server) getPairStatus(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	dev := getContextDevice(r)
	if dev == nil {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized", "device context missing")
		return
	}

	waitSec := parseWait(r.URL.Query().Get("wait"))
	initialStatus := dev.Status

	if initialStatus != "pending_confirm" || waitSec <= 0 {
		httpx.WriteJSON(w, http.StatusOK, map[string]any{
			"status":  dev.Status,
			"changed": false,
		})
		return
	}

	// Long-poll until status changes or wait timeout
	ch, cancel := s.Notifier.Sub("pair:" + dev.ID)
	defer cancel()

	timer := time.NewTimer(time.Duration(waitSec) * time.Second)
	defer timer.Stop()

	select {
	case <-ch:
		cur, err := s.Store.GetDevice(r.Context(), dev.ID)
		if err == nil && cur != nil {
			httpx.WriteJSON(w, http.StatusOK, map[string]any{
				"status":  cur.Status,
				"changed": cur.Status != initialStatus,
			})
			return
		}
	case <-timer.C:
	case <-r.Context().Done():
		return
	}

	cur, err := s.Store.GetDevice(r.Context(), dev.ID)
	status := initialStatus
	if err == nil && cur != nil {
		status = cur.Status
	}
	httpx.WriteJSON(w, http.StatusOK, map[string]any{
		"status":  status,
		"changed": status != initialStatus,
	})
}

func (s *Server) createUnlockRequest(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	dev := getContextDevice(r)
	if dev == nil {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized", "device context missing")
		return
	}

	var req struct {
		ClientRequestID string `json:"client_request_id"`
		Subject         string `json:"subject"`
		QuestionHash    string `json:"question_hash"`
		HintsUsed       int    `json:"hints_used"`
		Reason          string `json:"reason,omitempty"`
	}
	if err := httpx.DecodeJSON(r, &req); err != nil {
		httpx.WriteError(w, http.StatusBadRequest, "bad_request", "invalid json")
		return
	}
	if req.ClientRequestID == "" || req.QuestionHash == "" {
		httpx.WriteError(w, http.StatusBadRequest, "bad_request", "missing required fields")
		return
	}

	ctx := r.Context()

	// Check if pending request exists for this device (limit 1)
	unlocks, err := s.Store.ListUnlockRequests(ctx, 10)
	if err == nil {
		for _, u := range unlocks {
			if u.DeviceID == dev.ID && u.Status == "pending" {
				httpx.WriteError(w, http.StatusConflict, "pending_request_exists", "a pending unlock request already exists")
				return
			}
		}
	}

	// Derive 6-digit offline backup code from device codeSecret
	codeSecret, err := s.Master.Open(dev.CodeSecretEnc)
	if err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, "internal_error", "failed to decrypt code secret")
		return
	}
	backupCode := crypto.DeriveBackupCode(codeSecret, req.ClientRequestID, req.QuestionHash)

	taskID := uuid.NewString()
	exp := time.Now().UTC().Add(10 * time.Minute).Format(time.RFC3339Nano)
	unlockReq := &store.UnlockRequest{
		DeviceID:     dev.ID,
		QuestionHash: req.QuestionHash,
		Subject:      req.Subject,
		HintsUsed:    req.HintsUsed,
		Status:       "pending",
		Scope:        "question",
		ExpiresAt:    exp,
		TaskID:       store.NullString(taskID),
	}
	if err := s.Store.CreateUnlockRequest(ctx, unlockReq); err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, "internal_error", "failed to create unlock request")
		return
	}

	// Update response_code to the derived backup code
	_, _ = s.Store.DB.ExecContext(ctx, `UPDATE unlock_requests SET response_code=? WHERE id=?`, backupCode, unlockReq.ID)

	// Send WeCom approval card via outbox
	apps, _ := s.Store.ListWecomApps(ctx)
	useButtons := false
	for _, a := range apps {
		if wecom.ButtonModeFromApp(&a) {
			useButtons = true
			break
		}
	}
	unlockReq.ResponseCode = store.NullString(backupCode)
	notifyPayload := wecom.BuildUnlockNotifyPayload(unlockReq, useButtons)
	_ = outbox.EnqueueJSON(ctx, s.Store, "", "unlock_request", notifyPayload, nil)

	httpx.WriteJSON(w, http.StatusCreated, map[string]any{
		"id":         unlockReq.ID,
		"expires_at": unlockReq.ExpiresAt,
	})
}

func (s *Server) waitUnlockRequest(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	dev := getContextDevice(r)
	if dev == nil {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized", "device context missing")
		return
	}

	id := strings.TrimPrefix(r.URL.Path, "/api/v1/device/unlock-requests/")
	id = strings.Trim(id, "/")
	if id == "" {
		http.NotFound(w, r)
		return
	}

	ctx := r.Context()
	cur, err := s.Store.GetUnlockRequest(ctx, id)
	if err != nil || cur == nil || cur.DeviceID != dev.ID {
		httpx.WriteError(w, http.StatusNotFound, "not_found", "unlock request not found")
		return
	}

	waitSec := parseWait(r.URL.Query().Get("wait"))
	initialStatus := cur.Status

	if initialStatus == "pending" && waitSec > 0 {
		ch, cancel := s.Notifier.Sub("unlock:" + id)
		defer cancel()

		timer := time.NewTimer(time.Duration(waitSec) * time.Second)
		defer timer.Stop()

		select {
		case <-ch:
			updated, err := s.Store.GetUnlockRequest(ctx, id)
			if err == nil && updated != nil {
				cur = updated
			}
		case <-timer.C:
		case <-r.Context().Done():
			return
		}
	}

	resp := map[string]any{
		"id":      cur.ID,
		"status":  cur.Status,
		"changed": cur.Status != initialStatus,
	}

	if cur.Status == "approved" {
		// Ensure grant exists or create it
		grant, err := s.Store.GetGrantByRequestID(ctx, cur.ID)
		if err == nil && grant == nil {
			grantID := uuid.NewString()
			decidedLabel := "家长"
			if cur.DecidedBy.Valid {
				decidedLabel = cur.DecidedBy.String
			}
			payloadObj := crypto.GrantPayload{
				V:              1,
				GrantID:        grantID,
				RequestID:      cur.ID,
				DeviceID:       dev.ID,
				QuestionHash:   cur.QuestionHash,
				Scope:          cur.Scope,
				ExpiresAt:      cur.ExpiresAt,
				DecidedByLabel: decidedLabel,
			}
			canonical, _ := crypto.CanonicalGrantPayload(payloadObj)
			sig := crypto.SignGrant(s.SigningPriv, canonical)
			grant = &store.Grant{
				ID:        grantID,
				RequestID: cur.ID,
				DeviceID:  dev.ID,
				Scope:     cur.Scope,
				ExpiresAt: cur.ExpiresAt,
				Signature: sig,
			}
			_ = s.Store.CreateGrant(ctx, grant)
		}

		if grant != nil {
			decidedLabel := "家长"
			if cur.DecidedBy.Valid {
				decidedLabel = cur.DecidedBy.String
			}
			resp["grant"] = map[string]any{
				"payload": map[string]any{
					"v":                1,
					"grant_id":         grant.ID,
					"request_id":       cur.ID,
					"device_id":        dev.ID,
					"question_hash":    cur.QuestionHash,
					"scope":            grant.Scope,
					"expires_at":       grant.ExpiresAt,
					"decided_by_label": decidedLabel,
				},
				"signature": grant.Signature,
			}
		}
	}

	httpx.WriteJSON(w, http.StatusOK, resp)
}

func (s *Server) ackUnlockRequest(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	dev := getContextDevice(r)
	if dev == nil {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized", "device context missing")
		return
	}

	path := strings.TrimPrefix(r.URL.Path, "/api/v1/device/unlock-requests/")
	id := strings.TrimSuffix(path, "/ack")
	id = strings.Trim(id, "/")

	var req struct {
		Method  string `json:"method"`
		GrantID string `json:"grant_id,omitempty"`
	}
	if err := httpx.DecodeJSON(r, &req); err != nil {
		httpx.WriteError(w, http.StatusBadRequest, "bad_request", "invalid json")
		return
	}

	ctx := r.Context()
	cur, err := s.Store.GetUnlockRequest(ctx, id)
	if err != nil || cur == nil || cur.DeviceID != dev.ID {
		httpx.WriteError(w, http.StatusNotFound, "not_found", "unlock request not found")
		return
	}

	_, _ = s.Store.DB.ExecContext(ctx, `UPDATE unlock_requests SET status='consumed', consumed_at=? WHERE id=?`, store.NowUTC(), id)
	if req.GrantID != "" {
		_ = s.Store.ConsumeGrant(ctx, req.GrantID)
	}

	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) postAlert(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	dev := getContextDevice(r)
	if dev == nil {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized", "device context missing")
		return
	}

	var req struct {
		Level      string `json:"level"`
		Category   string `json:"category"`
		OccurredAt int64  `json:"occurred_at"`
		DedupeKey  string `json:"dedupe_key"`
		Snippet    string `json:"snippet,omitempty"`
	}
	if err := httpx.DecodeJSON(r, &req); err != nil {
		httpx.WriteError(w, http.StatusBadRequest, "bad_request", "invalid json")
		return
	}
	if req.Level == "" || req.Category == "" || req.OccurredAt <= 0 {
		httpx.WriteError(w, http.StatusBadRequest, "bad_request", "missing required fields")
		return
	}

	ctx := r.Context()

	// 30-minute deduplication window
	since := time.Now().UTC().Add(-30 * time.Minute)
	recent, err := s.Store.FindRecentAlert(ctx, dev.ID, req.Level, req.DedupeKey, since)
	if err == nil && recent != nil {
		httpx.WriteJSON(w, http.StatusAccepted, map[string]string{"id": recent.ID})
		return
	}

	// Encrypt snippet if present (30-day retention)
	snippetEnc := ""
	var snippetExp sql.NullString
	if req.Snippet != "" {
		sEnc, err := s.Master.Seal([]byte(req.Snippet))
		if err == nil {
			snippetEnc = sEnc
			snippetExp = store.NullString(time.Now().UTC().Add(30 * 24 * time.Hour).Format(time.RFC3339Nano))
		}
	}

	alertID := uuid.NewString()
	alert := &store.Alert{
		ID:               alertID,
		DeviceID:         dev.ID,
		ChildID:          dev.ChildID,
		Level:            req.Level,
		Category:         req.Category,
		OccurredAt:       req.OccurredAt,
		DedupeKey:        req.DedupeKey,
		SnippetEnc:       snippetEnc,
		SnippetExpiresAt: snippetExp,
	}
	if err := s.Store.CreateAlert(ctx, alert); err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, "internal_error", "failed to record alert")
		return
	}

	// WeCom notification: NO snippets in message (H3/H4).
	// Emergency alerts ("紧急") are delivered immediately (H5/H6).
	alertText := fmt.Sprintf("【%s安全提示】类别：%s。请在管理后台查看详情。", req.Level, req.Category)
	_ = outbox.EnqueueJSON(ctx, s.Store, "", "alert", map[string]any{
		"kind":        "alert",
		"alert_id":    alertID,
		"level":       req.Level,
		"category":    req.Category,
		"occurred_at": req.OccurredAt,
		"text":        alertText,
	}, nil)

	httpx.WriteJSON(w, http.StatusAccepted, map[string]string{"id": alertID})
}

func (s *Server) postEvents(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	dev := getContextDevice(r)
	if dev == nil {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized", "device context missing")
		return
	}

	var bodyReader io.Reader = r.Body
	if strings.EqualFold(r.Header.Get("Content-Encoding"), "gzip") {
		gz, err := gzip.NewReader(r.Body)
		if err != nil {
			httpx.WriteError(w, http.StatusBadRequest, "bad_request", "invalid gzip stream")
			return
		}
		defer gz.Close()
		bodyReader = gz
	}

	var req struct {
		Events []struct {
			EventID       string         `json:"event_id"`
			SchemaVersion int            `json:"schema_version"`
			DeviceID      string         `json:"device_id"`
			ChildID       string         `json:"child_id"`
			Type          string         `json:"type"`
			OccurredAt    int64          `json:"occurred_at"`
			Tz            string         `json:"tz"`
			Seq           int            `json:"seq"`
			Subject       string         `json:"subject"`
			KPIDs         []string       `json:"kp_ids"`
			Payload       map[string]any `json:"payload"`
		} `json:"events"`
	}

	dec := json.NewDecoder(bodyReader)
	if err := dec.Decode(&req); err != nil {
		httpx.WriteError(w, http.StatusBadRequest, "bad_request", "invalid json")
		return
	}

	if len(req.Events) > 500 {
		httpx.WriteError(w, http.StatusRequestEntityTooLarge, "batch_too_large", "exceeded 500 events per batch")
		return
	}

	ctx := r.Context()
	storeEvents := make([]store.StudyEvent, 0, len(req.Events))
	for _, e := range req.Events {
		kpJSON, _ := json.Marshal(e.KPIDs)
		payloadJSON, _ := json.Marshal(e.Payload)
		localDate := LocalDateInShanghai(e.OccurredAt)
		storeEvents = append(storeEvents, store.StudyEvent{
			EventID:       e.EventID,
			ChildID:       dev.ChildID,
			DeviceID:      dev.ID,
			Type:          e.Type,
			OccurredAt:    e.OccurredAt,
			LocalDate:     localDate,
			Seq:           e.Seq,
			Subject:       e.Subject,
			KPIDs:         string(kpJSON),
			PayloadJSON:   string(payloadJSON),
			SchemaVersion: e.SchemaVersion,
		})
	}

	maxSeq, insertedEvents, err := s.Store.InsertStudyEvents(ctx, storeEvents)
	if err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, "internal_error", "failed to insert events")
		return
	}

	// Update daily aggregate for newly inserted events (idempotent)
	if len(insertedEvents) > 0 {
		grouped := make(map[string][]store.StudyEvent)
		for _, e := range insertedEvents {
			key := fmt.Sprintf("%s|%s", e.LocalDate, e.Subject)
			grouped[key] = append(grouped[key], e)
		}
		for _, group := range grouped {
			_ = s.Store.UpdateDailyAggregateFromEvents(ctx, dev.ChildID, group[0].LocalDate, group[0].Subject, group)
		}
	}

	httpx.WriteJSON(w, http.StatusOK, map[string]int{"max_seq": maxSeq})
}

func (s *Server) listChildReports(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	dev := getContextDevice(r)
	if dev == nil {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized", "device context missing")
		return
	}

	ctx := r.Context()
	reports, err := s.Store.ListReports(ctx, dev.ChildID, 20)
	if err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, "internal_error", "failed to list reports")
		return
	}

	// Convert to ChildReport schema: MUST NOT contain parent_advice_json!
	out := make([]map[string]any, 0, len(reports))
	for _, rep := range reports {
		var metricsObj, analysisObj, parentTextObj, childTextObj, dataBasisObj any
		_ = json.Unmarshal([]byte(rep.MetricsJSON), &metricsObj)
		_ = json.Unmarshal([]byte(rep.AnalysisJSON), &analysisObj)
		_ = json.Unmarshal([]byte(rep.ParentTextJSON), &parentTextObj)
		_ = json.Unmarshal([]byte(rep.ChildTextJSON), &childTextObj)
		_ = json.Unmarshal([]byte(rep.DataBasisJSON), &dataBasisObj)

		item := map[string]any{
			"id":               rep.ID,
			"kind":             rep.Kind,
			"period_start":     rep.PeriodStart,
			"period_end":       rep.PeriodEnd,
			"metrics_json":     metricsObj,
			"analysis_json":    analysisObj,
			"parent_text_json": parentTextObj,
			"child_text_json":  childTextObj,
			"text_source":      rep.TextSource,
			"data_basis_json":  dataBasisObj,
			"metric_version":   rep.MetricVersion,
			"version":          rep.Version,
			"status":           rep.Status,
			"generated_at":     rep.GeneratedAt,
		}
		out = append(out, item)
	}

	httpx.WriteJSON(w, http.StatusOK, out)
}

func (s *Server) heartbeat(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	dev := getContextDevice(r)
	if dev == nil {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized", "device context missing")
		return
	}

	var req struct {
		AppVersion           string `json:"app_version"`
		ConfigVersionApplied int    `json:"config_version_applied"`
		Turns                int    `json:"turns"`
		Images               int    `json:"images"`
		CompanionMinutes     int    `json:"companion_minutes"`
		PermissionCheck      struct {
			NotificationsEnabled bool `json:"notifications_enabled"`
			BatteryUnrestricted  bool `json:"battery_unrestricted"`
			BackgroundAllowed    bool `json:"background_allowed"`
			ExactAlarmAllowed    bool `json:"exact_alarm_allowed"`
		} `json:"permission_check"`
	}
	if err := httpx.DecodeJSON(r, &req); err != nil {
		httpx.WriteError(w, http.StatusBadRequest, "bad_request", "invalid json")
		return
	}

	ctx := r.Context()
	_ = s.Store.UpdateDeviceHeartbeat(ctx, dev.ID, req.AppVersion, req.ConfigVersionApplied)

	// Record daily usage
	today := LocalDateInShanghai(time.Now().UnixMilli())
	_ = s.Store.RecordUsage(ctx, dev.ChildID, today, req.Turns, req.Images, req.CompanionMinutes)

	serverTime := time.Now().UTC().Format(time.RFC3339Nano)
	resp := map[string]any{
		"server_time":          serverTime,
		"app_update_available": false,
	}

	serverVer, cfgJSON, err := s.Store.GetDeviceConfig(ctx, dev.ID)
	if err == nil && serverVer > req.ConfigVersionApplied {
		resp["config_version"] = serverVer
		var parsed any
		if err := json.Unmarshal([]byte(cfgJSON), &parsed); err == nil {
			resp["config_json"] = parsed
		}
	}

	httpx.WriteJSON(w, http.StatusOK, resp)
}

func (s *Server) putBackup(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPut {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	dev := getContextDevice(r)
	if dev == nil {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized", "device context missing")
		return
	}

	contentSHA := r.Header.Get("X-K12-Content-Sha256")
	kdfParams := r.Header.Get("X-K12-Kdf-Params")
	if contentSHA == "" || kdfParams == "" {
		httpx.WriteError(w, http.StatusBadRequest, "bad_request", "missing backup metadata headers")
		return
	}

	body, err := io.ReadAll(r.Body)
	if err != nil {
		httpx.WriteError(w, http.StatusBadRequest, "bad_request", "failed to read body")
		return
	}

	// Verify magic bytes: ASCII K12B
	if len(body) < 4 || string(body[:4]) != "K12B" {
		httpx.WriteError(w, http.StatusBadRequest, "bad_format", "invalid backup file magic header")
		return
	}

	// Verify SHA-256
	h := sha256.Sum256(body)
	actualSHA := hex.EncodeToString(h[:])
	if !strings.EqualFold(actualSHA, contentSHA) {
		httpx.WriteError(w, http.StatusBadRequest, "checksum_mismatch", "sha256 mismatch")
		return
	}

	backupDir := filepath.Join(s.Cfg.DataDir, "backups")
	_ = os.MkdirAll(backupDir, 0o750)
	blobFile := fmt.Sprintf("%s_%d.k12b", dev.ID, time.Now().UnixNano())
	blobPath := filepath.Join(backupDir, blobFile)
	if err := os.WriteFile(blobPath, body, 0o600); err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, "internal_error", "failed to save backup file")
		return
	}

	backupRecord := &store.DeviceBackup{
		ID:        uuid.NewString(),
		DeviceID:  dev.ID,
		ChildID:   dev.ChildID,
		Size:      int64(len(body)),
		SHA256:    actualSHA,
		BlobPath:  blobPath,
		KDFParams: kdfParams,
	}
	if err := s.Store.CreateBackup(r.Context(), backupRecord); err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, "internal_error", "failed to record backup")
		return
	}

	// Retention pruning: keep 7 daily + 4 weekly backups
	s.pruneOldBackups(r.Context(), dev.ChildID)

	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) pruneOldBackups(ctx context.Context, childID string) {
	backups, err := s.Store.ListBackups(ctx, childID)
	if err != nil || len(backups) <= 11 {
		return
	}

	// Keep up to 7 distinct days and 4 distinct weeks
	kept := make(map[string]bool)
	distinctDays := make(map[string]bool)
	distinctWeeks := make(map[string]bool)

	for _, b := range backups {
		t, err := time.Parse(time.RFC3339Nano, b.CreatedAt)
		if err != nil {
			t = time.Now()
		}
		dayKey := t.Format("2006-01-02")
		year, week := t.ISOWeek()
		weekKey := fmt.Sprintf("%d-W%02d", year, week)

		if len(distinctDays) < 7 && !distinctDays[dayKey] {
			distinctDays[dayKey] = true
			kept[b.ID] = true
			continue
		}
		if len(distinctWeeks) < 4 && !distinctWeeks[weekKey] {
			distinctWeeks[weekKey] = true
			kept[b.ID] = true
			continue
		}
	}

	for _, b := range backups {
		if !kept[b.ID] {
			_ = os.Remove(b.BlobPath)
			_ = s.Store.DeleteBackup(ctx, b.ID)
		}
	}
}

func (s *Server) getLatestBackup(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	dev := getContextDevice(r)
	if dev == nil {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized", "device context missing")
		return
	}

	ctx := r.Context()
	backup, err := s.Store.GetLatestBackup(ctx, dev.ChildID)
	if err != nil || backup == nil {
		httpx.WriteError(w, http.StatusNotFound, "not_found", "no backup found")
		return
	}

	data, err := os.ReadFile(backup.BlobPath)
	if err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, "internal_error", "failed to read backup file")
		return
	}

	w.Header().Set("X-K12-Content-Sha256", backup.SHA256)
	w.Header().Set("X-K12-Kdf-Params", backup.KDFParams)
	w.Header().Set("Content-Type", "application/octet-stream")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(data)
}

func (s *Server) getAppUpdate(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	httpx.WriteJSON(w, http.StatusOK, map[string]any{
		"available": false,
	})
}

func (s *Server) postDiagnostics(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	dev := getContextDevice(r)
	if dev == nil {
		httpx.WriteError(w, http.StatusUnauthorized, "unauthorized", "device context missing")
		return
	}

	var req struct {
		Kind string `json:"kind"`
		Body string `json:"body"`
	}
	if err := httpx.DecodeJSON(r, &req); err != nil {
		httpx.WriteError(w, http.StatusBadRequest, "bad_request", "invalid json")
		return
	}

	diagID := uuid.NewString()
	record := &store.DiagnosticRecord{
		ID:       diagID,
		DeviceID: dev.ID,
		Kind:     req.Kind,
		Body:     req.Body,
	}
	_ = s.Store.CreateDiagnostics(r.Context(), record)

	httpx.WriteJSON(w, http.StatusAccepted, map[string]string{"id": diagID})
}

func (s *Server) getSignedReport(w http.ResponseWriter, r *http.Request) {
	linkID := strings.TrimPrefix(r.URL.Path, "/r/")
	linkID = strings.Trim(linkID, "/")
	if linkID == "" {
		http.NotFound(w, r)
		return
	}

	q := r.URL.Query()
	expStr := q.Get("exp")
	sig := q.Get("sig")
	if expStr == "" || sig == "" {
		s.renderReportExpired(w)
		return
	}

	exp, err := strconv.ParseInt(expStr, 10, 64)
	if err != nil {
		s.renderReportExpired(w)
		return
	}

	// Verify HMAC-SHA256
	reportKey := s.Master.DeriveKey("report-link-sign")
	if !crypto.VerifyReportLink(reportKey, linkID, exp, sig) {
		s.renderReportExpired(w)
		return
	}

	// Check expiration
	if time.Now().Unix() > exp {
		s.renderReportExpired(w)
		return
	}

	ctx := r.Context()
	delivery, err := s.Store.GetReportDeliveryByLink(ctx, linkID)
	if err != nil || delivery == nil || delivery.Status == "revoked" || delivery.LinkRevokedAt.Valid {
		s.renderReportExpired(w)
		return
	}

	report, err := s.Store.GetReport(ctx, delivery.ReportID)
	if err != nil || report == nil {
		s.renderReportExpired(w)
		return
	}

	_ = s.Store.TouchReportDeliveryOpened(ctx, linkID)

	s.renderReportHTML(w, report)
}

func (s *Server) renderReportExpired(w http.ResponseWriter) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.WriteHeader(http.StatusNotFound)
	_, _ = w.Write([]byte(`<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="robots" content="noindex">
  <meta name="referrer" content="no-referrer">
  <title>报告已失效</title>
  <style>body{font-family:sans-serif;max-width:600px;margin:40px auto;padding:0 20px;color:#333;text-align:center;}</style>
</head>
<body>
  <h2>报告链接已失效或过期</h2>
  <p>该学情简报链接已超过有效期，或已被管理员撤回。请登录管理后台查看最新学情。</p>
</body>
</html>`))
}

func (s *Server) renderReportHTML(w http.ResponseWriter, r *store.Report) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.WriteHeader(http.StatusOK)

	var parentText struct {
		Summary string `json:"summary"`
	}
	_ = json.Unmarshal([]byte(r.ParentTextJSON), &parentText)
	summary := parentText.Summary
	if summary == "" {
		summary = "今日学情已归档。"
	}

	var metrics struct {
		ActiveMinutes int `json:"active_minutes"`
		Attempts      int `json:"attempts"`
		Reveals       int `json:"reveals"`
		Mistakes      int `json:"mistakes"`
	}
	_ = json.Unmarshal([]byte(r.MetricsJSON), &metrics)

	html := fmt.Sprintf(`<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="robots" content="noindex">
  <meta name="referrer" content="no-referrer">
  <title>伴学日简报 - %s</title>
  <style>
    body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; max-width: 680px; margin: 30px auto; padding: 0 20px; color: #1f2937; line-height: 1.6; }
    .card { background: #fff; border: 1px solid #e5e7eb; border-radius: 12px; padding: 24px; box-shadow: 0 1px 3px rgba(0,0,0,0.05); }
    h1 { font-size: 20px; margin-top: 0; color: #111827; }
    .stats { display: flex; gap: 16px; margin: 20px 0; }
    .stat { flex: 1; background: #f9fafb; padding: 12px; border-radius: 8px; text-align: center; }
    .stat-val { font-size: 22px; font-weight: 700; color: #2563eb; }
    .stat-lbl { font-size: 13px; color: #6b7280; }
    .summary { background: #f0fdf4; border-left: 4px solid #22c55e; padding: 14px; border-radius: 4px; margin: 20px 0; }
    .footer { font-size: 12px; color: #9ca3af; margin-top: 24px; text-align: center; }
  </style>
</head>
<body>
  <div class="card">
    <h1>伴学日简报（%s）</h1>
    <div class="stats">
      <div class="stat"><div class="stat-val">%d</div><div class="stat-lbl">有效学习分钟</div></div>
      <div class="stat"><div class="stat-val">%d</div><div class="stat-lbl">作答题目</div></div>
      <div class="stat"><div class="stat-val">%d</div><div class="stat-lbl">看答案次数</div></div>
      <div class="stat"><div class="stat-val">%d</div><div class="stat-lbl">新增错题</div></div>
    </div>
    <div class="summary">
      <strong>学情摘要：</strong>%s
    </div>
    <div class="footer">数据来源：%s 模板 | 伴学 Minis 保护隐私与数据自托管</div>
  </div>
</body>
</html>`, r.PeriodStart, r.PeriodStart, metrics.ActiveMinutes, metrics.Attempts, metrics.Reveals, metrics.Mistakes, summary, r.TextSource)

	_, _ = w.Write([]byte(html))
}

func parseWait(raw string) int {
	if raw == "" {
		return 0
	}
	n, err := strconv.Atoi(raw)
	if err != nil || n < 0 {
		return 0
	}
	if n > 25 {
		return 25
	}
	return n
}

func LocalDateInShanghai(ms int64) string {
	loc := time.FixedZone("Asia/Shanghai", 8*3600)
	t := time.UnixMilli(ms).In(loc)
	if t.Hour() < 4 {
		t = t.Add(-24 * time.Hour)
	}
	return t.Format("2006-01-02")
}
