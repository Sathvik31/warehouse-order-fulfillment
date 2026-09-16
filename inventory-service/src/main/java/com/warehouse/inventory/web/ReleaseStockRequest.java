package com.warehouse.inventory.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

@Schema(description = "Request to release a reservation (undo a hold or compensate a confirmed order)")
public record ReleaseStockRequest(

        @Schema(description = "The reservation to release", example = "550e8400-e29b-41d4-a716-446655440000")
        @NotNull
        UUID reservationId,

        @Schema(description = "Human-readable reason (audit trail)", example = "ORDER_CANCELLED")
        @NotBlank
        String reason
) {}