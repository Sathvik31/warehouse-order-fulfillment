package com.warehouse.fulfillment.service;

import com.warehouse.fulfillment.domain.OrderStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record CancelOrderResult(
        UUID orderId,
        OrderStatus status,
        OffsetDateTime updatedAt,
        boolean wasIdempotentReplay
) {}