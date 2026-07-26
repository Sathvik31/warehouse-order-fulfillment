# ADR-008: No API Gateway in v1

**Status:** Accepted — 2026-07-25 (design review pass 1)

## Context

Standard microservices reference architectures include an API gateway (Spring Cloud Gateway, Kong, AWS API Gateway) as a single entry point in front of the services. The gateway typically handles:

- Request routing to the right service.
- Authentication and authorization.
- Rate limiting.
- Request logging and correlation-ID injection.
- CORS handling.
- Request/response transformation.

For this project, the question is whether to include a gateway in v1.

## Decision

**No API gateway in v1.** External clients call service endpoints directly. Gateway is documented as future work, to be added when authentication is introduced.

## Consequences

**Gained:**

- **No scaffolding without purpose.** The BRD explicitly excludes authentication, rate limiting, and complex request routing from v1. A gateway configured only to route requests to three static services on a Docker network is pattern-cargo-culting — the "problem" it solves does not exist in v1.
- **One less container to run, monitor, and debug.** Simpler `docker compose up` topology.
- **Preserved focus on the core distributed-systems story.** Time spent configuring a gateway with nothing to do is time not spent on the Saga implementation, which is the project's actual resume signal.
- **Cleaner scoping narrative.** "I deferred the gateway because with no auth, no rate limiting, and three static services, it would have added scaffolding without solving a problem" is a stronger interview answer than "I added a gateway because microservices need one."

**Given up:**

- **The reference-architecture look.** A diagram with three services behind a gateway is more immediately recognizable to interviewers scanning the README. A diagram with three services and no gateway looks less "complete" at first glance. Mitigated by explicit README documentation calling out the deferral and rationale.
- **Clients must know internal service names/ports.** For the Docker Compose demo, this is trivial (`http://fulfillment:8080`, `http://inventory:8081`, `http://notification:8082`). In production, this would be unacceptable and the gateway would exist.
- **No single place to add cross-cutting concerns.** Adding correlation-ID injection or request logging in v1 requires implementing it in each service (via a Spring Boot filter). Accepted because the duplication is minimal at three services.

## Notes

Post-v1 addition is planned but not committed. The intent is to add Spring Cloud Gateway as a fourth container after v1 ships, at which point:

- All external traffic routes through the gateway.
- Services move to an internal-only network.
- Correlation-ID injection moves from per-service filters to the gateway.
- Introducing authentication becomes a natural next step, using the gateway as the auth boundary.

This is documented in the README's future-work section explicitly, so the omission reads as a considered choice rather than an oversight.

## Related

- ADR-009 (two-layer threshold model) illustrates the same scoping principle: avoid adding infrastructure that has no user-visible effect in v1.
- ADR-006 (items as first-class entity) is the counter-case: a modeling correction that *does* have effects in v1, so it was included.
