package com.warehouse.fulfillment.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.warehouse.fulfillment.domain.ProcessedEvent;
import com.warehouse.fulfillment.repository.ProcessedEventRepository;
import com.warehouse.fulfillment.saga.OrchestratorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class InventoryEventListener {

    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final OrchestratorService orchestratorService;
    private final TransactionTemplate transactionTemplate;

    @KafkaListener(
            topics = "${fulfillment.consumed-topics.inventory-events}",
            groupId = "${spring.kafka.consumer.group-id}"
    )
    public void handle(String message, Acknowledgment ack) {
        try {
            JsonNode envelope = objectMapper.readTree(message);
            String eventType = envelope.get("eventType").asText();
            UUID eventId = UUID.fromString(envelope.get("eventId").asText());

            log.info("Received event: type={}, eventId={}", eventType, eventId);

            switch (eventType) {
                case "StockReserved", "StockConfirmed" -> processSagaEvent(eventId, eventType, envelope);
                default -> log.debug("Ignoring event type: {}", eventType);
            }

            ack.acknowledge();
        } catch (Exception e) {
            log.error("Failed to process message, will NOT acknowledge (Kafka will redeliver): {}",
                    message, e);
            // Do NOT ack — Kafka will redeliver.
        }
    }

    private void processSagaEvent(UUID eventId, String eventType, JsonNode envelope) {
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
                                .eventType(eventType)
                                .build()
                );

                // 2. Extract correlationId (which IS the orderId per our convention)
                UUID orderId = UUID.fromString(envelope.get("correlationId").asText());

                // 3. Extract event payload as a plain Map
                Map<String, Object> payload = jsonNodeToMap(envelope.get("payload"));

                // 4. Delegate to the orchestrator
                orchestratorService.handleInventoryEvent(orderId, eventType, payload);
            });
        } catch (DataIntegrityViolationException e) {
            log.info("Duplicate event caught at insert time, skipping: eventId={}", eventId);
        }
    }

    private Map<String, Object> jsonNodeToMap(JsonNode node) {
        Map<String, Object> map = new HashMap<>();
        if (node == null || node.isNull()) return map;
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            JsonNode value = entry.getValue();
            if (value.isTextual())      map.put(entry.getKey(), value.asText());
            else if (value.isInt())     map.put(entry.getKey(), value.asInt());
            else if (value.isLong())    map.put(entry.getKey(), value.asLong());
            else if (value.isBoolean()) map.put(entry.getKey(), value.asBoolean());
            else                        map.put(entry.getKey(), value.toString());
        }
        return map;
    }
}