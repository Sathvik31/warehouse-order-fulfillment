-- =====================================================================
-- V1: Fulfillment's domain tables.
--
-- `orders` is the customer-facing domain entity (returned by GET /orders/{id}).
-- `order_saga_state` is the orchestrator's internal state machine, deliberately
-- separate from `orders` per ADR-002 — lets us evolve the Saga without
-- touching the domain schema.
-- =====================================================================

CREATE TABLE orders (
                        id                UUID         PRIMARY KEY,
                        customer_id       UUID         NOT NULL,
                        sku               VARCHAR(64)  NOT NULL,
                        quantity          INT          NOT NULL,
                        status            VARCHAR(16)  NOT NULL,
                        reservation_id    UUID,
                        idempotency_key   VARCHAR(64)  NOT NULL,
                        created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                        updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

                        CONSTRAINT orders_quantity_positive       CHECK (quantity > 0),
                        CONSTRAINT orders_status_valid            CHECK (status IN ('PENDING', 'RESERVED', 'CONFIRMED', 'CANCELLED', 'FAILED')),
                        CONSTRAINT orders_idempotency_key_unique  UNIQUE (idempotency_key)
);

CREATE INDEX idx_orders_customer_id  ON orders (customer_id);
CREATE INDEX idx_orders_status       ON orders (status);
CREATE INDEX idx_orders_created_at   ON orders (created_at DESC);


CREATE TABLE order_saga_state (
                                  order_id          UUID         PRIMARY KEY,
                                  current_state     VARCHAR(24)  NOT NULL,
                                  last_event_type   VARCHAR(64),
                                  last_event_at     TIMESTAMPTZ,
                                  retry_count       INT          NOT NULL DEFAULT 0,
                                  error_details     TEXT,
                                  created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                                  updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

                                  CONSTRAINT fk_saga_order          FOREIGN KEY (order_id) REFERENCES orders (id),
                                  CONSTRAINT saga_current_state_valid CHECK (current_state IN ('PENDING', 'RESERVED', 'CONFIRMED', 'CANCELLED', 'FAILED'))
);

CREATE INDEX idx_saga_current_state  ON order_saga_state (current_state);
CREATE INDEX idx_saga_updated_at     ON order_saga_state (updated_at);