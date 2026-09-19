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
    private final InventoryQueryService inventoryQueryService;

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
        inventoryQueryService.invalidateStockCache(item.getSku());

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

    @Transactional
    public ReservationOperationResult confirm(ConfirmReservationRequest request) {
        log.info("Confirm request: reservationId={}, idempotencyKey={}",
                request.reservationId(), request.idempotencyKey());

        // ---- 1. Lock the reservation ----
        var reservation = reservationRepository.findByIdForUpdate(request.reservationId())
                .orElseThrow(() -> new ReservationNotFoundException(request.reservationId()));
        var itemSku = itemRepository.findById(reservation.getItemId())
                .map(Item::getSku).orElse(null);
        // ---- 2. Idempotency check (post-lock, so we see the definitive state) ----
        if (reservation.getStatus() == ReservationStatus.CONFIRMED) {
            log.info("Idempotent replay: reservation {} already CONFIRMED", reservation.getId());
            return toOperationResult(reservation, /* wasIdempotentReplay = */ true);
        }

        // ---- 3. State check: only RESERVED can be confirmed ----
        if (reservation.getStatus() != ReservationStatus.RESERVED) {
            throw new InvalidReservationStateException(
                    reservation.getId(), reservation.getStatus(), "confirm");
        }

        // ---- 4. Lock the stock row and apply the confirm math ----
        var stock = stockRepository.findByItemIdAndWarehouseIdForUpdate(
                        reservation.getItemId(), reservation.getWarehouseId())
                .orElseThrow(() -> new StockNotFoundException(
                        reservation.getItemId(), reservation.getWarehouseId()));

        // ---- Capture quantityOnHand BEFORE the confirm math, for crossing detection ----
        int quantityOnHandBefore = stock.getQuantityOnHand();
        // Confirm: physical stock leaves, reservation is fulfilled.
        // Both quantity_on_hand and quantity_reserved decrement by the same amount.
        stock.setQuantityOnHand(stock.getQuantityOnHand() - reservation.getQuantity());
        stock.setQuantityReserved(stock.getQuantityReserved() - reservation.getQuantity());
        stockRepository.save(stock);



        reservation.setStatus(ReservationStatus.CONFIRMED);
        var savedReservation = reservationRepository.save(reservation);

        writeStockConfirmedToOutbox(savedReservation, stock);

        // ---- NEW: edge-triggered StockLow check ----
        checkAndPublishStockLow(stock, quantityOnHandBefore);

        if (itemSku != null) {
            inventoryQueryService.invalidateStockCache(itemSku);
        }

        log.info("Reservation confirmed: reservationId={}, stockRemaining={}",
                savedReservation.getId(), stock.getQuantityOnHand());

        return toOperationResult(savedReservation, false);
    }

    @Transactional
    public ReservationOperationResult release(ReleaseReservationRequest request) {
        log.info("Release request: reservationId={}, reason={}, idempotencyKey={}",
                request.reservationId(), request.reason(), request.idempotencyKey());

        // ---- 1. Lock the reservation ----
        var reservation = reservationRepository.findByIdForUpdate(request.reservationId())
                .orElseThrow(() -> new ReservationNotFoundException(request.reservationId()));
        var itemSku = itemRepository.findById(reservation.getItemId())
                .map(Item::getSku).orElse(null);
        // ---- 2. Idempotency check ----
        if (reservation.getStatus() == ReservationStatus.RELEASED) {
            log.info("Idempotent replay: reservation {} already RELEASED", reservation.getId());
            return toOperationResult(reservation, true);
        }

        // ---- 3. State check: only RESERVED or CONFIRMED can be released ----
        var priorStatus = reservation.getStatus();
        if (priorStatus != ReservationStatus.RESERVED && priorStatus != ReservationStatus.CONFIRMED) {
            throw new InvalidReservationStateException(
                    reservation.getId(), priorStatus, "release");
        }

        // ---- 4. Lock stock and apply the correct release math based on prior state ----
        var stock = stockRepository.findByItemIdAndWarehouseIdForUpdate(
                        reservation.getItemId(), reservation.getWarehouseId())
                .orElseThrow(() -> new StockNotFoundException(
                        reservation.getItemId(), reservation.getWarehouseId()));

        if (priorStatus == ReservationStatus.RESERVED) {
            // Release of a still-held reservation: just undo the hold.
            stock.setQuantityReserved(stock.getQuantityReserved() - reservation.getQuantity());
        } else {
            // priorStatus == CONFIRMED: compensation for an already-completed order.
            // Physical stock returns to the warehouse.
            stock.setQuantityOnHand(stock.getQuantityOnHand() + reservation.getQuantity());
        }
        stockRepository.save(stock);

        reservation.setStatus(ReservationStatus.RELEASED);
        var savedReservation = reservationRepository.save(reservation);

        writeStockReleasedToOutbox(savedReservation, stock, request.reason(), priorStatus);

        if (itemSku != null) {
            inventoryQueryService.invalidateStockCache(itemSku);
        }

        log.info("Reservation released: reservationId={}, priorStatus={}, availableAfter={}",
                savedReservation.getId(), priorStatus, stock.getQuantityAvailable());

        return toOperationResult(savedReservation, false);
    }

// ==================== Outbox helpers ====================

    private void writeStockConfirmedToOutbox(Reservation reservation, Stock stock) {
        var eventId = UUID.randomUUID();
        var sku = itemRepository.findById(reservation.getItemId())
                .map(Item::getSku).orElse("<unknown>");

        Map<String, Object> envelope = new HashMap<>();
        envelope.put("eventId", eventId.toString());
        envelope.put("eventType", "StockConfirmed");
        envelope.put("eventVersion", 1);
        envelope.put("occurredAt", OffsetDateTime.now().toString());
        envelope.put("correlationId", reservation.getOrderId().toString());
        envelope.put("producer", "inventory-service");

        Map<String, Object> payload = new HashMap<>();
        payload.put("reservationId", reservation.getId().toString());
        payload.put("orderId", reservation.getOrderId().toString());
        payload.put("itemId", reservation.getItemId().toString());
        payload.put("sku", sku);
        payload.put("warehouseId", reservation.getWarehouseId());
        payload.put("quantity", reservation.getQuantity());
        payload.put("quantityOnHandAfter", stock.getQuantityOnHand());
        envelope.put("payload", payload);

        var outboxEvent = OutboxEvent.builder()
                .id(eventId)
                .aggregateType("Reservation")
                .aggregateId(reservation.getId().toString())
                .topic("inventory.events")
                .partitionKey(sku)
                .eventType("StockConfirmed")
                .payload(envelope)
                .build();

        outboxRepository.save(outboxEvent);
    }

    private void writeStockReleasedToOutbox(
            Reservation reservation, Stock stock, String reason, ReservationStatus priorStatus) {
        var eventId = UUID.randomUUID();
        var sku = itemRepository.findById(reservation.getItemId())
                .map(Item::getSku).orElse("<unknown>");

        Map<String, Object> envelope = new HashMap<>();
        envelope.put("eventId", eventId.toString());
        envelope.put("eventType", "StockReleased");
        envelope.put("eventVersion", 1);
        envelope.put("occurredAt", OffsetDateTime.now().toString());
        envelope.put("correlationId", reservation.getOrderId().toString());
        envelope.put("producer", "inventory-service");

        Map<String, Object> payload = new HashMap<>();
        payload.put("reservationId", reservation.getId().toString());
        payload.put("orderId", reservation.getOrderId().toString());
        payload.put("itemId", reservation.getItemId().toString());
        payload.put("sku", sku);
        payload.put("warehouseId", reservation.getWarehouseId());
        payload.put("quantity", reservation.getQuantity());
        payload.put("quantityAvailableAfter", stock.getQuantityAvailable());
        payload.put("reason", reason);
        payload.put("priorStatus", priorStatus.name());
        envelope.put("payload", payload);

        var outboxEvent = OutboxEvent.builder()
                .id(eventId)
                .aggregateType("Reservation")
                .aggregateId(reservation.getId().toString())
                .topic("inventory.events")
                .partitionKey(sku)
                .eventType("StockReleased")
                .payload(envelope)
                .build();

        outboxRepository.save(outboxEvent);
    }

    private void checkAndPublishStockLow(Stock stock, int quantityOnHandBefore) {
        int threshold = stock.getPublishThreshold();
        int quantityOnHandAfter = stock.getQuantityOnHand();

        boolean wasAboveThreshold = quantityOnHandBefore >= threshold;
        boolean isNowBelowThreshold = quantityOnHandAfter < threshold;

        if (wasAboveThreshold && isNowBelowThreshold) {
            var item = itemRepository.findById(stock.getItemId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Data integrity error: stock references non-existent item " + stock.getItemId()));

            log.info("Stock crossed below publish threshold: sku={}, quantityOnHand={}, threshold={}",
                    item.getSku(), quantityOnHandAfter, threshold);

            writeStockLowToOutbox(item, stock);
        }
    }

    private void writeStockLowToOutbox(Item item, Stock stock) {
        var eventId = UUID.randomUUID();

        Map<String, Object> envelope = new HashMap<>();
        envelope.put("eventId", eventId.toString());
        envelope.put("eventType", "StockLow");
        envelope.put("eventVersion", 1);
        envelope.put("occurredAt", OffsetDateTime.now().toString());
        envelope.put("correlationId", item.getSku());   // no order context here — SKU is the natural correlation key
        envelope.put("producer", "inventory-service");

        Map<String, Object> payload = new HashMap<>();
        payload.put("sku", item.getSku());
        payload.put("itemId", item.getId().toString());
        payload.put("quantityOnHand", stock.getQuantityOnHand());
        payload.put("publishThreshold", stock.getPublishThreshold());
        envelope.put("payload", payload);

        var outboxEvent = OutboxEvent.builder()
                .id(eventId)
                .aggregateType("Stock")
                .aggregateId(item.getSku())
                .topic("inventory.events")
                .partitionKey(item.getSku())
                .eventType("StockLow")
                .payload(envelope)
                .build();

        outboxRepository.save(outboxEvent);
    }

// ==================== Result mapper for confirm/release ====================

    private ReservationOperationResult toOperationResult(Reservation r, boolean wasIdempotentReplay) {
        return new ReservationOperationResult(
                r.getId(),
                r.getStatus(),
                r.getUpdatedAt(),
                wasIdempotentReplay
        );
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

    public static class ReservationNotFoundException extends RuntimeException {
        public ReservationNotFoundException(UUID reservationId) {
            super("Reservation not found: " + reservationId);
        }
    }

    public static class InvalidReservationStateException extends RuntimeException {
        public InvalidReservationStateException(UUID reservationId, ReservationStatus currentStatus, String operation) {
            super(String.format(
                    "Cannot %s reservation %s in state %s",
                    operation, reservationId, currentStatus));
        }
    }
}