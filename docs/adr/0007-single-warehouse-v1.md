# ADR-007: Single-Warehouse in v1 with `warehouse_id` as Extension Seam

**Status:** Accepted — 2026-07-25 (design review pass 1)

## Context

Real warehouse management systems are multi-warehouse — stock is tracked per (item, location) pair, and orders route to the warehouse best positioned to fulfill them (proximity, availability, cost).

For a demo-scale project, multi-warehouse routing logic adds substantial scope: warehouse master data, per-warehouse stock partitioning, routing rules, warehouse selection algorithms, cross-warehouse transfers. None of these are relevant to the project's core distributed-systems story (Saga pattern with compensating transactions).

The question is whether to model warehouse as a first-class entity (a `warehouses` table with FKs from `stock` and `reservations`), or to defer it while keeping the schema extensible.

## Decision

**Single-warehouse in v1.** All stock lives in a single logical warehouse identified by the constant `W-DEFAULT`. The `warehouse_id` column exists on `stock` and `reservations` as a `VARCHAR(32) DEFAULT 'W-DEFAULT'`, serving as the extension seam for future multi-warehouse support.

**No `warehouses` table in v1.**

## Consequences

**Gained:**

- **Scope discipline.** The BRD explicitly commits to single-warehouse in v1; the implementation matches. Consistency between requirements and design is itself a professional signal.
- **Zero-cost extension path.** Adding a `warehouses` table later is a pure additive migration: create the table, add FK from the existing `warehouse_id` columns, backfill `W-DEFAULT` as the first row. No changes to event schemas, API contracts, or business logic on the existing single-warehouse path.
- **No dead code.** A `warehouses` table with exactly one row that changes zero code paths would be scaffolding without purpose. Skipping it avoids the over-engineering.

**Given up:**

- **No warehouse metadata storage in v1.** Warehouse name, address, capacity, operating hours have no home. Accepted because none of these are needed for the demo.
- **`W-DEFAULT` is a magic string.** Mitigated by placing it in `application.yml` (`warehouse.default-id=W-DEFAULT`) as a config value, not a hardcoded literal in code. This is a small but real signal of professional practice: constants for domain values live in config, not scattered in source.
- **No referential integrity on `warehouse_id`.** A typo (`W-DEFULT`) would silently create orphan data. Mitigated by application-level validation against the config value in v1.

## Notes

The contrast with ADR-006 (items as first-class entity) is worth naming:

- Items have real domain content in v1 (name, description, category, unit of measure). Adding the items table was a **modeling correction**.
- Warehouses have no distinguishing content in v1 — the domain doesn't distinguish `W-DEFAULT` from any other warehouse because there aren't any others. Adding a warehouses table now would be a **modeling extension for a feature out of scope**.

Different reasoning, different answers. Both are defensible in interviews as long as the reasoning is explicit.

## Future Work Trigger

Add the `warehouses` table when any of the following becomes true:

- Multiple physical warehouse locations enter the demo scope.
- Warehouse metadata (name, address, capacity) needs to be stored.
- Cross-warehouse routing logic is added to Fulfillment.
