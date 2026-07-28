-- Items: the product catalog
CREATE TABLE items (
                       id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                       sku VARCHAR(64) NOT NULL UNIQUE,
                       name VARCHAR(255) NOT NULL,
                       description TEXT,
                       category VARCHAR(64),
                       unit_of_measure VARCHAR(16) NOT NULL DEFAULT 'EACH',
                       active BOOLEAN NOT NULL DEFAULT true,
                       created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                       updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_items_active ON items(active) WHERE active = true;

-- Stock: the ledger, one row per (item, warehouse)
CREATE TABLE stock (
                       id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                       item_id UUID NOT NULL REFERENCES items(id),
                       warehouse_id VARCHAR(32) NOT NULL DEFAULT 'W-DEFAULT',
                       quantity_on_hand INTEGER NOT NULL CHECK (quantity_on_hand >= 0),
                       quantity_reserved INTEGER NOT NULL DEFAULT 0 CHECK (quantity_reserved >= 0),
                       publish_threshold INTEGER,
                       version BIGINT NOT NULL DEFAULT 0,
                       created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                       updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                       CONSTRAINT uq_stock_item_warehouse UNIQUE (item_id, warehouse_id)
);

CREATE INDEX idx_stock_item ON stock(item_id);