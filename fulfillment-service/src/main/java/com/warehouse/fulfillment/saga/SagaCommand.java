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
    ORDER_CONFIRMED
    // Session 10 will add: RELEASE_STOCK, ORDER_FAILED, ORDER_CANCELLED
}