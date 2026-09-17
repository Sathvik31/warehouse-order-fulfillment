package com.warehouse.fulfillment.service;

import com.warehouse.fulfillment.domain.OrderStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Result of placing an order. Status will always be PENDING right after creation —
 * the Saga advances asynchronously, and clients poll GET /orders/{id} for updates.
 *
 * @param wasIdempotentReplay true if this call replayed a prior successful placement.
 */
public record PlaceOrderResult(
        UUID orderId,
        OrderStatus status,
        OffsetDateTime createdAt,
        boolean wasIdempotentReplay
) {}