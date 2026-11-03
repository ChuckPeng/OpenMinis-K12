package wecom

import (
	"context"
	"database/sql"
	"fmt"
	"strings"

	"github.com/ChuckPeng/OpenMinis-K12/server/internal/store"
)

// Event keys used on unlock template cards.
const (
	EventApproveQuestion = "approve_question"
	EventUnlock15        = "unlock_15"
	EventDeny            = "deny"
)

// HandleCardEvent processes a template_card_event (J12). Non-approvers are no-ops (J7).
func HandleCardEvent(ctx context.Context, st *store.Store, app *store.WecomApp, ev CardEvent) error {
	parent, err := st.GetParentByWecomUser(ctx, app.FamilyID, ev.FromUserName)
	if err != nil {
		return err
	}
	if parent == nil || !parent.CanApprove {
		_ = st.AppendAudit(ctx, "wecom:"+ev.FromUserName, "card_ignored_not_approver", ev.TaskID)
		return nil
	}
	req, err := st.GetUnlockByTaskID(ctx, ev.TaskID)
	if err != nil {
		return err
	}
	if req == nil {
		return fmt.Errorf("unknown task_id")
	}
	status, scope := "approved", req.Scope
	switch ev.EventKey {
	case EventApproveQuestion:
		status, scope = "approved", "question"
	case EventUnlock15:
		status, scope = "approved", "minutes_15"
	case EventDeny:
		status, scope = "denied", req.Scope
	default:
		_ = st.AppendAudit(ctx, parent.Label, "card_unknown_key", ev.EventKey)
		return nil
	}
	changed, err := st.DecideUnlock(ctx, req.ID, status, parent.Label, scope)
	if err != nil {
		return err
	}
	if changed {
		_ = st.AppendAudit(ctx, parent.Label, "unlock_"+status, req.ID)
	}
	return nil
}

// ApproveByBackupCode implements the text / 6-digit fallback (J14).
func ApproveByBackupCode(ctx context.Context, st *store.Store, app *store.WecomApp, fromUser, code string) error {
	parent, err := st.GetParentByWecomUser(ctx, app.FamilyID, fromUser)
	if err != nil {
		return err
	}
	label := fromUser
	if parent != nil {
		if !parent.CanApprove {
			_ = st.AppendAudit(ctx, "wecom:"+fromUser, "code_ignored_not_approver", code)
			return nil
		}
		label = parent.Label
	}
	req, err := st.GetUnlockByResponseCode(ctx, code)
	if err != nil {
		return err
	}
	if req == nil {
		return nil
	}
	changed, err := st.DecideUnlock(ctx, req.ID, "approved", label, req.Scope)
	if err != nil {
		return err
	}
	if changed {
		_ = st.AppendAudit(ctx, label, "unlock_approved_backup_code", req.ID)
	}
	return nil
}

// BuildUnlockNotifyPayload builds outbox JSON for an unlock notification.
// When buttonCallbackOK is false/unknown, include the 6-digit fallback prominently.
func BuildUnlockNotifyPayload(req *store.UnlockRequest, useButtons bool) map[string]any {
	code := ""
	if req.ResponseCode.Valid {
		code = req.ResponseCode.String
	}
	payload := map[string]any{
		"kind":          "unlock_request",
		"request_id":    req.ID,
		"subject":       req.Subject,
		"task_id":       nullString(req.TaskID),
		"response_code": code,
		"use_buttons":   useButtons,
		// Minimized content: type, parent label filled at send, time, level, backup code.
		"text": fmt.Sprintf("解锁申请（%s）。备用码 %s。将码告知孩子即可批准（文本路径）。",
			safeSubject(req.Subject), code),
	}
	if useButtons {
		payload["card_buttons"] = []string{EventApproveQuestion, EventUnlock15, EventDeny}
	}
	return payload
}

func nullString(ns sql.NullString) string {
	if ns.Valid {
		return ns.String
	}
	return ""
}

func safeSubject(s string) string {
	s = strings.TrimSpace(s)
	if s == "" {
		return "科目未标注"
	}
	return s
}

// ButtonModeFromApp returns whether button cards should be primary (J14).
func ButtonModeFromApp(app *store.WecomApp) bool {
	return app.ButtonCallbackOK.Valid && app.ButtonCallbackOK.Bool
}
