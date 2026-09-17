package com.warehouse.fulfillment.service;

import java.util.UUID;

/**
 * Input to place a new order — starts the Saga.
 *
 * @param customerId      Opaque customer identifier (no FK to a customers table).
 * @param sku             SKU to order (validated in Inventory during Saga's reserve step).
 * @param quantity        Units to order. Must be > 0.
 * @param idempotencyKey  Client-supplied key for retry safety.
 */
public record PlaceOrderRequest(
        UUID customerId,
        String sku,
        int quantity,
        String idempotencyKey
) {}