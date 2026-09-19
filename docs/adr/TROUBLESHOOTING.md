# Troubleshooting Notes

Real issues hit during development, with root causes and fixes. Kept here
because several of these are genuinely non-obvious and worth documenting
for future-me or anyone else standing up this system.

---

## Windows: Git Bash mangles container-internal paths

**Symptom:**

OCI runtime exec failed: exec failed: unable to start container process:
exec: "C:/Program Files/Git/opt/kafka/bin/kafka-topics.sh": stat ...: no such file or directory


**Cause:** Git Bash's MSYS layer auto-converts any argument starting with `/`
into a Windows path before passing it to `docker exec` — so
`/opt/kafka/bin/kafka-topics.sh` becomes a nonsensical Windows path, even
though the path is correct *inside* the Linux container.

**Fix:** Run Kafka/Docker CLI commands from **PowerShell**, not Git Bash.
Reserve Git Bash for `git` commands only. (A `//` prefix or
`MSYS_NO_PATHCONV=1` env var also works as one-off workarounds, but
switching shells entirely is simpler long-term.)

---

## Kafka: `UnknownHostException: kafka` when running Spring Boot from the host

**Symptom:** Spring Boot app (run via `mvn spring-boot:run` on the host, not
in a container) fails to connect to Kafka with `UnknownHostException: kafka`.

**Cause:** Kafka's `KAFKA_ADVERTISED_LISTENERS` was set to
`PLAINTEXT://kafka:9092` — correct for container-to-container communication,
but the hostname `kafka` doesn't resolve from the host machine.

**Fix:** Configure Kafka with **two listeners** — an internal one
(`kafka:9092`) for container-to-container traffic, and an external one
(`localhost:9093`) for host-run applications:

```yaml
KAFKA_LISTENERS: PLAINTEXT://0.0.0.0:9092,EXTERNAL://0.0.0.0:9093,CONTROLLER://0.0.0.0:9094
KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:9092,EXTERNAL://localhost:9093
KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT,EXTERNAL:PLAINTEXT
```

Point `spring.kafka.bootstrap-servers` at `localhost:9093` (the external
listener) in every service's `application.yml`.

---

## Kafka: `NOT_COORDINATOR` errors immediately after a fresh cluster start

**Symptom:**

Group coordinator ... is unavailable or invalid due to cause: error response NOT_COORDINATOR

repeating rapidly right after `docker compose up` on a freshly wiped Kafka volume.

**Cause:** Kafka's internal `__consumer_offsets` topic hasn't finished
leader election yet — this is normal, transient behavior in the few hundred
milliseconds after a KRaft cluster starts from scratch.

**Fix:** Usually self-resolves within 1-2 seconds. If it doesn't, verify
`KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1` is set (a single-broker cluster
can never satisfy the default replication factor of 3, which would cause
this to loop forever instead of resolving).

---

## Spring Kafka: `IllegalStateException: No Acknowledgment available` — the big one

**Symptom:** Every Kafka listener invocation throws:

java.lang.IllegalStateException: No Acknowledgment available as an argument,
the listener container must have a MANUAL AckMode to populate the Acknowledgment.

Consumer offsets still appear to advance (visible via
`kafka-consumer-groups.sh --describe`), which is misleading — it looks like
messages are being processed when they're actually all failing silently.

**Cause:** YAML indentation bug. `spring.kafka.listener.ack-mode: manual`
was accidentally nested **inside** `spring.kafka.consumer` instead of being
a sibling of `producer`/`consumer` under `spring.kafka`. Spring silently
ignores unknown YAML paths — there's no startup error, just wrong runtime
behavior. Same bug also affected `spring.data.redis`, which had been
accidentally nested inside `spring.kafka`.

**How this was diagnosed:** the giveaway was that `processed_events` and
business-logic tables stayed empty despite Kafka showing zero consumer lag —
meaning messages were being fetched and *something* was acknowledging them,
but the listener method body was never actually executing. Reading the full
stack trace (not just the top-level `ListenerExecutionFailedException`, but
the *suppressed* nested exception) revealed the real cause.

**Fix:** Verify indentation carefully:

```yaml
spring:
  kafka:
    bootstrap-servers: ...
    producer:
      ...
    consumer:
      ...
    listener:              # <- sibling of producer/consumer, NOT nested inside either
      ack-mode: manual
  data:                    # <- sibling of kafka, NOT nested inside it
    redis:
      ...
```

**Lesson:** YAML's silent tolerance of unknown paths makes structural
mistakes look like "nothing happened" rather than throwing a clear error.
When a Spring Boot feature seems to have zero effect despite being
"configured," suspect indentation before suspecting the feature itself.

---

## Maven: `AccessDeniedException` on `mvn clean` (Windows + OneDrive)

**Symptom:**

java.nio.file.AccessDeniedException: ...\target\classes...

specifically on directories, not files, during `mvn clean`.

**Cause:** OneDrive's real-time file sync (or, less commonly, Windows
Defender's real-time scanning) briefly locks files right after they're
written, racing against Maven's delete operation.

**Fix:** Move the project outside any OneDrive-synced folder. If the issue
persists, check for orphaned Java processes (`Get-Process | Where-Object
{ $_.ProcessName -like "*java*" }`) holding file handles, and add the
project folder to Windows Defender's exclusion list.

---

## Hibernate: `ObjectOptimisticLockingFailureException` on a first insert

**Symptom:**

Row was updated or deleted by another transaction (or unsaved-value mapping
was incorrect)

on an entity that was never previously saved.

**Cause:** The entity had `@GeneratedValue` on its `@Id` field, but the
application code was also explicitly setting that ID before calling
`save()`. Hibernate saw a non-null ID and assumed the row already existed,
issuing an `UPDATE` instead of an `INSERT` — which matched zero rows and
was misreported as an optimistic-locking conflict.

**Fix:** Never mix `@GeneratedValue` with an application-assigned ID. If the
ID must be assigned by the application (e.g., outbox events, where the ID
needs to match the Kafka event envelope's `eventId` for correlation), remove
`@GeneratedValue` entirely.

---

## Spring Boot 4.x dependency incompatibility

**Symptom:** Maven Initializr's "latest" preset (Spring Boot 4.1.0 at the
time) produced a `pom.xml` referencing dependencies that don't exist in the
Maven repository — `spring-boot-starter-webmvc`, `spring-boot-starter-flyway`,
and several `-test` variants that were consolidated differently between
major versions.

**Fix:** Downgraded to Spring Boot **3.4.3** — the latest stable 3.x release
at the time, with full ecosystem support (Hypersistence Utils, springdoc,
etc.). Corrected artifact names: `spring-boot-starter-web` (not `-webmvc`),
`flyway-core` + `flyway-database-postgresql` (not a single `-flyway`
starter), and consolidated all test dependencies into the single
`spring-boot-starter-test`.

---

## General lesson across all of the above

Several of the hardest bugs in this project shared a pattern: **the symptom
appeared far downstream from the actual cause**, and the system's own
signals (consumer offsets advancing, no startup errors) were actively
misleading. The fix in every case was the same discipline: get the *actual*
stack trace or log line before forming a theory, and verify each layer
independently (is the topic reachable? is the listener registered? is the
config actually applied?) rather than assuming the most recently changed
thing is the culprit.