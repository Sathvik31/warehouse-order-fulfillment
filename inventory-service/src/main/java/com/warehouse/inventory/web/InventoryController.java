package com.warehouse.inventory.web;

import com.warehouse.inventory.service.InventoryService;
import com.warehouse.inventory.service.ReservationRequest;
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
@RequestMapping("/inventory")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Inventory", description = "Stock reservation, release, confirmation, and lookup")
public class InventoryController {

    private final InventoryService inventoryService;

    @PostMapping("/reserve")
    @Operation(summary = "Reserve stock for an order (Saga step)")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Stock reserved successfully"),
            @ApiResponse(responseCode = "200", description = "Idempotent replay of a prior reservation"),
            @ApiResponse(responseCode = "400", description = "Invalid request"),
            @ApiResponse(responseCode = "404", description = "Item not found"),
            @ApiResponse(responseCode = "409", description = "Insufficient stock")
    })
    public ResponseEntity<ReserveStockResponse> reserve(
            @Parameter(description = "Client-supplied idempotency key", required = true)
            @RequestHeader("Idempotency-Key") String idempotencyKey,

            @Valid @RequestBody ReserveStockRequest request
    ) {
        var serviceRequest = new ReservationRequest(
                request.orderId(),
                request.sku(),
                request.quantity(),
                request.warehouseId(),
                idempotencyKey
        );

        var result = inventoryService.reserve(serviceRequest);

        var response = new ReserveStockResponse(
                result.reservationId(),
                result.orderId(),
                result.itemId(),
                result.sku(),
                result.quantity(),
                result.status(),
                result.reservedAt()
        );

        var status = result.wasIdempotentReplay() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(response);
    }
}