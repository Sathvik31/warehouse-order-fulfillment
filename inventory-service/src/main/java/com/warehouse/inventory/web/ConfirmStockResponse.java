package com.warehouse.inventory.web;

import com.warehouse.inventory.domain.ReservationStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.UUID;

@Schema(description = "Result of a confirm operation")
public record ConfirmStockResponse(
        UUID reservationId,
        ReservationStatus status,
        OffsetDateTime completedAt
) {}