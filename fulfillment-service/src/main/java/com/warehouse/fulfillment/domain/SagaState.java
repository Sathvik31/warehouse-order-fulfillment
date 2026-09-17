package com.warehouse.fulfillment.domain;

/**
 * Internal state-machine state for the Saga orchestrator.
 * Kept in sync with Order.status but represents the orchestrator's view,
 * per ADR-002 — orchestrator machinery separated from domain schema.
 */
public enum SagaState {
    PENDING,
    RESERVED,
    CONFIRMED,
    CANCELLED,
    FAILED
}