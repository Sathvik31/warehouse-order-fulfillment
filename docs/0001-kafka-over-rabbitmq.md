# ADR-001: Kafka over RabbitMQ for the Event Bus

**Status:** Accepted — 2026-07-25

## Context

The system requires an event bus for asynchronous communication between three services (Inventory, Fulfillment, Notification). Two categories of message pass through the bus:

- **Domain events** — `StockReserved`, `OrderConfirmed`, `StockLow`, etc. Published once, potentially consumed by multiple services independently (Notification consumes `StockLow` for alerting; a future dashboard could consume the same event for real-time display).
- **Saga commands** — `ReserveStock`, `CancelOrder`. Published to trigger a specific action in one service.

The bus must guarantee at-least-once delivery, preserve ordering per aggregate (per-SKU for stock events, per-order for Saga events), and support independent consumption by different services without duplication.

Two candidates were considered: **Apache Kafka** and **RabbitMQ**. Both are mature, widely deployed, and have first-class Spring Boot integration.

## Decision

Use **Apache Kafka** as the event bus.

## Consequences

**Gained:**

- **Consumer groups** enable multiple independent services to consume the same event stream without duplicating queues. Notification and Fulfillment both consume `inventory.events` for different reasons — Kafka handles this naturally with one topic and two consumer groups. RabbitMQ would require either fan-out exchanges with per-consumer queues or explicit topic-per-consumer configuration.
- **Per-partition ordering** guarantees. Partitioning `inventory.events` by SKU ensures every event for a given SKU is processed in order, which is correctness-critical for stock accounting. Partitioning `orders.events` by orderId gives the same guarantee for Saga step ordering. RabbitMQ can achieve this but requires more careful queue design.
- **Event replay capability.** Kafka retains events for the configured retention period (7 days in this project), so a new consumer can be added later and replay historical events to bootstrap state. RabbitMQ's message-broker model deletes messages after acknowledgment — replay requires additional infrastructure.
- **Alignment with target job market.** Every product company in the target list (Microsoft, Amazon, Salesforce, Atlassian, Goldman, JPMC) runs Kafka in production. The interview vocabulary — consumer groups, partitions, offsets, exactly-once semantics — is Kafka vocabulary.

**Given up:**

- **Higher operational complexity.** Kafka is a distributed log system with more moving parts than RabbitMQ (broker, controller, topic configuration). Partially mitigated in v1 by using KRaft mode (see ADR-004) which eliminates the Zookeeper dependency.
- **Higher learning-curve tax during initial setup.** Getting the first producer and consumer working end-to-end takes longer than the equivalent RabbitMQ setup. Offset by heavier interview payoff.
- **Overkill for the demo's traffic volume.** Kafka is designed for high-throughput streaming (millions of messages/sec); the project's traffic is tens of orders per minute. This is a stated non-issue: the choice is deliberately optimizing for the resume signal, not the runtime characteristics of the demo.

## Notes

Not a candidate: NATS, Pulsar, AWS SQS. NATS lacks the interview mindshare in this domain; Pulsar is genuinely interesting but has smaller ecosystem coverage; SQS is cloud-locked and doesn't run cleanly in Docker Compose.
