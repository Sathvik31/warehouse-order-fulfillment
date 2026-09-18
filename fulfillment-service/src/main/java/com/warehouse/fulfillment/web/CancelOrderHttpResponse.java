package com.warehouse.fulfillment.web;

import com.warehouse.fulfillment.domain.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.UUID;

@Schema(description = "Result of a cancel order operation")
public record CancelOrderHttpResponse(
        UUID orderId,
        OrderStatus status,
        OffsetDateTime updatedAt
) {}