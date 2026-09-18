-- =====================================================================
-- V1: Notification's domain tables.
--
-- `notifications` — audit/alert records created from consumed events.
-- `notification_rules` — per-SKU operator-configured alert thresholds,
-- per ADR-009's two-layer threshold model (Inventory's publish_threshold
-- vs this table's alert_threshold are independent policies).
-- =====================================================================

CREATE TABLE notifications (
                               id                    UUID          PRIMARY KEY,
                               type                  VARCHAR(32)   NOT NULL,
                               title                 VARCHAR(256)  NOT NULL,
                               message               TEXT          NOT NULL,
                               related_entity_type   VARCHAR(32)   NOT NULL,
                               related_entity_id     VARCHAR(64)   NOT NULL,
                               acknowledged          BOOLEAN       NOT NULL DEFAULT FALSE,
                               acknowledged_at       TIMESTAMPTZ,
                               created_at            TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

                               CONSTRAINT notifications_type_valid CHECK (
                                   type IN ('ORDER_CONFIRMED', 'ORDER_FAILED', 'ORDER_CANCELLED', 'STOCK_LOW')
                                   )
);

CREATE INDEX idx_notifications_acknowledged ON notifications (acknowledged);
CREATE INDEX idx_notifications_type ON notifications (type);
CREATE INDEX idx_notifications_created_at ON notifications (created_at DESC);
CREATE INDEX idx_notifications_related_entity ON notifications (related_entity_type, related_entity_id);


CREATE TABLE notification_rules (
                                    id                UUID         PRIMARY KEY,
                                    sku               VARCHAR(64)  NOT NULL,
                                    alert_threshold   INT          NOT NULL,
                                    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                                    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

                                    CONSTRAINT notification_rules_sku_unique UNIQUE (sku),
                                    CONSTRAINT notification_rules_threshold_positive CHECK (alert_threshold >= 0)
);