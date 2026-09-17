package com.warehouse.fulfillment.repository;

import com.warehouse.fulfillment.domain.OutboxEvent;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

import java.util.List;
import java.util.UUID;

public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Fetch a batch of unpublished outbox rows with row-level locks,
     * skipping rows already locked by other poller instances.
     *
     * Same shape as Inventory's version — SELECT ... FOR UPDATE SKIP LOCKED.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({
            @QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2")
    })
    @Query("""
        SELECT o FROM OutboxEvent o
         WHERE o.publishedAt IS NULL
         ORDER BY o.createdAt ASC
        """)
    List<OutboxEvent> findUnpublishedForPublish(Pageable batchSize);
}