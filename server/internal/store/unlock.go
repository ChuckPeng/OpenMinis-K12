package store

import (
	"context"
	"crypto/rand"
	"database/sql"
	"fmt"
	"math/big"
	"time"
)

// UnlockRequest tracks a pending / decided unlock (no question text).
type UnlockRequest struct {
	ID           string
	DeviceID     string
	QuestionHash string
	Subject      string
	HintsUsed    int
	Status       string
	Scope        string
	CreatedAt    string
	ExpiresAt    string
	DecidedBy    sql.NullString
	DecidedAt    sql.NullString
	TaskID       sql.NullString
	ResponseCode sql.NullString
	ConsumedAt   sql.NullString
}

func (s *Store) CreateUnlockRequest(ctx context.Context, r *UnlockRequest) error {
	if r.ID == "" {
		r.ID = newID()
	}
	if r.Status == "" {
		r.Status = "pending"
	}
	if r.Scope == "" {
		r.Scope = "question"
	}
	if r.CreatedAt == "" {
		r.CreatedAt = NowUTC()
	}
	if r.ExpiresAt == "" {
		r.ExpiresAt = time.Now().UTC().Add(10 * time.Minute).Format(time.RFC3339Nano)
	}
	code, err := sixDigitCode()
	if err != nil {
		return err
	}
	r.ResponseCode = sql.NullString{String: code, Valid: true}
	_, err = s.DB.ExecContext(ctx, `
INSERT INTO unlock_requests (
  id, device_id, question_hash, subject, hints_used, status, scope,
  created_at, expires_at, task_id, response_code
) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
		r.ID, r.DeviceID, r.QuestionHash, r.Subject, r.HintsUsed, r.Status, r.Scope,
		r.CreatedAt, r.ExpiresAt, r.TaskID, r.ResponseCode.String)
	return err
}

func sixDigitCode() (string, error) {
	n, err := rand.Int(rand.Reader, big.NewInt(1000000))
	if err != nil {
		return "", err
	}
	return fmt.Sprintf("%06d", n.Int64()), nil
}

func (s *Store) GetUnlockRequest(ctx context.Context, id string) (*UnlockRequest, error) {
	r := &UnlockRequest{}
	err := s.DB.QueryRowContext(ctx, `
SELECT id, device_id, question_hash, subject, hints_used, status, scope,
       created_at, expires_at, decided_by, decided_at, task_id, response_code, consumed_at
FROM unlock_requests WHERE id=?`, id).Scan(
		&r.ID, &r.DeviceID, &r.QuestionHash, &r.Subject, &r.HintsUsed, &r.Status, &r.Scope,
		&r.CreatedAt, &r.ExpiresAt, &r.DecidedBy, &r.DecidedAt, &r.TaskID, &r.ResponseCode, &r.ConsumedAt)
	if err == sql.ErrNoRows {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return r, nil
}

func (s *Store) GetUnlockByTaskID(ctx context.Context, taskID string) (*UnlockRequest, error) {
	r := &UnlockRequest{}
	err := s.DB.QueryRowContext(ctx, `
SELECT id, device_id, question_hash, subject, hints_used, status, scope,
       created_at, expires_at, decided_by, decided_at, task_id, response_code, consumed_at
FROM unlock_requests WHERE task_id=?`, taskID).Scan(
		&r.ID, &r.DeviceID, &r.QuestionHash, &r.Subject, &r.HintsUsed, &r.Status, &r.Scope,
		&r.CreatedAt, &r.ExpiresAt, &r.DecidedBy, &r.DecidedAt, &r.TaskID, &r.ResponseCode, &r.ConsumedAt)
	if err == sql.ErrNoRows {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return r, nil
}

func (s *Store) GetUnlockByResponseCode(ctx context.Context, code string) (*UnlockRequest, error) {
	r := &UnlockRequest{}
	err := s.DB.QueryRowContext(ctx, `
SELECT id, device_id, question_hash, subject, hints_used, status, scope,
       created_at, expires_at, decided_by, decided_at, task_id, response_code, consumed_at
FROM unlock_requests WHERE response_code=? AND status='pending'`, code).Scan(
		&r.ID, &r.DeviceID, &r.QuestionHash, &r.Subject, &r.HintsUsed, &r.Status, &r.Scope,
		&r.CreatedAt, &r.ExpiresAt, &r.DecidedBy, &r.DecidedAt, &r.TaskID, &r.ResponseCode, &r.ConsumedAt)
	if err == sql.ErrNoRows {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return r, nil
}

// DecideUnlock applies an approval/denial idempotently (J12).
func (s *Store) DecideUnlock(ctx context.Context, id, status, decidedBy, scope string) (bool, error) {
	cur, err := s.GetUnlockRequest(ctx, id)
	if err != nil || cur == nil {
		return false, err
	}
	if cur.Status != "pending" {
		return false, nil // already decided — idempotent no-op
	}
	exp, err := time.Parse(time.RFC3339Nano, cur.ExpiresAt)
	if err == nil && time.Now().UTC().After(exp) {
		_, _ = s.DB.ExecContext(ctx, `UPDATE unlock_requests SET status='expired' WHERE id=? AND status='pending'`, id)
		return false, fmt.Errorf("expired")
	}
	if scope == "" {
		scope = cur.Scope
	}
	res, err := s.DB.ExecContext(ctx, `
UPDATE unlock_requests SET status=?, decided_by=?, decided_at=?, scope=?
WHERE id=? AND status='pending'`, status, decidedBy, NowUTC(), scope, id)
	if err != nil {
		return false, err
	}
	n, _ := res.RowsAffected()
	return n > 0, nil
}

func (s *Store) ListUnlockRequests(ctx context.Context, limit int) ([]UnlockRequest, error) {
	if limit <= 0 {
		limit = 50
	}
	rows, err := s.DB.QueryContext(ctx, `
SELECT id, device_id, question_hash, subject, hints_used, status, scope,
       created_at, expires_at, decided_by, decided_at, task_id, response_code, consumed_at
FROM unlock_requests ORDER BY created_at DESC LIMIT ?`, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []UnlockRequest
	for rows.Next() {
		var r UnlockRequest
		if err := rows.Scan(&r.ID, &r.DeviceID, &r.QuestionHash, &r.Subject, &r.HintsUsed, &r.Status, &r.Scope,
			&r.CreatedAt, &r.ExpiresAt, &r.DecidedBy, &r.DecidedAt, &r.TaskID, &r.ResponseCode, &r.ConsumedAt); err != nil {
			return nil, err
		}
		out = append(out, r)
	}
	return out, rows.Err()
}
