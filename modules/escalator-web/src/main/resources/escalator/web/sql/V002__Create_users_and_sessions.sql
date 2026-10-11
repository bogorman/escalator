SELECT seed_attributes('USER_ROLE',   ARRAY['ADMIN','USER']);
SELECT seed_attributes('USER_STATUS', ARRAY['ACTIVE','PENDING','DISABLED']);
SELECT seed_attributes('USER_SESSION_TYPE', ARRAY['UI','CLI']);

CREATE TABLE users (
    id                   BIGSERIAL PRIMARY KEY,
    email                VARCHAR(255) NOT NULL UNIQUE CHECK (email = lower(email)),
    encrypted_password   VARCHAR(128),                  -- unused: sign-in is by email code
    full_name            VARCHAR(255),
    phone                VARCHAR(32),
    email_verified_at    TIMESTAMP,
    email_code_hash      VARCHAR(128),                  -- sha256 of the current 6-digit code
    email_code_expires_at TIMESTAMP,
    sign_in_count        INTEGER NOT NULL DEFAULT 0,
    current_sign_in_at   TIMESTAMP,
    last_sign_in_at      TIMESTAMP,
    current_sign_in_ip   VARCHAR(45),
    last_sign_in_ip      VARCHAR(45),
    must_change_password BOOLEAN NOT NULL DEFAULT FALSE,
    password_changed_at  TIMESTAMP,
    reset_password_token VARCHAR(255),
    role   attribute_type NOT NULL REFERENCES attributes(attr_type) ON UPDATE CASCADE CHECK ((role).attr = 'USER_ROLE'),
    status attribute_type NOT NULL REFERENCES attributes(attr_type) ON UPDATE CASCADE CHECK ((status).attr = 'USER_STATUS'),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE user_sessions (
    id            BIGSERIAL PRIMARY KEY,
    session_id    VARCHAR(128) UNIQUE NOT NULL,
    user_id       BIGINT REFERENCES users(id) ON DELETE CASCADE,
    email         VARCHAR(255) NOT NULL,
    role          VARCHAR(50),
    session_type  attribute_type NOT NULL REFERENCES attributes(attr_type) ON UPDATE CASCADE
                  DEFAULT ('USER_SESSION_TYPE', 'UI') CHECK ((session_type).attr = 'USER_SESSION_TYPE'),
    session_data  JSONB,
    ip_address    VARCHAR(45),
    user_agent    TEXT,
    last_accessed TIMESTAMP NOT NULL DEFAULT NOW(),
    expires_at    TIMESTAMP NOT NULL,
    is_active     BOOLEAN DEFAULT true,
    created_at    TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_user_sessions_user_id ON user_sessions(user_id);
CREATE INDEX idx_user_sessions_expires_at ON user_sessions(expires_at);
