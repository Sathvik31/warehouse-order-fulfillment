package com.warehouse.notification.web;

import com.warehouse.notification.domain.NotificationType;
import com.warehouse.notification.service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.warehouse.notification.web.NotificationRuleRequest;
import com.warehouse.notification.web.NotificationRuleView;
import jakarta.validation.Valid;
import java.util.List;

import java.util.UUID;

@RestController
@RequestMapping("/notifications")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Notifications", description = "Notification listing and acknowledgement")
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping
    @Operation(summary = "List notifications with optional filters and pagination")
    public ResponseEntity<Page<NotificationView>> listNotifications(
            @Parameter(description = "Filter by acknowledged status (optional)")
            @RequestParam(required = false) Boolean acknowledged,

            @Parameter(description = "Filter by notification type (optional)", example = "STOCK_LOW")
            @RequestParam(required = false) String type,

            @Parameter(description = "Page number (0-indexed)", example = "0")
            @RequestParam(defaultValue = "0") int page,

            @Parameter(description = "Page size", example = "20")
            @RequestParam(defaultValue = "20") int size,

            @Parameter(description = "Sort field and direction, e.g. createdAt,desc", example = "createdAt,desc")
            @RequestParam(defaultValue = "createdAt,desc") String sort
    ) {
        NotificationType parsedType = (type != null && !type.isBlank())
                ? NotificationType.valueOf(type.toUpperCase())
                : null;

        var result = notificationService.listNotifications(acknowledged, parsedType, page, size, sort);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/{notificationId}/acknowledge")
    @Operation(summary = "Mark a notification as acknowledged")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Notification acknowledged"),
            @ApiResponse(responseCode = "404", description = "Notification not found")
    })
    public ResponseEntity<NotificationView> acknowledge(
            @Parameter(description = "Notification ID", required = true)
            @PathVariable UUID notificationId
    ) {
        var result = notificationService.acknowledge(notificationId);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/rules")
    @Operation(summary = "Create or update a per-SKU alert threshold rule")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Rule created or updated")
    })
    public ResponseEntity<NotificationRuleView> upsertRule(
            @Valid @RequestBody NotificationRuleRequest request
    ) {
        var result = notificationService.upsertRule(request.sku(), request.alertThreshold());
        return ResponseEntity.ok(result);
    }

    @GetMapping("/rules")
    @Operation(summary = "List all configured notification rules")
    public ResponseEntity<List<NotificationRuleView>> listRules() {
        var result = notificationService.listRules();
        return ResponseEntity.ok(result);
    }
}