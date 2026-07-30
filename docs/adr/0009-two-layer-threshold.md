# ADR-009: Two-Layer Threshold Model (`publish_threshold` and `alert_threshold`)

**Status:** Accepted — 2026-07-25 (design review pass 1)

## Context

The initial LLD had a field named `low_stock_threshold` on both `stock` (Inventory service) and `notification_rules` (Notification service). This appeared to be duplicated state, raising the question of whether one service should be the source of truth and propagate changes to the other via events.

On closer examination, the two fields represent **different concerns** at different layers:

- **Inventory's field** decides when a stock level is worth publishing a `StockLow` event onto the bus. This is Inventory's own operational policy about event traffic.
- **Notification's field** decides when a received `StockLow` event should produce a human-visible notification. This is the operator's alerting policy about human attention.

They are conceptually independent — same numeric value in most cases, but they answer different questions.

## Decision

Keep both fields, but **rename them to reflect their distinct semantics**:

- `stock.low_stock_threshold` → `stock.publish_threshold` (in Inventory)
- `notification_rules.low_stock_threshold` → `notification_rules.alert_threshold` (in Notification)

Do **not** synchronize the two fields via events. They are independent policies at independent layers.

## Consequences

**Gained:**

- **Semantic clarity at the schema level.** Different names for different concepts prevents future developers from assuming the two fields must be kept in sync.
- **Preserved service autonomy.** Neither service has to know about the other's policy. Inventory decides when to emit; Notification decides when to alert. No coordination event needed.
- **Flexibility for legitimate divergence.** Inventory can publish verbosely (low `publish_threshold`) while Notification alerts selectively (higher `alert_threshold` per SKU). Or the reverse. Both are legitimate business scenarios.
- **Simpler v1.** No `LowStockRuleChanged` event, no cross-service sync logic, no eventual-consistency window to reason about. Roughly 2-3 hours of build time saved compared to a single-source-of-truth model.

**Given up:**

- **No single "the threshold for this SKU" concept.** An operator changing "the low-stock threshold" for SKU ABC-123 conceptually has to change it in two places — first in `notification_rules.alert_threshold` (their own alerting policy), and separately request Inventory to update its `publish_threshold`. In v1 with no admin UI, this is not a real problem. In a system with a real admin UI, the UI could hide the split by presenting a single control that updates both.
- **Requires explanation.** The two-field design is unusual enough that it invites the question "why aren't these one field?" — an interviewer probe. Prepared answer: "They're independent policies at different layers. One is about event traffic; one is about human attention. Renaming enforces the semantic distinction at the schema level."

## Notes

The relevant interview-signal question is: **can you defend the design when questioned?** Both "one rule, replicated for autonomy" (event-synchronized threshold) and "two rules at different layers" (independent policies) are legitimate architectures. The weak answer is "both places because decoupling"; the strong answer is naming *what specifically* each layer is deciding.

## Related

If the design were ever to move to "single business rule, propagated across services," the rollback path is:

1. Nominate one service as the source of truth (most likely Notification, since it's operator-facing).
2. Add a `LowStockRuleChanged` event on `notifications.events`.
3. Inventory subscribes and updates its `publish_threshold` from the event.
4. Rename Inventory's field back to `low_stock_threshold` (or `synced_threshold`) to signal that it's a projection, not a source.

This is a legitimate v2 architecture — deferred to a future ADR if the need arises.
