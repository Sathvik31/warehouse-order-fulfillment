package com.warehouse.fulfillment.domain;

/**
 * Public-facing order status returned to clients.
 * Kept in sync with OrderSagaState.currentState but conceptually a domain field.
 */
public enum OrderStatus {
    PENDING,     // initial state, ReserveStock command issued
    RESERVED,    // Inventory confirmed the reservation
    CONFIRMED,   // Saga complete, stock permanently deducted
    CANCELLED,   // compensation completed after a CONFIRMED order was cancelled
    FAILED       // terminal — order failed before any stock movement
}