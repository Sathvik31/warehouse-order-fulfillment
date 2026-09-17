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
}