package com.warehouse.inventory.service;

import java.util.UUID;

public record ReleaseReservationRequest(
        UUID reservationId,
        String reason,
        String idempotencyKey
) {}