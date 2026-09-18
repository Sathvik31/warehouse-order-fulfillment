package com.warehouse.fulfillment.saga;

/**
 * Commands the orchestrator can decide to issue after a state transition.
 *
 * NONE = no command; the transition happened but nothing needs publishing
 *        (e.g., reaching a terminal state where no further action is required).
 * CONFIRM_RESERVATION = publish a ConfirmReservation command to orders.events.
 * ORDER_CONFIRMED = publish an OrderConfirmed domain event (for Notification, not a command).
 */
public enum SagaCommand {
    NONE,
    CONFIRM_RESERVATION,
    ORDER_CONFIRMED,
    ORDER_CANCELLED   // NEW — domain event published when compensation completes
    // Session 10 optionally: RELEASE_STOCK is issued directly from the cancel API,
    // not from the state machine, so it doesn't need to be here.
}