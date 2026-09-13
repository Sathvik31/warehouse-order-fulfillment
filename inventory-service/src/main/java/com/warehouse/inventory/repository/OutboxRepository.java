package com.warehouse.inventory.repository;

import com.warehouse.inventory.domain.OutboxEvent;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Fetch a batch of unpublished outbox rows with a row-level lock,
     * skipping any rows already locked by other poller instances.
     *
     * Uses PESSIMISTIC_WRITE (SELECT ... FOR UPDATE) combined with
     * SKIP LOCKED via the jakarta.persistence.lock.timeout hint value -2,
     * which is Hibernate's mapping to SKIP LOCKED behavior on Postgres.
     *
     * @param batchSize maximum number of rows to return in one call
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
    List<OutboxEvent> findUnpublishedForPublish(
            org.springframework.data.domain.Pageable batchSize
    );
}