package com.warehouse.fulfillment.web;

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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.warehouse.fulfillment.service.CancelOrderRequest;
import org.springframework.web.bind.annotation.PathVariable;
import java.util.UUID;

@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Orders", description = "Order placement, status, cancellation, and listing")
public class OrderController {

    private final OrderService orderService;

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
}