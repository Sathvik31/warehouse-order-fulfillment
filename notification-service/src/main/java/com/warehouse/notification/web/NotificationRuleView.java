package com.warehouse.notification.web;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.UUID;

@Schema(description = "A configured notification rule")
public record NotificationRuleView(
        UUID id,
        String sku,
        int alertThreshold,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}