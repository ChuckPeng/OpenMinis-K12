package admin

import (
	"bytes"
	"crypto/rand"
	"encoding/json"
	"fmt"
	"math/big"
	"net/http"
	"strings"
	"text/template"
	"time"

	"github.com/ChuckPeng/OpenMinis-K12/server/internal/crypto"
	"github.com/ChuckPeng/OpenMinis-K12/server/internal/deviceapi"
	"github.com/ChuckPeng/OpenMinis-K12/server/internal/httpx"
	"github.com/ChuckPeng/OpenMinis-K12/server/internal/store"
	"github.com/google/uuid"
)

// Devices
func (s *Server) listDevices(w http.ResponseWriter, r *http.Request) {
	devices, err := s.Store.ListDevices(r.Context())
	if err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, "internal_error", "failed to list devices")
		return
	}
	out := make([]map[string]any, 0, len(devices))
	for _, d := range devices {
		child, _ := s.Store.GetChild(r.Context(), d.ChildID)
		childLabel := d.ChildID
		if child != nil && child.Label != "" {
			childLabel = child.Label
		}
		item := map[string]any{
			"id":                     d.ID,
			"child_id":               d.ChildID,
			"child_label":            childLabel,
			"label":                  d.Label,
			"pubkey":                 d.Pubkey,
			"status":                 d.Status,
			"app_version":            d.AppVersion,
			"config_version_applied": d.ConfigVersionApplied,
			"created_at":             d.CreatedAt,
		}
		if d.PairedAt.Valid {
			item["paired_at"] = d.PairedAt.String
		}
		if d.RevokedAt.Valid {
			item["revoked_at"] = d.RevokedAt.String
		}
		if d.LastSeen.Valid {
			item["last_seen"] = d.LastSeen.String
		}
		out = append(out, item)
	}
	httpx.WriteJSON(w, http.StatusOK, out)
}

func (s *Server) devicesSub(w http.ResponseWriter, r *http.Request) {
	path := strings.TrimPrefix(r.URL.Path, "/api/v1/admin/devices/")
	parts := strings.Split(path, "/")
	id := parts[0]
	if len(parts) >= 2 && parts[1] == "revoke" && r.Method == http.MethodPost {
		s.withAuth(true, func(w http.ResponseWriter, r *http.Request) {
			_ = s.Store.UpdateDeviceStatus(r.Context(), id, "revoked")
			if s.Notifier != nil {
				s.Notifier.Wake("pair:" + id)
			}
			_ = s.Store.AppendAudit(r.Context(), s.currentUser(r), "revoke_device", id)
			w.WriteHeader(http.StatusNoContent)
		})(w, r)
		return
	}
	if len(parts) >= 2 && parts[1] == "approve" && r.Method == http.MethodPost {
		s.withAuth(true, func(w http.ResponseWriter, r *http.Request) {
			_ = s.Store.UpdateDeviceStatus(r.Context(), id, "active")
			if s.Notifier != nil {
				s.Notifier.Wake("pair:" + id)
			}
			_ = s.Store.AppendAudit(r.Context(), s.currentUser(r), "approve_device", id)
			w.WriteHeader(http.StatusNoContent)
		})(w, r)
		return
	}
	http.NotFound(w, r)
}

func (s *Server) unlockRequestsSub(w http.ResponseWriter, r *http.Request) {
	// e.g. /api/v1/admin/unlock-requests/{id}/decide
	path := strings.TrimPrefix(r.URL.Path, "/api/v1/admin/unlock-requests/")
	parts := strings.Split(path, "/")
	if len(parts) >= 2 && parts[1] == "decide" && r.Method == http.MethodPost {
		id := parts[0]
		s.withAuth(true, func(w http.ResponseWriter, r *http.Request) {
			var req struct {
				Decision string `json:"decision"`
				Scope    string `json:"scope"`
			}
			if err := httpx.DecodeJSON(r, &req); err != nil {
				httpx.WriteError(w, http.StatusBadRequest, "bad_request", "invalid json")
				return
			}
			status := "approved"
			if strings.EqualFold(req.Decision, "deny") || strings.EqualFold(req.Decision, "denied") {
				status = "denied"
			}
			ok, err := s.Store.DecideUnlock(r.Context(), id, status, s.currentUser(r), req.Scope)
			if err != nil {
				httpx.WriteError(w, http.StatusInternalServerError, "internal_error", err.Error())
				return
			}
			if !ok {
				httpx.WriteError(w, http.StatusConflict, "conflict", "request already decided or expired")
				return
			}
			if s.Notifier != nil {
				s.Notifier.Wake("unlock:" + id)
			}
			_ = s.Store.AppendAudit(r.Context(), s.currentUser(r), "decide_unlock", fmt.Sprintf("%s:%s", id, status))
			httpx.WriteJSON(w, http.StatusOK, map[string]any{"ok": true, "status": status})
		})(w, r)
		return
	}
	http.NotFound(w, r)
}

// Pair codes: 6-digit one-time code, 10 min expiry
func (s *Server) createPairCode(w http.ResponseWriter, r *http.Request) {
	n, err := rand.Int(rand.Reader, big.NewInt(1000000))
	if err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, "internal_error", "failed to generate code")
		return
	}
	code := fmt.Sprintf("%06d", n.Int64())
	familyID := "home"
	fam, err := s.Store.EnsureFamily(r.Context(), "home")
	if err == nil && fam != "" {
		familyID = fam
	}

	ttl := 10 * time.Minute
	if err := s.Store.CreatePairCode(r.Context(), familyID, code, ttl); err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, "internal_error", "failed to store pair code")
		return
	}

	exp := time.Now().UTC().Add(ttl).Format(time.RFC3339Nano)
	qrPayload := map[string]string{
		"public_base_url": s.Cfg.PublicBaseURL,
		"pair_code":       code,
	}
	qrBytes, _ := json.Marshal(qrPayload)

	_ = s.Store.AppendAudit(r.Context(), s.currentUser(r), "create_pair_code", code)

	httpx.WriteJSON(w, http.StatusOK, map[string]any{
		"code":       code,
		"pair_code":  code,
		"expires_at": exp,
		"qr_payload": string(qrBytes),
	})
}

// Alerts & Snippet Audit
func (s *Server) listAlerts(w http.ResponseWriter, r *http.Request) {
	alerts, err := s.Store.ListAlerts(r.Context(), 50)
	if err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, "internal_error", "failed to list alerts")
		return
	}
	out := make([]map[string]any, 0, len(alerts))
	for _, a := range alerts {
		item := map[string]any{
			"id":              a.ID,
			"device_id":       a.DeviceID,
			"child_id":        a.ChildID,
			"level":           a.Level,
			"category":        a.Category,
			"occurred_at":     a.OccurredAt,
			"has_snippet":     a.SnippetEnc != "",
			"created_at":      a.CreatedAt,
			"acknowledged_by": a.AcknowledgedBy.String,
		}
		out = append(out, item)
	}
	httpx.WriteJSON(w, http.StatusOK, out)
}

func (s *Server) alertsSub(w http.ResponseWriter, r *http.Request) {
	path := strings.TrimPrefix(r.URL.Path, "/api/v1/admin/alerts/")
	parts := strings.Split(path, "/")
	id := parts[0]
	if len(parts) >= 2 && parts[1] == "snippet" && r.Method == http.MethodGet {
		s.withAuth(false, func(w http.ResponseWriter, r *http.Request) {
			alert, err := s.Store.GetAlert(r.Context(), id)
			if err != nil || alert == nil {
				httpx.WriteError(w, http.StatusNotFound, "not_found", "alert not found")
				return
			}
			if alert.SnippetEnc == "" {
				httpx.WriteJSON(w, http.StatusOK, map[string]string{"snippet": ""})
				return
			}
			plain, err := s.Master.Open(alert.SnippetEnc)
			if err != nil {
				httpx.WriteError(w, http.StatusInternalServerError, "internal_error", "decrypt failed")
				return
			}
			// Viewing snippet is strictly logged in audit_log
			_ = s.Store.AppendAudit(r.Context(), s.currentUser(r), "view_alert_snippet", id)
			httpx.WriteJSON(w, http.StatusOK, map[string]string{"snippet": string(plain)})
		})(w, r)
		return
	}
	if len(parts) >= 2 && parts[1] == "ack" && r.Method == http.MethodPost {
		s.withAuth(true, func(w http.ResponseWriter, r *http.Request) {
			user := s.currentUser(r)
			_ = s.Store.AcknowledgeAlert(r.Context(), id, user)
			_ = s.Store.AppendAudit(r.Context(), user, "ack_alert", id)
			w.WriteHeader(http.StatusNoContent)
		})(w, r)
		return
	}
	http.NotFound(w, r)
}

// Children & Config
func (s *Server) listChildren(w http.ResponseWriter, r *http.Request) {
	children, err := s.Store.ListChildren(r.Context())
	if err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, "internal_error", "failed to list children")
		return
	}
	out := make([]map[string]any, 0, len(children))
	for _, c := range children {
		item := map[string]any{
			"id":         c.ID,
			"label":      c.Label,
			"stage":      c.Stage,
			"device_id":  c.DeviceID,
			"created_at": c.CreatedAt,
		}
		if c.DeviceID != "" {
			ver, cfgJSON, _ := s.Store.GetDeviceConfig(r.Context(), c.DeviceID)
			item["config_version"] = ver
			var parsed any
			_ = json.Unmarshal([]byte(cfgJSON), &parsed)
			item["config_json"] = parsed
		}
		out = append(out, item)
	}
	httpx.WriteJSON(w, http.StatusOK, out)
}

func (s *Server) childrenSub(w http.ResponseWriter, r *http.Request) {
	path := strings.TrimPrefix(r.URL.Path, "/api/v1/admin/children/")
	parts := strings.Split(path, "/")
	id := parts[0]
	if len(parts) >= 2 && parts[1] == "config" && r.Method == http.MethodPut {
		s.withAuth(true, func(w http.ResponseWriter, r *http.Request) {
			var body map[string]any
			if err := httpx.DecodeJSON(r, &body); err != nil {
				httpx.WriteError(w, http.StatusBadRequest, "bad_request", "invalid json")
				return
			}
			// Enforce §19.4 allowed keys only (NO alert thresholds!)
			allowed := make(map[string]any)
			allowedKeys := []string{
				"stage", "daily_limit_minutes", "weekend_extra_minutes",
				"break_reminder_minutes", "late_night_start", "late_night_end",
				"reveal_policy", "turn_limit", "image_limit", "companion_limit_minutes",
				"study_day_min_minutes", "hotline_text", "hotline_number",
			}
			for _, k := range allowedKeys {
				if v, exists := body[k]; exists {
					allowed[k] = v
				}
			}
			cfgBytes, _ := json.Marshal(allowed)
			user := s.currentUser(r)

			child, err := s.Store.GetChild(r.Context(), id)
			if err != nil || child == nil {
				httpx.WriteError(w, http.StatusNotFound, "not_found", "child not found")
				return
			}
			if child.DeviceID == "" {
				httpx.WriteError(w, http.StatusBadRequest, "no_device", "child has no bound device")
				return
			}

			newVer, err := s.Store.SetDeviceConfig(r.Context(), child.DeviceID, string(cfgBytes), user)
			if err != nil {
				httpx.WriteError(w, http.StatusInternalServerError, "internal_error", "failed to update config")
				return
			}
			_ = s.Store.AppendAudit(r.Context(), user, "update_config", fmt.Sprintf("%s:v%d", child.DeviceID, newVer))
			httpx.WriteJSON(w, http.StatusOK, map[string]any{
				"config_version": newVer,
				"config_json":    allowed,
			})
		})(w, r)
		return
	}
	http.NotFound(w, r)
}

// Reports & Links
func (s *Server) listReports(w http.ResponseWriter, r *http.Request) {
	reports, err := s.Store.ListReports(r.Context(), "", 50)
	if err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, "internal_error", "failed to list reports")
		return
	}
	out := make([]map[string]any, 0, len(reports))
	for _, rep := range reports {
		var metricsObj, parentTextObj any
		_ = json.Unmarshal([]byte(rep.MetricsJSON), &metricsObj)
		_ = json.Unmarshal([]byte(rep.ParentTextJSON), &parentTextObj)
		item := map[string]any{
			"id":               rep.ID,
			"child_id":         rep.ChildID,
			"kind":             rep.Kind,
			"period_start":     rep.PeriodStart,
			"period_end":       rep.PeriodEnd,
			"metrics_json":     metricsObj,
			"parent_text_json": parentTextObj,
			"text_source":      rep.TextSource,
			"status":           rep.Status,
			"generated_at":     rep.GeneratedAt,
		}
		out = append(out, item)
	}
	httpx.WriteJSON(w, http.StatusOK, out)
}

const defaultReportTemplate = `{{.ChildLabel}}今日有效学习 {{.ActiveMinutes}} 分钟，共完成 {{.Attempts}} 次作答，首次答对率 {{.AccuracyPercent}}%。揭晓查看答案 {{.Reveals}} 次，新增错题 {{.Mistakes}} 道。{{if eq .Reveals 0}}今日独立思考表现优异！{{end}}`

func (s *Server) generateReport(w http.ResponseWriter, r *http.Request) {
	var req struct {
		ChildID string `json:"child_id"`
		Date    string `json:"date"` // YYYY-MM-DD
	}
	if err := httpx.DecodeJSON(r, &req); err != nil {
		httpx.WriteError(w, http.StatusBadRequest, "bad_request", "invalid json")
		return
	}
	if req.ChildID == "" {
		httpx.WriteError(w, http.StatusBadRequest, "bad_request", "child_id required")
		return
	}
	if req.Date == "" {
		req.Date = deviceapi.LocalDateInShanghai(time.Now().UnixMilli())
	}

	ctx := r.Context()
	child, _ := s.Store.GetChild(ctx, req.ChildID)
	childLabel := req.ChildID
	if child != nil && child.Label != "" {
		childLabel = child.Label
	}

	aggs, err := s.Store.GetDailyAggregate(ctx, req.ChildID, req.Date)
	if err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, "internal_error", "failed to read aggregate")
		return
	}

	var activeSec, attempts, firstCorrect, hints, reveals, mistakes, flags int
	for _, a := range aggs {
		activeSec += a.ActiveSeconds
		attempts += a.AttemptsCount
		firstCorrect += a.FirstTryCorrect
		hints += a.HintsUsedCount
		reveals += a.RevealsCount
		mistakes += a.MistakesCount
		flags += a.FlagsCount
	}
	activeMin := activeSec / 60
	accuracyPct := 0
	if attempts > 0 {
		accuracyPct = (firstCorrect * 100) / attempts
	}

	tmplData := map[string]any{
		"ChildLabel":      childLabel,
		"Date":            req.Date,
		"ActiveMinutes":   activeMin,
		"Attempts":        attempts,
		"FirstCorrect":    firstCorrect,
		"Hints":           hints,
		"Reveals":         reveals,
		"Mistakes":        mistakes,
		"Flags":           flags,
		"AccuracyPercent": accuracyPct,
	}

	tmpl, err := template.New("briefing").Parse(defaultReportTemplate)
	if err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, "internal_error", "template error")
		return
	}
	var buf bytes.Buffer
	_ = tmpl.Execute(&buf, tmplData)
	summaryText := buf.String()

	metricsMap := map[string]any{
		"active_minutes": activeMin,
		"attempts":       attempts,
		"first_correct":  firstCorrect,
		"reveals":        reveals,
		"mistakes":       mistakes,
		"flags":          flags,
	}
	metricsJSON, _ := json.Marshal(metricsMap)
	parentTextJSON, _ := json.Marshal(map[string]string{"summary": summaryText})
	childTextJSON, _ := json.Marshal(map[string]string{"summary": fmt.Sprintf("你今天完成了 %d 次练习，用时 %d 分钟！", attempts, activeMin)})

	repID := uuid.NewString()
	rep := &store.Report{
		ID:               repID,
		ChildID:          req.ChildID,
		Kind:             "daily",
		PeriodStart:      req.Date,
		PeriodEnd:        req.Date,
		MetricsJSON:      string(metricsJSON),
		ParentTextJSON:   string(parentTextJSON),
		ChildTextJSON:    string(childTextJSON),
		ParentAdviceJSON: `{"parent_advice": "亲子建议仅供家长参考"}`,
		TextSource:       "template",
	}
	if err := s.Store.CreateReport(ctx, rep); err != nil {
		httpx.WriteError(w, http.StatusInternalServerError, "internal_error", "failed to create report")
		return
	}

	// Create signed delivery link (72h expiry, S16)
	linkID := uuid.NewString()
	exp := time.Now().Add(72 * time.Hour).Unix()
	reportKey := s.Master.DeriveKey("report-link-sign")
	sig := crypto.SignReportLink(reportKey, linkID, exp)
	signedURL := fmt.Sprintf("%s/r/%s?exp=%d&sig=%s", s.Cfg.PublicBaseURL, linkID, exp, sig)

	delivery := &store.ReportDelivery{
		ReportID:      repID,
		Channel:       "web",
		LinkID:        linkID,
		LinkExpiresAt: time.Now().Add(72 * time.Hour).Format(time.RFC3339Nano),
		Status:        "active",
	}
	_ = s.Store.CreateReportDelivery(ctx, delivery)

	_ = s.Store.AppendAudit(ctx, s.currentUser(r), "generate_report", repID)

	httpx.WriteJSON(w, http.StatusOK, map[string]any{
		"report_id":  repID,
		"link_id":    linkID,
		"signed_url": signedURL,
		"summary":    summaryText,
		"metrics":    metricsMap,
		"exp":        exp,
		"sig":        sig,
	})
}

func (s *Server) reportsSub(w http.ResponseWriter, r *http.Request) {
	// e.g. /api/v1/admin/reports/{id}/links/{link_id}/revoke
	path := strings.TrimPrefix(r.URL.Path, "/api/v1/admin/reports/")
	parts := strings.Split(path, "/")
	if len(parts) >= 4 && parts[1] == "links" && parts[3] == "revoke" && r.Method == http.MethodPost {
		linkID := parts[2]
		s.withAuth(true, func(w http.ResponseWriter, r *http.Request) {
			_ = s.Store.RevokeReportLink(r.Context(), linkID)
			_ = s.Store.AppendAudit(r.Context(), s.currentUser(r), "revoke_report_link", linkID)
			w.WriteHeader(http.StatusNoContent)
		})(w, r)
		return
	}
	http.NotFound(w, r)
}

func (s *Server) currentUser(r *http.Request) string {
	sessVal := r.Context().Value(ctxSession)
	if sessVal == nil {
		return "admin"
	}
	sess, ok := sessVal.(*store.Session)
	if !ok || sess == nil {
		return "admin"
	}
	a, err := s.Store.GetAdmin(r.Context(), sess.AdminID)
	if err == nil && a != nil {
		return a.Username
	}
	return "admin"
}
