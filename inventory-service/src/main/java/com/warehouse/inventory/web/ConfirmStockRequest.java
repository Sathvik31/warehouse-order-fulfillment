package com.warehouse.inventory.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

@Schema(description = "Request to confirm a reservation, converting the hold into a real deduction")
public record ConfirmStockRequest(

        @Schema(description = "The reservation to confirm", example = "550e8400-e29b-41d4-a716-446655440000")
        @NotNull
        UUID reservationId
) {}