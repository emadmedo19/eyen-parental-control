CREATE TABLE IF NOT EXISTS pairing_sessions (
  id UUID PRIMARY KEY,
  code_hash TEXT NOT NULL UNIQUE,
  poll_token_hash TEXT NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL,
  claimed_at TIMESTAMPTZ,
  child_device_id UUID UNIQUE,
  child_device_name TEXT,
  child_token_hash TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT pairing_claim_fields_consistent CHECK (
    (claimed_at IS NULL AND child_device_id IS NULL AND child_token_hash IS NULL)
    OR
    (claimed_at IS NOT NULL AND child_device_id IS NOT NULL AND child_token_hash IS NOT NULL)
  )
);

CREATE INDEX IF NOT EXISTS pairing_sessions_expiry_idx ON pairing_sessions (expires_at);
