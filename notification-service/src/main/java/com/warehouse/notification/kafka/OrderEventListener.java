package com.warehouse.notification.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.warehouse.notification.domain.Notification;
import com.warehouse.notification.domain.NotificationType;
import com.warehouse.notification.domain.ProcessedEvent;
import com.warehouse.notification.repository.NotificationRepository;
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
public class OrderEventListener {

    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final NotificationRepository notificationRepository;
    private final TransactionTemplate transactionTemplate;

    @KafkaListener(
            topics = "${notification.consumed-topics.orders-events}",
            groupId = "${spring.kafka.consumer.group-id}"
    )
    public void handle(String message, Acknowledgment ack) {
        try {
            JsonNode envelope = objectMapper.readTree(message);
            String eventType = envelope.get("eventType").asText();
            UUID eventId = UUID.fromString(envelope.get("eventId").asText());

            log.info("Received event: type={}, eventId={}", eventType, eventId);

            switch (eventType) {
                case "OrderConfirmed" -> processTerminalEvent(
                        eventId, envelope, NotificationType.ORDER_CONFIRMED,
                        "Order Confirmed", "Order %s was confirmed successfully.");
                case "OrderFailed" -> processTerminalEvent(
                        eventId, envelope, NotificationType.ORDER_FAILED,
                        "Order Failed", "Order %s failed and could not be fulfilled.");
                case "OrderCancelled" -> processTerminalEvent(
                        eventId, envelope, NotificationType.ORDER_CANCELLED,
                        "Order Cancelled", "Order %s was cancelled and stock was released.");
                default -> log.debug("Ignoring event type: {}", eventType);
            }

            ack.acknowledge();
        } catch (Exception e) {
            log.error("Failed to process message, will NOT acknowledge (Kafka will redeliver): {}",
                    message, e);
        }
    }

    private void processTerminalEvent(
            UUID eventId, JsonNode envelope, NotificationType type,
            String titlePrefix, String messageTemplate) {

        if (processedEventRepository.existsById(eventId)) {
            log.info("Duplicate event, skipping: eventId={}", eventId);
            return;
        }

        try {
            transactionTemplate.executeWithoutResult(status -> {
                processedEventRepository.save(
                        ProcessedEvent.builder()
                                .eventId(eventId)
                                .eventType(envelope.get("eventType").asText())
                                .build()
                );

                JsonNode payload = envelope.get("payload");
                String orderId = payload.get("orderId").asText();

                var notification = Notification.builder()
                        .id(UUID.randomUUID())
                        .type(type)
                        .title(titlePrefix)
                        .message(String.format(messageTemplate, orderId))
                        .relatedEntityType("Order")
                        .relatedEntityId(orderId)
                        .acknowledged(false)
                        .build();

                notificationRepository.save(notification);

                log.info("Created notification: type={}, orderId={}", type, orderId);
            });
        } catch (DataIntegrityViolationException e) {
            log.info("Duplicate event caught at insert time, skipping: eventId={}", eventId);
        }
    }
}