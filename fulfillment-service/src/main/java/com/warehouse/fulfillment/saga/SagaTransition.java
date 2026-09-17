package com.warehouse.fulfillment.saga;

import com.warehouse.fulfillment.domain.SagaState;

/**
 * The result of running the state machine on one incoming event.
 *
 * @param nextState        the new Saga state after this transition
 * @param commandToIssue   the command (if any) that should be issued as a result
 * @param isValid          false if the (current state, incoming event) combination
 *                         didn't match any known rule — a signal to the caller
 *                         that this event was unexpected in the current state
 */
public record SagaTransition(
        SagaState nextState,
        SagaCommand commandToIssue,
        boolean isValid
) {

    public static SagaTransition of(SagaState nextState, SagaCommand command) {
        return new SagaTransition(nextState, command, true);
    }

    public static SagaTransition invalid(SagaState currentState) {
        return new SagaTransition(currentState, SagaCommand.NONE, false);
    }
}