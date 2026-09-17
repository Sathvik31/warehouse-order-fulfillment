package com.warehouse.inventory.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.warehouse.inventory.domain.ProcessedEvent;
import com.warehouse.inventory.repository.ProcessedEventRepository;
import com.warehouse.inventory.service.InventoryService;
import com.warehouse.inventory.service.ReservationRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import com.warehouse.inventory.service.ConfirmReservationRequest;

import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class OrderCommandListener {

    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final InventoryService inventoryService;
    private final TransactionTemplate transactionTemplate;

    @KafkaListener(
            topics = "${inventory.consumed-topics.orders-events}",
            groupId = "${spring.kafka.consumer.group-id}"
    )
    public void handle(String message, Acknowledgment ack) {
        try {
            JsonNode envelope = objectMapper.readTree(message);
            String eventType = envelope.get("eventType").asText();
            UUID eventId = UUID.fromString(envelope.get("eventId").asText());

            log.info("Received event: type={}, eventId={}", eventType, eventId);

            switch (eventType) {
                case "ReserveStock" -> processReserveStock(eventId, envelope);
                case "ConfirmReservation" -> processConfirmReservation(eventId, envelope);
                default -> log.debug("Ignoring event type: {}", eventType);
            }

            ack.acknowledge();
        } catch (Exception e) {
            log.error("Failed to process message, will NOT acknowledge (Kafka will redeliver): {}",
                    message, e);
            // Do NOT ack — Kafka will redeliver on next poll.
        }
    }

    private void processReserveStock(UUID eventId, JsonNode envelope) {
        // Fast-path dedupe check
        if (processedEventRepository.existsById(eventId)) {
            log.info("Duplicate event, skipping: eventId={}", eventId);
            return;
        }

        try {
            transactionTemplate.executeWithoutResult(status -> {
                // 1. Record processed (PK conflict = duplicate under race)
                processedEventRepository.save(
                        ProcessedEvent.builder()
                                .eventId(eventId)
                                .eventType("ReserveStock")
                                .build()
                );

                // 2. Extract payload
                JsonNode payload = envelope.get("payload");
                UUID orderId = UUID.fromString(payload.get("orderId").asText());
                String sku = payload.get("sku").asText();
                int quantity = payload.get("quantity").asInt();

                // 3. Delegate to existing reserve() — atomic with processed_events insert
                var request = new ReservationRequest(
                        orderId, sku, quantity, null,
                        "kafka-reserve-" + eventId
                );
                inventoryService.reserve(request);

                log.info("Reserved stock for orderId={}, sku={}, quantity={} (from Kafka event {})",
                        orderId, sku, quantity, eventId);
            });
        } catch (DataIntegrityViolationException e) {
            log.info("Duplicate event caught at insert time, skipping: eventId={}", eventId);
        }
    }

    private void processConfirmReservation(UUID eventId, JsonNode envelope) {
        // Fast-path dedupe check
        if (processedEventRepository.existsById(eventId)) {
            log.info("Duplicate event, skipping: eventId={}", eventId);
            return;
        }

        try {
            transactionTemplate.executeWithoutResult(status -> {
                // 1. Record processed
                processedEventRepository.save(
                        ProcessedEvent.builder()
                                .eventId(eventId)
                                .eventType("ConfirmReservation")
                                .build()
                );

                // 2. Extract payload — key field is reservationId, not orderId/sku/quantity
                JsonNode payload = envelope.get("payload");
                UUID reservationId = UUID.fromString(payload.get("reservationId").asText());

                // 3. Delegate to existing confirm() — atomic with processed_events insert
                var request = new ConfirmReservationRequest(
                        reservationId,
                        /* idempotencyKey */ "kafka-confirm-" + eventId
                );
                inventoryService.confirm(request);

                log.info("Confirmed reservationId={} (from Kafka event {})",
                        reservationId, eventId);
            });
        } catch (DataIntegrityViolationException e) {
            log.info("Duplicate event caught at insert time, skipping: eventId={}", eventId);
        }
    }

//    @Transactional
//    protected void processReserveStockInTransaction(UUID eventId, JsonNode envelope) {
//        // 1. Record this event as processed (PK conflict = duplicate)
//        processedEventRepository.save(
//                ProcessedEvent.builder()
//                        .eventId(eventId)
//                        .eventType("ReserveStock")
//                        .build()
//        );
//
//        // 2. Extract command payload
//        JsonNode payload = envelope.get("payload");
//        UUID orderId = UUID.fromString(payload.get("orderId").asText());
//        String sku = payload.get("sku").asText();
//        int quantity = payload.get("quantity").asInt();
//
//        // 3. Build the service request. The eventId doubles as the idempotency key
//        // for the reserve operation — a redelivered event would try to re-insert
//        // the same idempotency_key, which the reservations table's unique constraint
//        // rejects. Belt-and-suspenders with the processed_events dedupe above.
//        var request = new ReservationRequest(
//                orderId,
//                sku,
//                quantity,
//                /* warehouseId */ null,   // use default
//                /* idempotencyKey */ "kafka-reserve-" + eventId
//        );
//
//        // 4. Call the existing reserve() logic — writes the StockReserved
//        // event to the outbox atomically. Inventory's OutboxPoller publishes
//        // it to inventory.events on the next tick.
//        inventoryService.reserve(request);
//
//        log.info("Reserved stock for orderId={}, sku={}, quantity={} (from Kafka event {})",
//                orderId, sku, quantity, eventId);
//    }
}