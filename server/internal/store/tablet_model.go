package store

import (
	"context"
	"database/sql"
)

// TabletModel is the family-level tablet chat-model config.
// APIKeyEnc is ciphertext. Callers must not put it in JSON or logs.
type TabletModel struct {
	FamilyID        string
	Version         int64
	Cleared         bool
	ProviderType    string
	BaseURL         string
	ModelName       string
	VisionModelName string
	APIKeyEnc       string
	APIKeyChangedAt string
	UpdatedAt       string
	UpdatedBy       string
}

// TabletModelDelivery is the latest recorded hand-off of a config version
// to one device. It never includes the API key.
type TabletModelDelivery struct {
	DeviceID    string
	DeviceLabel string
	Status      string
	Version     int64
	DeliveredAt string
}

// TabletModelVersion returns the monotonic version for a family, or 0 when
// the parent has never saved a config.
func (s *Store) TabletModelVersion(ctx context.Context, familyID string) (int64, error) {
	var v int64
	err := s.DB.QueryRowContext(ctx, `SELECT version FROM tablet_model_config WHERE family_id=?`, familyID).Scan(&v)
	if err == sql.ErrNoRows {
		return 0, nil
	}
	return v, err
}

// GetTabletModel returns the stored config. A missing row is version 0 and cleared.
func (s *Store) GetTabletModel(ctx context.Context, familyID string) (TabletModel, error) {
	var m TabletModel
	var cleared int
	var changed sql.NullString
	err := s.DB.QueryRowContext(ctx, `
SELECT family_id, version, cleared, provider_type, base_url, model_name, vision_model_name,
       api_key_enc, api_key_changed_at, updated_at, updated_by
FROM tablet_model_config WHERE family_id=?`, familyID).Scan(
		&m.FamilyID, &m.Version, &cleared, &m.ProviderType, &m.BaseURL, &m.ModelName, &m.VisionModelName,
		&m.APIKeyEnc, &changed, &m.UpdatedAt, &m.UpdatedBy,
	)
	if err == sql.ErrNoRows {
		return TabletModel{FamilyID: familyID, Cleared: true}, nil
	}
	if err != nil {
		return TabletModel{}, err
	}
	m.Cleared = cleared != 0
	if changed.Valid {
		m.APIKeyChangedAt = changed.String
	}
	return m, nil
}

// TabletModelSave is a non-secret write. KeyEnc is empty when the existing
// ciphertext should be kept. KeyChanged records a new ciphertext.
type TabletModelSave struct {
	ProviderType    string
	BaseURL         string
	ModelName       string
	VisionModelName string
	UpdatedBy       string
	KeyEnc          string
	KeyChanged      bool
}

// SaveTabletModel inserts or replaces the family config and bumps version by 1.
func (s *Store) SaveTabletModel(ctx context.Context, familyID string, in TabletModelSave) (int64, error) {
	tx, err := s.DB.BeginTx(ctx, nil)
	if err != nil {
		return 0, err
	}
	defer func() { _ = tx.Rollback() }()

	var oldVer int64
	var oldEnc, oldChanged sql.NullString
	err = tx.QueryRowContext(ctx, `
SELECT version, api_key_enc, api_key_changed_at FROM tablet_model_config WHERE family_id=?`, familyID).
		Scan(&oldVer, &oldEnc, &oldChanged)
	now := NowUTC()
	keyEnc := in.KeyEnc
	changedAt := now
	if !in.KeyChanged {
		if oldEnc.Valid {
			keyEnc = oldEnc.String
		}
		if oldChanged.Valid {
			changedAt = oldChanged.String
		} else {
			changedAt = ""
		}
	}
	var version int64
	if err == sql.ErrNoRows {
		version = 1
		_, err = tx.ExecContext(ctx, `
INSERT INTO tablet_model_config (
  family_id, version, cleared, provider_type, base_url, model_name, vision_model_name,
  api_key_enc, api_key_changed_at, updated_at, updated_by
) VALUES (?, ?, 0, ?, ?, ?, ?, ?, ?, ?, ?)`,
			familyID, version, in.ProviderType, in.BaseURL, in.ModelName, in.VisionModelName,
			keyEnc, nullIfEmpty(changedAt), now, in.UpdatedBy,
		)
	} else if err != nil {
		return 0, err
	} else {
		version = oldVer + 1
		_, err = tx.ExecContext(ctx, `
UPDATE tablet_model_config SET
  version=?, cleared=0, provider_type=?, base_url=?, model_name=?, vision_model_name=?,
  api_key_enc=?, api_key_changed_at=?, updated_at=?, updated_by=?
WHERE family_id=?`,
			version, in.ProviderType, in.BaseURL, in.ModelName, in.VisionModelName,
			keyEnc, nullIfEmpty(changedAt), now, in.UpdatedBy, familyID,
		)
	}
	if err != nil {
		return 0, err
	}
	if err := tx.Commit(); err != nil {
		return 0, err
	}
	return version, nil
}

// ClearTabletModel bumps the version, drops the ciphertext, and marks the
// config cleared so tablets fall back to their previous local provider.
func (s *Store) ClearTabletModel(ctx context.Context, familyID, updatedBy string) (int64, error) {
	tx, err := s.DB.BeginTx(ctx, nil)
	if err != nil {
		return 0, err
	}
	defer func() { _ = tx.Rollback() }()

	var oldVer int64
	err = tx.QueryRowContext(ctx, `SELECT version FROM tablet_model_config WHERE family_id=?`, familyID).Scan(&oldVer)
	now := NowUTC()
	var version int64
	if err == sql.ErrNoRows {
		version = 1
		_, err = tx.ExecContext(ctx, `
INSERT INTO tablet_model_config (
  family_id, version, cleared, provider_type, base_url, model_name, vision_model_name,
  api_key_enc, api_key_changed_at, updated_at, updated_by
) VALUES (?, ?, 1, '', '', '', '', '', NULL, ?, ?)`,
			familyID, version, now, updatedBy,
		)
	} else if err != nil {
		return 0, err
	} else {
		version = oldVer + 1
		_, err = tx.ExecContext(ctx, `
UPDATE tablet_model_config SET
  version=?, cleared=1, provider_type='', base_url='', model_name='', vision_model_name='',
  api_key_enc='', api_key_changed_at=NULL, updated_at=?, updated_by=?
WHERE family_id=?`,
			version, now, updatedBy, familyID,
		)
	}
	if err != nil {
		return 0, err
	}
	if err := tx.Commit(); err != nil {
		return 0, err
	}
	return version, nil
}

// RecordTabletModelDelivery appends one delivery row. The key is not a column.
func (s *Store) RecordTabletModelDelivery(ctx context.Context, familyID, deviceID string, version int64) error {
	_, err := s.DB.ExecContext(ctx, `
INSERT INTO tablet_model_delivery (id, family_id, device_id, version, delivered_at)
VALUES (?, ?, ?, ?, ?)`, newID(), familyID, deviceID, version, NowUTC())
	return err
}

// ListTabletModelDeliveries returns the latest delivery per device in the family.
func (s *Store) ListTabletModelDeliveries(ctx context.Context, familyID string) ([]TabletModelDelivery, error) {
	rows, err := s.DB.QueryContext(ctx, `
SELECT d.id, d.label, d.status,
       (SELECT version FROM tablet_model_delivery WHERE device_id=d.id ORDER BY delivered_at DESC LIMIT 1),
       (SELECT delivered_at FROM tablet_model_delivery WHERE device_id=d.id ORDER BY delivered_at DESC LIMIT 1)
FROM devices d
WHERE d.family_id=?
ORDER BY d.label, d.id`, familyID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []TabletModelDelivery
	for rows.Next() {
		var item TabletModelDelivery
		var ver sql.NullInt64
		var at sql.NullString
		if err := rows.Scan(&item.DeviceID, &item.DeviceLabel, &item.Status, &ver, &at); err != nil {
			return nil, err
		}
		if ver.Valid {
			item.Version = ver.Int64
		}
		if at.Valid {
			item.DeliveredAt = at.String
		}
		out = append(out, item)
	}
	return out, rows.Err()
}

func nullIfEmpty(s string) any {
	if s == "" {
		return nil
	}
	return s
}
