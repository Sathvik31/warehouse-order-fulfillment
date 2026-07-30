package com.warehouse.inventory.service;

import java.util.UUID;

/**
 * Input for a stock reservation. Immutable by construction (record).
 *
 * @param orderId         The order this reservation is for (opaque cross-service correlation ID).
 * @param sku             Business-friendly item identifier (resolved to item_id server-side).
 * @param quantity        Units to reserve. Must be > 0.
 * @param warehouseId     Optional warehouse override. Null → uses the configured default (W-DEFAULT).
 * @param idempotencyKey  Client-supplied key for retry safety. Required.
 */
public record ReservationRequest(
        UUID orderId,
        String sku,
        int quantity,
        String warehouseId,
        String idempotencyKey
) {}