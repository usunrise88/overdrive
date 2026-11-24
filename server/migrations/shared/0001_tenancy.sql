-- The `shared` schema: who the users are, how they sign in, and the tables every user's requests share (multi-tenancy:
-- one PostgreSQL schema per user, plus this one). Never edit this file once it has been released: add a new one.

CREATE TABLE IF NOT EXISTS users (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  handle text NOT NULL UNIQUE CHECK (handle ~ '^[a-z][a-z0-9_-]{1,31}$'),
  -- Null until the user has set one.
  passphrase_hash text,
  telegram_chat_id text UNIQUE,
  disabled_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now()
);

-- Device and MCP bearer tokens, stored only as SHA-256 digests.
CREATE TABLE IF NOT EXISTS user_tokens (
  token_sha256 text NOT NULL,
  kind text NOT NULL CHECK (kind IN ('device', 'mcp')),
  name text NOT NULL,
  user_id bigint NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  created_at timestamptz NOT NULL DEFAULT now(),
  revoked_at timestamptz,
  PRIMARY KEY (token_sha256, kind)
);

CREATE TABLE IF NOT EXISTS user_settings (
  user_id bigint NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  key text NOT NULL,
  value text NOT NULL,
  PRIMARY KEY (user_id, key)
);

-- Which user an OAuth authorization (family) belongs to.
CREATE TABLE IF NOT EXISTS oauth_families (
  family_id text PRIMARY KEY,
  user_id bigint NOT NULL REFERENCES users (id) ON DELETE CASCADE
);

-- Copies of the tables of tenant migrations 0003 and 0004, which move here when the OAuth and Telegram code is
-- pointed at `shared`. The definitions are identical (a test compares them).
CREATE TABLE IF NOT EXISTS oauth_clients (
  client_id text PRIMARY KEY,
  -- Null for a public client (token_endpoint_auth_method none).
  client_secret_sha256 text,
  client_name text NOT NULL,
  redirect_uris jsonb NOT NULL CHECK (jsonb_typeof(redirect_uris) = 'array'),
  token_endpoint_auth_method text NOT NULL
    CHECK (token_endpoint_auth_method IN ('none', 'client_secret_basic', 'client_secret_post')),
  created_at timestamptz NOT NULL,
  -- Null until the owner has authorised this client once: clients that never get there are pruned.
  last_authorized_at timestamptz,
  CONSTRAINT oauth_clients_secret_matches_method
    CHECK ((token_endpoint_auth_method = 'none') = (client_secret_sha256 IS NULL))
);

CREATE TABLE IF NOT EXISTS oauth_codes (
  code_sha256 text PRIMARY KEY,
  client_id text NOT NULL REFERENCES oauth_clients (client_id) ON DELETE CASCADE,
  redirect_uri text NOT NULL,
  code_challenge text NOT NULL,
  scope text NOT NULL,
  resource text NOT NULL,
  family_id text NOT NULL,
  expires_at timestamptz NOT NULL,
  consumed_at timestamptz
);

CREATE TABLE IF NOT EXISTS oauth_tokens (
  token_sha256 text PRIMARY KEY,
  kind text NOT NULL CHECK (kind IN ('access', 'refresh')),
  family_id text NOT NULL,
  client_id text NOT NULL REFERENCES oauth_clients (client_id) ON DELETE CASCADE,
  scope text NOT NULL,
  -- The audience: the MCP endpoint this token is for.
  resource text NOT NULL,
  expires_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL,
  -- A refresh token that was exchanged for the next one.
  used_at timestamptz,
  revoked_at timestamptz
);

CREATE INDEX IF NOT EXISTS oauth_tokens_by_family ON oauth_tokens (family_id);
CREATE INDEX IF NOT EXISTS oauth_tokens_by_expiry ON oauth_tokens (expires_at);
CREATE INDEX IF NOT EXISTS oauth_codes_by_expiry ON oauth_codes (expires_at);

-- Small values the bot keeps between runs (the next getUpdates offset).
CREATE TABLE IF NOT EXISTS bot_state (
  key text PRIMARY KEY,
  value text NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now()
);
