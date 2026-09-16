package com.warehouse.inventory.web;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

@Schema(description = "Current stock view for a SKU")
public record StockView(

        @Schema(description = "SKU", example = "WIDGET-001")
        String sku,

        @Schema(description = "Item display name", example = "Blue Steel Widget")
        String name,

        @Schema(description = "Warehouse", example = "W-DEFAULT")
        String warehouseId,

        @Schema(description = "Physical stock on hand", example = "100")
        int quantityOnHand,

        @Schema(description = "Currently reserved (held for pending orders)", example = "10")
        int quantityReserved,

        @Schema(description = "Available to reserve (onHand - reserved)", example = "90")
        int quantityAvailable,

        @Schema(description = "Threshold at which StockLow events publish", example = "20")
        int publishThreshold,

        @Schema(description = "Last update timestamp")
        OffsetDateTime updatedAt
) {}