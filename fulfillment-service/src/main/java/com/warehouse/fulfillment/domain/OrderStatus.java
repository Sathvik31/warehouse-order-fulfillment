package com.warehouse.fulfillment.domain;

public enum OrderStatus {
    PENDING,
    RESERVED,
    CONFIRMED,
    CANCELLING,   // NEW
    CANCELLED,
    FAILED
}