package com.warehouse.fulfillment.web;

import com.warehouse.fulfillment.service.OrderQueryService;
import com.warehouse.fulfillment.service.OrderService;
import com.warehouse.fulfillment.service.PlaceOrderRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.warehouse.fulfillment.service.CancelOrderRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import com.warehouse.fulfillment.domain.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.RequestParam;
import java.util.UUID;

@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Orders", description = "Order placement, status, cancellation, and listing")
public class OrderController {

    private final OrderService orderService;
    private final OrderQueryService orderQueryService;

    @PostMapping
    @Operation(summary = "Place a new order (kicks off Saga)")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Order accepted, Saga started"),
            @ApiResponse(responseCode = "400", description = "Invalid request"),
            @ApiResponse(responseCode = "409", description = "Idempotency key already used with a different payload")
    })
    public ResponseEntity<PlaceOrderHttpResponse> placeOrder(
            @Parameter(description = "Client-supplied idempotency key", required = true)
            @RequestHeader("Idempotency-Key") String idempotencyKey,

            @Valid @RequestBody PlaceOrderHttpRequest request
    ) {
        var serviceRequest = new PlaceOrderRequest(
                request.customerId(),
                request.sku(),
                request.quantity(),
                idempotencyKey
        );

        var result = orderService.placeOrder(serviceRequest);

        var response = new PlaceOrderHttpResponse(
                result.orderId(),
                result.status(),
                result.createdAt()
        );

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }
    @PostMapping("/{orderId}/cancel")
    @Operation(summary = "Cancel a CONFIRMED order (triggers Saga compensation)")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Cancellation accepted, compensation in progress"),
            @ApiResponse(responseCode = "404", description = "Order not found"),
            @ApiResponse(responseCode = "409", description = "Order is in a state that cannot be cancelled")
    })
    public ResponseEntity<CancelOrderHttpResponse> cancel(
            @Parameter(description = "Order ID to cancel", required = true)
            @PathVariable UUID orderId,

            @RequestBody(required = false) CancelOrderHttpRequest request
    ) {
        var reason = (request != null && request.reason() != null)
                ? request.reason()
                : "ORDER_CANCELLED";

        var serviceRequest = new CancelOrderRequest(orderId, reason);
        var result = orderService.cancelOrder(serviceRequest);

        var response = new CancelOrderHttpResponse(
                result.orderId(),
                result.status(),
                result.updatedAt()
        );

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    @GetMapping("/{orderId}")
    @Operation(summary = "Get order details and current Saga state")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Order found"),
            @ApiResponse(responseCode = "404", description = "Order not found")
    })
    public ResponseEntity<OrderView> getOrder(
            @Parameter(description = "Order ID to look up", required = true)
            @PathVariable UUID orderId
    ) {
        var view = orderQueryService.getOrderById(orderId);
        return ResponseEntity.ok(view);
    }

    @GetMapping
    @Operation(summary = "List orders with optional filters and pagination")
    public ResponseEntity<Page<OrderView>> listOrders(
            @RequestParam(required = false) String customerId,
            @RequestParam(required = false) String status,

            @Parameter(description = "Page number (0-indexed)", example = "0")
            @RequestParam(defaultValue = "0") int page,

            @Parameter(description = "Page size", example = "20")
            @RequestParam(defaultValue = "20") int size,

            @Parameter(description = "Sort field and direction, e.g. createdAt,desc", example = "createdAt,desc")
            @RequestParam(defaultValue = "createdAt,desc") String sort
    ) {
        var sortParts = sort.split(",");
        var direction = sortParts.length > 1 && sortParts[1].equalsIgnoreCase("asc")
                ? Sort.Direction.ASC : Sort.Direction.DESC;
        var pageable = PageRequest.of(page, size, Sort.by(direction, sortParts[0]));

        UUID parsedCustomerId = (customerId != null && !customerId.isBlank())
                ? UUID.fromString(customerId) : null;
        OrderStatus parsedStatus = (status != null && !status.isBlank())
                ? OrderStatus.valueOf(status.toUpperCase()) : null;

        var result = orderQueryService.listOrders(parsedCustomerId, parsedStatus, pageable);
        return ResponseEntity.ok(result);
    }
}