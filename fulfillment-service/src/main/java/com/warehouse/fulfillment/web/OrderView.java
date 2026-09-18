package com.warehouse.fulfillment.web;

import com.warehouse.fulfillment.domain.OrderStatus;
import com.warehouse.fulfillment.domain.SagaState;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.UUID;

@Schema(description = "Full order details including current Saga state")
public record OrderView(

        @Schema(description = "Order ID")
        UUID orderId,

        @Schema(description = "Customer ID (opaque)")
        UUID customerId,

        @Schema(description = "SKU ordered", example = "WIDGET-001")
        String sku,

        @Schema(description = "Units ordered", example = "5")
        int quantity,

        @Schema(description = "Domain-facing order status",
                example = "CONFIRMED")
        OrderStatus status,

        @Schema(description = "Reservation ID (null until StockReserved)")
        UUID reservationId,

        @Schema(description = "Internal Saga state (matches status in v1)",
                example = "CONFIRMED")
        SagaState sagaState,

        @Schema(description = "Last event that advanced the Saga",
                example = "StockConfirmed")
        String lastEventType,

        @Schema(description = "When the last event was processed")
        OffsetDateTime lastEventAt,

        @Schema(description = "Order creation timestamp")
        OffsetDateTime createdAt,

        @Schema(description = "Last update timestamp (either order or saga state)")
        OffsetDateTime updatedAt
) {}