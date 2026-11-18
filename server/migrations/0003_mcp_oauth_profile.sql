-- The MCP server, its OAuth server and the rider's profile (ADR 0018). Never edit this file once it has been
-- released: add a new one.

-- The rider's profile (spec §8.3): one row, written by PATCH /v1/profile and the update_profile tool. `data` is the
-- profile as the API serves it (contracts/schema/profile.schema.json) without updatedAt, which is the column. No row
-- means the defaults.
CREATE TABLE profile (
  id boolean PRIMARY KEY DEFAULT true CHECK (id),
  data jsonb NOT NULL CHECK (jsonb_typeof(data) = 'object'),
  updated_at timestamptz NOT NULL
);

-- The workout a ride was played from, copied when the ride is stored (or the first time it is asked about): the
-- plan cache forgets days that have passed, and get_ride compares the ride with the workout as it was. The ride's
-- laps index the steps of exactly this workout. No foreign key to rides: rides are never deleted, and a table that
-- points at rides would make every TRUNCATE of rides (the tests', an operator's) name this one too.
CREATE TABLE ride_workouts (
  ride_id text PRIMARY KEY,
  workout jsonb NOT NULL,
  captured_at timestamptz NOT NULL
);

-- OAuth clients registered by RFC 7591 dynamic registration. Secrets and tokens are only ever stored as SHA-256
-- digests (they are 256-bit random values, so a fast hash is enough); the plain value is shown once.
CREATE TABLE oauth_clients (
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

-- Authorization codes: single use, short lived. A code seen again after it was used revokes the tokens it made
-- (family_id), as OAuth 2.1 asks.
CREATE TABLE oauth_codes (
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

-- Access and refresh tokens. A family is everything that descends from one authorization: refreshing replaces the
-- refresh token (the old one is marked used) and a used refresh token presented again revokes the whole family.
CREATE TABLE oauth_tokens (
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

CREATE INDEX oauth_tokens_by_family ON oauth_tokens (family_id);
CREATE INDEX oauth_tokens_by_expiry ON oauth_tokens (expires_at);
CREATE INDEX oauth_codes_by_expiry ON oauth_codes (expires_at);
