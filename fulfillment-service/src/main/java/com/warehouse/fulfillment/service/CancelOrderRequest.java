package com.warehouse.fulfillment.service;

import java.util.UUID;

public record CancelOrderRequest(
        UUID orderId,
        String reason
) {}