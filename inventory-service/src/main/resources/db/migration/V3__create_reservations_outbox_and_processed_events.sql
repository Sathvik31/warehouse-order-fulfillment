-- ============================================================
-- Reservations: one row per reservation attempt.
-- Status lifecycle: RESERVED -> CONFIRMED (success)
--                or RESERVED -> RELEASED  (compensation)
-- ============================================================
CREATE TABLE reservations (
                              id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                              order_id UUID NOT NULL,
                              item_id UUID NOT NULL REFERENCES items(id),
                              warehouse_id VARCHAR(32) NOT NULL,
                              quantity INTEGER NOT NULL CHECK (quantity > 0),
                              status VARCHAR(16) NOT NULL CHECK (status IN ('RESERVED', 'CONFIRMED', 'RELEASED')),
                              idempotency_key VARCHAR(64) NOT NULL UNIQUE,
                              created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                              updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_reservations_order_id ON reservations(order_id);
CREATE INDEX idx_reservations_status ON reservations(status);


-- ============================================================
-- Outbox: reliable event publishing (LLD 3.4)
-- Rows written in the SAME transaction as the business change.
-- Background poller publishes to Kafka and marks published_at.
-- ============================================================
CREATE TABLE outbox (
                        id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                        aggregate_type VARCHAR(32) NOT NULL,
                        aggregate_id VARCHAR(64) NOT NULL,
                        topic VARCHAR(64) NOT NULL,
                        partition_key VARCHAR(64) NOT NULL,
                        event_type VARCHAR(64) NOT NULL,
                        payload JSONB NOT NULL,
                        created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                        published_at TIMESTAMPTZ
);

-- Partial index: makes the poller's "find unpublished rows" query fast
-- by only indexing NULL published_at values.
CREATE INDEX idx_outbox_unpublished
    ON outbox(created_at)
    WHERE published_at IS NULL;


-- ============================================================
-- Processed events: consumer-side dedupe (LLD 3.5.2)
-- Every event handler INSERTs eventId here in the same
-- transaction as its business logic. PK conflict = redelivery.
-- ============================================================
CREATE TABLE processed_events (
                                  event_id UUID PRIMARY KEY,
                                  event_type VARCHAR(64) NOT NULL,
                                  processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);