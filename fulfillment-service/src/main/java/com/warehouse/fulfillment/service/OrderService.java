package com.warehouse.fulfillment.service;

import com.warehouse.fulfillment.domain.*;
import com.warehouse.fulfillment.repository.OrderRepository;
import com.warehouse.fulfillment.repository.OrderSagaStateRepository;
import com.warehouse.fulfillment.repository.OutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.warehouse.fulfillment.repository.OrderSagaStateRepository;


import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderSagaStateRepository sagaStateRepository;
    private final OutboxRepository outboxRepository;
    @Value("${fulfillment.events.topic-name}")
    private String ordersEventsTopic;

    @Transactional
    public PlaceOrderResult placeOrder(PlaceOrderRequest request) {
        log.info("Place order request: sku={}, quantity={}, customerId={}, idempotencyKey={}",
                request.sku(), request.quantity(), request.customerId(), request.idempotencyKey());

        // ---- 1. Idempotency check ----
        var existing = orderRepository.findByIdempotencyKey(request.idempotencyKey());
        if (existing.isPresent()) {
            var order = existing.get();
            log.info("Idempotent replay: order {} already exists for idempotencyKey={}",
                    order.getId(), request.idempotencyKey());
            return toResult(order, /* wasIdempotentReplay = */ true);
        }

        // ---- 2. Create the order (application-assigned ID) ----
        var orderId = UUID.randomUUID();
        var order = Order.builder()
                .id(orderId)
                .customerId(request.customerId())
                .sku(request.sku())
                .quantity(request.quantity())
                .status(OrderStatus.PENDING)
                .idempotencyKey(request.idempotencyKey())
                .build();
        var savedOrder = orderRepository.save(order);

        // ---- 3. Initialize the Saga state ----
        var sagaState = OrderSagaState.builder()
                .orderId(orderId)
                .currentState(SagaState.PENDING)
                .retryCount(0)
                .build();
        sagaStateRepository.save(sagaState);

        // ---- 4. Write the ReserveStock command to the outbox ----
        writeReserveStockCommandToOutbox(savedOrder);

        log.info("Order placed: orderId={}, sku={}, quantity={}",
                orderId, request.sku(), request.quantity());

        return toResult(savedOrder, false);
    }

    private void writeReserveStockCommandToOutbox(Order order) {
        var eventId = UUID.randomUUID();

        Map<String, Object> envelope = new HashMap<>();
        envelope.put("eventId", eventId.toString());
        envelope.put("eventType", "ReserveStock");
        envelope.put("eventVersion", 1);
        envelope.put("occurredAt", OffsetDateTime.now().toString());
        envelope.put("correlationId", order.getId().toString());
        envelope.put("producer", "fulfillment-service");

        Map<String, Object> payload = new HashMap<>();
        payload.put("orderId", order.getId().toString());
        payload.put("sku", order.getSku());
        payload.put("quantity", order.getQuantity());
        payload.put("customerId", order.getCustomerId().toString());
        envelope.put("payload", payload);

        var outboxEvent = OutboxEvent.builder()
                .id(eventId)
                .aggregateType("Order")
                .aggregateId(order.getId().toString())
                .topic(ordersEventsTopic)
                .partitionKey(order.getId().toString())    // partition by orderId
                .eventType("ReserveStock")
                .payload(envelope)
                .build();

        outboxRepository.save(outboxEvent);
    }

    private PlaceOrderResult toResult(Order order, boolean wasIdempotentReplay) {
        return new PlaceOrderResult(
                order.getId(),
                order.getStatus(),
                order.getCreatedAt(),
                wasIdempotentReplay
        );
    }
    public static class OrderNotFoundException extends RuntimeException {
        public OrderNotFoundException(UUID orderId) {
            super("Order not found: " + orderId);
        }
    }
    @Transactional
    public CancelOrderResult cancelOrder(CancelOrderRequest request) {
        log.info("Cancel order request: orderId={}, reason={}", request.orderId(), request.reason());

        // ---- 1. Lock the order row ----
        var order = orderRepository.findByIdForUpdate(request.orderId())
                .orElseThrow(() -> new OrderNotFoundException(request.orderId()));

        // ---- 2. Idempotency check via natural state ----
        if (order.getStatus() == OrderStatus.CANCELLING
                || order.getStatus() == OrderStatus.CANCELLED) {
            log.info("Idempotent replay: order {} already in {} state",
                    order.getId(), order.getStatus());
            return toCancelResult(order, /* wasIdempotentReplay = */ true);
        }

        // ---- 3. State validation: only CONFIRMED can be cancelled ----
        if (order.getStatus() != OrderStatus.CONFIRMED) {
            throw new InvalidOrderStateException(
                    order.getId(), order.getStatus(), "cancel");
        }

        // ---- 4. Lock the saga state row ----
        var sagaState = sagaStateRepository.findByOrderIdForUpdate(request.orderId())
                .orElseThrow(() -> new IllegalStateException(
                        "No saga state found for orderId=" + request.orderId()));

        // ---- 5. Transition both to CANCELLING ----
        order.setStatus(OrderStatus.CANCELLING);
        var savedOrder = orderRepository.save(order);

        sagaState.setCurrentState(SagaState.CANCELLING);
        sagaState.setLastEventType("CancelRequested");    // synthetic name — this transition
        // wasn't event-driven, it was API-driven
        sagaState.setLastEventAt(OffsetDateTime.now());
        sagaStateRepository.save(sagaState);

        // ---- 6. Write ReleaseStock command to the outbox ----
        writeReleaseStockCommandToOutbox(savedOrder, request.reason());

        log.info("Cancel initiated: orderId={}, transitioned to CANCELLING", savedOrder.getId());

        return toCancelResult(savedOrder, false);
    }

    private void writeReleaseStockCommandToOutbox(Order order, String reason) {
        var eventId = UUID.randomUUID();

        Map<String, Object> envelope = new HashMap<>();
        envelope.put("eventId", eventId.toString());
        envelope.put("eventType", "ReleaseStock");
        envelope.put("eventVersion", 1);
        envelope.put("occurredAt", OffsetDateTime.now().toString());
        envelope.put("correlationId", order.getId().toString());
        envelope.put("producer", "fulfillment-service");

        Map<String, Object> payload = new HashMap<>();
        payload.put("orderId", order.getId().toString());
        payload.put("reservationId", order.getReservationId().toString());
        payload.put("reason", reason != null ? reason : "ORDER_CANCELLED");
        envelope.put("payload", payload);

        var outboxEvent = OutboxEvent.builder()
                .id(eventId)
                .aggregateType("Order")
                .aggregateId(order.getId().toString())
                .topic(ordersEventsTopic)
                .partitionKey(order.getId().toString())
                .eventType("ReleaseStock")
                .payload(envelope)
                .build();

        outboxRepository.save(outboxEvent);
    }

    private CancelOrderResult toCancelResult(Order order, boolean wasIdempotentReplay) {
        return new CancelOrderResult(
                order.getId(),
                order.getStatus(),
                order.getUpdatedAt(),
                wasIdempotentReplay
        );
    }

    public static class InvalidOrderStateException extends RuntimeException {
        public InvalidOrderStateException(UUID orderId, OrderStatus currentStatus, String operation) {
            super(String.format(
                    "Cannot %s order %s in state %s (only CONFIRMED orders can be cancelled)",
                    operation, orderId, currentStatus));
        }
    }

}