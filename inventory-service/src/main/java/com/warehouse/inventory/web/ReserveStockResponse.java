package com.warehouse.inventory.web;

import com.warehouse.inventory.domain.ReservationStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.UUID;

@Schema(description = "Result of a stock reservation attempt")
public record ReserveStockResponse(
        UUID reservationId,
        UUID orderId,
        UUID itemId,
        String sku,
        int quantity,
        ReservationStatus status,
        OffsetDateTime reservedAt
) {}