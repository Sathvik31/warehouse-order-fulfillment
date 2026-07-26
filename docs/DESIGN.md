# Warehouse Order Fulfillment System — Design Document

**Status:** Draft complete — Sections 1 (BRD-lite), 2 (HLD), 3 (LLD), 4 (ADRs), and 5 (Design Review Notes) all done. Ready for build phase. Fulfillment review pass 2 pending.
**Author:** Saathwik
**Last updated:** 2026-07-25

---

## Table of Contents

1. [Business Requirements (BRD-lite)](#1-business-requirements-brd-lite)
2. [High-Level Design (HLD)](#2-high-level-design-hld)
3. [Low-Level Design (LLD)](#3-low-level-design-lld)
4. [Architecture Decision Records (ADRs)](#4-architecture-decision-records-adrs)
5. [Design Review Notes](#5-design-review-notes)

---

## 1. Business Requirements (BRD-lite)

### 1.1 Problem Statement

A warehouse operation needs to accept customer orders against a shared stock pool without overselling, coordinate stock reservation and confirmation across an ordering system and an inventory system, and notify operators when stock levels cross configured thresholds. The problem is not the individual capabilities — each is straightforward in isolation — but the **coordination between them under partial failure**: what happens when stock is reserved but the order fails to confirm, or when a confirmed order is later cancelled, or when the messaging layer redelivers an event.

This project builds a minimal but architecturally honest solution to that coordination problem, using the Saga pattern for distributed transactions across three microservices communicating over Kafka.

### 1.2 In Scope

- Stock management: reserve, release, confirm, and query stock per SKU.
- Order lifecycle: place, query, list, and cancel orders, with orchestrated Saga execution.
- Event-driven notifications: consume domain events, store notifications, expose read/acknowledge APIs, configure low-stock thresholds.
- Reliable event delivery via the transactional outbox pattern.
- Idempotent consumers across all event-handling paths.
- Full local deployment via Docker Compose.

### 1.3 Out of Scope

Explicitly not built in v1:

- User authentication and authorization.
- Payment processing.
- Shipping and carrier integration.
- Returns and reverse logistics.
- Supplier and purchase-order management.
- Multi-warehouse routing logic (schema supports it; runtime does not).
- Reporting and analytics dashboards.
- Admin UI.
- Kubernetes deployment, mTLS between services, secrets management beyond env vars.

Any of the above could be added as v2 features. None are required to demonstrate the distributed-systems patterns this project exists to showcase.

### 1.4 Functional Requirements

**FR-1 Stock management.** The Inventory service maintains, for each SKU, quantity on hand and quantity reserved. It exposes APIs to reserve stock against an order, release a reservation, confirm a reservation as a committed deduction, and query current levels.

**FR-2 Order lifecycle.** The Fulfillment service accepts new orders, orchestrates the Saga to reserve stock via Inventory, tracks order state through its lifecycle (`PENDING → RESERVED → CONFIRMED` on success; `PENDING → FAILED` or `CONFIRMED → CANCELLED` on compensation paths), and exposes order status, order list, and cancellation.

**FR-3 Reservation semantics.** Reservations prevent overselling under concurrent order placement for the same SKU. Two orders arriving simultaneously for the last unit of stock must result in exactly one success and one failure — never both success, never both failure when stock exists.

**FR-4 Event-driven notifications.** The Notification service consumes `StockLow` events from Inventory and Saga outcome events from Fulfillment, persists notifications, and exposes APIs to list, acknowledge, and configure per-SKU low-stock thresholds.

**FR-5 Cancellation and compensation.** A confirmed order may be cancelled, which triggers a compensating transaction to release the previously deducted stock back to available quantity.

### 1.5 Non-Functional Requirements

**NFR-1 Consistency model.** Eventual consistency across services; strong consistency within each service's database. The Saga guarantees every order reaches a terminal state (`CONFIRMED`, `FAILED`, or `CANCELLED`) and no stock reservation is orphaned.

**NFR-2 Reliability.** No event loss between services. Achieved through the transactional outbox pattern on producers and idempotent consumers on receivers. Kafka configured with `acks=all` and durable topic retention.

**NFR-3 Idempotency.** All Saga command APIs (`reserve`, `release`, `confirm`) accept an idempotency key; retries produce the same result as a single call.

**NFR-4 Performance targets (demo scale).**

- `GET /inventory/{sku}`: p95 under 50ms (Redis-cached).
- `POST /orders`: p95 under 200ms to return with `PENDING` state.
- Saga end-to-end completion: typical 1–2 seconds under no load.

**NFR-5 Observability.** Structured JSON logs with correlation ID (`orderId` / `sagaId`) threaded across all services for a given flow. `/actuator/health` and `/actuator/metrics` exposed per service.

**NFR-6 Scale (bounded).** Designed for single-node Docker Compose deployment. Not designed for horizontal scaling, multi-region, or HA. Future-work section of README describes what would change to scale.

### 1.6 Assumptions

- **Operational:** Single-machine Docker Compose deployment. No clustering, no failover, no automated recovery.
- **Data:** Items are registered via migration seed data before stock or orders reference them; no item-creation API in v1 (deferred to admin tooling). Stock in integer units. One conceptual warehouse (`W-DEFAULT`); `warehouse_id` column reserved for future extension.
- **Traffic:** Tens of orders per minute peak. Real but low concurrency. Postgres row-level locking sufficient; no distributed locks needed.
- **Security:** No auth, no TLS between services, no secrets management. Services trust each other on the internal Docker network. Called out explicitly rather than pretended otherwise.
- **Time/locale:** UTC timestamps stored as `TIMESTAMP WITH TIME ZONE`, serialized as ISO-8601. No currency modeling — orders have quantity, not price. English-only.

### 1.7 Success Criteria

**Functional acceptance.**

- Happy path: `POST /orders` → 202 → Saga completes → `GET /orders/{id}` shows `CONFIRMED` → `GET /inventory/{sku}` shows deducted stock.
- Failure path: order for insufficient stock produces `FAILED` state; no stock deducted.
- Compensation path: `POST /orders/{id}/cancel` on confirmed order releases stock back to available.
- Event-driven path: stock crossing configured threshold produces retrievable notification.

**Reliability acceptance.**

- Restarting Fulfillment mid-Saga does not leave stock reserved indefinitely; resolution within 60 seconds.
- Duplicate Kafka event delivery does not double-deduct stock.
- Both properties demonstrable via scripted test.

**Operational acceptance.**

- `docker compose up` from clean checkout brings full stack up, zero manual steps.
- All services report healthy `/actuator/health`.
- Logs across services contain correlation ID enabling single-order trace.
- README documents `curl`-based demo scenarios.

**Code quality acceptance.**

- Unit test coverage ≥ 70% on business logic (Saga transitions, stock math, event handlers).
- Testcontainers-based integration tests cover happy and compensation paths.
- No secrets in git history.
- `mvn clean verify` passes with zero warnings.

**Documentation acceptance.**

- This design doc complete across all sections.
- README with architecture diagram, quick-start, demo scenarios, future-work section.

### 1.8 Technology Stack

| Concern | Choice | One-line justification |
|---|---|---|
| Language | Java 17 | Target job market default; matches existing skills. |
| Framework | Spring Boot 3.x | Industry-default ecosystem for Kafka/JPA/Actuator. |
| Event bus | Apache Kafka (KRaft mode) | Consumer groups enable independent event consumption; KRaft eliminates Zookeeper operational overhead. |
| Database | PostgreSQL, one per service | Strong ACID within service; DB-per-service enforces service autonomy. |
| Cache | Redis | Cache-aside for hot reads; distributed idempotency-key store. |
| Containerization | Docker + Docker Compose | Full stack up on one command; Kubernetes explicitly deferred to future work. |
| Build tool | Maven | Broader enterprise fit; standard in target companies. |
| Testing | JUnit 5 + Mockito + Testcontainers | Real Postgres/Kafka in integration tests; Testcontainers is high-signal on resume. |
| API docs | OpenAPI 3 via springdoc-openapi | Auto-generated Swagger UI at `/swagger-ui.html`. |
| Version control | Git with feature branches, conventional commits, self-reviewed PRs | Clean git history is itself an artifact. |

Detailed justifications for the non-obvious choices (Kafka, DB-per-service, orchestrated Saga, KRaft) live in Section 4 (ADRs).

---

## 2. High-Level Design (HLD)

### 2.1 System Context

The system exposes REST APIs to two categories of external actor: **client applications** (whatever calls `POST /orders` — a web frontend, a mobile app, or another backend), and **operators** (humans who configure low-stock rules and acknowledge notifications). Internally it depends on three infrastructure components: Kafka for events, Postgres for durable state, and Redis for caching and idempotency. Nothing else crosses the boundary — no external payment gateway, no shipping API, no auth provider. This clean boundary is deliberate and matches the out-of-scope list in Section 1.3.

### 2.2 Service Responsibilities

**Inventory Service — the resource owner.** Owns all stock data. Every read or write of stock quantity goes through this service; no other service touches the stock table. Responsibilities:

- Expose reserve, release, confirm, and query APIs.
- Enforce atomicity of reservations under concurrent access using Postgres row-level locking (`SELECT ... FOR UPDATE`).
- Publish `StockReserved`, `StockReleased`, `StockConfirmed`, and `StockLow` domain events via the outbox pattern.
- Maintain Redis cache for hot stock lookups (cache-aside with TTL invalidation on write).

Does not know about orders — it only knows about reservations and their owning `orderId` as an opaque correlation key.

**Fulfillment Service — the coordinator.** Owns all order data and hosts the Saga orchestrator. Responsibilities:

- Accept order requests and persist orders with initial `PENDING` state.
- Drive the Saga through its state transitions by sending commands to Inventory and reacting to Inventory's events.
- Handle cancellation requests, including triggering compensation.
- Expose order query and list APIs.

The Saga orchestrator is an internal component of this service — same JVM, same deployment, same Postgres, dedicated `order_saga_state` table.

**Notification Service — the observer.** Purely event-driven. Owns notifications and low-stock rules. Responsibilities:

- Consume `StockLow` events, checking against configured per-SKU thresholds.
- Consume Saga terminal events (`OrderConfirmed`, `OrderFailed`, `OrderCancelled`) for audit-style notifications.
- Persist notifications and expose list/acknowledge/rules APIs.

Has no role in the Saga — it cannot fail the Saga, and the Saga does not wait for it. This isolation is deliberate: Notification can be down without breaking order processing.

**Mental model:** Inventory owns a thing, Fulfillment coordinates a workflow, Notification watches the world go by. Three genuinely different service shapes, which is what makes the split defensible in interviews.

### 2.3 Architecture Diagram

*(Rendered inline in design conversation as a Mermaid/SVG diagram — recreate in the repo README using Mermaid. Textual description follows.)*

The system has four horizontal tiers:

1. **External actors** (top): Client applications call Fulfillment's order APIs; Operators call Notification and Inventory admin APIs.
2. **Services** (three side-by-side): Fulfillment (left, coordinator), Inventory (center, resource owner), Notification (right, observer).
3. **Kafka bus** (spanning full width): Three topics — `orders.events`, `inventory.events`, `notifications.events`.
4. **Data tier** (bottom): One private Postgres per service. Redis sits alongside Inventory only.

Solid arrows = HTTP/REST. Dashed lines = JDBC/Redis connections (private data ownership). Kafka spans the full width to communicate that it is the *nervous system* of the design, not just another component.

### 2.4 Kafka Topic Design

Three topics, one per bounded context. Each service publishes to *its own* topic and subscribes to others.

| Topic | Publisher | Events | Consumers |
|---|---|---|---|
| `orders.events` | Fulfillment | `OrderPlaced`, `OrderConfirmed`, `OrderFailed`, `OrderCancelled` | Notification (audit) |
| `inventory.events` | Inventory | `StockReserved`, `StockReservationFailed`, `StockReleased`, `StockConfirmed`, `StockLow` | Fulfillment (Saga), Notification (`StockLow`) |
| `notifications.events` | Notification | `NotificationCreated`, `NotificationAcknowledged` | *(none in v1; reserved for future consumers)* |

**Partitioning strategy:**

- `orders.events` partitioned by `orderId` — all events for one order land on the same partition, preserving order for consumers.
- `inventory.events` partitioned by `sku` — all events for one SKU are ordered, critical for correct stock accounting.
- `notifications.events` partitioned by `sku` for consistency with `inventory.events`.

**Retention:** 7 days for all topics in v1. Long enough for replay during debugging; short enough that disk usage is trivial on a laptop.

### 2.5 Saga State Machine

The state machine lives in Fulfillment's `order_saga_state` table and is driven by the orchestrator. Five states, three of them terminal.

```
                 (order placed)
                       │
                       ▼
                  [ PENDING ]
                 /          \
   StockReserved              StockReservationFailed
                 \          /
                  ▼        ▼
             [ RESERVED ]  [ FAILED* ]
                  │
              confirm()
                  │
                  ▼
             [ CONFIRMED ]
                  │
             cancel() → release
                  │
                  ▼
             [ CANCELLED* ]

* = terminal state
```

**State semantics:**

- **`PENDING`** — order created, reservation in flight. No stock committed yet.
- **`RESERVED`** — stock reserved (available decremented, reservation row written) but not yet confirmed.
- **`CONFIRMED`** — terminal success. Stock deduction is final; reservation converted to committed.
- **`FAILED`** — terminal failure. No stock was ever touched, so no compensation is needed. This distinction matters: `PENDING → FAILED` is *not* a compensation.
- **`CANCELLED`** — terminal compensation. Stock was deducted, then released back. `CONFIRMED → CANCELLED` *is* a compensation and triggers `POST /inventory/release`.

**No transitions out of `FAILED` or `CANCELLED`.** Terminal is terminal — API returns 409 Conflict on any subsequent state-change attempt.

### 2.6 Event Flows

**Happy path.**

1. Client `POST /orders`. Fulfillment writes order to DB in `PENDING`, writes `OrderPlaced` to its outbox, returns 202.
2. Outbox poller publishes `OrderPlaced` to `orders.events`.
3. Inventory consumes `OrderPlaced` (as a `ReserveStock` command), locks the stock row (`SELECT ... FOR UPDATE`), decrements available, writes reservation row, writes `StockReserved` to its outbox.
4. Inventory's outbox poller publishes `StockReserved` to `inventory.events`.
5. Fulfillment's Saga listener consumes `StockReserved`, transitions order to `RESERVED`, then issues a `ConfirmReservation` command.
6. Inventory confirms the reservation, publishes `StockConfirmed`.
7. Fulfillment consumes `StockConfirmed`, transitions order to `CONFIRMED`, publishes `OrderConfirmed`.
8. Notification consumes `OrderConfirmed`, creates audit notification.

**Failure path.**

1. Client `POST /orders`. Fulfillment writes `PENDING`, publishes `OrderPlaced`.
2. Inventory attempts reservation; stock is insufficient.
3. Inventory writes `StockReservationFailed` with a failure reason to its outbox and publishes.
4. Fulfillment consumes, transitions order to `FAILED`, publishes `OrderFailed`.
5. Notification consumes `OrderFailed` for audit.

No stock was touched — no compensation runs.

**Compensation path (cancel).**

1. Order is in `CONFIRMED` state.
2. Operator sends `POST /orders/{id}/cancel`.
3. Fulfillment validates the state transition is legal, publishes `CancelOrder` command targeting the original reservation.
4. Inventory increments available stock, writes reservation row to `RELEASED`, publishes `StockReleased`.
5. Fulfillment consumes `StockReleased`, transitions order to `CANCELLED`, publishes `OrderCancelled`.
6. Notification consumes `OrderCancelled` for audit.

### 2.7 Command-vs-Event Design Choice

Saga steps between Fulfillment and Inventory could be implemented as **synchronous REST calls** or as **asynchronous commands over Kafka**. This project uses commands over Kafka throughout, with two consequences worth flagging:

- **Benefit:** one transport layer (Kafka) means one failure mode to reason about. The system is fully event-driven, which is a cleaner interview story.
- **Cost:** debugging requires inspecting Kafka topics (`docker exec` + `kafka-console-consumer`) rather than reproducing with `curl`. Mitigated by structured logging with correlation IDs and by exposing `GET /orders/{id}` which shows the current Saga state at any time.

Some teams separate commands and domain events into distinct topics (`orders.commands` vs `orders.events`); v1 puts both on the same topic distinguished by an event `type` field. This is documented as future work.

Full rationale in ADR-005.

---

## 3. Low-Level Design (LLD)

### 3.1 Data Models

Each service owns its schema. No cross-service foreign keys. Timestamps are `TIMESTAMPTZ` (UTC). Primary keys are UUIDs.

#### 3.1.1 Inventory Service

**`items`** — the product catalog. One row per SKU. Owned by Inventory (no separate Catalog service in v1).

| Column | Type | Constraints |
|---|---|---|
| `id` | UUID | PK — internal reference |
| `sku` | VARCHAR(64) | NOT NULL, UNIQUE — business identifier |
| `name` | VARCHAR(255) | NOT NULL |
| `description` | TEXT | NULL |
| `category` | VARCHAR(64) | NULL |
| `unit_of_measure` | VARCHAR(16) | NOT NULL, DEFAULT `'EACH'` |
| `active` | BOOLEAN | NOT NULL, DEFAULT true |
| `created_at`, `updated_at` | TIMESTAMPTZ | NOT NULL, DEFAULT `now()` |

Populated in v1 via migration seed data. No item-CRUD APIs — admin tooling deferred to future work.

**`stock`** — the ledger. One row per (item, warehouse).

| Column | Type | Constraints |
|---|---|---|
| `id` | UUID | PK |
| `item_id` | UUID | NOT NULL, FK → `items(id)` |
| `warehouse_id` | VARCHAR(32) | NOT NULL, DEFAULT `'W-DEFAULT'` |
| `quantity_on_hand` | INTEGER | NOT NULL, CHECK ≥ 0 |
| `quantity_reserved` | INTEGER | NOT NULL, DEFAULT 0, CHECK ≥ 0 |
| `publish_threshold` | INTEGER | NULL — Inventory's decision about when to emit `StockLow` |
| `version` | BIGINT | NOT NULL, DEFAULT 0 |
| `created_at`, `updated_at` | TIMESTAMPTZ | NOT NULL, DEFAULT `now()` |

Unique constraint: `(item_id, warehouse_id)`. Index on `item_id`. `quantity_available` computed as `quantity_on_hand - quantity_reserved` in application, not stored.

**Note on `publish_threshold`:** This is Inventory's own operational policy about when a stock level warrants emitting a `StockLow` event — an *event-traffic* concern. Distinct from Notification's `alert_threshold` (see Section 3.1.3), which is the operator's alerting policy — a *human-attention* concern. Same numeric value in many cases, but conceptually independent policies at different layers.

**`reservations`** — one row per reservation attempt.

| Column | Type | Constraints |
|---|---|---|
| `id` | UUID | PK |
| `order_id` | UUID | NOT NULL |
| `item_id` | UUID | NOT NULL, FK → `items(id)` |
| `warehouse_id` | VARCHAR(32) | NOT NULL |
| `quantity` | INTEGER | NOT NULL, CHECK > 0 |
| `status` | VARCHAR(16) | NOT NULL — `RESERVED`, `CONFIRMED`, `RELEASED` |
| `idempotency_key` | VARCHAR(64) | NOT NULL, UNIQUE |
| `created_at`, `updated_at` | TIMESTAMPTZ | NOT NULL, DEFAULT `now()` |

Indexes: `order_id`, `status`, unique on `idempotency_key`.

**External vs internal identifiers.** All Inventory APIs accept `sku` (business identifier) in URLs and request bodies. Server resolves `sku → item_id` internally. Kafka event payloads carry both — `itemId` for machine correlation, `sku` for human-readable logs and analytics.

**`outbox`, `processed_events`** — see Sections 3.4, 3.5.

#### 3.1.2 Fulfillment Service

**`orders`** — one row per order.

| Column | Type | Constraints |
|---|---|---|
| `id` | UUID | PK |
| `customer_id` | VARCHAR(64) | NOT NULL |
| `sku` | VARCHAR(64) | NOT NULL |
| `quantity` | INTEGER | NOT NULL, CHECK > 0 |
| `status` | VARCHAR(16) | NOT NULL — Saga state |
| `failure_reason` | VARCHAR(255) | NULL |
| `reservation_id` | UUID | NULL |
| `version` | BIGINT | NOT NULL, DEFAULT 0 |
| `created_at`, `updated_at` | TIMESTAMPTZ | NOT NULL, DEFAULT `now()` |

Indexes: `customer_id`, `status`, `created_at DESC`, composite `(status, created_at)`.

**`order_saga_state`** — one row per order Saga.

| Column | Type | Constraints |
|---|---|---|
| `order_id` | UUID | PK, FK → orders(id) |
| `current_state` | VARCHAR(16) | NOT NULL |
| `last_event_type` | VARCHAR(64) | NULL |
| `last_event_at` | TIMESTAMPTZ | NULL |
| `retry_count` | INTEGER | NOT NULL, DEFAULT 0 |
| `updated_at` | TIMESTAMPTZ | NOT NULL, DEFAULT `now()` |

Separation from `orders.status` is deliberate: `orders` is the public domain entity, `order_saga_state` is the orchestrator's internal state machine. They're updated in the same transaction, but serve different audiences.

**`outbox`, `processed_events`** — see Sections 3.4, 3.5.

#### 3.1.3 Notification Service

**`notifications`** — one row per notification.

| Column | Type | Constraints |
|---|---|---|
| `id` | UUID | PK |
| `type` | VARCHAR(32) | NOT NULL — `STOCK_LOW`, `ORDER_CONFIRMED`, `ORDER_FAILED`, `ORDER_CANCELLED` |
| `severity` | VARCHAR(16) | NOT NULL — `INFO`, `WARNING`, `CRITICAL` |
| `subject_type` | VARCHAR(16) | NOT NULL — `SKU`, `ORDER` |
| `subject_id` | VARCHAR(64) | NOT NULL |
| `payload` | JSONB | NOT NULL |
| `acknowledged` | BOOLEAN | NOT NULL, DEFAULT false |
| `acknowledged_at` | TIMESTAMPTZ | NULL |
| `acknowledged_by` | VARCHAR(64) | NULL |
| `created_at` | TIMESTAMPTZ | NOT NULL, DEFAULT `now()` |

Indexes: composite `(acknowledged, created_at DESC)`, `type`, `(subject_type, subject_id)`.

**`notification_rules`** — one row per configured SKU rule.

| Column | Type | Constraints |
|---|---|---|
| `id` | UUID | PK |
| `sku` | VARCHAR(64) | NOT NULL, UNIQUE |
| `alert_threshold` | INTEGER | NOT NULL, CHECK > 0 — operator's alerting policy |
| `severity` | VARCHAR(16) | NOT NULL, DEFAULT `'WARNING'` |
| `enabled` | BOOLEAN | NOT NULL, DEFAULT true |
| `created_at`, `updated_at` | TIMESTAMPTZ | NOT NULL, DEFAULT `now()` |

**Note on `alert_threshold`:** The operator's decision about when a received `StockLow` event should produce a human-visible notification. Distinct from Inventory's `stock.publish_threshold` — see Section 3.1.1 for the semantic split. Rules seeded via migration in v1 (no rule-onboarding UI); single upsert via `POST /notifications/rules` supports runtime changes.

**`processed_events`** — see Section 3.5. No outbox in Notification: `NotificationCreated` and `NotificationAcknowledged` are published best-effort (fire-and-forget after DB commit). Legitimate for v1 because no consumer subscribes yet, so a lost event has no user-visible impact. Promoted to outbox-backed publishing when the first real consumer (email dispatcher, WebSocket dashboard) is added.

### 3.2 API Contracts

Error response format follows RFC 7807 Problem Details:

```json
{
  "type": "https://api.warehouse/errors/insufficient-stock",
  "title": "Insufficient stock",
  "status": 409,
  "detail": "SKU 'ABC-123' has 5 available, 10 requested",
  "instance": "/inventory/reserve",
  "correlationId": "b3e2c4d5-..."
}
```

All state-changing endpoints accept `Idempotency-Key: <uuid>` in headers. Handling in Section 3.5.

#### 3.2.1 Inventory APIs

**`POST /inventory/reserve`** — reserve stock. Request: `{ orderId, sku, quantity, warehouseId? }`. Success 201 with reservation object. Errors: 400 (malformed/unknown SKU), 409 (insufficient stock / idempotency conflict). Same idempotency key + same payload replays as 200 (not 201) with original response.

**`POST /inventory/release`** — release a reservation. Request: `{ reservationId, reason }`. Success 200. Errors: 404, 409 (wrong state, or idempotent replay returns 200 with existing state).

**`POST /inventory/confirm`** — commit reservation to real deduction. Request: `{ reservationId }`. Success 200. Errors: 404, 409.

**`GET /inventory/{sku}`** — query stock (warehouse defaults to `W-DEFAULT` in v1). Success 200 with `{ sku, itemId, quantityOnHand, quantityReserved, quantityAvailable, publishThreshold, asOf }`. Errors: 404. Served from Redis (5s TTL) with Postgres fallback.

**Item resolution across all Inventory APIs.** Requests accept `sku` externally (URLs and bodies). Server performs `sku → item_id` lookup once per request; unknown SKU returns 404. Response bodies include both `sku` and `itemId` where relevant, so clients can pass `itemId` to other services if needed.

#### 3.2.2 Fulfillment APIs

**`POST /orders`** — place order (kicks off Saga). Request: `{ customerId, sku, quantity }`. Success **202 Accepted** with `{ orderId, status: "PENDING", createdAt, _links }`. Errors: 400, 409 (idempotency).

**`GET /orders/{id}`** — order status. Success 200 with full order + `sagaState` sub-object. Errors: 404.

**`POST /orders/{id}/cancel`** — cancel order. Request: `{ reason }`. Success **202 Accepted** (compensation runs async). Errors: 404, 409 (already terminal, or still `PENDING`).

**`GET /orders`** — list with filters. Query: `status`, `customerId`, `from`, `to`, `page`, `size`. Success 200 paginated. List responses omit `sagaState` for performance.

#### 3.2.3 Notification APIs

**`GET /notifications`** — list. Query: `acknowledged`, `type`, `page`, `size`. Success 200 paginated.

**`POST /notifications/{id}/acknowledge`** — mark acknowledged. Request: `{ acknowledgedBy }`. Success 200. Idempotent — already-acknowledged returns 200 with existing state.

**`POST /notifications/rules`** — configure per-SKU rule (upsert). Request: `{ sku, lowStockThreshold, severity, enabled }`. Success 201 (create) or 200 (update). Errors: 400.

**`GET /notifications/rules?enabled=...`** — list rules. No pagination (bounded count).

### 3.3 Event Schemas

Common envelope for all events:

```json
{
  "eventId": "uuid",
  "eventType": "StockReserved",
  "eventVersion": 1,
  "occurredAt": "2026-07-25T10:32:15.123Z",
  "correlationId": "uuid",
  "producer": "inventory-service",
  "payload": { ... }
}
```

**Kafka message key** is the partition key (`orderId` or `sku`), not part of the envelope. **Headers:** `content-type: application/json`, `event-type: <eventType>`, `schema-version: <version>`.

#### 3.3.1 `orders.events`

- **`OrderPlaced`** — `{ orderId, customerId, sku, quantity, warehouseId }`. Also serves as implicit `ReserveStock` command for Inventory.
- **`OrderConfirmed`** — `{ orderId, sku, quantity, reservationId, confirmedAt }`.
- **`OrderFailed`** — `{ orderId, sku, quantity, failureReason, detail }`.
- **`OrderCancelled`** — `{ orderId, sku, quantity, reservationId, cancellationReason }`.

#### 3.3.2 `inventory.events`

- **`StockReserved`** — `{ reservationId, orderId, sku, warehouseId, quantity, quantityAvailableAfter }`. Available-after included so Notification can evaluate low-stock rules without an extra query.
- **`StockReservationFailed`** — `{ orderId, sku, warehouseId, quantityRequested, quantityAvailable, reason }`.
- **`StockConfirmed`** — `{ reservationId, orderId, sku, warehouseId, quantity, quantityOnHandAfter }`.
- **`StockReleased`** — `{ reservationId, orderId, sku, warehouseId, quantity, quantityAvailableAfter, reason }`.
- **`StockLow`** — `{ itemId, sku, warehouseId, quantityAvailable, publishThreshold }`. Published by Inventory based on `stock.publish_threshold`; Notification separately evaluates its own `alert_threshold` per SKU rule.

**Item identifier convention across events.** All event payloads carry both `itemId` (UUID, machine correlation) and `sku` (string, human-readable). Consumers may use whichever is more convenient; the pair is always consistent.

#### 3.3.3 `notifications.events`

**`NotificationCreated`**, **`NotificationAcknowledged`** — payloads mirror the notification row. Best-effort publish (no outbox in v1).

### 3.4 Outbox Pattern

Solves the dual-write problem: without it, writing to Postgres and publishing to Kafka can't be atomic. The pattern: in the same DB transaction as the state change, insert into an `outbox` table. A background poller reads unpublished rows and publishes to Kafka.

**`outbox`** (identical in Fulfillment and Inventory):

| Column | Type | Constraints |
|---|---|---|
| `id` | UUID | PK — matches envelope `eventId` |
| `aggregate_type` | VARCHAR(32) | NOT NULL |
| `aggregate_id` | VARCHAR(64) | NOT NULL |
| `topic` | VARCHAR(64) | NOT NULL |
| `partition_key` | VARCHAR(64) | NOT NULL |
| `event_type` | VARCHAR(64) | NOT NULL |
| `payload` | JSONB | NOT NULL — full envelope |
| `created_at` | TIMESTAMPTZ | NOT NULL, DEFAULT `now()` |
| `published_at` | TIMESTAMPTZ | NULL |

Partial index on `(published_at, created_at)` where `published_at IS NULL`.

**Poller behavior:**

- Runs every 500ms.
- Batches up to 100 unpublished rows, ordered by `created_at ASC`.
- Uses `SELECT ... FOR UPDATE SKIP LOCKED` for safe multi-poller execution.
- Publishes to Kafka with `acks=all`. On success, sets `published_at`.
- On failure, leaves the row for retry.
- Separate scheduled job deletes rows with `published_at < now() - 7 days`.

Debezium/CDC is a legitimate alternative — documented as future work.

### 3.5 Idempotency

Two distinct problems, two distinct solutions.

#### 3.5.1 API Idempotency (Redis)

Client retries a `POST /inventory/reserve` after timeout. Server must not double-process.

**Redis key:**

```
Key:    idempotency:{service}:{endpoint}:{idempotency-key}
Value:  { "requestHash": "sha256-of-body", "response": {...}, "statusCode": 201 }
TTL:    24 hours
```

**Handler flow:**

1. Extract `Idempotency-Key` header. Missing → 400.
2. Compute `SHA-256(requestBody)`.
3. `GET` Redis key.
4. Present + hash matches → return stored response with 200 (replay).
5. Present + hash differs → 409.
6. Absent → process request, then `SET` result with TTL.

Race condition: two concurrent requests with the same key. Handle with `SET ... NX` for an "in-flight" marker; if NX fails, return 409 with "concurrent request in flight."

#### 3.5.2 Event-Consumer Idempotency (Postgres)

Kafka is at-least-once. Consumers may see the same event twice.

**`processed_events`** (in every consuming service):

| Column | Type | Constraints |
|---|---|---|
| `event_id` | UUID | PK — from envelope |
| `event_type` | VARCHAR(64) | NOT NULL |
| `processed_at` | TIMESTAMPTZ | NOT NULL, DEFAULT `now()` |

**Consumer flow:**

1. Extract `eventId`.
2. In a single transaction, `INSERT INTO processed_events`.
3. If insert succeeds — first delivery. Proceed with business logic in the same transaction.
4. If PK conflict — redelivery. Skip logic, commit transaction, ack the Kafka message.
5. Ack the Kafka message only after commit.

**Why Postgres, not Redis?** The dedupe check must be transactional with the business logic. Redis can't participate in the DB transaction; a Redis dedupe would leave a small race window.

Cleanup: scheduled job deletes rows older than 30 days.

### 3.6 Redis Cache Design

Two roles, two namespaces:

```
idempotency:{service}:{endpoint}:{key}    # Section 3.5.1
stock:{warehouseId}:{sku}                 # cache-aside for GET /inventory/{sku}
```

**Stock cache-aside:**

- On `GET`: read cache → return if present. On miss, query Postgres, `SET` with 5s TTL, return.
- On successful reservation/release/confirm: `DEL` the key in the same code path as the DB commit (best-effort — cache is a hint).

5s TTL is a safe upper bound for stale display. Actual reservation always re-locks the row in Postgres, so the cache is never the source of truth for correctness.

Orders are deliberately not cached.

### 3.7 Concurrency Control

The correctness-critical scenario: two orders arrive simultaneously for the last unit of stock. Exactly one must succeed.

**In `POST /inventory/reserve`:**

```sql
BEGIN TRANSACTION;

-- Step 1: resolve SKU to item_id (from items table)
SELECT id FROM items WHERE sku = ? AND active = true;
-- If not found: ROLLBACK; return 404.

-- Step 2: lock the stock row
SELECT quantity_on_hand, quantity_reserved, version
  FROM stock
  WHERE item_id = ? AND warehouse_id = ?
  FOR UPDATE;                              -- row-level lock

-- Application checks: available >= requested?
-- If not: ROLLBACK; return 409.

INSERT INTO reservations (...) VALUES (...);
UPDATE stock SET quantity_reserved = quantity_reserved + ?, version = version + 1
  WHERE item_id = ? AND warehouse_id = ?;
INSERT INTO outbox (...) VALUES (...);     -- StockReserved event
COMMIT;
```

`SELECT ... FOR UPDATE` acquires a row-level exclusive lock. The second concurrent request blocks until the first commits, then re-reads fresh state and correctly sees insufficient stock. No distributed locks needed at this scale.

Same pattern for release and confirm, locking the `reservations` row instead.

The `version` column enables optimistic locking as a defense-in-depth against code paths that ever forget `FOR UPDATE`.

---

## 4. Architecture Decision Records (ADRs)

Individual ADRs live as separate files in `docs/adr/`, one per decision. This mirrors the industry-standard layout used by `adr-tools` and most Spring/Java corporate repositories.

Each ADR follows Michael Nygard's four-section format: **Status**, **Context**, **Decision**, **Consequences**. Some include additional **Notes** or **Related** sections where relevant.

| # | Decision | File |
|---|---|---|
| 001 | Kafka over RabbitMQ for the event bus | `docs/adr/0001-kafka-over-rabbitmq.md` |
| 002 | Orchestrated Saga (embedded in Fulfillment) over Choreography | `docs/adr/0002-orchestrated-saga.md` |
| 003 | Database per service | `docs/adr/0003-database-per-service.md` |
| 004 | KRaft mode over Zookeeper | `docs/adr/0004-kraft-mode.md` |
| 005 | Command-style events over Kafka vs synchronous REST between services | `docs/adr/0005-events-over-rest.md` |
| 006 | `items` as a first-class entity in Inventory | `docs/adr/0006-items-as-first-class-entity.md` |
| 007 | Single-warehouse in v1 with `warehouse_id` as extension seam | `docs/adr/0007-single-warehouse-v1.md` |
| 008 | No API gateway in v1 | `docs/adr/0008-no-api-gateway-v1.md` |
| 009 | Two-layer threshold model (`publish_threshold` and `alert_threshold`) | `docs/adr/0009-two-layer-threshold.md` |

**Reading order for a new engineer joining the project:** 003 → 001 → 002 → 005 → 006 → 009 → 007 → 008 → 004. This order presents the foundational architectural choices first (data ownership, transport, Saga style), then the schema decisions, then the scoping decisions, then the operational-detail choices.

**Superseded ADRs** are kept in place with `Status: Superseded by ADR-NNN`. No ADRs are currently superseded.

---

## 5. Design Review Notes

This section captures the outcomes of design review pass 1, conducted after the initial LLD. Decisions are recorded with brief rationale so the reasoning is preserved alongside the design.

### 5.1 Inventory Service — Locked Decisions

**Three business tables, not two.** Added `items` as a first-class entity separating the product catalog (WHAT things exist) from the stock ledger (HOW MUCH exists where). Original design used bare SKU strings — a domain-modeling gap. Real WMS platforms make this split for the same reason: catalog changes are rare and human-driven; ledger changes are frequent and machine-driven.

**Items managed via seed data, no item-CRUD APIs.** Deliberate scoping. Item onboarding is a rare, admin-only event in real warehouses. Skipping item CRUD keeps Inventory focused on its Saga participation. Extension path: add `POST /items` and `GET /items/{sku}` when moving beyond seed data.

**Single warehouse in v1 with `warehouse_id` column retained.** Not adding a `warehouses` table. Single-warehouse committed in the BRD; the column is the extension seam. Adding the table now would carry weight of an entity with exactly one row and zero code-path differences.

**SKU is the external identifier; `item_id` is internal.** APIs accept SKU in URLs and bodies. Server resolves once per request. Events carry both. External identifiers should be business-meaningful; internal identifiers should be stable.

**`stock.publish_threshold`** (renamed from `low_stock_threshold`). Semantic clarity: this is Inventory's decision about when to emit a `StockLow` event — an event-traffic concern, distinct from Notification's alerting policy.

### 5.2 Notification Service — Locked Decisions

**Notifications persisted, not pure event forwarding.** Operators need to list and acknowledge — requires storage. Publishing `NotificationCreated` and `NotificationAcknowledged` best-effort even with no consumers, so the event stream is complete for future channels.

**Two-layer threshold model.** `notification_rules.alert_threshold` (renamed from `low_stock_threshold`) is the operator's alerting policy. Independent from Inventory's `stock.publish_threshold`. Same numeric value in most cases, but conceptually different concerns — not duplicated state.

**No notification channels in v1.** API is the only channel. Email, SMS, WebSocket dashboard are future subscribers to `NotificationCreated`, added additively without changing Notification's core.

**Rules stay inside Notification** — not a separate service. Splitting them out would be textbook microservice fragmentation for zero benefit at this scale.

**No grouping / digest logic.** 100 events produce 100 notifications. Grouping is a UX/product problem, not a distributed-systems problem — no resume-signal, out of scope.

**No TTL / auto-expiry in v1.** Table growth is not a concern at demo scale. Documented as future work: "add scheduled job to archive acknowledged notifications older than 90 days."

**No bulk operations on rules API.** Single upsert only via `POST /notifications/rules`. Bulk adds partial-failure complexity for no distributed-systems signal. Seed rules for demo via migration.

**`acknowledgedBy` is free-form string in v1.** No authentication, so operator identifier is client-supplied. Future work: derive from authenticated principal when auth is added.

### 5.3 System-Wide — Locked Decisions

**No API gateway in v1.** With no auth, no rate limiting, and three static services on a Docker network, a gateway would be scaffolding without a purpose. Documented as future work: "add Spring Cloud Gateway as auth boundary and route target when authentication is introduced." Post-v1 addition is a plausible but not committed extension.

**Fulfillment Service — deferred from review pass 1.** All Fulfillment design decisions from Section 3.1.2 remain locked from the initial LLD. Explicit scope items to revisit in a future review: Saga orchestrator embedding, `orders` + `order_saga_state` two-table split, single-SKU orders vs multi-item orders, stuck-Saga timeout mechanism, cancellation state constraints. Deferring these does not block v1 build — the initial design is buildable as-is.

### 5.4 Follow-Up Work Recorded

Documented in the future-work section of the README (to be written):

- Item CRUD APIs (`POST /items`, `GET /items/{sku}`, `GET /items`) when admin tooling is needed.
- `warehouses` table + FK from `stock` and `reservations` when multi-warehouse is added.
- Notification channels — email dispatcher, WebSocket dashboard — as consumers of `notifications.events`.
- Outbox pattern in Notification service when the first real channel is added.
- Scheduled cleanup job for acknowledged notifications > 90 days old.
- API gateway (Spring Cloud Gateway) when authentication is introduced.
- Fulfillment design deep-dive in review pass 2.
