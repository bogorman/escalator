SELECT seed_attributes('WORKSPACE_FILE_STATUS', ARRAY['UPLOADING','READY','FAILED','DELETED']);

-- Encrypted document store (chunked, resumable uploads). Owned by a user.
CREATE TABLE workspace_files (
    id                BIGSERIAL PRIMARY KEY,
    user_id           BIGINT NOT NULL REFERENCES users(id),
    filename          VARCHAR(255) NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    storage_key       UUID NOT NULL UNIQUE,
    mime              VARCHAR(255),
    bytes             BIGINT NOT NULL CHECK (bytes >= 0),
    sha256            CHAR(64),
    expected_sha256   CHAR(64),
    chunk_bytes       INTEGER NOT NULL CHECK (chunk_bytes > 0),
    received_chunks   JSONB NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(received_chunks) = 'array'),
    purpose           VARCHAR(32) NOT NULL DEFAULT 'upload',     -- upload
    pages             INTEGER,
    text_extracted_at TIMESTAMP,
    retention_until   TIMESTAMP,
    status attribute_type NOT NULL REFERENCES attributes(attr_type) CHECK ((status).attr = 'WORKSPACE_FILE_STATUS'),
    created_at        TIMESTAMP NOT NULL DEFAULT now(),
    updated_at        TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX workspace_files_user_id ON workspace_files(user_id, id DESC);
