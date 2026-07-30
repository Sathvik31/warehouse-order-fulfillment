package com.warehouse.inventory.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

@Schema(description = "Request to reserve stock for a given SKU against an order")
public record ReserveStockRequest(

        @Schema(description = "The order this reservation is for", example = "550e8400-e29b-41d4-a716-446655440000")
        @NotNull
        UUID orderId,

        @Schema(description = "SKU of the item to reserve", example = "WIDGET-001")
        @NotBlank
        String sku,

        @Schema(description = "Number of units to reserve", example = "3")
        @Min(1)
        int quantity,

        @Schema(description = "Optional warehouse override; defaults to W-DEFAULT", example = "W-DEFAULT")
        String warehouseId
) {}