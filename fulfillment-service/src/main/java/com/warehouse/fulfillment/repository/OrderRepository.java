package com.warehouse.fulfillment.repository;

import com.warehouse.fulfillment.domain.Order;
import com.warehouse.fulfillment.domain.OrderStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, UUID> {

    Optional<Order> findByIdempotencyKey(String idempotencyKey);

    // ---- Query methods for GET /orders ----
    Page<Order> findAll(Pageable pageable);   // provided by JpaRepository
    Page<Order> findByCustomerId(UUID customerId, Pageable pageable);
    Page<Order> findByStatus(OrderStatus status, Pageable pageable);
    Page<Order> findByCustomerIdAndStatus(UUID customerId, OrderStatus status, Pageable pageable);

    // ---- Lock query (unchanged from Sub-step 7.3) ----
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM Order o WHERE o.id = :id")
    Optional<Order> findByIdForUpdate(@Param("id") UUID id);
}