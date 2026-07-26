# Warehouse Order Fulfillment System

A microservices demonstration of the Saga pattern for distributed transactions.
Three Spring Boot services (Inventory, Fulfillment, Notification) coordinate
order placement, stock reservation, and event-driven notifications over Apache
Kafka, backed by per-service Postgres databases.

**Status:** In development.

**Design documentation:** See [docs/DESIGN.md](docs/DESIGN.md) and [docs/adr/](docs/adr/).