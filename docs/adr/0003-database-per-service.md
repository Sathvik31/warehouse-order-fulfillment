# ADR-003: Database per Service

**Status:** Accepted — 2026-07-25

## Context

A defining characteristic of a microservices architecture is service autonomy: each service owns its data and evolves its schema independently. The alternative — a shared database across services — is a well-known anti-pattern that produces "distributed monoliths" (services that look independent but are actually coupled through the schema).

This project has three services (Inventory, Fulfillment, Notification) and must decide whether each gets a private Postgres database or whether they share.

## Decision

Each service runs its own **private Postgres database**. No cross-service foreign keys. No shared schemas. All cross-service data movement happens exclusively through Kafka events.

## Consequences

**Gained:**

- **True service autonomy.** Each service can evolve its schema without coordinating with the others. Adding a column to `orders` doesn't require a migration in Inventory or Notification.
- **Enforced separation via the constraint of "no cross-service joins."** Because services can't query each other's tables, they're forced to communicate through the intended interface (events and APIs). This prevents the subtle coupling where "service A reads from service B's table for convenience" grows into an implicit dependency.
- **Independent scaling and tuning.** Each database can be sized, indexed, and tuned for its specific workload. Inventory is write-heavy on `stock` and `reservations`; Notification is read-heavy on `notifications`. Different index strategies apply.
- **Clear ownership boundaries** — the tables in each service's schema *are* the service's domain model. Nobody else touches them.

**Given up:**

- **No cross-service transactions.** Cannot write an order and update stock atomically at the SQL level — this is precisely the problem the Saga pattern (see ADR-002) exists to solve. Accepted, because the whole point of this project is to demonstrate the Saga solution.
- **Data duplication is legitimate.** Reservation records in Inventory carry an `order_id` that references an order in Fulfillment's database — no FK, just an opaque correlation ID. If Fulfillment deletes an order (which it never does in v1), the reservation record is orphaned. This is an accepted tradeoff.
- **Operational overhead in v1.** Three Postgres containers in the Docker Compose file instead of one. Trivial at demo scale; a real production deployment would use managed Postgres instances per service.
- **Reporting queries that span services** require reading from multiple sources and joining in application code, or building a separate read-model service that consumes events from all three. Out of scope in v1; documented as future work.

## Notes

The rule is enforced *by convention*, not by infrastructure. Nothing physically prevents Fulfillment's connection string from pointing at Inventory's database — the discipline is that it doesn't. In production, network policies or IAM would enforce the isolation.

For local development in Docker Compose, each service's connection string points at a different Postgres container (`postgres-inventory`, `postgres-fulfillment`, `postgres-notification`) on the internal network.
