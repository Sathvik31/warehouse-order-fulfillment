package com.warehouse.fulfillment.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

@Schema(description = "Request to place a new order — kicks off the Saga")
public record PlaceOrderHttpRequest(

        @Schema(description = "Customer identifier (opaque UUID)",
                example = "550e8400-e29b-41d4-a716-446655440000")
        @NotNull
        UUID customerId,

        @Schema(description = "SKU to order", example = "WIDGET-001")
        @NotBlank
        String sku,

        @Schema(description = "Units to order", example = "3")
        @Min(1)
        int quantity
) {}