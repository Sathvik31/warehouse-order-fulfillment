package com.warehouse.inventory.web;

import com.warehouse.inventory.service.*;
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
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/inventory")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Inventory", description = "Stock reservation, release, confirmation, and lookup")
public class InventoryController {

    private final InventoryService inventoryService;
    private final InventoryQueryService inventoryQueryService;

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

    @PostMapping("/confirm")
    @Operation(summary = "Confirm a reservation (Saga completion — stock physically leaves)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Reservation confirmed successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid request"),
            @ApiResponse(responseCode = "404", description = "Reservation not found"),
            @ApiResponse(responseCode = "409", description = "Reservation is in a state that cannot be confirmed")
    })
    public ResponseEntity<ConfirmStockResponse> confirm(
            @Parameter(description = "Client-supplied idempotency key", required = true)
            @RequestHeader("Idempotency-Key") String idempotencyKey,

            @Valid @RequestBody ConfirmStockRequest request
    ) {
        var serviceRequest = new ConfirmReservationRequest(
                request.reservationId(),
                idempotencyKey
        );

        var result = inventoryService.confirm(serviceRequest);

        var response = new ConfirmStockResponse(
                result.reservationId(),
                result.status(),
                result.completedAt()
        );

        return ResponseEntity.ok(response);
    }

    @PostMapping("/release")
    @Operation(summary = "Release a reservation (undo hold or compensate confirmed order)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Reservation released successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid request"),
            @ApiResponse(responseCode = "404", description = "Reservation not found"),
            @ApiResponse(responseCode = "409", description = "Reservation is in a state that cannot be released")
    })
    public ResponseEntity<ReleaseStockResponse> release(
            @Parameter(description = "Client-supplied idempotency key", required = true)
            @RequestHeader("Idempotency-Key") String idempotencyKey,

            @Valid @RequestBody ReleaseStockRequest request
    ) {
        var serviceRequest = new ReleaseReservationRequest(
                request.reservationId(),
                request.reason(),
                idempotencyKey
        );

        var result = inventoryService.release(serviceRequest);

        var response = new ReleaseStockResponse(
                result.reservationId(),
                result.status(),
                result.completedAt()
        );

        return ResponseEntity.ok(response);
    }
    @GetMapping("/{sku}")
    @Operation(summary = "Get current stock for a SKU (cached)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Stock view"),
            @ApiResponse(responseCode = "404", description = "SKU not found")
    })
    public ResponseEntity<StockView> getStock(
            @Parameter(description = "The SKU to look up", example = "WIDGET-001")
            @PathVariable String sku
    ) {
        var view = inventoryQueryService.getStockBySku(sku);
        return ResponseEntity.ok(view);
    }
}