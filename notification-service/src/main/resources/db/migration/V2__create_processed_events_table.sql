-- =====================================================================
-- V2: Consumer-side idempotency table.
--
-- No outbox table in v1 — Notification's own outbound events
-- (NotificationCreated) are published best-effort, not via the
-- reliable outbox pattern, since no consumer subscribes yet.
-- =====================================================================

CREATE TABLE processed_events (
                                  event_id       UUID         PRIMARY KEY,
                                  event_type     VARCHAR(64)  NOT NULL,
                                  processed_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_processed_events_type ON processed_events (event_type);