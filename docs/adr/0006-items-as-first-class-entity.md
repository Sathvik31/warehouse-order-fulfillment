# ADR-006: `items` as a First-Class Entity in Inventory

**Status:** Accepted — 2026-07-25 (design review pass 1)

## Context

The initial LLD modeled stock as a `stock` table keyed on `sku VARCHAR(64)` — a bare string identifier with no owning entity. This left several concerns unaddressed:

- No place to store item metadata (name, description, category, unit of measure).
- No referential integrity — a typo'd SKU in a request would silently create orphan data.
- Design signaled "SKU is data" rather than "SKU is an entity identifier."

Real warehouse management systems (SAP, NetSuite, Oracle Retail, Shopify) uniformly separate the **product catalog** (item master data) from the **stock ledger** (quantities in locations). The catalog changes slowly and is human-driven; the ledger changes frequently and is machine-driven.

## Decision

Add an **`items` table** to Inventory as a first-class entity. Change `stock` and `reservations` to reference items via `item_id` FK (internal) while continuing to expose `sku` externally (in URLs, request bodies, and event payloads).

The items table is owned by the Inventory service. No separate Catalog service is created — item lifecycle is low-frequency and Inventory owns the "what is this thing" question in v1.

## Consequences

**Gained:**

- **Domain-modeling correctness.** Item is a first-class entity with a stable identity (`id`) and business identifier (`sku`). Metadata columns (`name`, `description`, `category`, `unit_of_measure`, `active`) have a natural home.
- **Referential integrity.** `stock.item_id` and `reservations.item_id` are FKs. Impossible to create stock for a non-existent item at the database level.
- **Separation of change rhythms.** The `items` table changes rarely (new products, renames, deactivation). The `stock` table changes on every reservation. Splitting them means the hot path doesn't touch slow-moving data.
- **Alignment with real WMS platforms.** Matches how Oracle NetSuite, SAP, and every other production inventory system models this domain. Interview signal: "I modeled the catalog and the ledger as separate aggregates" is a stronger sentence than "SKU is a column."
- **External API stability.** External identifiers stay as SKUs (business-meaningful, stable, human-readable). Internal identifiers are UUIDs (stable, opaque, decoupled from business changes to SKU strings).

**Given up:**

- **One extra table** and one extra join on every stock lookup. Trivial cost given the index on `items.sku` and the low cardinality of the items table.
- **Item lifecycle management** must exist somewhere. V1 handles this via migration seed data — no runtime CRUD APIs. Documented as future work: add `POST /items`, `GET /items/{sku}`, `GET /items` when admin tooling is needed.

## Notes

The initial design's use of bare `sku VARCHAR` was a modeling gap identified during design review pass 1, not a scoping decision. The correction is a schema improvement, not scope creep — the design is more correct with items than without.

The related question of "should there also be a `warehouses` table" was considered and deferred (see ADR-007). The reasoning differs: `items` is a modeling correction; `warehouses` would be a modeling extension for a feature that is out of v1 scope.
