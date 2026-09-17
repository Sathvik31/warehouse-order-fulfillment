package com.warehouse.fulfillment.repository;

import com.warehouse.fulfillment.domain.OrderSagaState;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface OrderSagaStateRepository extends JpaRepository<OrderSagaState, UUID> {

    /**
     * Lock the saga state row for the transaction — used when applying a
     * state transition triggered by a consumed Kafka event.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM OrderSagaState s WHERE s.orderId = :orderId")
    Optional<OrderSagaState> findByOrderIdForUpdate(@Param("orderId") UUID orderId);
}