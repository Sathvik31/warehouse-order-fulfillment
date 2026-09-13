package com.warehouse.inventory.kafka;

import com.warehouse.inventory.domain.OutboxEvent;
import com.warehouse.inventory.repository.OutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxPoller {

    private static final int BATCH_SIZE = 100;

    private final OutboxRepository outboxRepository;
    private final KafkaProducerService kafkaProducerService;

    /**
     * Wake every 500ms, fetch a batch of unpublished outbox rows,
     * publish each to Kafka, and mark successfully published rows.
     *
     * Runs in its own transaction so:
     * - Row locks (from SELECT ... FOR UPDATE SKIP LOCKED) are held only for this tick
     * - Failed publishes leave rows unlocked and unpublished for retry next tick
     */
    @Scheduled(fixedDelay = 500)
    @Transactional
    public void pollAndPublish() {
        List<OutboxEvent> unpublished = outboxRepository.findUnpublishedForPublish(
                PageRequest.of(0, BATCH_SIZE)
        );

        if (unpublished.isEmpty()) {
            return;
        }

        log.debug("Polling batch: {} unpublished events found", unpublished.size());

        int publishedCount = 0;
        int failedCount = 0;

        for (OutboxEvent event : unpublished) {
            try {
                kafkaProducerService.publish(
                        event.getTopic(),
                        event.getPartitionKey(),
                        event.getPayload()
                );
                event.setPublishedAt(OffsetDateTime.now());
                outboxRepository.save(event);
                publishedCount++;
            } catch (KafkaProducerService.KafkaPublishException e) {
                log.warn("Failed to publish event id={}, will retry next tick", event.getId(), e);
                failedCount++;
                // Deliberately do NOT set publishedAt — row remains unpublished.
                // Loop continues to try other rows in the batch.
            }
        }

        if (publishedCount > 0 || failedCount > 0) {
            log.info("Poll tick complete: published={}, failed={}", publishedCount, failedCount);
        }
    }
}