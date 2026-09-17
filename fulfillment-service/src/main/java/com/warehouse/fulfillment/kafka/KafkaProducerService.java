package com.warehouse.fulfillment.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
@RequiredArgsConstructor
@Slf4j
public class KafkaProducerService {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public void publish(String topic, String partitionKey, Map<String, Object> payload) {
        String jsonValue;
        try {
            jsonValue = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new KafkaPublishException("Failed to serialize payload to JSON", e);
        }

        try {
            CompletableFuture<?> future = kafkaTemplate.send(topic, partitionKey, jsonValue);
            future.get(30, TimeUnit.SECONDS);
            log.debug("Published to topic={}, key={}", topic, partitionKey);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KafkaPublishException("Publish interrupted", e);
        } catch (ExecutionException e) {
            throw new KafkaPublishException("Publish failed", e.getCause());
        } catch (TimeoutException e) {
            throw new KafkaPublishException("Publish timed out after 30 seconds", e);
        }
    }

    public static class KafkaPublishException extends RuntimeException {
        public KafkaPublishException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}