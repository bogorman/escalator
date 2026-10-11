SELECT seed_attributes('INTERACTION_TYPE', ARRAY['EMAIL']);

-- Outbox: every email is written here first and sent by InteractionActor.
CREATE TABLE interactions (
    id               BIGSERIAL PRIMARY KEY,
    interaction_type attribute_type NOT NULL REFERENCES attributes(attr_type) ON UPDATE CASCADE
                     CHECK ((interaction_type).attr = 'INTERACTION_TYPE'),
    source_model     VARCHAR(255) NOT NULL,     -- User, Scan
    source_model_id  BIGINT NOT NULL,
    reason           VARCHAR(255) NOT NULL,     -- email_code, reference_request, reference_reminder, offer, …
    address          VARCHAR(255) NOT NULL,
    header           VARCHAR(255),
    content          TEXT,
    approved         BOOLEAN NOT NULL DEFAULT TRUE,
    complete_at      TIMESTAMP,
    success          BOOLEAN,
    result_message   TEXT,
    created_at       TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX interactions_uidx ON interactions(source_model, source_model_id, reason);
CREATE INDEX idx_interactions_pending ON interactions(complete_at) WHERE complete_at IS NULL;
