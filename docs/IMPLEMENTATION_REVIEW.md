# Implementation review

## Mandatory requirements implemented

- Java 17, Spring Boot 4, Hibernate/JPA, MySQL, REST and RabbitMQ.
- Accounts, supported currencies, exact amounts and configured overdraft.
- Atomic balanced transactions and detailed refusals.
- Immutable history with Hibernate annotations and MySQL update/delete guards.
- Linked single reversal, overdraft checks and no reversal of reversals.
- Persistent operation-scoped idempotency and payload fingerprint checks.
- Payment consumption, payment-ID deduplication, audit and confirmed quarantine.
- Transactional outbox, confirms and documented delivery semantics.
- Ordered account locks, constraints and transient retries.
- Docker build/start/test workflow, sample payments and English documentation.

## Requested additions implemented

- Hibernate-only persistence, including tests: mapped technical entities,
  EntityManager-based atomic native upserts and outbox claims, no JdbcTemplate.
- Reference OpenAPI contract served at /openapi.yaml, controller annotations,
  live /v3/api-docs[.yaml] and interactive /swagger-ui.html.
- Newest-first account entries with pagination and per-entry running balances.
- Historical balances using inclusive posting timestamps.
- Strictly increasing timestamps per account under database locks.
- HTTP/AMQP W3C context, correlation headers, MDC logging, persistent outbox
  context and payment/source-event correlation in announcements.
- Adapted/extended tests for Hibernate, history, pagination and tracing.
- Shared unit/integration base classes; a cached minimal unit Spring context and
  a cached Boot integration context with JVM-singleton MySQL/RabbitMQ containers.
- Dedicated service logic tests and literal document example integration tests;
  a singleton peer application context verifies cross-instance guarantees.

## Verification status

No tests or containers were started for these additions, as requested. Java 17
source/test compilation succeeded using Maven test-compile. Earlier 12-test runs
passed before these changes; that does not validate the current version.
Explicitly run the latest suite and all migrations against actual MySQL/RabbitMQ.

## Caveats

- Fault windows are simulated; full process-kill/network outage testing remains.
- Tracing provides propagated context/correlated logs, not exported timed spans.
- OpenAPI was introduced after initial implementation, not historically API-first.
- Timestamp pagination is not a durable snapshot across HTTP requests.
- History uses posting time, not the database commit visibility timeline.
- Existing RabbitMQ queues may require controlled delivery-limit reconciliation.
- V4 backfills entry running balances. Previously serialized idempotency responses
  and announcements retain their original content.
- Domain models are separate from JPA entities; business services use input/output
  ports and adapters perform mapping. Runtime verification of the refactor remains pending.
- Public repository publication and commit history remain user-controlled.

## Hexagonal architecture and logging update

Business Account and LedgerTransaction models now own financial rules independently
of JPA/Spring. REST input ports, persistence/history/payment/idempotency output ports
and the transactional announcement port isolate application use cases from adapters.
REST validation DTOs are separate from application commands. Log4j2 with Lombok
@Log4j2 replaces Logback; correlation-aware console output and lifecycle/retry/read
logs are configured. Dependency inspection confirmed only the Log4j2 backend.
Domain and dependency-boundary tests were added but not executed.

## Article-aligned package structure and composition root

The root now contains application, domain/model|service|exception and
infrastructure/input/rest|messaging|scheduler plus output/persistence|messaging
and serialization. All entities and JPA repositories are infrastructure-only.
Use cases have no Spring/JPA annotations and are constructed in UseCaseConfiguration;
UnitOfWork implements transaction/read/snapshot boundaries externally. Domain models
have no runtime framework dependencies. REST validation and OpenAPI annotations
are input-adapter concerns. A client adapter is unnecessary because there are no
outgoing HTTP integrations in the specification.

## Remaining requirement work

All specified mandatory business flows and optional paged/history/correlation
features have code implementations. This is not a claim that the current version
passes: the latest architecture changes, Swagger runtime behavior and migrations
require explicitly authorized test execution. A complete process-crash/network
fault suite remains incomplete, public repository/commit-history delivery has not
been performed, and the initial API-first chronology cannot be retroactively met.
Telemetry span export, production HA/security/retention and contract drift checking
remain production improvements rather than required challenge features.
