package com.warehouse.notification.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Request to create or update a per-SKU alert threshold")
public record NotificationRuleRequest(

        @Schema(description = "SKU this rule applies to", example = "WIDGET-001")
        @NotBlank
        String sku,

        @Schema(description = "Alert when stock drops below this level", example = "10")
        @Min(0)
        int alertThreshold
) {}