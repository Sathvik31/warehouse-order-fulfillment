package com.warehouse.inventory.service;

import com.warehouse.inventory.domain.ReservationStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Output from a reservation attempt.
 *
 * @param wasIdempotentReplay true if this call replayed a prior successful reservation
 *                            with the same idempotency key (client retry).
 */
public record ReservationResult(
        UUID reservationId,
        UUID orderId,
        UUID itemId,
        String sku,
        int quantity,
        ReservationStatus status,
        OffsetDateTime reservedAt,
        boolean wasIdempotentReplay
) {}