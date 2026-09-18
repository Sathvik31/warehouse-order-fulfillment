package com.warehouse.notification.web;

import com.warehouse.notification.domain.NotificationType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.UUID;

@Schema(description = "A notification record")
public record NotificationView(
        UUID id,
        NotificationType type,
        String title,
        String message,
        String relatedEntityType,
        String relatedEntityId,
        boolean acknowledged,
        OffsetDateTime acknowledgedAt,
        OffsetDateTime createdAt
) {}