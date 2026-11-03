-- M5a initial schema. family_id present from day one; v1 runtime is single-family.

CREATE TABLE IF NOT EXISTS schema_migrations (
  version TEXT PRIMARY KEY,
  applied_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS families (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL DEFAULT '',
  created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS admins (
  id TEXT PRIMARY KEY,
  username TEXT NOT NULL UNIQUE,
  password_hash TEXT NOT NULL,
  failed_count INTEGER NOT NULL DEFAULT 0,
  locked_until TEXT,
  last_login TEXT
);

CREATE TABLE IF NOT EXISTS sessions (
  id TEXT PRIMARY KEY,
  admin_id TEXT NOT NULL,
  csrf_token TEXT NOT NULL,
  created_at TEXT NOT NULL,
  last_seen_at TEXT NOT NULL,
  absolute_expires_at TEXT NOT NULL,
  FOREIGN KEY (admin_id) REFERENCES admins(id)
);

CREATE TABLE IF NOT EXISTS settings (
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS wecom_apps (
  id TEXT PRIMARY KEY,
  family_id TEXT NOT NULL,
  corpid TEXT NOT NULL DEFAULT '',
  agentid INTEGER NOT NULL DEFAULT 0,
  secret_enc TEXT NOT NULL DEFAULT '',
  token TEXT NOT NULL DEFAULT '',
  aeskey_enc TEXT NOT NULL DEFAULT '',
  api_base_url TEXT NOT NULL DEFAULT 'https://qyapi.weixin.qq.com',
  callback_key TEXT NOT NULL UNIQUE,
  enabled INTEGER NOT NULL DEFAULT 1,
  button_callback_ok INTEGER, -- NULL=untested, 1=ok, 0=failed (J14)
  FOREIGN KEY (family_id) REFERENCES families(id)
);

CREATE TABLE IF NOT EXISTS parents (
  id TEXT PRIMARY KEY,
  family_id TEXT NOT NULL,
  wecom_userid TEXT NOT NULL DEFAULT '',
  label TEXT NOT NULL,
  can_approve INTEGER NOT NULL DEFAULT 0,
  channel TEXT NOT NULL DEFAULT 'wecom_app',
  FOREIGN KEY (family_id) REFERENCES families(id)
);

CREATE TABLE IF NOT EXISTS unlock_requests (
  id TEXT PRIMARY KEY,
  device_id TEXT NOT NULL DEFAULT '',
  question_hash TEXT NOT NULL DEFAULT '',
  subject TEXT NOT NULL DEFAULT '',
  hints_used INTEGER NOT NULL DEFAULT 0,
  status TEXT NOT NULL,
  scope TEXT NOT NULL DEFAULT 'question',
  created_at TEXT NOT NULL,
  expires_at TEXT NOT NULL,
  decided_by TEXT,
  decided_at TEXT,
  task_id TEXT,
  response_code TEXT,
  consumed_at TEXT
);

CREATE TABLE IF NOT EXISTS outbox (
  id TEXT PRIMARY KEY,
  app_id TEXT NOT NULL DEFAULT '',
  kind TEXT NOT NULL,
  payload_json TEXT NOT NULL,
  attempts INTEGER NOT NULL DEFAULT 0,
  next_at TEXT NOT NULL,
  status TEXT NOT NULL,
  expires_at TEXT,
  last_error TEXT,
  created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS audit_log (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  actor TEXT NOT NULL,
  action TEXT NOT NULL,
  target TEXT NOT NULL DEFAULT '',
  at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS login_attempts (
  key TEXT PRIMARY KEY,
  failed_count INTEGER NOT NULL DEFAULT 0,
  locked_until TEXT
);
