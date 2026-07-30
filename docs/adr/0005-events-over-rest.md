# ADR-005: Command-Style Events over Kafka (Not Synchronous REST) for Saga Steps

**Status:** Accepted — 2026-07-25

## Context

The Saga orchestrator in Fulfillment (see ADR-002) must issue commands to Inventory: `ReserveStock`, `ConfirmReservation`, `ReleaseStock`. Two implementation styles are common:

- **Synchronous REST** — Fulfillment calls Inventory's HTTP endpoints directly (`POST /inventory/reserve`), waits for the response, transitions Saga state based on the response.
- **Command-style events over Kafka** — Fulfillment publishes a command event (`ReserveStock`) to a Kafka topic; Inventory consumes it, executes the operation, publishes a response event (`StockReserved` or `StockReservationFailed`); Fulfillment's Saga listener consumes the response and transitions state.

The system also uses Kafka for domain events (`OrderConfirmed`, `StockLow`) — those are always asynchronous. The question is specifically about the Saga's step-by-step interaction.

## Decision

Use **command-style events over Kafka** for all Saga step interactions between Fulfillment and Inventory. No synchronous REST calls between services.

Inventory's HTTP endpoints (`POST /inventory/reserve`, etc.) remain, but are used by external clients or admin tools — not by Fulfillment during Saga execution.

## Consequences

**Gained:**

- **One transport layer.** All inter-service communication is through Kafka. There is one failure mode to reason about (Kafka availability), one reliability pattern (outbox + idempotent consumer), one observability integration. Adding HTTP as a second transport would double the failure modes to handle.
- **Natural durability.** Kafka retains commands until they're consumed. If Inventory is down when Fulfillment publishes `ReserveStock`, the command sits on the topic; Inventory processes it on restart. A synchronous REST call would fail immediately and require retry logic in Fulfillment.
- **Cleaner Saga narrative in interviews.** "Fully event-driven Saga" is a stronger sentence than "event-driven Saga that also uses REST for some steps." Consistency reads as intentional design.
- **Uniform backpressure model.** If Inventory is slow, commands queue in Kafka; the system degrades gracefully. Synchronous REST would cascade timeouts up to the client.

**Given up:**

- **Higher end-to-end latency.** Saga completion goes from ~50ms (with direct REST) to ~1-2s (with Kafka round-trips). This is a stated non-issue: `POST /orders` returns 202 immediately with `PENDING` state; the Saga completes asynchronously.
- **Debugging is harder with pure events.** `curl` alone cannot reproduce a full Saga flow — inspecting Kafka topics via `kafka-console-consumer` is required. Mitigation: structured logging with correlation IDs threads through every event; `GET /orders/{id}` returns the current Saga state at any time.
- **Command-vs-event naming discipline required.** The `orders.events` topic carries both true domain events (`OrderConfirmed`) and command-shaped events (`OrderPlaced`, which is *also* the `ReserveStock` command for Inventory). Some teams separate these into `.commands` and `.events` topics for clarity. V1 puts both on one topic distinguished by event type; documented as a v2 refinement.

## Notes

The choice here is a spectrum, not a binary. Real systems often mix — synchronous REST for read queries, asynchronous events for state changes. The v1 decision to go pure-events is deliberately strict for consistency and interview signal; a production system would likely relax it for specific read paths.
