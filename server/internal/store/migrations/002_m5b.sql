-- M5b schema: device pairing, grants, alerts, study events, daily aggregate,
-- reports, backups, and parental usage.

CREATE TABLE IF NOT EXISTS devices (
  id TEXT PRIMARY KEY,
  family_id TEXT NOT NULL,
  child_id TEXT NOT NULL UNIQUE,
  label TEXT NOT NULL DEFAULT '',
  pubkey TEXT NOT NULL,
  code_secret_enc TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'pending_confirm',
  paired_at TEXT,
  revoked_at TEXT,
  last_seen TEXT,
  app_version TEXT NOT NULL DEFAULT '',
  config_version_applied INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL,
  FOREIGN KEY (family_id) REFERENCES families(id)
);

CREATE TABLE IF NOT EXISTS device_config (
  device_id TEXT PRIMARY KEY,
  version INTEGER NOT NULL DEFAULT 1,
  config_json TEXT NOT NULL,
  updated_by TEXT NOT NULL DEFAULT '',
  updated_at TEXT NOT NULL,
  FOREIGN KEY (device_id) REFERENCES devices(id)
);

CREATE TABLE IF NOT EXISTS pair_codes (
  code_hash TEXT PRIMARY KEY,
  family_id TEXT NOT NULL,
  expires_at TEXT NOT NULL,
  used_at TEXT,
  created_at TEXT NOT NULL,
  FOREIGN KEY (family_id) REFERENCES families(id)
);

CREATE TABLE IF NOT EXISTS nonces (
  device_id TEXT NOT NULL,
  nonce TEXT NOT NULL,
  seen_at TEXT NOT NULL,
  PRIMARY KEY (device_id, nonce)
);

CREATE TABLE IF NOT EXISTS grants (
  id TEXT PRIMARY KEY,
  request_id TEXT NOT NULL,
  device_id TEXT NOT NULL,
  scope TEXT NOT NULL,
  expires_at TEXT NOT NULL,
  signature TEXT NOT NULL,
  consumed_at TEXT,
  created_at TEXT NOT NULL,
  FOREIGN KEY (request_id) REFERENCES unlock_requests(id)
);

CREATE TABLE IF NOT EXISTS alerts (
  id TEXT PRIMARY KEY,
  device_id TEXT NOT NULL,
  child_id TEXT NOT NULL,
  level TEXT NOT NULL,
  category TEXT NOT NULL,
  occurred_at INTEGER NOT NULL,
  dedupe_key TEXT NOT NULL DEFAULT '',
  snippet_enc TEXT NOT NULL DEFAULT '',
  snippet_expires_at TEXT,
  acknowledged_by TEXT,
  acknowledged_at TEXT,
  created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS children (
  id TEXT PRIMARY KEY,
  family_id TEXT NOT NULL,
  label TEXT NOT NULL DEFAULT '',
  stage TEXT NOT NULL DEFAULT '小学低年级',
  device_id TEXT UNIQUE,
  report_settings_json TEXT NOT NULL DEFAULT '{}',
  created_at TEXT NOT NULL,
  FOREIGN KEY (family_id) REFERENCES families(id)
);

CREATE TABLE IF NOT EXISTS study_event (
  event_id TEXT PRIMARY KEY,
  child_id TEXT NOT NULL,
  device_id TEXT NOT NULL,
  type TEXT NOT NULL,
  occurred_at INTEGER NOT NULL,
  local_date TEXT NOT NULL,
  seq INTEGER NOT NULL DEFAULT 0,
  subject TEXT NOT NULL DEFAULT '',
  kp_ids TEXT NOT NULL DEFAULT '[]',
  payload_json TEXT NOT NULL DEFAULT '{}',
  schema_version INTEGER NOT NULL DEFAULT 1,
  received_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_study_event_child_date ON study_event(child_id, local_date);
CREATE INDEX IF NOT EXISTS idx_study_event_device_seq ON study_event(device_id, seq);

CREATE TABLE IF NOT EXISTS daily_aggregate (
  child_id TEXT NOT NULL,
  local_date TEXT NOT NULL,
  subject TEXT NOT NULL DEFAULT '',
  active_seconds INTEGER NOT NULL DEFAULT 0,
  idle_seconds INTEGER NOT NULL DEFAULT 0,
  attempts_count INTEGER NOT NULL DEFAULT 0,
  first_try_correct INTEGER NOT NULL DEFAULT 0,
  hints_used_count INTEGER NOT NULL DEFAULT 0,
  reveals_count INTEGER NOT NULL DEFAULT 0,
  mistakes_count INTEGER NOT NULL DEFAULT 0,
  flags_count INTEGER NOT NULL DEFAULT 0,
  questions_started INTEGER NOT NULL DEFAULT 0,
  is_study_day INTEGER NOT NULL DEFAULT 0,
  metric_version INTEGER NOT NULL DEFAULT 1,
  computed_at TEXT NOT NULL,
  PRIMARY KEY (child_id, local_date, subject)
);

CREATE TABLE IF NOT EXISTS kp_daily (
  child_id TEXT NOT NULL,
  local_date TEXT NOT NULL,
  kp_id TEXT NOT NULL,
  attempts INTEGER NOT NULL DEFAULT 0,
  correct INTEGER NOT NULL DEFAULT 0,
  mastery REAL NOT NULL DEFAULT 0.0,
  level TEXT NOT NULL DEFAULT '薄弱',
  open_mistakes INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (child_id, local_date, kp_id)
);

CREATE TABLE IF NOT EXISTS report (
  id TEXT PRIMARY KEY,
  child_id TEXT NOT NULL,
  kind TEXT NOT NULL,
  period_start TEXT NOT NULL,
  period_end TEXT NOT NULL,
  metrics_json TEXT NOT NULL DEFAULT '{}',
  analysis_json TEXT NOT NULL DEFAULT '{}',
  parent_text_json TEXT NOT NULL DEFAULT '{}',
  child_text_json TEXT NOT NULL DEFAULT '{}',
  parent_advice_json TEXT NOT NULL DEFAULT '{}',
  text_source TEXT NOT NULL DEFAULT 'template',
  data_basis_json TEXT NOT NULL DEFAULT '{}',
  metric_version INTEGER NOT NULL DEFAULT 1,
  version INTEGER NOT NULL DEFAULT 1,
  status TEXT NOT NULL DEFAULT 'final',
  generated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS report_delivery (
  id TEXT PRIMARY KEY,
  report_id TEXT NOT NULL,
  channel TEXT NOT NULL DEFAULT 'wecom',
  recipient TEXT NOT NULL DEFAULT '',
  outbox_id TEXT NOT NULL DEFAULT '',
  link_id TEXT NOT NULL UNIQUE,
  link_expires_at TEXT NOT NULL,
  link_revoked_at TEXT,
  status TEXT NOT NULL DEFAULT 'pending',
  sent_at TEXT,
  first_opened_at TEXT,
  FOREIGN KEY (report_id) REFERENCES report(id)
);

CREATE TABLE IF NOT EXISTS usage_daily (
  child_id TEXT NOT NULL,
  date TEXT NOT NULL,
  turns INTEGER NOT NULL DEFAULT 0,
  images INTEGER NOT NULL DEFAULT 0,
  companion_minutes INTEGER NOT NULL DEFAULT 0,
  extensions_granted INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (child_id, date)
);

CREATE TABLE IF NOT EXISTS device_backup (
  id TEXT PRIMARY KEY,
  device_id TEXT NOT NULL,
  child_id TEXT NOT NULL,
  created_at TEXT NOT NULL,
  size INTEGER NOT NULL,
  sha256 TEXT NOT NULL,
  blob_path TEXT NOT NULL,
  kdf_params TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS diagnostics (
  id TEXT PRIMARY KEY,
  device_id TEXT NOT NULL,
  kind TEXT NOT NULL,
  body TEXT NOT NULL,
  created_at TEXT NOT NULL
);
