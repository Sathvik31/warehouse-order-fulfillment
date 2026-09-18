package com.warehouse.fulfillment.domain;

public enum SagaState {
    PENDING,
    RESERVED,
    CONFIRMED,
    CANCELLING,   // NEW — compensation in progress
    CANCELLED,    // terminal — compensation complete
    FAILED
}