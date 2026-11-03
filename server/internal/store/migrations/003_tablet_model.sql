-- Family-level tablet chat-model config (ADR-0015).
-- One row per family. No per-child override: v1 has a single household
-- config, and device_config must not carry secrets.
-- api_key_enc is AES-256-GCM under the master key. The plaintext key is
-- never stored in this table.

CREATE TABLE IF NOT EXISTS tablet_model_config (
  family_id TEXT PRIMARY KEY,
  version INTEGER NOT NULL,
  cleared INTEGER NOT NULL DEFAULT 0,
  provider_type TEXT NOT NULL DEFAULT '',
  base_url TEXT NOT NULL DEFAULT '',
  model_name TEXT NOT NULL DEFAULT '',
  vision_model_name TEXT NOT NULL DEFAULT '',
  api_key_enc TEXT NOT NULL DEFAULT '',
  api_key_changed_at TEXT,
  updated_at TEXT NOT NULL,
  updated_by TEXT NOT NULL DEFAULT '',
  FOREIGN KEY (family_id) REFERENCES families(id)
);

CREATE TABLE IF NOT EXISTS tablet_model_delivery (
  id TEXT PRIMARY KEY,
  family_id TEXT NOT NULL,
  device_id TEXT NOT NULL,
  version INTEGER NOT NULL,
  delivered_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_tablet_model_delivery_device
  ON tablet_model_delivery(device_id, delivered_at);
