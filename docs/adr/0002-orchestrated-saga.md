# ADR-002: Orchestrated Saga (Embedded in Fulfillment) over Choreography

**Status:** Accepted — 2026-07-25

## Context

The system's core distributed-systems responsibility is coordinating a multi-step transaction across Inventory and Fulfillment services, with a compensation path if any step fails or a cancellation is issued after confirmation. This is a textbook Saga pattern scenario.

Sagas have two established implementation styles:

- **Choreography** — services react to each other's events without a central coordinator. Fulfillment publishes `OrderPlaced`, Inventory reacts and publishes `StockReserved`, Fulfillment reacts to that, and so on. State is emergent across services.
- **Orchestration** — a coordinator explicitly drives the Saga steps. The coordinator holds an explicit state machine, sends commands to participants, reacts to their outcomes, and decides next steps. State is centralized.

A secondary question: if orchestration, should the coordinator be a **separate service** or **embedded inside Fulfillment** (same JVM, same database)?

## Decision

Use **orchestration**, with the orchestrator **embedded inside the Fulfillment service** as a dedicated component with its own `order_saga_state` table.

## Consequences

**Gained:**

- **Explicit, inspectable state.** The `order_saga_state` table records the current state of every Saga in one place. `GET /orders/{id}` returns the current Saga state without event replay or state reconstruction. A choreography-based Saga distributes state across services and requires inferring the current stage from the sequence of events processed.
- **Cleaner compensation logic.** The `CONFIRMED → CANCELLED` path (compensating a completed transaction) is genuinely complex. With orchestration, the compensation logic lives in one place — the orchestrator sends the `ReleaseStock` command and transitions state on `StockReleased`. With choreography, this logic would be smeared across services and harder to reason about.
- **Debuggability.** Stuck Sagas are diagnosable by querying `order_saga_state` for records that haven't transitioned within an expected window. In choreography, "stuck" is harder to define and harder to detect.
- **Cleaner interview narrative.** "I built an orchestrated Saga with an explicit state machine" lands as a stronger sentence than "I built a set of services that react to each other's events and the state is emergent." Both are legitimate architectures; the first is more resume-grade.
- **Embedded (not separate) orchestrator avoids over-engineering.** A separate Saga service is the "textbook" answer but adds a fourth service, a fourth database, and a fourth deployment for a coordination concern that fits naturally inside Fulfillment. Fulfillment already owns orders — hosting the orchestrator alongside is a small, additive component, not a new service.

**Given up:**

- **Fulfillment becomes the critical coordination point.** If Fulfillment is down, the Saga cannot progress. Choreography would distribute this responsibility. Mitigation: Fulfillment is stateless except for its Postgres, so restart is fast; the outbox pattern (see ADR-005 context) ensures no lost events on restart.
- **Adding a fourth service participant requires changes to Fulfillment.** In choreography, a new participant subscribes to existing events without informing anyone. In orchestration, the coordinator has to know about the new step. This is a real cost, accepted because the current three-service scope doesn't need the flexibility.
- **The orchestrator is coupled to Fulfillment's deployment.** Scaling the orchestrator independently is not possible without extracting it. Documented as future work: extract Saga orchestrator to its own service when order volume or Saga complexity justifies it.

## Notes

The embedded-orchestrator pattern is how many mid-sized teams implement Sagas in production before the flow gets complex enough to warrant extraction. It's not a compromise — it's the pragmatic middle ground between choreography (loose, hard to reason about) and full separate-service orchestration (over-engineered for three participants).
