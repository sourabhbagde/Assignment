-- Applied automatically at startup (spring.sql.init). Written in portable SQL so
-- the move to Postgres is mechanical. Idempotent (IF NOT EXISTS) so restarts and
-- the test harness can re-run it freely.

CREATE TABLE IF NOT EXISTS short_links (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    code            VARCHAR(64)              NOT NULL,
    long_url        VARCHAR(8192)            NOT NULL,
    normalized_hash CHAR(64)                 NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at      TIMESTAMP WITH TIME ZONE,
    active          BOOLEAN                  NOT NULL DEFAULT TRUE,
    created_by      VARCHAR(128),
    metadata        VARCHAR(65536),
    CONSTRAINT uq_short_links_code UNIQUE (code)
);

-- Dedupe lookups: newest active link for a given destination hash.
CREATE INDEX IF NOT EXISTS ix_short_links_hash_active ON short_links (normalized_hash, active);
-- Background expiry sweeps.
CREATE INDEX IF NOT EXISTS ix_short_links_expires_at ON short_links (expires_at);

-- Raw, append-only click stream. The hot path writes here in batches.
CREATE TABLE IF NOT EXISTS click_events (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    link_id     BIGINT                   NOT NULL,
    code        VARCHAR(64)              NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    referrer    VARCHAR(2048),
    user_agent  VARCHAR(2048),
    ip_hash     CHAR(64),
    CONSTRAINT fk_click_events_link FOREIGN KEY (link_id) REFERENCES short_links (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS ix_click_events_link_ts ON click_events (link_id, occurred_at);

-- Pre-aggregated per-day counters so "clicks over time" / "total clicks" never
-- scan the raw stream. Written in the same transaction as the raw insert.
CREATE TABLE IF NOT EXISTS click_daily (
    link_id   BIGINT   NOT NULL,
    click_day CHAR(10) NOT NULL,
    clicks    BIGINT   NOT NULL DEFAULT 0,
    CONSTRAINT pk_click_daily PRIMARY KEY (link_id, click_day),
    CONSTRAINT fk_click_daily_link FOREIGN KEY (link_id) REFERENCES short_links (id) ON DELETE CASCADE
);
