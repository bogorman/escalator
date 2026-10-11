-- escalator-agent tables, for JdbcAgentEventStore and JdbcPromptStore. Copy into the app as a Flyway migration.
-- Requires the platform migrations: attributes (attribute_type, seed_attributes) and users(id).
-- Source: catchpoll V020 (the shape the Jdbc stores in this module are written against).

-- Agent engine (modules/agent): conversations, their ordered event log, replayable message history, and the
-- owner's prompt-block overrides with their history. Ported from betbot's agent tables, minus teams, tiers,
-- sports and per-user keys. Enum idents are UPPER_SNAKE; the store maps them to the wire names
-- (e.g. AGENT_EVENT_KIND 'CONTENT_DELTA' <-> "content-delta", AGENT_PROVIDER 'CLAUDE_CLI' <-> "claude-cli").

SELECT seed_attributes('AGENT_PROVIDER',            ARRAY['CLAUDE_CLI','ANTHROPIC_API','CODEX_CLI','OPENAI_API']);
SELECT seed_attributes('AGENT_CONVERSATION_STATUS', ARRAY['IDLE','RUNNING','ARCHIVED']);
SELECT seed_attributes('AGENT_CREDENTIAL_SOURCE',   ARRAY['API_KEY','PROXY','CLI_CONFIG','NONE']);
SELECT seed_attributes('AGENT_MESSAGE_ROLE',        ARRAY['USER','ASSISTANT','TOOL']);
SELECT seed_attributes('AGENT_EVENT_KIND',          ARRAY['SESSION_STARTED','TURN_QUEUED','TURN_STARTED','CONTENT_DELTA',
                                                          'ITEM_STARTED','ITEM_UPDATED','ITEM_COMPLETED','REQUEST_OPENED',
                                                          'REQUEST_RESOLVED','FILE_UPDATED','TURN_COMPLETED','RUNTIME_ERROR']);

-- One thread: which profile and engine run it, its CLI resume handle/workspace and its usage rollup.
CREATE TABLE agent_conversations (
    id                  BIGSERIAL PRIMARY KEY,
    user_id             BIGINT REFERENCES users(id) ON DELETE CASCADE,
    profile             VARCHAR(32)  NOT NULL,
    provider            attribute_type NOT NULL REFERENCES attributes(attr_type) ON UPDATE CASCADE CHECK ((provider).attr = 'AGENT_PROVIDER'),
    model               VARCHAR(96)  NOT NULL,
    title               VARCHAR(255) NOT NULL,
    status              attribute_type NOT NULL REFERENCES attributes(attr_type) ON UPDATE CASCADE CHECK ((status).attr = 'AGENT_CONVERSATION_STATUS'),
    credential_source   attribute_type NOT NULL REFERENCES attributes(attr_type) ON UPDATE CASCADE CHECK ((credential_source).attr = 'AGENT_CREDENTIAL_SOURCE'),
    provider_session_id VARCHAR(128),
    workspace_dir       VARCHAR(1024),
    -- Allocation high-water mark for agent_event_logs.seq, bumped atomically (UPDATE ... RETURNING).
    last_seq            BIGINT  NOT NULL DEFAULT 0,
    turn_count          INTEGER NOT NULL DEFAULT 0,
    input_tokens        BIGINT  NOT NULL DEFAULT 0,
    output_tokens       BIGINT  NOT NULL DEFAULT 0,
    cost_usd            NUMERIC(12,6),
    created_at          TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX agent_conversations_profile_idx ON agent_conversations(profile, updated_at DESC);

-- The ordered event log (seq is the resume point). Plain index, not UNIQUE: uniqueness comes from last_seq.
CREATE TABLE agent_event_logs (
    id              BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT NOT NULL REFERENCES agent_conversations(id) ON DELETE CASCADE,
    seq             BIGINT NOT NULL,
    turn_id         VARCHAR(64),
    kind            attribute_type NOT NULL REFERENCES attributes(attr_type) ON UPDATE CASCADE CHECK ((kind).attr = 'AGENT_EVENT_KIND'),
    payload         JSONB   NOT NULL,
    raw             JSONB,
    payload_bytes   INTEGER NOT NULL,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX agent_event_logs_conversation_seq_idx ON agent_event_logs(conversation_id, seq);

-- Provider-neutral content blocks ([{"type":"text"}, {"type":"tool_use"}, {"type":"tool_result"}]) a direct-API
-- engine replays as history.
CREATE TABLE agent_messages (
    id              BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT NOT NULL REFERENCES agent_conversations(id) ON DELETE CASCADE,
    turn_id         VARCHAR(64) NOT NULL,
    role            attribute_type NOT NULL REFERENCES attributes(attr_type) ON UPDATE CASCADE CHECK ((role).attr = 'AGENT_MESSAGE_ROLE'),
    content         JSONB NOT NULL,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX agent_messages_conversation_idx ON agent_messages(conversation_id, id);

-- The owner's override of one prompt block (profile.<id> | shared.<name>). One row per key, kept by the store.
-- default_sha is the sha256 of the code default when the override was written (flags a moved default).
CREATE TABLE agent_prompt_blocks (
    id          BIGSERIAL PRIMARY KEY,
    key         VARCHAR(96) NOT NULL,
    body        TEXT NOT NULL,
    default_sha VARCHAR(64) NOT NULL,
    updated_by  BIGINT REFERENCES users(id) ON DELETE SET NULL,
    created_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX agent_prompt_blocks_key_idx ON agent_prompt_blocks(key);

-- Append-only: the body in force before each save/reset (the store keeps the last 20 per key).
CREATE TABLE agent_prompt_histories (
    id          BIGSERIAL PRIMARY KEY,
    key         VARCHAR(96) NOT NULL,
    body        TEXT NOT NULL,
    updated_by  BIGINT REFERENCES users(id) ON DELETE SET NULL,
    created_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX agent_prompt_histories_key_idx ON agent_prompt_histories(key, id);
