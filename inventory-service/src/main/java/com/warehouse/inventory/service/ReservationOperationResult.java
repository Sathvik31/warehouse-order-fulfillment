package com.warehouse.inventory.service;

import com.warehouse.inventory.domain.ReservationStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Result of confirm() or release() — the reservation's post-operation state.
 *
 * @param wasIdempotentReplay true if this call replayed a prior successful operation
 *                            (reservation was already in the target terminal state).
 */
public record ReservationOperationResult(
        UUID reservationId,
        ReservationStatus status,
        OffsetDateTime completedAt,
        boolean wasIdempotentReplay
) {}