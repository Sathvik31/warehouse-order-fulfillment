-- =====================================================================
-- V2: Infrastructure tables for reliable event handling.
--
-- `outbox` = producer-side reliability (dual-write problem).
-- `processed_events` = consumer-side idempotency (at-least-once delivery).
--
-- Both are structurally identical to Inventory's equivalents — the
-- outbox pattern and idempotent-consumer pattern are the same regardless
-- of which service uses them.
-- =====================================================================

CREATE TABLE outbox (
                        id               UUID         PRIMARY KEY,
                        aggregate_type   VARCHAR(64)  NOT NULL,
                        aggregate_id     VARCHAR(64)  NOT NULL,
                        topic            VARCHAR(128) NOT NULL,
                        partition_key    VARCHAR(64)  NOT NULL,
                        event_type       VARCHAR(64)  NOT NULL,
                        payload          JSONB        NOT NULL,
                        created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                        published_at     TIMESTAMPTZ
);

-- Partial index: only unpublished rows are indexed.
-- Keeps the poller's SELECT fast even when the table has millions of rows,
-- because published rows (the vast majority over time) are not in the index.
CREATE INDEX idx_outbox_unpublished
    ON outbox (created_at)
    WHERE published_at IS NULL;

CREATE INDEX idx_outbox_aggregate ON outbox (aggregate_type, aggregate_id);


CREATE TABLE processed_events (
                                  event_id       UUID         PRIMARY KEY,
                                  event_type     VARCHAR(64)  NOT NULL,
                                  processed_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_processed_events_type ON processed_events (event_type);