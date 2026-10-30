package store

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"time"
)

// Device represents a registered tablet.
type Device struct {
	ID                   string         `json:"id"`
	FamilyID             string         `json:"family_id"`
	ChildID              string         `json:"child_id"`
	Label                string         `json:"label"`
	Pubkey               string         `json:"pubkey"`
	CodeSecretEnc        string         `json:"-"`
	Status               string         `json:"status"` // pending_confirm, active, revoked
	PairedAt             sql.NullString `json:"paired_at,omitempty"`
	RevokedAt            sql.NullString `json:"revoked_at,omitempty"`
	LastSeen             sql.NullString `json:"last_seen,omitempty"`
	AppVersion           string         `json:"app_version"`
	ConfigVersionApplied int            `json:"config_version_applied"`
	CreatedAt            string         `json:"created_at"`
}

// Child represents a child profile associated with a device.
type Child struct {
	ID                 string `json:"id"`
	FamilyID           string `json:"family_id"`
	Label              string `json:"label"`
	Stage              string `json:"stage"`
	DeviceID           string `json:"device_id,omitempty"`
	ReportSettingsJSON string `json:"report_settings_json"`
	CreatedAt          string `json:"created_at"`
}

// Grant represents an approved unlock token signed by server.
type Grant struct {
	ID         string
	RequestID  string
	DeviceID   string
	Scope      string
	ExpiresAt  string
	Signature  string
	ConsumedAt sql.NullString
	CreatedAt  string
}

// Alert tracks a child safety / emotional notification.
type Alert struct {
	ID                string         `json:"id"`
	DeviceID          string         `json:"device_id"`
	ChildID           string         `json:"child_id"`
	Level             string         `json:"level"`
	Category          string         `json:"category"`
	OccurredAt        int64          `json:"occurred_at"`
	DedupeKey         string         `json:"dedupe_key"`
	SnippetEnc        string         `json:"-"`
	SnippetExpiresAt  sql.NullString `json:"-"`
	AcknowledgedBy    sql.NullString `json:"acknowledged_by,omitempty"`
	AcknowledgedAt    sql.NullString `json:"acknowledged_at,omitempty"`
	CreatedAt         string         `json:"created_at"`
}

// StudyEvent is a raw uploaded study event.
type StudyEvent struct {
	EventID       string `json:"event_id"`
	ChildID       string `json:"child_id"`
	DeviceID      string `json:"device_id"`
	Type          string `json:"type"`
	OccurredAt    int64  `json:"occurred_at"`
	LocalDate     string `json:"local_date"`
	Seq           int    `json:"seq"`
	Subject       string `json:"subject"`
	KPIDs         string `json:"kp_ids"`
	PayloadJSON   string `json:"payload_json"`
	SchemaVersion int    `json:"schema_version"`
	ReceivedAt    string `json:"received_at"`
}

// DailyAggregate aggregates day-level metrics.
type DailyAggregate struct {
	ChildID         string `json:"child_id"`
	LocalDate       string `json:"local_date"`
	Subject         string `json:"subject"`
	ActiveSeconds   int    `json:"active_seconds"`
	IdleSeconds     int    `json:"idle_seconds"`
	AttemptsCount   int    `json:"attempts_count"`
	FirstTryCorrect int    `json:"first_try_correct"`
	HintsUsedCount  int    `json:"hints_used_count"`
	RevealsCount    int    `json:"reveals_count"`
	MistakesCount   int    `json:"mistakes_count"`
	FlagsCount      int    `json:"flags_count"`
	IsStudyDay      int    `json:"is_study_day"`
	MetricVersion   int    `json:"metric_version"`
	ComputedAt      string `json:"computed_at"`
}

// Report represents a generated learning report.
type Report struct {
	ID               string `json:"id"`
	ChildID          string `json:"child_id"`
	Kind             string `json:"kind"`
	PeriodStart      string `json:"period_start"`
	PeriodEnd        string `json:"period_end"`
	MetricsJSON      string `json:"metrics_json"`
	AnalysisJSON     string `json:"analysis_json"`
	ParentTextJSON   string `json:"parent_text_json"`
	ChildTextJSON    string `json:"child_text_json"`
	ParentAdviceJSON string `json:"parent_advice_json"`
	TextSource       string `json:"text_source"`
	DataBasisJSON    string `json:"data_basis_json"`
	MetricVersion    int    `json:"metric_version"`
	Version          int    `json:"version"`
	Status           string `json:"status"`
	GeneratedAt      string `json:"generated_at"`
}

// ReportDelivery tracks links distributed to parents.
type ReportDelivery struct {
	ID            string         `json:"id"`
	ReportID      string         `json:"report_id"`
	Channel       string         `json:"channel"`
	Recipient     string         `json:"recipient"`
	OutboxID      string         `json:"outbox_id"`
	LinkID        string         `json:"link_id"`
	LinkExpiresAt string         `json:"link_expires_at"`
	LinkRevokedAt sql.NullString `json:"link_revoked_at,omitempty"`
	Status        string         `json:"status"`
	SentAt        sql.NullString `json:"sent_at,omitempty"`
	FirstOpenedAt sql.NullString `json:"first_opened_at,omitempty"`
}

// UsageDaily tracks parental daily consumption limits.
type UsageDaily struct {
	ChildID           string `json:"child_id"`
	Date              string `json:"date"`
	Turns             int    `json:"turns"`
	Images            int    `json:"images"`
	CompanionMinutes  int    `json:"companion_minutes"`
	ExtensionsGranted int    `json:"extensions_granted"`
}

// DeviceBackup tracks encrypted client backups.
type DeviceBackup struct {
	ID        string `json:"id"`
	DeviceID  string `json:"device_id"`
	ChildID   string `json:"child_id"`
	CreatedAt string `json:"created_at"`
	Size      int64  `json:"size"`
	SHA256    string `json:"sha256"`
	BlobPath  string `json:"blob_path"`
	KDFParams string `json:"kdf_params"`
}

// DiagnosticRecord stores sanitized logs and crash dumps.
type DiagnosticRecord struct {
	ID        string `json:"id"`
	DeviceID  string `json:"device_id"`
	Kind      string `json:"kind"`
	Body      string `json:"body"`
	CreatedAt string `json:"created_at"`
}

// Pair code management (10-minute expiry, SHA-256 stored).
func (s *Store) CreatePairCode(ctx context.Context, familyID, code string, ttl time.Duration) error {
	h := sha256.Sum256([]byte(stringsTrim(code)))
	codeHash := hex.EncodeToString(h[:])
	exp := time.Now().UTC().Add(ttl).Format(time.RFC3339Nano)
	_, err := s.DB.ExecContext(ctx, `
INSERT OR REPLACE INTO pair_codes (code_hash, family_id, expires_at, used_at, created_at)
VALUES (?, ?, ?, NULL, ?)`, codeHash, familyID, exp, NowUTC())
	return err
}

func (s *Store) ValidateAndConsumePairCode(ctx context.Context, familyID, code string) (bool, error) {
	h := sha256.Sum256([]byte(stringsTrim(code)))
	codeHash := hex.EncodeToString(h[:])
	var expiresAt string
	var usedAt sql.NullString
	err := s.DB.QueryRowContext(ctx, `
SELECT expires_at, used_at FROM pair_codes WHERE code_hash=? AND family_id=?`, codeHash, familyID).Scan(&expiresAt, &usedAt)
	if err == sql.ErrNoRows {
		return false, nil
	}
	if err != nil {
		return false, err
	}
	if usedAt.Valid {
		return false, nil
	}
	exp, err := time.Parse(time.RFC3339Nano, expiresAt)
	if err == nil && time.Now().UTC().After(exp) {
		return false, nil
	}
	res, err := s.DB.ExecContext(ctx, `
UPDATE pair_codes SET used_at=? WHERE code_hash=? AND used_at IS NULL`, NowUTC(), codeHash)
	if err != nil {
		return false, err
	}
	n, _ := res.RowsAffected()
	return n > 0, nil
}

// Device operations
func (s *Store) CreateDevice(ctx context.Context, d *Device) error {
	if d.ID == "" {
		d.ID = newID()
	}
	if d.Status == "" {
		d.Status = "pending_confirm"
	}
	if d.CreatedAt == "" {
		d.CreatedAt = NowUTC()
	}
	_, err := s.DB.ExecContext(ctx, `
INSERT INTO devices (
  id, family_id, child_id, label, pubkey, code_secret_enc,
  status, paired_at, revoked_at, last_seen, app_version,
  config_version_applied, created_at
) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
		d.ID, d.FamilyID, d.ChildID, d.Label, d.Pubkey, d.CodeSecretEnc,
		d.Status, d.PairedAt, d.RevokedAt, d.LastSeen, d.AppVersion,
		d.ConfigVersionApplied, d.CreatedAt)
	return err
}

func (s *Store) GetDevice(ctx context.Context, id string) (*Device, error) {
	d := &Device{}
	err := s.DB.QueryRowContext(ctx, `
SELECT id, family_id, child_id, label, pubkey, code_secret_enc,
       status, paired_at, revoked_at, last_seen, app_version,
       config_version_applied, created_at
FROM devices WHERE id=?`, id).Scan(
		&d.ID, &d.FamilyID, &d.ChildID, &d.Label, &d.Pubkey, &d.CodeSecretEnc,
		&d.Status, &d.PairedAt, &d.RevokedAt, &d.LastSeen, &d.AppVersion,
		&d.ConfigVersionApplied, &d.CreatedAt)
	if err == sql.ErrNoRows {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return d, nil
}

func (s *Store) GetDeviceByChildID(ctx context.Context, childID string) (*Device, error) {
	d := &Device{}
	err := s.DB.QueryRowContext(ctx, `
SELECT id, family_id, child_id, label, pubkey, code_secret_enc,
       status, paired_at, revoked_at, last_seen, app_version,
       config_version_applied, created_at
FROM devices WHERE child_id=?`, childID).Scan(
		&d.ID, &d.FamilyID, &d.ChildID, &d.Label, &d.Pubkey, &d.CodeSecretEnc,
		&d.Status, &d.PairedAt, &d.RevokedAt, &d.LastSeen, &d.AppVersion,
		&d.ConfigVersionApplied, &d.CreatedAt)
	if err == sql.ErrNoRows {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return d, nil
}

func (s *Store) ListDevices(ctx context.Context) ([]Device, error) {
	rows, err := s.DB.QueryContext(ctx, `
SELECT id, family_id, child_id, label, pubkey, code_secret_enc,
       status, paired_at, revoked_at, last_seen, app_version,
       config_version_applied, created_at
FROM devices ORDER BY created_at DESC`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Device
	for rows.Next() {
		var d Device
		if err := rows.Scan(
			&d.ID, &d.FamilyID, &d.ChildID, &d.Label, &d.Pubkey, &d.CodeSecretEnc,
			&d.Status, &d.PairedAt, &d.RevokedAt, &d.LastSeen, &d.AppVersion,
			&d.ConfigVersionApplied, &d.CreatedAt); err != nil {
			return nil, err
		}
		out = append(out, d)
	}
	return out, rows.Err()
}

func (s *Store) UpdateDeviceStatus(ctx context.Context, id, status string) error {
	now := NowUTC()
	var query string
	var args []any
	if status == "active" {
		query = `UPDATE devices SET status=?, paired_at=? WHERE id=?`
		args = []any{status, now, id}
	} else if status == "revoked" {
		query = `UPDATE devices SET status=?, revoked_at=? WHERE id=?`
		args = []any{status, now, id}
	} else {
		query = `UPDATE devices SET status=? WHERE id=?`
		args = []any{status, id}
	}
	_, err := s.DB.ExecContext(ctx, query, args...)
	return err
}

func (s *Store) UpdateDeviceHeartbeat(ctx context.Context, id, appVersion string, configApplied int) error {
	_, err := s.DB.ExecContext(ctx, `
UPDATE devices SET last_seen=?, app_version=?, config_version_applied=? WHERE id=?`,
		NowUTC(), appVersion, configApplied, id)
	return err
}

// Device Config operations
func (s *Store) GetDeviceConfig(ctx context.Context, deviceID string) (int, string, error) {
	var version int
	var cfgJSON string
	err := s.DB.QueryRowContext(ctx, `SELECT version, config_json FROM device_config WHERE device_id=?`, deviceID).Scan(&version, &cfgJSON)
	if err == sql.ErrNoRows {
		return 0, "", nil
	}
	return version, cfgJSON, err
}

func (s *Store) SetDeviceConfig(ctx context.Context, deviceID, configJSON, updatedBy string) (int, error) {
	var curVersion int
	err := s.DB.QueryRowContext(ctx, `SELECT version FROM device_config WHERE device_id=?`, deviceID).Scan(&curVersion)
	newVersion := 1
	if err == nil {
		newVersion = curVersion + 1
	}
	_, err = s.DB.ExecContext(ctx, `
INSERT INTO device_config (device_id, version, config_json, updated_by, updated_at)
VALUES (?, ?, ?, ?, ?)
ON CONFLICT(device_id) DO UPDATE SET
  version=excluded.version,
  config_json=excluded.config_json,
  updated_by=excluded.updated_by,
  updated_at=excluded.updated_at`,
		deviceID, newVersion, configJSON, updatedBy, NowUTC())
	return newVersion, err
}

// Nonces (10 minute replay protection)
func (s *Store) CheckAndRecordNonce(ctx context.Context, deviceID, nonce string, seenAt time.Time) (bool, error) {
	// First purge nonces older than 10 minutes
	cutoff := seenAt.Add(-10 * time.Minute).Format(time.RFC3339Nano)
	_, _ = s.DB.ExecContext(ctx, `DELETE FROM nonces WHERE seen_at < ?`, cutoff)

	_, err := s.DB.ExecContext(ctx, `
INSERT INTO nonces (device_id, nonce, seen_at) VALUES (?, ?, ?)`,
		deviceID, stringsTrim(nonce), seenAt.UTC().Format(time.RFC3339Nano))
	if err != nil {
		// Nonce collision / already seen
		return false, nil
	}
	return true, nil
}

// Grants
func (s *Store) CreateGrant(ctx context.Context, g *Grant) error {
	if g.ID == "" {
		g.ID = newID()
	}
	if g.CreatedAt == "" {
		g.CreatedAt = NowUTC()
	}
	_, err := s.DB.ExecContext(ctx, `
INSERT INTO grants (id, request_id, device_id, scope, expires_at, signature, created_at)
VALUES (?, ?, ?, ?, ?, ?, ?)`,
		g.ID, g.RequestID, g.DeviceID, g.Scope, g.ExpiresAt, g.Signature, g.CreatedAt)
	return err
}

func (s *Store) GetGrantByRequestID(ctx context.Context, requestID string) (*Grant, error) {
	g := &Grant{}
	err := s.DB.QueryRowContext(ctx, `
SELECT id, request_id, device_id, scope, expires_at, signature, consumed_at, created_at
FROM grants WHERE request_id=?`, requestID).Scan(
		&g.ID, &g.RequestID, &g.DeviceID, &g.Scope, &g.ExpiresAt, &g.Signature, &g.ConsumedAt, &g.CreatedAt)
	if err == sql.ErrNoRows {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return g, nil
}

func (s *Store) ConsumeGrant(ctx context.Context, grantID string) error {
	_, err := s.DB.ExecContext(ctx, `
UPDATE grants SET consumed_at=? WHERE id=? AND consumed_at IS NULL`, NowUTC(), grantID)
	return err
}

// Alerts
func (s *Store) CreateAlert(ctx context.Context, a *Alert) error {
	if a.ID == "" {
		a.ID = newID()
	}
	if a.CreatedAt == "" {
		a.CreatedAt = NowUTC()
	}
	_, err := s.DB.ExecContext(ctx, `
INSERT INTO alerts (
  id, device_id, child_id, level, category, occurred_at,
  dedupe_key, snippet_enc, snippet_expires_at, created_at
) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
		a.ID, a.DeviceID, a.ChildID, a.Level, a.Category, a.OccurredAt,
		a.DedupeKey, a.SnippetEnc, a.SnippetExpiresAt, a.CreatedAt)
	return err
}

func (s *Store) FindRecentAlert(ctx context.Context, deviceID, level, dedupeKey string, since time.Time) (*Alert, error) {
	if dedupeKey == "" {
		return nil, nil
	}
	sinceMs := since.UnixMilli()
	a := &Alert{}
	err := s.DB.QueryRowContext(ctx, `
SELECT id, device_id, child_id, level, category, occurred_at, dedupe_key, snippet_enc, snippet_expires_at, acknowledged_by, acknowledged_at, created_at
FROM alerts
WHERE device_id=? AND level=? AND dedupe_key=? AND occurred_at >= ?
ORDER BY occurred_at DESC LIMIT 1`, deviceID, level, dedupeKey, sinceMs).Scan(
		&a.ID, &a.DeviceID, &a.ChildID, &a.Level, &a.Category, &a.OccurredAt, &a.DedupeKey,
		&a.SnippetEnc, &a.SnippetExpiresAt, &a.AcknowledgedBy, &a.AcknowledgedAt, &a.CreatedAt)
	if err == sql.ErrNoRows {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return a, nil
}

func (s *Store) ListAlerts(ctx context.Context, limit int) ([]Alert, error) {
	if limit <= 0 {
		limit = 50
	}
	rows, err := s.DB.QueryContext(ctx, `
SELECT id, device_id, child_id, level, category, occurred_at, dedupe_key, snippet_enc, snippet_expires_at, acknowledged_by, acknowledged_at, created_at
FROM alerts ORDER BY occurred_at DESC LIMIT ?`, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Alert
	for rows.Next() {
		var a Alert
		if err := rows.Scan(
			&a.ID, &a.DeviceID, &a.ChildID, &a.Level, &a.Category, &a.OccurredAt, &a.DedupeKey,
			&a.SnippetEnc, &a.SnippetExpiresAt, &a.AcknowledgedBy, &a.AcknowledgedAt, &a.CreatedAt); err != nil {
			return nil, err
		}
		out = append(out, a)
	}
	return out, rows.Err()
}

func (s *Store) GetAlert(ctx context.Context, id string) (*Alert, error) {
	a := &Alert{}
	err := s.DB.QueryRowContext(ctx, `
SELECT id, device_id, child_id, level, category, occurred_at, dedupe_key, snippet_enc, snippet_expires_at, acknowledged_by, acknowledged_at, created_at
FROM alerts WHERE id=?`, id).Scan(
		&a.ID, &a.DeviceID, &a.ChildID, &a.Level, &a.Category, &a.OccurredAt, &a.DedupeKey,
		&a.SnippetEnc, &a.SnippetExpiresAt, &a.AcknowledgedBy, &a.AcknowledgedAt, &a.CreatedAt)
	if err == sql.ErrNoRows {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return a, nil
}

func (s *Store) AcknowledgeAlert(ctx context.Context, id, ackBy string) error {
	_, err := s.DB.ExecContext(ctx, `
UPDATE alerts SET acknowledged_by=?, acknowledged_at=? WHERE id=?`, ackBy, NowUTC(), id)
	return err
}

// Children
func (s *Store) EnsureChild(ctx context.Context, id, familyID, label, stage string) (*Child, error) {
	c, err := s.GetChild(ctx, id)
	if err == nil && c != nil {
		return c, nil
	}
	if label == "" {
		label = "孩子"
	}
	if stage == "" {
		stage = "小学低年级"
	}
	_, err = s.DB.ExecContext(ctx, `
INSERT OR IGNORE INTO children (id, family_id, label, stage, created_at)
VALUES (?, ?, ?, ?, ?)`, id, familyID, label, stage, NowUTC())
	if err != nil {
		return nil, err
	}
	return s.GetChild(ctx, id)
}

func (s *Store) GetChild(ctx context.Context, id string) (*Child, error) {
	c := &Child{}
	var devID sql.NullString
	err := s.DB.QueryRowContext(ctx, `
SELECT id, family_id, label, stage, device_id, report_settings_json, created_at
FROM children WHERE id=?`, id).Scan(
		&c.ID, &c.FamilyID, &c.Label, &c.Stage, &devID, &c.ReportSettingsJSON, &c.CreatedAt)
	if err == sql.ErrNoRows {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	if devID.Valid {
		c.DeviceID = devID.String
	}
	return c, nil
}

func (s *Store) BindChildDevice(ctx context.Context, childID, deviceID string) error {
	_, err := s.DB.ExecContext(ctx, `UPDATE children SET device_id=? WHERE id=?`, deviceID, childID)
	return err
}

func (s *Store) ListChildren(ctx context.Context) ([]Child, error) {
	rows, err := s.DB.QueryContext(ctx, `
SELECT id, family_id, label, stage, device_id, report_settings_json, created_at
FROM children ORDER BY created_at ASC`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Child
	for rows.Next() {
		var c Child
		var devID sql.NullString
		if err := rows.Scan(
			&c.ID, &c.FamilyID, &c.Label, &c.Stage, &devID, &c.ReportSettingsJSON, &c.CreatedAt); err != nil {
			return nil, err
		}
		if devID.Valid {
			c.DeviceID = devID.String
		}
		out = append(out, c)
	}
	return out, rows.Err()
}

// Study Events & Aggregates
func (s *Store) InsertStudyEvents(ctx context.Context, events []StudyEvent) (int, error) {
	tx, err := s.DB.BeginTx(ctx, nil)
	if err != nil {
		return 0, err
	}
	defer tx.Rollback()

	maxSeq := 0
	for _, ev := range events {
		if ev.Seq > maxSeq {
			maxSeq = ev.Seq
		}
		if ev.ReceivedAt == "" {
			ev.ReceivedAt = NowUTC()
		}
		_, err := tx.ExecContext(ctx, `
INSERT OR IGNORE INTO study_event (
  event_id, child_id, device_id, type, occurred_at, local_date,
  seq, subject, kp_ids, payload_json, schema_version, received_at
) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
			ev.EventID, ev.ChildID, ev.DeviceID, ev.Type, ev.OccurredAt, ev.LocalDate,
			ev.Seq, ev.Subject, ev.KPIDs, ev.PayloadJSON, ev.SchemaVersion, ev.ReceivedAt)
		if err != nil {
			return 0, err
		}
	}
	return maxSeq, tx.Commit()
}

func (s *Store) UpdateDailyAggregateFromEvents(ctx context.Context, childID, localDate, subject string, events []StudyEvent) error {
	// Calculate increments
	var activeSec, attempts, firstCorrect, hints, reveals, mistakes, flags int
	for _, ev := range events {
		switch ev.Type {
		case "active_span":
			var p struct {
				Start int64 `json:"start"`
				End   int64 `json:"end"`
			}
			if err := json.Unmarshal([]byte(ev.PayloadJSON), &p); err == nil && p.End > p.Start {
				activeSec += int((p.End - p.Start) / 1000)
			}
		case "attempt":
			attempts++
			var p struct {
				Correct  bool `json:"correct"`
				FirstTry bool `json:"first_try"`
			}
			if err := json.Unmarshal([]byte(ev.PayloadJSON), &p); err == nil && p.Correct && p.FirstTry {
				firstCorrect++
			}
		case "hint_used":
			hints++
		case "answer_reveal":
			reveals++
		case "mistake_created":
			mistakes++
		case "wellbeing_flag":
			flags++
		}
	}

	_, err := s.DB.ExecContext(ctx, `
INSERT INTO daily_aggregate (
  child_id, local_date, subject, active_seconds, idle_seconds,
  attempts_count, first_try_correct, hints_used_count, reveals_count,
  mistakes_count, flags_count, is_study_day, metric_version, computed_at
) VALUES (?, ?, ?, ?, 0, ?, ?, ?, ?, ?, ?, IIF(?>=600, 1, 0), 1, ?)
ON CONFLICT(child_id, local_date, subject) DO UPDATE SET
  active_seconds = active_seconds + excluded.active_seconds,
  attempts_count = attempts_count + excluded.attempts_count,
  first_try_correct = first_try_correct + excluded.first_try_correct,
  hints_used_count = hints_used_count + excluded.hints_used_count,
  reveals_count = reveals_count + excluded.reveals_count,
  mistakes_count = mistakes_count + excluded.mistakes_count,
  flags_count = flags_count + excluded.flags_count,
  is_study_day = IIF(active_seconds + excluded.active_seconds >= 600, 1, 0),
  computed_at = excluded.computed_at`,
		childID, localDate, subject, activeSec, attempts, firstCorrect, hints, reveals, mistakes, flags, activeSec, NowUTC())
	return err
}

func (s *Store) GetDailyAggregate(ctx context.Context, childID, localDate string) ([]DailyAggregate, error) {
	rows, err := s.DB.QueryContext(ctx, `
SELECT child_id, local_date, subject, active_seconds, idle_seconds, attempts_count,
       first_try_correct, hints_used_count, reveals_count, mistakes_count,
       flags_count, is_study_day, metric_version, computed_at
FROM daily_aggregate WHERE child_id=? AND local_date=?`, childID, localDate)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []DailyAggregate
	for rows.Next() {
		var a DailyAggregate
		if err := rows.Scan(
			&a.ChildID, &a.LocalDate, &a.Subject, &a.ActiveSeconds, &a.IdleSeconds,
			&a.AttemptsCount, &a.FirstTryCorrect, &a.HintsUsedCount, &a.RevealsCount,
			&a.MistakesCount, &a.FlagsCount, &a.IsStudyDay, &a.MetricVersion, &a.ComputedAt); err != nil {
			return nil, err
		}
		out = append(out, a)
	}
	return out, rows.Err()
}

// Reports
func (s *Store) CreateReport(ctx context.Context, r *Report) error {
	if r.ID == "" {
		r.ID = newID()
	}
	if r.GeneratedAt == "" {
		r.GeneratedAt = NowUTC()
	}
	_, err := s.DB.ExecContext(ctx, `
INSERT INTO report (
  id, child_id, kind, period_start, period_end, metrics_json,
  analysis_json, parent_text_json, child_text_json, parent_advice_json,
  text_source, data_basis_json, metric_version, version, status, generated_at
) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
		r.ID, r.ChildID, r.Kind, r.PeriodStart, r.PeriodEnd, r.MetricsJSON,
		r.AnalysisJSON, r.ParentTextJSON, r.ChildTextJSON, r.ParentAdviceJSON,
		r.TextSource, r.DataBasisJSON, r.MetricVersion, r.Version, r.Status, r.GeneratedAt)
	return err
}

func (s *Store) GetReport(ctx context.Context, id string) (*Report, error) {
	r := &Report{}
	err := s.DB.QueryRowContext(ctx, `
SELECT id, child_id, kind, period_start, period_end, metrics_json,
       analysis_json, parent_text_json, child_text_json, parent_advice_json,
       text_source, data_basis_json, metric_version, version, status, generated_at
FROM report WHERE id=?`, id).Scan(
		&r.ID, &r.ChildID, &r.Kind, &r.PeriodStart, &r.PeriodEnd, &r.MetricsJSON,
		&r.AnalysisJSON, &r.ParentTextJSON, &r.ChildTextJSON, &r.ParentAdviceJSON,
		&r.TextSource, &r.DataBasisJSON, &r.MetricVersion, &r.Version, &r.Status, &r.GeneratedAt)
	if err == sql.ErrNoRows {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return r, nil
}

func (s *Store) ListReports(ctx context.Context, childID string, limit int) ([]Report, error) {
	if limit <= 0 {
		limit = 50
	}
	var query string
	var args []any
	if childID != "" {
		query = `
SELECT id, child_id, kind, period_start, period_end, metrics_json,
       analysis_json, parent_text_json, child_text_json, parent_advice_json,
       text_source, data_basis_json, metric_version, version, status, generated_at
FROM report WHERE child_id=? ORDER BY generated_at DESC LIMIT ?`
		args = []any{childID, limit}
	} else {
		query = `
SELECT id, child_id, kind, period_start, period_end, metrics_json,
       analysis_json, parent_text_json, child_text_json, parent_advice_json,
       text_source, data_basis_json, metric_version, version, status, generated_at
FROM report ORDER BY generated_at DESC LIMIT ?`
		args = []any{limit}
	}

	rows, err := s.DB.QueryContext(ctx, query, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Report
	for rows.Next() {
		var r Report
		if err := rows.Scan(
			&r.ID, &r.ChildID, &r.Kind, &r.PeriodStart, &r.PeriodEnd, &r.MetricsJSON,
			&r.AnalysisJSON, &r.ParentTextJSON, &r.ChildTextJSON, &r.ParentAdviceJSON,
			&r.TextSource, &r.DataBasisJSON, &r.MetricVersion, &r.Version, &r.Status, &r.GeneratedAt); err != nil {
			return nil, err
		}
		out = append(out, r)
	}
	return out, rows.Err()
}

// Report Delivery & Links
func (s *Store) CreateReportDelivery(ctx context.Context, d *ReportDelivery) error {
	if d.ID == "" {
		d.ID = newID()
	}
	_, err := s.DB.ExecContext(ctx, `
INSERT INTO report_delivery (
  id, report_id, channel, recipient, outbox_id, link_id,
  link_expires_at, link_revoked_at, status, sent_at
) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
		d.ID, d.ReportID, d.Channel, d.Recipient, d.OutboxID, d.LinkID,
		d.LinkExpiresAt, d.LinkRevokedAt, d.Status, d.SentAt)
	return err
}

func (s *Store) GetReportDeliveryByLink(ctx context.Context, linkID string) (*ReportDelivery, error) {
	d := &ReportDelivery{}
	err := s.DB.QueryRowContext(ctx, `
SELECT id, report_id, channel, recipient, outbox_id, link_id, link_expires_at, link_revoked_at, status, sent_at, first_opened_at
FROM report_delivery WHERE link_id=?`, linkID).Scan(
		&d.ID, &d.ReportID, &d.Channel, &d.Recipient, &d.OutboxID, &d.LinkID,
		&d.LinkExpiresAt, &d.LinkRevokedAt, &d.Status, &d.SentAt, &d.FirstOpenedAt)
	if err == sql.ErrNoRows {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return d, nil
}

func (s *Store) TouchReportDeliveryOpened(ctx context.Context, linkID string) error {
	_, err := s.DB.ExecContext(ctx, `
UPDATE report_delivery SET first_opened_at=COALESCE(first_opened_at, ?) WHERE link_id=?`, NowUTC(), linkID)
	return err
}

func (s *Store) RevokeReportLink(ctx context.Context, linkID string) error {
	_, err := s.DB.ExecContext(ctx, `
UPDATE report_delivery SET link_revoked_at=?, status='revoked' WHERE link_id=?`, NowUTC(), linkID)
	return err
}

// Usage Daily
func (s *Store) RecordUsage(ctx context.Context, childID, date string, turns, images, companionMin int) error {
	_, err := s.DB.ExecContext(ctx, `
INSERT INTO usage_daily (child_id, date, turns, images, companion_minutes, extensions_granted)
VALUES (?, ?, ?, ?, ?, 0)
ON CONFLICT(child_id, date) DO UPDATE SET
  turns = turns + excluded.turns,
  images = images + excluded.images,
  companion_minutes = companion_minutes + excluded.companion_minutes`,
		childID, date, turns, images, companionMin)
	return err
}

func (s *Store) GetUsage(ctx context.Context, childID, date string) (*UsageDaily, error) {
	u := &UsageDaily{ChildID: childID, Date: date}
	err := s.DB.QueryRowContext(ctx, `
SELECT turns, images, companion_minutes, extensions_granted
FROM usage_daily WHERE child_id=? AND date=?`, childID, date).Scan(
		&u.Turns, &u.Images, &u.CompanionMinutes, &u.ExtensionsGranted)
	if err == sql.ErrNoRows {
		return u, nil
	}
	if err != nil {
		return nil, err
	}
	return u, nil
}

// Backups (7 daily + 4 weekly retention)
func (s *Store) CreateBackup(ctx context.Context, b *DeviceBackup) error {
	if b.ID == "" {
		b.ID = newID()
	}
	if b.CreatedAt == "" {
		b.CreatedAt = NowUTC()
	}
	_, err := s.DB.ExecContext(ctx, `
INSERT INTO device_backup (id, device_id, child_id, created_at, size, sha256, blob_path, kdf_params)
VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
		b.ID, b.DeviceID, b.ChildID, b.CreatedAt, b.Size, b.SHA256, b.BlobPath, b.KDFParams)
	return err
}

func (s *Store) GetLatestBackup(ctx context.Context, childID string) (*DeviceBackup, error) {
	b := &DeviceBackup{}
	err := s.DB.QueryRowContext(ctx, `
SELECT id, device_id, child_id, created_at, size, sha256, blob_path, kdf_params
FROM device_backup WHERE child_id=? ORDER BY created_at DESC LIMIT 1`, childID).Scan(
		&b.ID, &b.DeviceID, &b.ChildID, &b.CreatedAt, &b.Size, &b.SHA256, &b.BlobPath, &b.KDFParams)
	if err == sql.ErrNoRows {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return b, nil
}

func (s *Store) ListBackups(ctx context.Context, childID string) ([]DeviceBackup, error) {
	rows, err := s.DB.QueryContext(ctx, `
SELECT id, device_id, child_id, created_at, size, sha256, blob_path, kdf_params
FROM device_backup WHERE child_id=? ORDER BY created_at DESC`, childID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []DeviceBackup
	for rows.Next() {
		var b DeviceBackup
		if err := rows.Scan(
			&b.ID, &b.DeviceID, &b.ChildID, &b.CreatedAt, &b.Size, &b.SHA256, &b.BlobPath, &b.KDFParams); err != nil {
			return nil, err
		}
		out = append(out, b)
	}
	return out, rows.Err()
}

func (s *Store) DeleteBackup(ctx context.Context, id string) error {
	_, err := s.DB.ExecContext(ctx, `DELETE FROM device_backup WHERE id=?`, id)
	return err
}

// Diagnostics
func (s *Store) CreateDiagnostics(ctx context.Context, d *DiagnosticRecord) error {
	if d.ID == "" {
		d.ID = newID()
	}
	if d.CreatedAt == "" {
		d.CreatedAt = NowUTC()
	}
	_, err := s.DB.ExecContext(ctx, `
INSERT INTO diagnostics (id, device_id, kind, body, created_at)
VALUES (?, ?, ?, ?, ?)`, d.ID, d.DeviceID, d.Kind, d.Body, d.CreatedAt)
	return err
}

func stringsTrim(s string) string {
	return fmt.Sprintf("%s", s)
}
