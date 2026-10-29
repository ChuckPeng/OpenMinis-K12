package admin

import (
	"database/sql"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/ChuckPeng/OpenMinis-K12/server/internal/auth"
	"github.com/ChuckPeng/OpenMinis-K12/server/internal/httpx"
	"github.com/ChuckPeng/OpenMinis-K12/server/internal/outbox"
	"github.com/ChuckPeng/OpenMinis-K12/server/internal/store"
	"github.com/ChuckPeng/OpenMinis-K12/server/internal/version"
	"github.com/ChuckPeng/OpenMinis-K12/server/internal/wecom"
	"github.com/google/uuid"
)

func (s *Server) setup(w http.ResponseWriter, r *http.Request) {
	n, err := s.Store.CountAdmins(r.Context())
	if err != nil {
		httpx.WriteError(w, 500, "internal", "store error")
		return
	}
	if n > 0 {
		httpx.WriteError(w, 409, "already_setup", "admin already configured")
		return
	}
	var body struct {
		Password string `json:"password"`
		Username string `json:"username"`
	}
	if err := httpx.DecodeJSON(r, &body); err != nil {
		httpx.WriteError(w, 400, "bad_request", "invalid json")
		return
	}
	if body.Username == "" {
		body.Username = "admin"
	}
	if err := auth.ValidatePassword(body.Password); err != nil {
		httpx.WriteError(w, 400, "weak_password", err.Error())
		return
	}
	hash, err := auth.HashPassword(body.Password)
	if err != nil {
		httpx.WriteError(w, 500, "internal", "hash failed")
		return
	}
	a, err := s.Store.CreateAdmin(r.Context(), body.Username, hash)
	if err != nil {
		httpx.WriteError(w, 500, "internal", "create admin failed")
		return
	}
	_, _ = s.Store.EnsureFamily(r.Context(), "home")
	csrf, _ := auth.RandomToken(32)
	sess, err := s.Store.CreateSession(r.Context(), a.ID, csrf, idleTTL, absoluteTTL)
	if err != nil {
		httpx.WriteError(w, 500, "internal", "session failed")
		return
	}
	s.setSessionCookie(w, r, sess.ID)
	_ = s.Store.AppendAudit(r.Context(), body.Username, "setup", "")
	w.Header().Set("X-CSRF-Token", csrf)
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) login(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Username string `json:"username"`
		Password string `json:"password"`
	}
	if err := httpx.DecodeJSON(r, &body); err != nil {
		httpx.WriteError(w, 400, "bad_request", "invalid json")
		return
	}
	if body.Username == "" {
		body.Username = "admin"
	}
	a, err := s.Store.GetAdminByUsername(r.Context(), body.Username)
	if err != nil || a == nil {
		httpx.WriteError(w, 401, "unauthorized", "invalid credentials")
		return
	}
	if a.LockedUntil.Valid {
		until, _ := time.Parse(time.RFC3339Nano, a.LockedUntil.String)
		if time.Now().UTC().Before(until) {
			httpx.WriteError(w, 429, "locked", "account locked")
			return
		}
	}
	ok, err := auth.VerifyPassword(a.PasswordHash, body.Password)
	if err != nil || !ok {
		fc := a.FailedCount + 1
		var until *time.Time
		if fc >= lockAfter {
			t := time.Now().UTC().Add(lockFor)
			until = &t
			fc = 0
		}
		_ = s.Store.RecordLoginFailure(r.Context(), a.ID, fc, until)
		if until != nil {
			httpx.WriteError(w, 429, "locked", "account locked")
			return
		}
		httpx.WriteError(w, 401, "unauthorized", "invalid credentials")
		return
	}
	_ = s.Store.RecordLoginSuccess(r.Context(), a.ID)
	csrf, _ := auth.RandomToken(32)
	sess, err := s.Store.CreateSession(r.Context(), a.ID, csrf, idleTTL, absoluteTTL)
	if err != nil {
		httpx.WriteError(w, 500, "internal", "session failed")
		return
	}
	s.setSessionCookie(w, r, sess.ID)
	_ = s.Store.AppendAudit(r.Context(), body.Username, "login", s.Proxies.ClientIP(r))
	w.Header().Set("X-CSRF-Token", csrf)
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) logout(w http.ResponseWriter, r *http.Request) {
	sess := r.Context().Value(ctxSession).(*store.Session)
	_ = s.Store.DeleteSession(r.Context(), sess.ID)
	s.clearSessionCookie(w)
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) sessionInfo(w http.ResponseWriter, r *http.Request) {
	sess := r.Context().Value(ctxSession).(*store.Session)
	a, _ := s.Store.GetAdmin(r.Context(), sess.AdminID)
	user := ""
	if a != nil {
		user = a.Username
	}
	n, _ := s.Store.CountAdmins(r.Context())
	httpx.WriteJSON(w, 200, map[string]any{
		"username":     user,
		"csrf_token":   sess.CSRFToken,
		"setup_done":   n > 0,
		"version":      version.String(),
		"public_base_url": s.Cfg.PublicBaseURL,
	})
}

func (s *Server) wecomApps(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodGet:
		s.withAuth(false, s.listWecom)(w, r)
	case http.MethodPut:
		s.withAuth(true, s.putWecom)(w, r)
	default:
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
	}
}

func (s *Server) listWecom(w http.ResponseWriter, r *http.Request) {
	apps, err := s.Store.ListWecomApps(r.Context())
	if err != nil {
		httpx.WriteError(w, 500, "internal", "list failed")
		return
	}
	out := make([]map[string]any, 0, len(apps))
	for _, a := range apps {
		item := map[string]any{
			"id":            a.ID,
			"corpid":        a.CorpID,
			"agentid":       a.AgentID,
			"token":         a.Token,
			"api_base_url":  a.APIBaseURL,
			"callback_key":  a.CallbackKey,
			"callback_url":  s.Cfg.CallbackURL(a.CallbackKey),
			"enabled":       a.Enabled,
			"secret_set":    a.SecretEnc != "",
			"aeskey_set":    a.AESKeyEnc != "",
		}
		if a.ButtonCallbackOK.Valid {
			item["button_callback_ok"] = a.ButtonCallbackOK.Bool
		} else {
			item["button_callback_ok"] = nil
		}
		out = append(out, item)
	}
	httpx.WriteJSON(w, 200, out)
}

func (s *Server) putWecom(w http.ResponseWriter, r *http.Request) {
	var body struct {
		ID         string `json:"id"`
		CorpID     string `json:"corpid"`
		AgentID    int    `json:"agentid"`
		Secret     string `json:"secret"`
		Token      string `json:"token"`
		AESKey     string `json:"encoding_aes_key"`
		APIBaseURL string `json:"api_base_url"`
		Enabled    *bool  `json:"enabled"`
	}
	if err := httpx.DecodeJSON(r, &body); err != nil {
		httpx.WriteError(w, 400, "bad_request", "invalid json")
		return
	}
	fam, err := s.Store.EnsureFamily(r.Context(), "home")
	if err != nil {
		httpx.WriteError(w, 500, "internal", "family")
		return
	}
	app := &store.WecomApp{
		ID:         body.ID,
		FamilyID:   fam,
		CorpID:     body.CorpID,
		AgentID:    body.AgentID,
		Token:      body.Token,
		APIBaseURL: body.APIBaseURL,
		Enabled:    true,
	}
	if body.Enabled != nil {
		app.Enabled = *body.Enabled
	}
	if body.ID != "" {
		if existing, _ := s.Store.GetWecomApp(r.Context(), body.ID); existing != nil {
			app.CallbackKey = existing.CallbackKey
			app.SecretEnc = existing.SecretEnc
			app.AESKeyEnc = existing.AESKeyEnc
			app.ButtonCallbackOK = existing.ButtonCallbackOK
		}
	}
	if body.Secret != "" {
		enc, err := s.Master.Seal([]byte(body.Secret))
		if err != nil {
			httpx.WriteError(w, 500, "internal", "seal failed")
			return
		}
		app.SecretEnc = enc
	}
	if body.AESKey != "" {
		if len(body.AESKey) != 43 {
			httpx.WriteError(w, 400, "bad_request", "encoding_aes_key must be 43 characters")
			return
		}
		enc, err := s.Master.Seal([]byte(body.AESKey))
		if err != nil {
			httpx.WriteError(w, 500, "internal", "seal failed")
			return
		}
		app.AESKeyEnc = enc
	}
	if err := s.Store.UpsertWecomApp(r.Context(), app); err != nil {
		httpx.WriteError(w, 500, "internal", "save failed")
		return
	}
	_ = s.Store.AppendAudit(r.Context(), "admin", "wecom_save", app.ID)
	s.listWecom(w, r)
}

func (s *Server) wecomAppsSub(w http.ResponseWriter, r *http.Request) {
	path := strings.TrimPrefix(r.URL.Path, "/api/v1/admin/wecom-apps/")
	parts := strings.Split(path, "/")
	if len(parts) < 2 {
		http.NotFound(w, r)
		return
	}
	id, action := parts[0], parts[1]
	switch action {
	case "test-token":
		s.withAuth(true, func(w http.ResponseWriter, r *http.Request) {
			s.testToken(w, r, id)
		})(w, r)
	case "test-send":
		s.withAuth(true, func(w http.ResponseWriter, r *http.Request) {
			s.testSend(w, r, id)
		})(w, r)
	case "mark-button-callback":
		s.withAuth(true, func(w http.ResponseWriter, r *http.Request) {
			s.markButton(w, r, id)
		})(w, r)
	default:
		http.NotFound(w, r)
	}
}

func (s *Server) testToken(w http.ResponseWriter, r *http.Request, id string) {
	app, err := s.Store.GetWecomApp(r.Context(), id)
	if err != nil || app == nil {
		httpx.WriteError(w, 404, "not_found", "app not found")
		return
	}
	secret, err := s.Master.Open(app.SecretEnc)
	if err != nil {
		httpx.WriteJSON(w, 200, map[string]any{"ok": false, "message": "secret not set"})
		return
	}
	cli := s.WeCom
	if cli == nil {
		cli = wecom.NewClient(app.APIBaseURL)
	}
	err = cli.TestToken(r.Context(), app.CorpID, string(secret))
	if err != nil {
		httpx.WriteJSON(w, 200, map[string]any{"ok": false, "message": "gettoken failed"})
		return
	}
	httpx.WriteJSON(w, 200, map[string]any{"ok": true, "message": "ok"})
}

func (s *Server) testSend(w http.ResponseWriter, r *http.Request, id string) {
	var body struct {
		Kind string `json:"kind"`
	}
	_ = httpx.DecodeJSON(r, &body)
	app, err := s.Store.GetWecomApp(r.Context(), id)
	if err != nil || app == nil {
		httpx.WriteError(w, 404, "not_found", "app not found")
		return
	}
	parents, _ := s.Store.ListParents(r.Context())
	to := "@all"
	for _, p := range parents {
		if p.WecomUserID != "" {
			to = p.WecomUserID
			break
		}
	}
	secret, err := s.Master.Open(app.SecretEnc)
	if err != nil {
		httpx.WriteJSON(w, 200, map[string]any{"ok": false, "message": "secret not set"})
		return
	}
	cli := wecom.NewClient(app.APIBaseURL)
	text := "伴学助手测试消息（无学生内容）"
	if body.Kind == "button_card" {
		text = "按钮卡片测试：若一分钟内无回调，请在管理页标记按钮回调失败并改用备用码。"
	}
	err = cli.SendText(r.Context(), app.CorpID, string(secret), app.AgentID, to, text)
	if err != nil {
		// Still enqueue so outbox path is exercised when API is down (R5).
		_ = outbox.EnqueueJSON(r.Context(), s.Store, app.ID, "test_send", map[string]any{
			"to": to, "text": text,
		}, nil)
		httpx.WriteJSON(w, 200, map[string]any{"ok": false, "message": "send failed; queued in outbox"})
		return
	}
	httpx.WriteJSON(w, 200, map[string]any{"ok": true, "message": "sent"})
}

func (s *Server) markButton(w http.ResponseWriter, r *http.Request, id string) {
	var body struct {
		OK bool `json:"ok"`
	}
	if err := httpx.DecodeJSON(r, &body); err != nil {
		httpx.WriteError(w, 400, "bad_request", "invalid json")
		return
	}
	if err := s.Store.SetButtonCallbackResult(r.Context(), id, body.OK); err != nil {
		httpx.WriteError(w, 500, "internal", "update failed")
		return
	}
	_ = s.Store.AppendAudit(r.Context(), "admin", "j14_button_callback", strconv.FormatBool(body.OK))
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) parents(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodGet:
		s.withAuth(false, s.listParents)(w, r)
	case http.MethodPost:
		s.withAuth(true, s.createParent)(w, r)
	default:
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
	}
}

func (s *Server) listParents(w http.ResponseWriter, r *http.Request) {
	list, err := s.Store.ListParents(r.Context())
	if err != nil {
		httpx.WriteError(w, 500, "internal", "list failed")
		return
	}
	out := make([]map[string]any, 0, len(list))
	for _, p := range list {
		out = append(out, map[string]any{
			"id": p.ID, "wecom_userid": p.WecomUserID, "label": p.Label,
			"can_approve": p.CanApprove, "channel": p.Channel,
		})
	}
	httpx.WriteJSON(w, 200, out)
}

func (s *Server) createParent(w http.ResponseWriter, r *http.Request) {
	var body struct {
		WecomUserID string `json:"wecom_userid"`
		Label       string `json:"label"`
		CanApprove  bool   `json:"can_approve"`
		Channel     string `json:"channel"`
	}
	if err := httpx.DecodeJSON(r, &body); err != nil || body.Label == "" {
		httpx.WriteError(w, 400, "bad_request", "label required")
		return
	}
	fam, _ := s.Store.EnsureFamily(r.Context(), "home")
	p := &store.Parent{FamilyID: fam, WecomUserID: body.WecomUserID, Label: body.Label, CanApprove: body.CanApprove, Channel: body.Channel}
	if err := s.Store.CreateParent(r.Context(), p); err != nil {
		httpx.WriteError(w, 500, "internal", "create failed")
		return
	}
	httpx.WriteJSON(w, 201, map[string]any{"id": p.ID, "label": p.Label, "wecom_userid": p.WecomUserID, "can_approve": p.CanApprove, "channel": p.Channel})
}

func (s *Server) parentsSub(w http.ResponseWriter, r *http.Request) {
	id := strings.TrimPrefix(r.URL.Path, "/api/v1/admin/parents/")
	id = strings.Trim(id, "/")
	if id == "" || strings.Contains(id, "/") {
		http.NotFound(w, r)
		return
	}
	switch r.Method {
	case http.MethodPut:
		s.withAuth(true, func(w http.ResponseWriter, r *http.Request) {
			var body struct {
				WecomUserID string `json:"wecom_userid"`
				Label       string `json:"label"`
				CanApprove  bool   `json:"can_approve"`
				Channel     string `json:"channel"`
			}
			if err := httpx.DecodeJSON(r, &body); err != nil {
				httpx.WriteError(w, 400, "bad_request", "invalid json")
				return
			}
			p := &store.Parent{ID: id, WecomUserID: body.WecomUserID, Label: body.Label, CanApprove: body.CanApprove, Channel: body.Channel}
			if err := s.Store.UpdateParent(r.Context(), p); err != nil {
				httpx.WriteError(w, 500, "internal", "update failed")
				return
			}
			httpx.WriteJSON(w, 200, map[string]any{"id": p.ID, "label": p.Label, "wecom_userid": p.WecomUserID, "can_approve": p.CanApprove, "channel": p.Channel})
		})(w, r)
	case http.MethodDelete:
		s.withAuth(true, func(w http.ResponseWriter, r *http.Request) {
			_ = s.Store.DeleteParent(r.Context(), id)
			w.WriteHeader(204)
		})(w, r)
	default:
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
	}
}

func (s *Server) listUnlocks(w http.ResponseWriter, r *http.Request) {
	list, err := s.Store.ListUnlockRequests(r.Context(), 50)
	if err != nil {
		httpx.WriteError(w, 500, "internal", "list failed")
		return
	}
	out := make([]map[string]any, 0, len(list))
	for _, u := range list {
		row := map[string]any{
			"id": u.ID, "device_id": u.DeviceID, "subject": u.Subject, "status": u.Status,
			"created_at": u.CreatedAt,
		}
		if u.DecidedBy.Valid {
			row["decided_by"] = u.DecidedBy.String
		}
		if u.DecidedAt.Valid {
			row["decided_at"] = u.DecidedAt.String
		}
		out = append(out, row)
	}
	httpx.WriteJSON(w, 200, out)
}

func (s *Server) listAudit(w http.ResponseWriter, r *http.Request) {
	list, err := s.Store.ListAudit(r.Context(), 100)
	if err != nil {
		httpx.WriteError(w, 500, "internal", "list failed")
		return
	}
	httpx.WriteJSON(w, 200, list)
}

func (s *Server) settings(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodGet:
		s.withAuth(false, func(w http.ResponseWriter, r *http.Request) {
			httpx.WriteJSON(w, 200, map[string]any{
				"public_base_url":  s.Cfg.PublicBaseURL,
				"trusted_proxies":  s.Cfg.TrustedProxies,
				"device_listen":    s.Cfg.DeviceListen,
				"admin_listen":     s.Cfg.AdminListen,
				"version":          version.String(),
			})
		})(w, r)
	case http.MethodPut:
		s.withAuth(true, func(w http.ResponseWriter, r *http.Request) {
			var body map[string]string
			if err := httpx.DecodeJSON(r, &body); err != nil {
				httpx.WriteError(w, 400, "bad_request", "invalid json")
				return
			}
			for k, v := range body {
				if k == "public_base_url" || k == "timezone" || strings.HasPrefix(k, "retention_") {
					_ = s.Store.SetSetting(r.Context(), k, v)
				}
			}
			w.WriteHeader(204)
		})(w, r)
	default:
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
	}
}

func (s *Server) status(w http.ResponseWriter, r *http.Request) {
	pending, _ := s.Store.CountOutboxPending(r.Context())
	apps, _ := s.Store.ListWecomApps(r.Context())
	var button any
	if len(apps) > 0 && apps[0].ButtonCallbackOK.Valid {
		button = apps[0].ButtonCallbackOK.Bool
	}
	httpx.WriteJSON(w, 200, map[string]any{
		"outbox_pending":      pending,
		"button_callback_ok":  button,
		"version":             version.String(),
		"public_base_url_set": s.Cfg.PublicBaseURL != "",
	})
}

// simulateJ12 is the callback harness used when WeCom cannot reach CI (WP-S-0).
func (s *Server) simulateJ12(w http.ResponseWriter, r *http.Request) {
	var body struct {
		AppID        string `json:"app_id"`
		FromUserName string `json:"from_userid"`
		EventKey     string `json:"event_key"`
		TaskID       string `json:"task_id"`
	}
	if err := httpx.DecodeJSON(r, &body); err != nil {
		httpx.WriteError(w, 400, "bad_request", "invalid json")
		return
	}
	app, err := s.Store.GetWecomApp(r.Context(), body.AppID)
	if err != nil || app == nil {
		apps, _ := s.Store.ListWecomApps(r.Context())
		if len(apps) == 0 {
			httpx.WriteError(w, 404, "not_found", "no wecom app")
			return
		}
		app = &apps[0]
	}
	ev := wecom.CardEvent{FromUserName: body.FromUserName, EventKey: body.EventKey, TaskID: body.TaskID}
	if err := wecom.HandleCardEvent(r.Context(), s.Store, app, ev); err != nil {
		httpx.WriteError(w, 400, "handler_error", err.Error())
		return
	}
	_ = s.Store.SetButtonCallbackResult(r.Context(), app.ID, true)
	httpx.WriteJSON(w, 200, map[string]any{"ok": true})
}

func (s *Server) createFallbackUnlock(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Subject string `json:"subject"`
	}
	_ = httpx.DecodeJSON(r, &body)
	taskID := uuid.NewString()
	req := &store.UnlockRequest{
		DeviceID:     "harness",
		QuestionHash: "0" + strings.Repeat("a", 63),
		Subject:      body.Subject,
		TaskID:       sql.NullString{String: taskID, Valid: true},
	}
	if err := s.Store.CreateUnlockRequest(r.Context(), req); err != nil {
		httpx.WriteError(w, 500, "internal", "create failed")
		return
	}
	apps, _ := s.Store.ListWecomApps(r.Context())
	useButtons := false
	appID := ""
	if len(apps) > 0 {
		appID = apps[0].ID
		useButtons = wecom.ButtonModeFromApp(&apps[0])
	}
	payload := wecom.BuildUnlockNotifyPayload(req, useButtons)
	exp := time.Now().UTC().Add(10 * time.Minute)
	_ = outbox.EnqueueJSON(r.Context(), s.Store, appID, "unlock_request", payload, &exp)
	httpx.WriteJSON(w, 201, map[string]any{
		"id":            req.ID,
		"task_id":       taskID,
		"response_code": req.ResponseCode.String,
		"use_buttons":   useButtons,
	})
}
