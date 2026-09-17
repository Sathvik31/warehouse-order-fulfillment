package com.warehouse.fulfillment.repository;

import com.warehouse.fulfillment.domain.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, UUID> {
    // Simple JpaRepository is enough. The consumer inserts a row keyed by
    // event_id; PK conflict on duplicate delivery is the dedupe signal.
}