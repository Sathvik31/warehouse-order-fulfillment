-- =====================================================================
-- V3: Add CANCELLING as an allowed value for orders.status and
--     order_saga_state.current_state.
--
-- CANCELLING is an intermediate state during compensation — the API
-- has accepted a cancel request but Inventory hasn't confirmed stock
-- release yet. Transitions to CANCELLED (terminal) on StockReleased.
-- =====================================================================

ALTER TABLE orders DROP CONSTRAINT orders_status_valid;
ALTER TABLE orders ADD CONSTRAINT orders_status_valid
    CHECK (status IN ('PENDING', 'RESERVED', 'CONFIRMED', 'CANCELLING', 'CANCELLED', 'FAILED'));

ALTER TABLE order_saga_state DROP CONSTRAINT saga_current_state_valid;
ALTER TABLE order_saga_state ADD CONSTRAINT saga_current_state_valid
    CHECK (current_state IN ('PENDING', 'RESERVED', 'CONFIRMED', 'CANCELLING', 'CANCELLED', 'FAILED'));