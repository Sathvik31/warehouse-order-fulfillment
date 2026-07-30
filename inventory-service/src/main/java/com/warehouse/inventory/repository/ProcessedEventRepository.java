package com.warehouse.inventory.repository;

import com.warehouse.inventory.domain.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, UUID> {
    // Empty for Session 3 — no consumers yet.
}