package com.warehouse.inventory.service;

import java.util.UUID;

public record ConfirmReservationRequest(
        UUID reservationId,
        String idempotencyKey
) {}