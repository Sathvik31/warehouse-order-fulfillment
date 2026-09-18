package com.warehouse.fulfillment.web;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Optional body for order cancellation")
public record CancelOrderHttpRequest(

        @Schema(description = "Optional reason for the cancellation (audit trail)",
                example = "customer_request")
        String reason
) {}