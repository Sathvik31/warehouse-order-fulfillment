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
}