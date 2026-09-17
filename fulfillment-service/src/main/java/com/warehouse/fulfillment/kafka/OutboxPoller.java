package com.warehouse.fulfillment.kafka;

import com.warehouse.fulfillment.domain.OutboxEvent;
import com.warehouse.fulfillment.repository.OutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
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
            }
        }

        if (publishedCount > 0 || failedCount > 0) {
            log.info("Poll tick complete: published={}, failed={}", publishedCount, failedCount);
        }
    }
}