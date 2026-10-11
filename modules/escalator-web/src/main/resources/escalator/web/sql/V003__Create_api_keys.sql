SELECT seed_attributes('API_KEY_STATUS', ARRAY['ACTIVE','REVOKED','EXPIRED']);

-- Agent/API access (MCP + REST, later). Keys are `mc_…`, stored only as sha256.
CREATE TABLE api_keys (
    id           BIGSERIAL PRIMARY KEY,
    user_id      BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    key_hash     VARCHAR(255) NOT NULL,
    key_name     VARCHAR(100) NOT NULL,
    key_hint     VARCHAR(16),
    scopes       TEXT[] NOT NULL DEFAULT ARRAY['scan'],
    last_used_at TIMESTAMP,
    expires_at   TIMESTAMP,
    total_calls  BIGINT NOT NULL DEFAULT 0,
    last_ip      VARCHAR(45),
    status attribute_type NOT NULL REFERENCES attributes(attr_type) ON UPDATE CASCADE CHECK ((status).attr = 'API_KEY_STATUS'),
    created_at   TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_api_keys_user_id ON api_keys(user_id);
CREATE INDEX idx_api_keys_key_hash ON api_keys(key_hash);
