package com.warehouse.fulfillment.saga;

import com.warehouse.fulfillment.domain.SagaState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * The Saga orchestrator's state machine — pure logic, no side effects,
 * no dependencies on Kafka, database, or Spring context injection.
 *
 * Given a current state and an incoming event type, decides:
 *   - what the next state is
 *   - what command (if any) the caller should publish
 *
 * This is the conceptual core of Fulfillment as an orchestrator.
 * Every workflow decision lives here.
 */
@Component
@Slf4j
public class SagaStateMachine {

    /**
     * Compute the next transition. Returns an invalid transition if the
     * (currentState, eventType) combination has no matching rule — this signals
     * to the caller that the event was unexpected but shouldn't crash.
     */
    public SagaTransition nextTransition(SagaState currentState, String eventType) {
        // Happy-path transitions — Session 9 scope
        if (currentState == SagaState.PENDING && "StockReserved".equals(eventType)) {
            return SagaTransition.of(SagaState.RESERVED, SagaCommand.CONFIRM_RESERVATION);
        }

        if (currentState == SagaState.RESERVED && "StockConfirmed".equals(eventType)) {
            return SagaTransition.of(SagaState.CONFIRMED, SagaCommand.ORDER_CONFIRMED);
        }

        // Session 10 will add:
        //   PENDING  + StockReservationFailed → FAILED (issue OrderFailed event)
        //   CONFIRMED + [cancel API triggers manually, not via event] → issues RELEASE_STOCK
        //   [any]    + StockReleased          → CANCELLED (issue OrderCancelled event)

        // No matching rule — event unexpected in this state
        log.warn("No Saga transition defined for state={}, event={}", currentState, eventType);
        return SagaTransition.invalid(currentState);
    }
}