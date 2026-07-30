# ADR-004: KRaft Mode for Kafka (No Zookeeper)

**Status:** Accepted — 2026-07-25

## Context

Apache Kafka historically depended on Apache Zookeeper for metadata management, controller election, and cluster coordination. Kafka 3.3 (October 2022) made KRaft (Kafka Raft) mode generally available; KRaft implements the same coordination responsibilities natively inside Kafka brokers using the Raft consensus protocol. Kafka 4.0 makes KRaft the default and deprecates Zookeeper.

For this project's Docker Compose deployment, the choice is:

- **Zookeeper-based** — traditional; two containers (Kafka + Zookeeper); more tutorials and Stack Overflow answers available; well-understood operational model.
- **KRaft mode** — modern; one container; simpler operational surface; the direction Kafka itself is moving.

## Decision

Use **KRaft mode**. The Docker Compose file will run a single Kafka container with KRaft configured; no Zookeeper container.

## Consequences

**Gained:**

- **One less container** in the deployment. Fewer failure modes (no "Kafka can't reach Zookeeper" class of errors), less resource usage on the development laptop, faster startup.
- **Simpler mental model.** Kafka is self-contained; there is no external coordination service to configure, monitor, or reason about.
- **Alignment with the direction of the ecosystem.** KRaft is the default in Kafka 4.0+; new deployments in 2026 default to KRaft. Choosing Zookeeper today would be building against a deprecated dependency.

**Given up:**

- **Fewer tutorials use KRaft.** Most Kafka tutorials predating late 2023 assume Zookeeper. Following an older tutorial requires mentally translating Zookeeper setup steps to KRaft equivalents. Mitigation: application code is identical between the two modes; only the broker's `docker-compose.yml` service definition differs.
- **Slightly less battle-tested at extreme scale.** Zookeeper has decades of operational history in the largest Kafka deployments. This is a non-issue for a demo running one broker on a laptop.

## Notes

Not a resume-line-item decision. "Used KRaft over Zookeeper" is a small talking point if an interviewer probes deeply, not a bullet on the resume itself. The value here is operational simplicity, not signaling.

If tutorials followed during learning use Zookeeper, remember: only the broker's Docker service definition differs. `spring-kafka` configuration, producer code, consumer code, and topic management commands (`kafka-topics.sh`) are unchanged.
