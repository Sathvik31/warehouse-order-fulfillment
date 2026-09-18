package com.warehouse.fulfillment.saga;

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

/**
 * The orchestrator's core operation: given a Saga event arriving from Kafka,
 * apply the state machine and persist the resulting transition atomically.
 *
 * All state changes and outbox writes happen inside one @Transactional method,
 * per the outbox pattern's atomicity requirement.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrchestratorService {

    private final OrderRepository orderRepository;
    private final OrderSagaStateRepository sagaStateRepository;
    private final OutboxRepository outboxRepository;
    private final SagaStateMachine sagaStateMachine;

    @Value("${fulfillment.events.topic-name}")
    private String ordersEventsTopic;

    /**
     * Called by InventoryEventListener when a StockReserved or StockConfirmed
     * event arrives from Kafka.
     *
     * @param orderId          from the event's correlationId
     * @param eventType        "StockReserved" or "StockConfirmed"
     * @param eventPayload     the parsed payload of the incoming event (fields vary by type)
     */
    @Transactional
    public void handleInventoryEvent(UUID orderId, String eventType, Map<String, Object> eventPayload) {
        // 1. Lock both rows for the transaction
        var sagaStateOpt = sagaStateRepository.findByOrderIdForUpdate(orderId);
        if (sagaStateOpt.isEmpty()) {
            log.warn("No saga state found for orderId={} — likely stale/test event, skipping", orderId);
            return;
        }
        var sagaState = sagaStateOpt.get();

        var orderOpt = orderRepository.findByIdForUpdate(orderId);
        if (orderOpt.isEmpty()) {
            log.warn("No order found for orderId={} — likely stale/test event, skipping", orderId);
            return;
        }
        var order = orderOpt.get();;

        // 2. Consult the state machine
        var transition = sagaStateMachine.nextTransition(sagaState.getCurrentState(), eventType);

        if (!transition.isValid()) {
            log.warn("Unexpected event for orderId={}: state={}, eventType={} — ignoring",
                    orderId, sagaState.getCurrentState(), eventType);
            return;
        }

        log.info("Saga transition: orderId={}, {} + {} → {} (command: {})",
                orderId, sagaState.getCurrentState(), eventType,
                transition.nextState(), transition.commandToIssue());

        // 3. Apply the transition to saga state
        sagaState.setCurrentState(transition.nextState());
        sagaState.setLastEventType(eventType);
        sagaState.setLastEventAt(OffsetDateTime.now());
        sagaStateRepository.save(sagaState);

        // 4. Mirror the state onto the Order (public-facing status)
        order.setStatus(mapSagaStateToOrderStatus(transition.nextState()));

        // 5. If this event carried a reservationId (StockReserved does), store it on the Order
        if ("StockReserved".equals(eventType)) {
            var reservationIdStr = (String) eventPayload.get("reservationId");
            if (reservationIdStr != null) {
                order.setReservationId(UUID.fromString(reservationIdStr));
            }
        }
        orderRepository.save(order);

        // 6. Issue the resulting command/event, if any, via the outbox
        if (transition.commandToIssue() != SagaCommand.NONE) {
            writeCommandToOutbox(transition.commandToIssue(), order);
        }
    }

    private void writeCommandToOutbox(SagaCommand command, Order order) {
        var eventId = UUID.randomUUID();
        String eventType;
        Map<String, Object> payload = new HashMap<>();

        switch (command) {
            case CONFIRM_RESERVATION -> {
                eventType = "ConfirmReservation";
                payload.put("orderId", order.getId().toString());
                payload.put("reservationId", order.getReservationId().toString());
            }
            case ORDER_CONFIRMED -> {
                eventType = "OrderConfirmed";
                payload.put("orderId", order.getId().toString());
                payload.put("customerId", order.getCustomerId().toString());
                payload.put("sku", order.getSku());
                payload.put("quantity", order.getQuantity());
            }
            case ORDER_CANCELLED -> {   // NEW
                eventType = "OrderCancelled";
                payload.put("orderId", order.getId().toString());
                payload.put("customerId", order.getCustomerId().toString());
                payload.put("sku", order.getSku());
                payload.put("quantity", order.getQuantity());
            }
            default -> throw new IllegalStateException("Unhandled SagaCommand: " + command);
        }

        Map<String, Object> envelope = new HashMap<>();
        envelope.put("eventId", eventId.toString());
        envelope.put("eventType", eventType);
        envelope.put("eventVersion", 1);
        envelope.put("occurredAt", OffsetDateTime.now().toString());
        envelope.put("correlationId", order.getId().toString());
        envelope.put("producer", "fulfillment-service");
        envelope.put("payload", payload);

        var outboxEvent = OutboxEvent.builder()
                .id(eventId)
                .aggregateType("Order")
                .aggregateId(order.getId().toString())
                .topic(ordersEventsTopic)
                .partitionKey(order.getId().toString())
                .eventType(eventType)
                .payload(envelope)
                .build();

        outboxRepository.save(outboxEvent);
    }

    private OrderStatus mapSagaStateToOrderStatus(SagaState sagaState) {
        return switch (sagaState) {
            case PENDING    -> OrderStatus.PENDING;
            case RESERVED   -> OrderStatus.RESERVED;
            case CONFIRMED  -> OrderStatus.CONFIRMED;
            case CANCELLING -> OrderStatus.CANCELLING;   // NEW
            case CANCELLED  -> OrderStatus.CANCELLED;
            case FAILED     -> OrderStatus.FAILED;
        };
    }
}