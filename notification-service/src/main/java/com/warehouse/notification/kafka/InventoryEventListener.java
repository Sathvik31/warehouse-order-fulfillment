package com.warehouse.notification.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.warehouse.notification.domain.Notification;
import com.warehouse.notification.domain.NotificationType;
import com.warehouse.notification.domain.ProcessedEvent;
import com.warehouse.notification.repository.NotificationRepository;
import com.warehouse.notification.repository.NotificationRuleRepository;
import com.warehouse.notification.repository.ProcessedEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class InventoryEventListener {

    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final NotificationRepository notificationRepository;
    private final NotificationRuleRepository notificationRuleRepository;
    private final TransactionTemplate transactionTemplate;

    @KafkaListener(
            topics = "${notification.consumed-topics.inventory-events}",
            groupId = "${spring.kafka.consumer.group-id}"
    )
    public void handle(String message, Acknowledgment ack) {
        try {
            JsonNode envelope = objectMapper.readTree(message);
            String eventType = envelope.get("eventType").asText();
            UUID eventId = UUID.fromString(envelope.get("eventId").asText());

            log.info("Received event: type={}, eventId={}", eventType, eventId);

            switch (eventType) {
                case "StockLow" -> processStockLow(eventId, envelope);
                default -> log.debug("Ignoring event type: {}", eventType);
            }

            ack.acknowledge();
        } catch (Exception e) {
            log.error("Failed to process message, will NOT acknowledge (Kafka will redeliver): {}",
                    message, e);
        }
    }

    private void processStockLow(UUID eventId, JsonNode envelope) {
        // Fast-path dedupe check
        if (processedEventRepository.existsById(eventId)) {
            log.info("Duplicate event, skipping: eventId={}", eventId);
            return;
        }

        try {
            transactionTemplate.executeWithoutResult(status -> {
                // 1. Record processed — this happens regardless of whether
                //    a notification is ultimately created, since the EVENT
                //    was processed even if no alert resulted.
                processedEventRepository.save(
                        ProcessedEvent.builder()
                                .eventId(eventId)
                                .eventType("StockLow")
                                .build()
                );

                // 2. Extract payload
                JsonNode payload = envelope.get("payload");
                String sku = payload.get("sku").asText();
                int quantityOnHand = payload.get("quantityOnHand").asInt();

                // 3. Check if an operator has configured a rule for this SKU
                var ruleOpt = notificationRuleRepository.findBySku(sku);
                if (ruleOpt.isEmpty()) {
                    log.info("No notification rule configured for sku={}, skipping alert", sku);
                    return;
                }

                var rule = ruleOpt.get();

                // 4. Apply the operator's own threshold — independent of
                //    Inventory's publish_threshold that triggered this event.
                if (quantityOnHand >= rule.getAlertThreshold()) {
                    log.info("Stock for sku={} ({} units) is at or above alert threshold ({}), no notification",
                            sku, quantityOnHand, rule.getAlertThreshold());
                    return;
                }

                // 5. Threshold breached — create the alert
                var notification = Notification.builder()
                        .id(UUID.randomUUID())
                        .type(NotificationType.STOCK_LOW)
                        .title("Low Stock Alert: " + sku)
                        .message(String.format(
                                "Stock for %s has dropped to %d units, below your alert threshold of %d.",
                                sku, quantityOnHand, rule.getAlertThreshold()))
                        .relatedEntityType("Item")
                        .relatedEntityId(sku)
                        .acknowledged(false)
                        .build();

                notificationRepository.save(notification);

                log.info("Created STOCK_LOW notification: sku={}, quantityOnHand={}, alertThreshold={}",
                        sku, quantityOnHand, rule.getAlertThreshold());
            });
        } catch (DataIntegrityViolationException e) {
            log.info("Duplicate event caught at insert time, skipping: eventId={}", eventId);
        }
    }
}