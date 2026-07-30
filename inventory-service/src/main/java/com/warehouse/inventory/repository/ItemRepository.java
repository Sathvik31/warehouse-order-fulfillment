package com.warehouse.inventory.repository;

import com.warehouse.inventory.domain.Item;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ItemRepository extends JpaRepository<Item, UUID> {
    Optional<Item> findBySkuAndActiveTrue(String sku);
}