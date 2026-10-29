package store

import (
	"context"
	"database/sql"
	"time"
)

// Admin is a row in admins.
type Admin struct {
	ID           string
	Username     string
	PasswordHash string
	FailedCount  int
	LockedUntil  sql.NullString
	LastLogin    sql.NullString
}

func (s *Store) CountAdmins(ctx context.Context) (int, error) {
	var n int
	err := s.DB.QueryRowContext(ctx, `SELECT COUNT(*) FROM admins`).Scan(&n)
	return n, err
}

func (s *Store) CreateAdmin(ctx context.Context, username, passwordHash string) (*Admin, error) {
	a := &Admin{ID: newID(), Username: username, PasswordHash: passwordHash}
	_, err := s.DB.ExecContext(ctx, `INSERT INTO admins (id, username, password_hash) VALUES (?, ?, ?)`, a.ID, a.Username, a.PasswordHash)
	if err != nil {
		return nil, err
	}
	return a, nil
}

func (s *Store) GetAdminByUsername(ctx context.Context, username string) (*Admin, error) {
	a := &Admin{}
	err := s.DB.QueryRowContext(ctx, `
SELECT id, username, password_hash, failed_count, locked_until, last_login
FROM admins WHERE username=?`, username).Scan(
		&a.ID, &a.Username, &a.PasswordHash, &a.FailedCount, &a.LockedUntil, &a.LastLogin)
	if err == sql.ErrNoRows {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return a, nil
}

func (s *Store) GetAdmin(ctx context.Context, id string) (*Admin, error) {
	a := &Admin{}
	err := s.DB.QueryRowContext(ctx, `
SELECT id, username, password_hash, failed_count, locked_until, last_login
FROM admins WHERE id=?`, id).Scan(
		&a.ID, &a.Username, &a.PasswordHash, &a.FailedCount, &a.LockedUntil, &a.LastLogin)
	if err == sql.ErrNoRows {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return a, nil
}

func (s *Store) ResetAdminPassword(ctx context.Context, username, passwordHash string) error {
	res, err := s.DB.ExecContext(ctx, `
UPDATE admins SET password_hash=?, failed_count=0, locked_until=NULL WHERE username=?`, passwordHash, username)
	if err != nil {
		return err
	}
	n, _ := res.RowsAffected()
	if n == 0 {
		return sql.ErrNoRows
	}
	return nil
}

func (s *Store) RecordLoginSuccess(ctx context.Context, id string) error {
	_, err := s.DB.ExecContext(ctx, `UPDATE admins SET failed_count=0, locked_until=NULL, last_login=? WHERE id=?`, NowUTC(), id)
	return err
}

func (s *Store) RecordLoginFailure(ctx context.Context, id string, failedCount int, lockedUntil *time.Time) error {
	var until any
	if lockedUntil != nil {
		until = lockedUntil.UTC().Format(time.RFC3339Nano)
	}
	_, err := s.DB.ExecContext(ctx, `UPDATE admins SET failed_count=?, locked_until=? WHERE id=?`, failedCount, until, id)
	return err
}

// Session is a browser admin session.
type Session struct {
	ID                 string
	AdminID            string
	CSRFToken          string
	CreatedAt          string
	LastSeenAt         string
	AbsoluteExpiresAt  string
}

func (s *Store) CreateSession(ctx context.Context, adminID, csrf string, idle, absolute time.Duration) (*Session, error) {
	now := time.Now().UTC()
	sess := &Session{
		ID:                newID(),
		AdminID:           adminID,
		CSRFToken:         csrf,
		CreatedAt:         now.Format(time.RFC3339Nano),
		LastSeenAt:        now.Format(time.RFC3339Nano),
		AbsoluteExpiresAt: now.Add(absolute).Format(time.RFC3339Nano),
	}
	_, err := s.DB.ExecContext(ctx, `
INSERT INTO sessions (id, admin_id, csrf_token, created_at, last_seen_at, absolute_expires_at)
VALUES (?, ?, ?, ?, ?, ?)`, sess.ID, sess.AdminID, sess.CSRFToken, sess.CreatedAt, sess.LastSeenAt, sess.AbsoluteExpiresAt)
	return sess, err
}

func (s *Store) GetSession(ctx context.Context, id string) (*Session, error) {
	sess := &Session{}
	err := s.DB.QueryRowContext(ctx, `
SELECT id, admin_id, csrf_token, created_at, last_seen_at, absolute_expires_at
FROM sessions WHERE id=?`, id).Scan(
		&sess.ID, &sess.AdminID, &sess.CSRFToken, &sess.CreatedAt, &sess.LastSeenAt, &sess.AbsoluteExpiresAt)
	if err == sql.ErrNoRows {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return sess, nil
}

func (s *Store) TouchSession(ctx context.Context, id string) error {
	_, err := s.DB.ExecContext(ctx, `UPDATE sessions SET last_seen_at=? WHERE id=?`, NowUTC(), id)
	return err
}

func (s *Store) DeleteSession(ctx context.Context, id string) error {
	_, err := s.DB.ExecContext(ctx, `DELETE FROM sessions WHERE id=?`, id)
	return err
}

func (s *Store) DeleteAdminSessions(ctx context.Context, adminID string) error {
	_, err := s.DB.ExecContext(ctx, `DELETE FROM sessions WHERE admin_id=?`, adminID)
	return err
}
