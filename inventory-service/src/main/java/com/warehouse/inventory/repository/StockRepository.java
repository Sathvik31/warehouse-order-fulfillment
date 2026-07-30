package com.warehouse.inventory.repository;

import com.warehouse.inventory.domain.Stock;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface StockRepository extends JpaRepository<Stock, UUID> {

    Optional<Stock> findByItemIdAndWarehouseId(UUID itemId, String warehouseId);

    /**
     * PESSIMISTIC_WRITE = SELECT ... FOR UPDATE in Postgres.
     * Used during reservation to lock the stock row for the transaction.
     * Blocks concurrent reservations against the same (item, warehouse) row.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Stock s WHERE s.itemId = :itemId AND s.warehouseId = :warehouseId")
    Optional<Stock> findByItemIdAndWarehouseIdForUpdate(
            @Param("itemId") UUID itemId,
            @Param("warehouseId") String warehouseId
    );
}