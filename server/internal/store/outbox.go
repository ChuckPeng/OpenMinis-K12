package store

import (
	"context"
	"database/sql"
	"time"
)

// OutboxItem is a durable outbound message.
type OutboxItem struct {
	ID          string
	AppID       string
	Kind        string
	PayloadJSON string
	Attempts    int
	NextAt      string
	Status      string
	ExpiresAt   sql.NullString
	LastError   sql.NullString
	CreatedAt   string
}

func (s *Store) EnqueueOutbox(ctx context.Context, item *OutboxItem) error {
	if item.ID == "" {
		item.ID = newID()
	}
	if item.Status == "" {
		item.Status = "pending"
	}
	if item.CreatedAt == "" {
		item.CreatedAt = NowUTC()
	}
	if item.NextAt == "" {
		item.NextAt = item.CreatedAt
	}
	_, err := s.DB.ExecContext(ctx, `
INSERT INTO outbox (id, app_id, kind, payload_json, attempts, next_at, status, expires_at, created_at)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`,
		item.ID, item.AppID, item.Kind, item.PayloadJSON, item.Attempts, item.NextAt, item.Status, item.ExpiresAt, item.CreatedAt)
	return err
}

func (s *Store) ClaimDueOutbox(ctx context.Context, limit int) ([]OutboxItem, error) {
	now := NowUTC()
	rows, err := s.DB.QueryContext(ctx, `
SELECT id, app_id, kind, payload_json, attempts, next_at, status, expires_at, last_error, created_at
FROM outbox
WHERE status='pending' AND next_at<=?
ORDER BY next_at ASC LIMIT ?`, now, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []OutboxItem
	for rows.Next() {
		var it OutboxItem
		if err := rows.Scan(&it.ID, &it.AppID, &it.Kind, &it.PayloadJSON, &it.Attempts, &it.NextAt, &it.Status, &it.ExpiresAt, &it.LastError, &it.CreatedAt); err != nil {
			return nil, err
		}
		out = append(out, it)
	}
	return out, rows.Err()
}

func (s *Store) MarkOutboxSent(ctx context.Context, id string) error {
	_, err := s.DB.ExecContext(ctx, `UPDATE outbox SET status='sent' WHERE id=?`, id)
	return err
}

func (s *Store) MarkOutboxDropped(ctx context.Context, id, reason string) error {
	_, err := s.DB.ExecContext(ctx, `UPDATE outbox SET status='dropped', last_error=? WHERE id=?`, reason, id)
	return err
}

func (s *Store) MarkOutboxRetry(ctx context.Context, id string, attempts int, next time.Time, lastErr string) error {
	_, err := s.DB.ExecContext(ctx, `
UPDATE outbox SET attempts=?, next_at=?, last_error=? WHERE id=?`,
		attempts, next.UTC().Format(time.RFC3339Nano), lastErr, id)
	return err
}

func (s *Store) CountOutboxPending(ctx context.Context) (int, error) {
	var n int
	err := s.DB.QueryRowContext(ctx, `SELECT COUNT(*) FROM outbox WHERE status='pending'`).Scan(&n)
	return n, err
}

func (s *Store) AppendAudit(ctx context.Context, actor, action, target string) error {
	_, err := s.DB.ExecContext(ctx, `INSERT INTO audit_log (actor, action, target, at) VALUES (?, ?, ?, ?)`,
		actor, action, target, NowUTC())
	return err
}

type AuditRow struct {
	Actor  string `json:"actor"`
	Action string `json:"action"`
	Target string `json:"target"`
	At     string `json:"at"`
}

func (s *Store) ListAudit(ctx context.Context, limit int) ([]AuditRow, error) {
	if limit <= 0 {
		limit = 100
	}
	rows, err := s.DB.QueryContext(ctx, `SELECT actor, action, target, at FROM audit_log ORDER BY id DESC LIMIT ?`, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []AuditRow
	for rows.Next() {
		var r AuditRow
		if err := rows.Scan(&r.Actor, &r.Action, &r.Target, &r.At); err != nil {
			return nil, err
		}
		out = append(out, r)
	}
	return out, rows.Err()
}
