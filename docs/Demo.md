# Demo Script

Copy-paste curl sequences demonstrating the system's core behaviors. Each
scenario is self-contained — reset state between scenarios if you want a
clean slate (reset commands provided at the bottom).

**Prerequisites:** all three services running (`mvn spring-boot:run` in each
service folder) and infrastructure up (`docker compose up -d`).

**Note on timing:** the system is fully asynchronous — after most commands,
wait 1-3 seconds before checking status, to allow the outbox pollers
(500ms interval) and Kafka round-trips to complete.

---

## Scenario 1 — Happy path: order → reserved → confirmed

```bash
# Place an order
curl -s -X POST http://localhost:8080/orders \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: demo-happy-$(date +%s)" \
  -d '{"customerId":"11111111-1111-1111-1111-111111111111","sku":"WIDGET-001","quantity":3}' \
  | tee /tmp/order.json

# Extract the order ID (requires jq; or copy manually from the output above)
ORDER_ID=$(cat /tmp/order.json | jq -r '.orderId')
echo "Order ID: $ORDER_ID"
```

**Expected:** HTTP 202, `status: "PENDING"`.

```bash
sleep 3

# Check final status
curl -s http://localhost:8080/orders/$ORDER_ID | jq
```

**Expected:** `status: "CONFIRMED"`, `sagaState: "CONFIRMED"`, `lastEventType: "StockConfirmed"`, `reservationId` populated.

**Watch it progress in real time** (optional, more impressive live):

```bash
for i in 1 2 3; do
  curl -s http://localhost:8080/orders/$ORDER_ID | jq -r '.status'
  sleep 1
done
```

**Expected output:** `PENDING`, then `RESERVED`, then `CONFIRMED` — the Saga advancing visibly across ~2-3 seconds.

---

## Scenario 2 — Compensation: cancel a confirmed order

Uses the order from Scenario 1. Assumes `$ORDER_ID` is still set (or substitute a real order ID).

```bash
# Check stock before cancellation
curl -s http://localhost:8081/inventory/WIDGET-001 | jq '.quantityOnHand, .quantityAvailable'
```

```bash
# Cancel the confirmed order
curl -s -X POST http://localhost:8080/orders/$ORDER_ID/cancel \
  -H "Content-Type: application/json" \
  -d '{"reason":"demo_cancellation"}' | jq
```

**Expected:** HTTP 202, `status: "CANCELLING"`.

```bash
sleep 3

curl -s http://localhost:8080/orders/$ORDER_ID | jq '.status, .sagaState'
```

**Expected:** both `"CANCELLED"`.

```bash
# Verify stock physically returned
curl -s http://localhost:8081/inventory/WIDGET-001 | jq '.quantityOnHand, .quantityAvailable'
```

**Expected:** `quantityOnHand` back to its pre-order value — the 3 units are returned to the warehouse.

---

## Scenario 3 — Insufficient stock (order fails at reservation)

```bash
curl -s -X POST http://localhost:8080/orders \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: demo-insufficient-$(date +%s)" \
  -d '{"customerId":"11111111-1111-1111-1111-111111111111","sku":"WIDGET-001","quantity":999999}' \
  | tee /tmp/order2.json

ORDER_ID_2=$(cat /tmp/order2.json | jq -r '.orderId')
```

**Expected:** HTTP 202, `status: "PENDING"` (the API always accepts the order — the Saga discovers the failure asynchronously).

```bash
sleep 3
curl -s http://localhost:8080/orders/$ORDER_ID_2 | jq '.status'
```

**Note:** in the current version, a reservation failure at Inventory does not yet drive the order to a `FAILED` terminal state (see README's Known Limitations) — the order will remain `PENDING`. This is a documented gap, not a crash; Inventory correctly rejects the reservation and no stock is affected.

```bash
# Confirm no stock was actually deducted
curl -s http://localhost:8081/inventory/WIDGET-001 | jq '.quantityOnHand'
```

**Expected:** unchanged from before this scenario — the insufficient-stock rejection is fully transactional; nothing partially applies.

---

## Scenario 4 — Idempotency: safe retries

```bash
KEY="demo-idempotent-$(date +%s)"

# First call
curl -s -X POST http://localhost:8080/orders \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $KEY" \
  -d '{"customerId":"22222222-2222-2222-2222-222222222222","sku":"BOLT-M8-25","quantity":2}' \
  -w "\nHTTP %{http_code}\n"
```

**Expected:** HTTP 202.

```bash
# Exact same call again — simulates a client retry after a network timeout
curl -s -X POST http://localhost:8080/orders \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $KEY" \
  -d '{"customerId":"22222222-2222-2222-2222-222222222222","sku":"BOLT-M8-25","quantity":2}' \
  -w "\nHTTP %{http_code}\n"
```

**Expected:** HTTP 202 again, **same `orderId`** as the first call — proving the retry didn't create a second order.

```bash
# Verify only ONE order was actually created
docker compose exec postgres-fulfillment psql -U fulfillment_user -d fulfillment_db \
  -c "SELECT count(*) FROM orders WHERE idempotency_key = '$KEY';"
```

**Expected:** `1`.

---

## Scenario 5 — Two-layer stock alerting

```bash
# Configure an alert rule for CABLE-USB-C
curl -s -X POST http://localhost:8082/notifications/rules \
  -H "Content-Type: application/json" \
  -d '{"sku":"CABLE-USB-C","alertThreshold":30}' | jq
```

```bash
# Set stock just above both Inventory's publish_threshold and the alert_threshold
docker compose exec postgres-inventory psql -U inventory_user -d inventory_db \
  -c "UPDATE stock SET quantity_on_hand = 35, quantity_reserved = 0, publish_threshold = 34 WHERE item_id = (SELECT id FROM items WHERE sku = 'CABLE-USB-C');"
```

```bash
# Place and confirm an order that crosses BOTH thresholds in one step (35 -> 25)
curl -s -X POST http://localhost:8080/orders \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: demo-stocklow-$(date +%s)" \
  -d '{"customerId":"33333333-3333-3333-3333-333333333333","sku":"CABLE-USB-C","quantity":10}' \
  | tee /tmp/order3.json

sleep 3
```

```bash
# Check the resulting notification
curl -s "http://localhost:8082/notifications?type=STOCK_LOW" | jq
```

**Expected:** one `STOCK_LOW` notification mentioning ~25 units remaining and the 30-unit threshold.

---

## Reset between demo runs

```bash
docker compose exec postgres-inventory psql -U inventory_user -d inventory_db \
  -c "UPDATE stock SET quantity_on_hand = 100, quantity_reserved = 0, publish_threshold = 20; TRUNCATE reservations, outbox, processed_events CASCADE;"

docker compose exec postgres-fulfillment psql -U fulfillment_user -d fulfillment_db \
  -c "TRUNCATE orders, order_saga_state, outbox, processed_events CASCADE;"

docker compose exec postgres-notification psql -U notification_user -d notification_db \
  -c "TRUNCATE notifications, processed_events CASCADE;"
```

**Note:** this leaves `notification_rules` and `items` intact — only transactional data is reset.