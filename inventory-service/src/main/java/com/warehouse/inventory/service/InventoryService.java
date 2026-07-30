package com.warehouse.inventory.service;

import com.warehouse.inventory.domain.*;
import com.warehouse.inventory.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class InventoryService {

    private final ItemRepository itemRepository;
    private final StockRepository stockRepository;
    private final ReservationRepository reservationRepository;
    private final OutboxRepository outboxRepository;

    @Value("${warehouse.default-id}")
    private String defaultWarehouseId;

    @Transactional
    public ReservationResult reserve(ReservationRequest request) {
        log.info("Reservation request: sku={}, quantity={}, orderId={}, idempotencyKey={}",
                request.sku(), request.quantity(), request.orderId(), request.idempotencyKey());

        // ---- 1. Idempotency check ---------------------------------------
        // If this idempotency key has been seen before, return the original result.
        var existing = reservationRepository.findByIdempotencyKey(request.idempotencyKey());
        if (existing.isPresent()) {
            var r = existing.get();
            log.info("Idempotent replay for key={}, returning existing reservationId={}",
                    request.idempotencyKey(), r.getId());
            return toResult(r, /* wasIdempotentReplay = */ true);
        }

        // ---- 2. Resolve SKU → item_id -----------------------------------
        var item = itemRepository.findBySkuAndActiveTrue(request.sku())
                .orElseThrow(() -> new ItemNotFoundException(request.sku()));

        // ---- 3. Resolve warehouse (use default if unspecified) ----------
        var warehouseId = request.warehouseId() != null
                ? request.warehouseId()
                : defaultWarehouseId;

        // ---- 4. Lock the stock row (SELECT ... FOR UPDATE) --------------
        // Blocks any concurrent reservation attempts for the same (item, warehouse).
        var stock = stockRepository.findByItemIdAndWarehouseIdForUpdate(item.getId(), warehouseId)
                .orElseThrow(() -> new StockNotFoundException(item.getId(), warehouseId));

        // ---- 5. Check availability --------------------------------------
        int available = stock.getQuantityAvailable();
        if (available < request.quantity()) {
            log.info("Insufficient stock for sku={}: requested={}, available={}",
                    request.sku(), request.quantity(), available);
            throw new InsufficientStockException(request.sku(), request.quantity(), available);
        }

        // ---- 6. Update stock (increment reserved) -----------------------
        stock.setQuantityReserved(stock.getQuantityReserved() + request.quantity());
        stockRepository.save(stock);

        // ---- 7. Create reservation row ----------------------------------
        var reservation = Reservation.builder()
                .orderId(request.orderId())
                .itemId(item.getId())
                .warehouseId(warehouseId)
                .quantity(request.quantity())
                .status(ReservationStatus.RESERVED)
                .idempotencyKey(request.idempotencyKey())
                .build();
        reservation = reservationRepository.save(reservation);

        // ---- 8. Write to outbox (same transaction) ----------------------
        writeStockReservedToOutbox(reservation, item, stock);

        log.info("Reservation created: reservationId={}, orderId={}, sku={}, quantity={}",
                reservation.getId(), reservation.getOrderId(), request.sku(), request.quantity());

        return toResult(reservation, /* wasIdempotentReplay = */ false);
    }

    private void writeStockReservedToOutbox(Reservation reservation, Item item, Stock stock) {
        var eventId = UUID.randomUUID();

        Map<String, Object> envelope = new HashMap<>();
        envelope.put("eventId", eventId.toString());
        envelope.put("eventType", "StockReserved");
        envelope.put("eventVersion", 1);
        envelope.put("occurredAt", OffsetDateTime.now().toString());
        envelope.put("correlationId", reservation.getOrderId().toString());
        envelope.put("producer", "inventory-service");

        Map<String, Object> payload = new HashMap<>();
        payload.put("reservationId", reservation.getId().toString());
        payload.put("orderId", reservation.getOrderId().toString());
        payload.put("itemId", item.getId().toString());
        payload.put("sku", item.getSku());
        payload.put("warehouseId", reservation.getWarehouseId());
        payload.put("quantity", reservation.getQuantity());
        payload.put("quantityAvailableAfter", stock.getQuantityAvailable());
        envelope.put("payload", payload);

        var outboxEvent = OutboxEvent.builder()
                .id(eventId)
                .aggregateType("Reservation")
                .aggregateId(reservation.getId().toString())
                .topic("inventory.events")
                .partitionKey(item.getSku())
                .eventType("StockReserved")
                .payload(envelope)
                .build();

        outboxRepository.save(outboxEvent);
    }

    private ReservationResult toResult(Reservation r, boolean wasIdempotentReplay) {
        // Look up SKU for the response (we have item_id, need to include human-readable sku)
        var sku = itemRepository.findById(r.getItemId())
                .map(Item::getSku)
                .orElse("<unknown>");
        return new ReservationResult(
                r.getId(),
                r.getOrderId(),
                r.getItemId(),
                sku,
                r.getQuantity(),
                r.getStatus(),
                r.getCreatedAt(),
                wasIdempotentReplay
        );
    }

    // ==================== Exception types ====================

    public static class ItemNotFoundException extends RuntimeException {
        public ItemNotFoundException(String sku) {
            super("Item not found or inactive: " + sku);
        }
    }

    public static class StockNotFoundException extends RuntimeException {
        public StockNotFoundException(UUID itemId, String warehouseId) {
            super("Stock not found for item " + itemId + " in warehouse " + warehouseId);
        }
    }

    public static class InsufficientStockException extends RuntimeException {
        private final int requested;
        private final int available;

        public InsufficientStockException(String sku, int requested, int available) {
            super(String.format(
                    "Insufficient stock for %s: requested %d, available %d",
                    sku, requested, available));
            this.requested = requested;
            this.available = available;
        }

        public int getRequested() { return requested; }
        public int getAvailable() { return available; }
    }
}