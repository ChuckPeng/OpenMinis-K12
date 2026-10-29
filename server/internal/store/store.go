package store

import (
	"context"
	"database/sql"
	"fmt"
	"os"
	"path/filepath"
	"time"

	_ "modernc.org/sqlite"
)

// Store is the sole database entry point.
type Store struct {
	DB *sql.DB
}

// Open opens (or creates) the SQLite database with WAL.
func Open(path string) (*Store, error) {
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return nil, err
	}
	dsn := path + "?_pragma=busy_timeout(5000)&_pragma=foreign_keys(1)"
	db, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(1)
	if _, err := db.Exec(`PRAGMA journal_mode=WAL`); err != nil {
		_ = db.Close()
		return nil, err
	}
	s := &Store{DB: db}
	if err := s.Migrate(context.Background()); err != nil {
		_ = db.Close()
		return nil, err
	}
	return s, nil
}

// Close closes the DB.
func (s *Store) Close() error {
	return s.DB.Close()
}

// Ping checks readiness.
func (s *Store) Ping(ctx context.Context) error {
	return s.DB.PingContext(ctx)
}

// NowUTC returns an RFC3339 UTC timestamp.
func NowUTC() string {
	return time.Now().UTC().Format(time.RFC3339Nano)
}

// EnsureFamily creates the single v1 family if missing and returns its id.
func (s *Store) EnsureFamily(ctx context.Context, name string) (string, error) {
	var id string
	err := s.DB.QueryRowContext(ctx, `SELECT id FROM families LIMIT 1`).Scan(&id)
	if err == nil {
		return id, nil
	}
	if err != sql.ErrNoRows {
		return "", err
	}
	id = newID()
	_, err = s.DB.ExecContext(ctx, `INSERT INTO families (id, name, created_at) VALUES (?, ?, ?)`, id, name, NowUTC())
	return id, err
}

// SetSetting upserts a settings row.
func (s *Store) SetSetting(ctx context.Context, key, value string) error {
	_, err := s.DB.ExecContext(ctx, `
INSERT INTO settings (key, value) VALUES (?, ?)
ON CONFLICT(key) DO UPDATE SET value=excluded.value`, key, value)
	return err
}

// GetSetting returns a setting or empty string.
func (s *Store) GetSetting(ctx context.Context, key string) (string, error) {
	var v string
	err := s.DB.QueryRowContext(ctx, `SELECT value FROM settings WHERE key=?`, key).Scan(&v)
	if err == sql.ErrNoRows {
		return "", nil
	}
	return v, err
}

// AutoBackup copies the DB file beside itself before migrations (R10).
func AutoBackup(dbPath string, keep int) (string, error) {
	src, err := os.Open(dbPath)
	if err != nil {
		if os.IsNotExist(err) {
			return "", nil
		}
		return "", err
	}
	defer src.Close()
	dir := filepath.Join(filepath.Dir(dbPath), "backups")
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return "", err
	}
	name := fmt.Sprintf("pre-migrate-%s.db", time.Now().UTC().Format("20060102T150405Z"))
	dstPath := filepath.Join(dir, name)
	dst, err := os.OpenFile(dstPath, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o600)
	if err != nil {
		return "", err
	}
	defer dst.Close()
	if _, err := dst.ReadFrom(src); err != nil {
		return "", err
	}
	_ = pruneBackups(dir, keep)
	return dstPath, nil
}

func pruneBackups(dir string, keep int) error {
	if keep <= 0 {
		keep = 14
	}
	entries, err := os.ReadDir(dir)
	if err != nil {
		return err
	}
	if len(entries) <= keep {
		return nil
	}
	// ReadDir is sorted by name; our names are timestamp-prefixed.
	drop := len(entries) - keep
	for i := 0; i < drop; i++ {
		_ = os.Remove(filepath.Join(dir, entries[i].Name()))
	}
	return nil
}
