package store

import (
	"context"
	"database/sql"
)

// WecomApp is a configured WeCom application (secrets stored encrypted).
type WecomApp struct {
	ID               string
	FamilyID         string
	CorpID           string
	AgentID          int
	SecretEnc        string
	Token            string
	AESKeyEnc        string
	APIBaseURL       string
	CallbackKey      string
	Enabled          bool
	ButtonCallbackOK sql.NullBool // J14
}

func (s *Store) ListWecomApps(ctx context.Context) ([]WecomApp, error) {
	rows, err := s.DB.QueryContext(ctx, `
SELECT id, family_id, corpid, agentid, secret_enc, token, aeskey_enc, api_base_url, callback_key, enabled, button_callback_ok
FROM wecom_apps ORDER BY id`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []WecomApp
	for rows.Next() {
		var a WecomApp
		var en int
		if err := rows.Scan(&a.ID, &a.FamilyID, &a.CorpID, &a.AgentID, &a.SecretEnc, &a.Token, &a.AESKeyEnc, &a.APIBaseURL, &a.CallbackKey, &en, &a.ButtonCallbackOK); err != nil {
			return nil, err
		}
		a.Enabled = en != 0
		out = append(out, a)
	}
	return out, rows.Err()
}

func (s *Store) GetWecomApp(ctx context.Context, id string) (*WecomApp, error) {
	a := &WecomApp{}
	var en int
	err := s.DB.QueryRowContext(ctx, `
SELECT id, family_id, corpid, agentid, secret_enc, token, aeskey_enc, api_base_url, callback_key, enabled, button_callback_ok
FROM wecom_apps WHERE id=?`, id).Scan(
		&a.ID, &a.FamilyID, &a.CorpID, &a.AgentID, &a.SecretEnc, &a.Token, &a.AESKeyEnc, &a.APIBaseURL, &a.CallbackKey, &en, &a.ButtonCallbackOK)
	if err == sql.ErrNoRows {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	a.Enabled = en != 0
	return a, nil
}

func (s *Store) GetWecomAppByCallbackKey(ctx context.Context, key string) (*WecomApp, error) {
	a := &WecomApp{}
	var en int
	err := s.DB.QueryRowContext(ctx, `
SELECT id, family_id, corpid, agentid, secret_enc, token, aeskey_enc, api_base_url, callback_key, enabled, button_callback_ok
FROM wecom_apps WHERE callback_key=?`, key).Scan(
		&a.ID, &a.FamilyID, &a.CorpID, &a.AgentID, &a.SecretEnc, &a.Token, &a.AESKeyEnc, &a.APIBaseURL, &a.CallbackKey, &en, &a.ButtonCallbackOK)
	if err == sql.ErrNoRows {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	a.Enabled = en != 0
	return a, nil
}

func (s *Store) UpsertWecomApp(ctx context.Context, a *WecomApp) error {
	if a.ID == "" {
		a.ID = newID()
	}
	if a.CallbackKey == "" {
		a.CallbackKey = newID()
	}
	if a.APIBaseURL == "" {
		a.APIBaseURL = "https://qyapi.weixin.qq.com"
	}
	en := 0
	if a.Enabled {
		en = 1
	}
	_, err := s.DB.ExecContext(ctx, `
INSERT INTO wecom_apps (id, family_id, corpid, agentid, secret_enc, token, aeskey_enc, api_base_url, callback_key, enabled, button_callback_ok)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
ON CONFLICT(id) DO UPDATE SET
  corpid=excluded.corpid,
  agentid=excluded.agentid,
  secret_enc=CASE WHEN excluded.secret_enc='' THEN wecom_apps.secret_enc ELSE excluded.secret_enc END,
  token=excluded.token,
  aeskey_enc=CASE WHEN excluded.aeskey_enc='' THEN wecom_apps.aeskey_enc ELSE excluded.aeskey_enc END,
  api_base_url=excluded.api_base_url,
  callback_key=excluded.callback_key,
  enabled=excluded.enabled`,
		a.ID, a.FamilyID, a.CorpID, a.AgentID, a.SecretEnc, a.Token, a.AESKeyEnc, a.APIBaseURL, a.CallbackKey, en, a.ButtonCallbackOK)
	return err
}

func (s *Store) SetButtonCallbackResult(ctx context.Context, appID string, ok bool) error {
	_, err := s.DB.ExecContext(ctx, `UPDATE wecom_apps SET button_callback_ok=? WHERE id=?`, ok, appID)
	return err
}

// Parent is a WeCom recipient label (no real names).
type Parent struct {
	ID          string
	FamilyID    string
	WecomUserID string
	Label       string
	CanApprove  bool
	Channel     string
}

func (s *Store) ListParents(ctx context.Context) ([]Parent, error) {
	rows, err := s.DB.QueryContext(ctx, `SELECT id, family_id, wecom_userid, label, can_approve, channel FROM parents ORDER BY label`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Parent
	for rows.Next() {
		var p Parent
		var can int
		if err := rows.Scan(&p.ID, &p.FamilyID, &p.WecomUserID, &p.Label, &can, &p.Channel); err != nil {
			return nil, err
		}
		p.CanApprove = can != 0
		out = append(out, p)
	}
	return out, rows.Err()
}

func (s *Store) GetParentByWecomUser(ctx context.Context, familyID, userid string) (*Parent, error) {
	p := &Parent{}
	var can int
	err := s.DB.QueryRowContext(ctx, `
SELECT id, family_id, wecom_userid, label, can_approve, channel FROM parents
WHERE family_id=? AND wecom_userid=?`, familyID, userid).Scan(&p.ID, &p.FamilyID, &p.WecomUserID, &p.Label, &can, &p.Channel)
	if err == sql.ErrNoRows {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	p.CanApprove = can != 0
	return p, nil
}

func (s *Store) CreateParent(ctx context.Context, p *Parent) error {
	if p.ID == "" {
		p.ID = newID()
	}
	can := 0
	if p.CanApprove {
		can = 1
	}
	if p.Channel == "" {
		p.Channel = "wecom_app"
	}
	_, err := s.DB.ExecContext(ctx, `
INSERT INTO parents (id, family_id, wecom_userid, label, can_approve, channel) VALUES (?, ?, ?, ?, ?, ?)`,
		p.ID, p.FamilyID, p.WecomUserID, p.Label, can, p.Channel)
	return err
}

func (s *Store) UpdateParent(ctx context.Context, p *Parent) error {
	can := 0
	if p.CanApprove {
		can = 1
	}
	_, err := s.DB.ExecContext(ctx, `
UPDATE parents SET wecom_userid=?, label=?, can_approve=?, channel=? WHERE id=?`,
		p.WecomUserID, p.Label, can, p.Channel, p.ID)
	return err
}

func (s *Store) DeleteParent(ctx context.Context, id string) error {
	_, err := s.DB.ExecContext(ctx, `DELETE FROM parents WHERE id=?`, id)
	return err
}
