package com.warehouse.inventory.repository;

import com.warehouse.inventory.domain.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {
    // Session 3: just save(). The poller (Session 4) will add
    // findUnpublished() with SKIP LOCKED.
}