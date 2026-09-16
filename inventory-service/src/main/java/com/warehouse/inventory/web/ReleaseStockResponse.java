package com.warehouse.inventory.web;

import com.warehouse.inventory.domain.ReservationStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.UUID;

@Schema(description = "Result of a release operation")
public record ReleaseStockResponse(
        UUID reservationId,
        ReservationStatus status,
        OffsetDateTime completedAt
) {}