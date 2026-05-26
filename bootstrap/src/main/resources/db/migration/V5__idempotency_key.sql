-- transfer-idempotency — V5
-- Server-side store mapping client-chosen Idempotency-Key values to the
-- response of the first POST /api/v1/transfers request that carried them.
-- A replay (same key + same body fingerprint) reads from this table and
-- short-circuits the transfer pipeline.
--
-- Storage shape (per design.md Decision 2 — structured, not literal JSON):
--   * http_status alone drives the response code.
--   * For 204 (success), envelope_* columns are NULL.
--   * For 4xx (classified rejection), envelope_* are populated and rebuild
--     the original ErrorEnvelope on replay (same code, message, timestamp).
--
-- Concurrency: atomicity is via the PRIMARY KEY unique constraint. The
-- INSERT-as-claim races; loser of the race reads the winner's PENDING or
-- COMPLETED row inside the same transaction (READ_COMMITTED isolation; see
-- design.md Decision 3).
--
-- Retention: rows are retained for at least 24h. A sweeper is OUT OF SCOPE
-- for this migration; the table grows monotonically within demo lifetimes.

CREATE TABLE idempotency_key (
    -- Column named `key_value`, not `key`, because H2 (and Postgres) reserve
    -- `key` as a keyword in some modes; renaming sidesteps the need for
    -- everywhere-quoting and keeps SQL portable across dialects.
    key_value            VARCHAR(200)              NOT NULL PRIMARY KEY,
    request_fingerprint  VARCHAR(64)               NOT NULL,
    status               VARCHAR(16)               NOT NULL,
    http_status          SMALLINT                  NOT NULL,
    envelope_code        VARCHAR(64),
    envelope_message     VARCHAR(2000),
    envelope_timestamp   TIMESTAMP WITH TIME ZONE,
    created_at           TIMESTAMP WITH TIME ZONE  NOT NULL,
    CONSTRAINT idempotency_status_valid CHECK (status IN ('PENDING', 'COMPLETED'))
);

CREATE INDEX idx_idempotency_key_created_at ON idempotency_key(created_at);
