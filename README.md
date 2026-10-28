# Double-entry Ledger API

Java 17, Spring Boot 4, Spring Data JPA/Hibernate, MySQL 8.4, Flyway and RabbitMQ.
Credits increase balances; debits decrease them. Accounts start at zero. Money
moves only through balanced, immutable transactions.

> **Important: authentication and authorization are intentionally not implemented.**
> They are not required by the challenge. Restrict access to the API and broker;
> production deployment must add identity, authorization and settlement-account policies.

## Running

Required: Docker with Linux containers and Compose, Git and JDK 17. Any compatible
Docker environment can be used; Rancher Desktop with Moby is the Windows development
setup, not a project requirement. Start the engine first. Linux/macOS:

```sh
sh run-all.sh
```

Windows PowerShell:

```powershell
# Explicit full build/start/test workflow:
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\run-all.ps1
```

The script builds the application/test images, starts the stack, waits for
`/actuator/health`, then runs tests in Docker. Maven is provided by the build
image. Testcontainers creates separate MySQL/RabbitMQ containers for tests using
the Docker socket and host.docker.internal. Initial image pulls can take minutes.
Keep Docker and test containers running until completion.

Two application instances: <http://localhost:8080> and <http://localhost:8081>,
sharing MySQL/RabbitMQ. Send a request to one and retry on the other to demonstrate
cross-instance idempotency. Both have image healthchecks and run as non-root UID 10001.
RabbitMQ UI: <http://localhost:15672>.
MySQL: localhost:3306, database ledger. Local credentials for both: ledger/ledger.

### IntelliJ and development

The original Windows .cmd build scripts have equivalent Linux/macOS .sh scripts:

| Script | Behavior |
| --- | --- |
| `sh Build17.sh` | Clean, compile, execute tests and install |
| `sh Build21SkipExecuteTests.sh` | Compile application/tests and install without executing tests |
| `sh Build21SkipCompileAndExecuteTests.sh` | Build/install without compiling or executing tests |

These local build scripts require Maven and use JAVA_HOME when provided, otherwise
Java from PATH. On macOS, if JAVA_HOME is absent, JDK 17 is selected using
`/usr/libexec/java_home`. The legacy `Build21` filenames are retained to match the
original Windows scripts; they do not select Java 21. For example on Linux:
`JAVA_HOME=/path/to/jdk-17 sh Build21SkipExecuteTests.sh`.
The Docker-based `run-all.sh` workflow does not require host Maven.

Import pom.xml; use C:\Java\jdk-17_x64 for Project SDK and Maven Runner JRE.
Run com.ledger.LedgerApplication with the project root as working directory.
The default local profile automatically starts MySQL/RabbitMQ with Docker Compose,
reuses running containers and leaves them running when the application stops.

```powershell
$env:JAVA_HOME = 'C:\Java\jdk-17_x64'
# Infrastructure only, without tests:
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\start-containers.ps1
mvn spring-boot:run
# Execute tests only when explicitly requested:
mvn clean verify
```

If Docker occupies port 8080, stop its application before using the IDE:
`docker compose -f compose.yaml -f compose-full.yaml stop app app-2`.
`docker compose stop` stops infrastructure.
`docker compose -f compose.yaml -f compose-full.yaml down` removes containers;
adding --volumes deletes local data too. Named volumes preserve data by default.
Infrastructure uses restart: unless-stopped.

## Architecture

A hexagonal modular monolith supporting multiple application instances.

```text
src/main/java/com/ledger/
  application/                    Framework-independent use-case implementations
    model/                        Commands and results
    port/in/                      Primary use-case interfaces
    port/out/                     Persistence, transaction and announcement interfaces
  domain/
    model/                        Account, LedgerTransaction, Direction
    service/                      MoneyRules
    exception/                    LedgerException
  infrastructure/
    configuration/                Spring composition root and framework configuration
    input/rest/                   Controllers, request DTOs, errors and HTTP filters
    input/messaging/              RabbitMQ consumers
    input/scheduler/              Outbox polling entry point
    output/persistence/           Hibernate adapters, JPA entities and repositories
    output/messaging/             Message producers and announcement adapter
    output/serialization/         JSON implementation
    tracing/                      W3C context and MDC implementation
  LedgerApplication.java
```

The requested output/client area is reserved for future HTTP clients; no HTTP
client adapter is needed by the challenge, so no empty placeholder is introduced.

```text
REST controllers ----+
                    +--> application services --> MySQL transaction
RabbitMQ consumer --+                              | accounts and balances
                                                   | transactions and entries
                                                   | idempotency and payments
                                                   + outbox
                                                       |
                                                outbox publisher --> RabbitMQ
```

Use cases cover accounts, current/historical balances, transaction recording,
reversal, settled payments and idempotency. Infrastructure handles delivery,
database locking, JSON, HTTP and scheduled publication.

All application and test database access uses Hibernate/JPA. Entities map
accounts, transactions, entries, idempotency records, processed payments, failed
messages and outbox events. Ordinary writes use managed entities and dirty
checking. Atomic key reservation uses native MySQL upserts through EntityManager;
outbox workers use native entity queries with FOR UPDATE SKIP LOCKED.
There is no JdbcTemplate usage. HibernateDatabase is the native-query escape hatch,
not a JDBC connection or independent transaction manager.

### Domain models, ports and adapters

`AccountEntity` and other JPA entities are database representations only. Business
objects are separate:

- `domain/model/Account`: account opening, exact balance changes and overdraft validation.
- `domain/service/MoneyRules`: currency support, precision and monetary bounds.
- `domain/model/LedgerTransaction`: immutable transaction/entry aggregate,
  balanced-posting validation, net account effects, running balances, posting time
  and reversal entry generation.
- `domain/model/Direction`: debit/credit semantics.

The domain imports neither Spring, JPA, RabbitMQ nor concrete infrastructure.
Application services orchestrate business objects through input/output ports:

| Input port | Application implementation | Output ports |
| --- | --- | --- |
| AccountUseCases | AccountService | AccountStore |
| AccountHistoryUseCases | AccountHistoryService | AccountHistoryReader, AccountUseCases |
| LedgerService | DefaultLedgerService | AccountStore, TransactionStore, TransactionAnnouncements |
| SettlePayment | PaymentService | PaymentStore, AccountStore, LedgerService, UnitOfWork, JsonCodec |
| IdempotentOperations | IdempotencyService | IdempotencyStore, UnitOfWork, JsonCodec |

Controllers depend on input ports and map validated transport requests to plain
application commands. Bean Validation annotations remain in REST request DTOs,
not in domain models/application commands. Hibernate adapters implement output
ports and map entities to/from domain objects; JPA repositories/entities never
escape into the business services. The outbox adapter implements the announcement
port and stores serialized messages in the same financial transaction.
Idempotency uses an IdempotencyStore port; transaction and JSON infrastructure are
behind UnitOfWork and JsonCodec ports. Application services have no Spring/JPA
annotations. UseCaseConfiguration constructs them explicitly, as the composition
root described in the architecture article. Read/snapshot transaction boundaries
are requested through UnitOfWork and implemented by DatabaseTransactions.

Native queries and persistence-specific coordination live in output adapters.
Rabbit listeners, HTTP filters and scheduled outbox publication are infrastructure
entry points. Domain and application imports point inward; all technology-specific
classes are under infrastructure. Technical entity methods only update persisted
state; financial validation and posting decisions belong to the domain/use cases.

Lombok supplies constructors, getters and logging. Entities avoid @Data and
unrestricted generated setters. Flyway owns schema evolution; Hibernate validates
it using ddl-auto: validate. Open EntityManager in View is disabled.

### Financial model

- EUR/USD allow two decimal places; JPY allows none. Invalid and unsupported
  currency codes have distinct errors. Invalid amounts are rejected, never rounded.
- BigDecimal and DECIMAL(24,2) preserve exact money; maximum stored amount/current
  balance magnitude is 9999999999999999999999.99. Overflow is rejected.
- Normal accounts default to zero overdraft, configurable at opening.
- Flyway seeds settlement-EUR/USD/JPY with unlimited overdraft represented by NULL.
- Transactions require a 1-500 character description and 2-1000 positive entries,
  existing accounts in one currency, equal debit/credit totals and valid final balances.
- Current balances and immutable entries commit atomically. Repeated-account
  overdraft is checked using the transaction's net effect.
- balanceAfter is the running account balance after each entry in original order.
  Intermediate balances can cross overdraft limits; only the atomic final state
  is constrained. Running balances use DECIMAL(28,2) to accommodate intermediate sums.
- Reversals create new transactions, flip all entries and reference the original.
  A transaction can be reversed once; a reversal cannot be reversed.
- Hibernate @Immutable and MySQL triggers reject history updates/deletes.
  V4 explicitly backfills earlier running balances during a controlled migration;
  previously serialized idempotency responses/outbox messages retain their content.

### Transactions and concurrency

TransactionTemplate establishes READ COMMITTED transactions. First reserve the
idempotency key/payment ID, then lock the original for reversals, then lock
accounts individually in lexicographic ID order using PESSIMISTIC_WRITE.
Validation completes before financial mutations. Entries, balances, transaction,
deduplication/idempotency result and outbox commit together. Transient database
errors receive up to three retries after the first attempt in fresh transactions.
REST returns 503 when those retries are exhausted.

Posting timestamps use UTC microseconds and increase strictly per affected account,
including across instances/clock regressions, under ordered account locks.
This can advance posting time slightly beyond the wall clock. Database keys use
case-sensitive collation. Unique constraints protect payment IDs and reversals.

## REST API and OpenAPI

### Interactive API documentation

- Swagger UI: <http://localhost:8080/swagger-ui.html> (also /swagger-ui/index.html).
- Live controller-derived OpenAPI JSON: <http://localhost:8080/v3/api-docs>.
- Live OpenAPI YAML: <http://localhost:8080/v3/api-docs.yaml>.
- Maintained reference contract: <http://localhost:8080/openapi.yaml>, sourced from
  src/main/resources/openapi.yaml.

Springdoc 3.x is used for Spring Boot 4 (the Baeldung article's 2.x examples target
Boot 3). Controllers carry @Tag, @Operation, @ApiResponse, request examples and
parameter documentation. DocumentedErrors provides shared typed error responses.
OpenApiConfiguration adds API metadata and correlation headers. REST Bean Validation
constraints are included in generated request schemas. Successful String-backed
idempotent responses explicitly document their actual Account/Transaction schemas.

Swagger UI uses the live generated contract, not a fixed YAML URL. Expand an
operation, select Try it out, supply Idempotency-Key for POSTs, replace sample
account IDs with real ones, then Execute. A new business attempt needs a new key;
reusing the same request/key intentionally replays the original result.
Pagination/history query parameters and reversal restrictions are documented too.
The reference contract and controller annotations should be kept synchronized.
Documentation was introduced after initial implementation, so the original work
was not historically API-first. No build plugin starts the app or runs tests to
generate documentation; Springdoc generates it when requested from a running app.

| Method | Path | Purpose |
| --- | --- | --- |
| POST | /api/v1/accounts | Open an account |
| GET | /api/v1/accounts/{id} | Read current account/balance |
| GET | /api/v1/accounts/{id}/entries | Paged newest-first entries |
| GET | /api/v1/accounts/{id}/balance?at=ISO_INSTANT | Historical balance |
| POST | /api/v1/transactions | Record a balanced transaction |
| GET | /api/v1/transactions/{id} | Read immutable transaction |
| POST | /api/v1/transactions/{id}/reversals | Reverse, with no body |

All POSTs require Idempotency-Key: 1-128 characters from A-Z a-z 0-9 . _ : -.
Keys are operation-scoped and never expire in this implementation. Same key and
parsed request replay the original HTTP status/body; different content returns 409.
The SHA-256 fingerprint ignores JSON whitespace/property order, but preserves
entry order and numeric scale. Unknown properties/trailing tokens are rejected.
Business refusals are saved too; retrying after funding still returns the original
refusal. Use a new key for a new attempt. Parsing/header/structural validation
errors occur before reservation and are not stored. A crash before commit permits
retry; after commit another instance can replay the stored response.

Success: 201 for creation and 200 for reads. Errors: 400 malformed structure or
parameters, 404 missing read/reversal resources, 409 conflict, 422 financial rules,
503 exhausted transient database retries. Error example:

```json
{"code":"INVALID_PRECISION","field":"entries[0].amount","message":"JPY permits at most 0 decimal places"}
```

Amounts accept decimal JSON numbers or strings. Clients must use decimal parsing
for numeric responses and avoid binary floating point.

### Account history

GET /accounts/{id}/entries accepts zero-based page (default 0), size (default 50,
maximum 200) and optional asOf ISO instant. Ordering: recordedAt DESC,
transactionId DESC, position DESC. Responses contain content, page, size,
totalElements, totalPages and asOf. Reuse returned asOf on later pages to exclude
new postings. Each request uses a repeatable-read snapshot; asOf is not a durable
snapshot token and does not freeze transactions already in flight.

GET /accounts/{id}/balance requires at. It sums committed entries whose recordedAt
is <= at, returning zero before the first posting, including before creation.
This is posting-time history, not reconstruction of database commit-time visibility.

### PowerShell examples

```powershell
$base = 'http://localhost:8080/api/v1'
$merchant = Invoke-RestMethod -Method Post -Uri "$base/accounts" `
  -Headers @{'Idempotency-Key'='merchant-42'} -ContentType 'application/json' `
  -Body '{"currency":"EUR","overdraftLimit":0}'
$payout = Invoke-RestMethod -Method Post -Uri "$base/accounts" `
  -Headers @{'Idempotency-Key'='payout-account'} -ContentType 'application/json' `
  -Body '{"currency":"EUR"}'
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\send-payment.ps1 `
  -MerchantAccountId $merchant.id -PaymentId payment-42 -Amount 125.50 -Currency EUR
# Wait for the asynchronous payment before posting a payout:
Invoke-RestMethod "$base/accounts/$($merchant.id)"
$body = @{
  description = 'Merchant payout'
  entries = @(
    @{accountId=$merchant.id; direction='DEBIT'; amount='98.50'},
    @{accountId=$payout.id; direction='CREDIT'; amount='98.50'}
  )
} | ConvertTo-Json -Depth 5
$tx = Invoke-RestMethod -Method Post -Uri "$base/transactions" `
  -Headers @{'Idempotency-Key'='payout-42'; 'X-Correlation-ID'='payout-request-42'} `
  -ContentType 'application/json' -Body $body
Invoke-RestMethod "$base/accounts/$($merchant.id)/entries?page=0&size=20"
$at = [Uri]::EscapeDataString($tx.recordedAt)
Invoke-RestMethod "$base/accounts/$($merchant.id)/balance?at=$at"
Invoke-RestMethod -Method Post -Uri "$base/transactions/$($tx.id)/reversals" `
  -Headers @{'Idempotency-Key'='reverse-payout-42'}
```

## Messaging and guarantees

Input: durable topic exchange payments, routing key payment.settled, quorum queue
ledger.payment-settled. Payload:

```json
{"eventId":"evt-1","paymentId":"pay-1","merchantAccountId":"ACCOUNT_ID","amount":"125.50","currency":"EUR"}
```

A payment debits its settlement account and credits its merchant. Deduplication
uses paymentId even if eventId changes. Different payment details under the same
ID are quarantined. IDs have maximum length 128. ACK follows commit; a crash before
ACK leads to safe redelivery.

Invalid messages are saved in failed_payment_messages, then published with confirms
to ledger.failed-payments / payment.failed / ledger.payment-settled.dlq. Original
ACK occurs only afterwards. Quarantine may contain duplicates after failures.
Infrastructure errors receive four local backoff attempts, then confirmed publication
to durable quorum retry queues with delays of 5, 30 and 120 seconds. Quorum at-least-once
dead-lettering returns them to the input queue. After those rounds, messages are
retained in ledger.payment-settled.parked for operator-controlled replay after recovery.
LEDGER_PAYMENTS_DELAYED_RETRIES configures 0-3 delayed rounds (default 3).
The original is ACKed only after the retry/parking publication is confirmed and routed.
If the broker itself cannot confirm recovery publication, the original is requeued
as a last-resort retention mechanism. Broker outage/reconnection behavior is distinct
from a poison payload or database outage; no payment is intentionally discarded.
The input queue declares
x-delivery-limit=-1 to prevent quorum retry limits discarding payments. Invalid
payloads do not block subsequent messages when infrastructure is available.
Existing queues with different immutable arguments need controlled topology
reconciliation; preserve pending messages. DLQ replay is not automatic.

Output: durable topic exchange ledger.transactions, routing key transaction.recorded,
quorum queue ledger.transaction-recorded. Independent subscribers bind their own
durable queues; consumers sharing one queue divide deliveries.

```json
{"eventId":"UUID","type":"transaction-recorded","version":1,"correlationId":"request-id","traceparent":"00-TRACE_ID-SPAN_ID-01","paymentId":null,"sourceEventId":null,"transaction":{"id":"UUID","description":"...","recordedAt":"...","reversesTransactionId":null,"currency":"EUR","entries":[]}}
```

The outbox commits with financial records. Workers use FOR UPDATE SKIP LOCKED,
short transactions to claim one event with a random lease token and a 30-second
expiry using the database clock. Publication waits for confirms outside any DB
transaction, without holding a Hikari connection. A second short transaction marks
completion only if the lease token still matches. Failed workers/publications become
eligible after expiry; another instance can resume. A worker stalled beyond its lease
can publish a duplicate, consistent with at-least-once delivery. The confirmation
timeout is 10 seconds; lease expiry also supplies retry backoff. Persistent messages,
mandatory routing and publisher confirms remain enabled. Poll interval: 500 ms.

- At-least-once delivery; deduplicate by stable eventId / AMQP message ID.
- Crash after commit leaves the event pending for publication.
- Crash after publish before marking may cause duplicate publication.
- No global/per-account delivery ordering guarantee with multiple workers.
- Assumes preserved MySQL and durable broker data. Subscribers ACK after their own
  durable processing. Offline subscribers require pre-existing durable queues.

## End-to-end correlation tracing

HTTP accepts X-Correlation-ID and W3C version-00 traceparent. Missing/invalid values
are replaced; response headers return context. Request completion logs include
method, path, status, duration and MDC correlationId/traceId/spanId. Idempotency
logs include scope/key; posting logs include transaction/event and source IDs.

Payments accept AMQP correlation_id and traceparent. Consumers create child spans;
paymentId and sourceEventId are included in transaction announcements. Outbox rows
persist correlation/trace context so another instance can restore it after restart.
Publishers emit AMQP correlation_id/traceparent and logs under that same trace.
DLQ messages also preserve correlation. send-payment.ps1 accepts -CorrelationId
and -Traceparent. Follow correlationId/traceId through logs and announcements.

This provides propagated W3C context and correlated logs, not exported timed spans
in an OpenTelemetry collector/backend. Replayed HTTP requests have their own request
span while the original event keeps its original context and stable identifiers.

### Logging

The logging backend is Log4j2, configured with spring-boot-starter-log4j2 and
log4j2-spring.xml. Boot's default Logback starter is excluded. Application loggers
use Lombok `@Log4j2`; Lombok generates the logger field, while Log4j2 handles output.
The SLF4J-to-Log4j2 provider keeps framework logs and existing MDC correlation on
the same backend. Console output includes timestamp, level, thread, logger,
correlationId, traceId and spanId. Logs are suitable for Docker collection.

INFO logs cover account creation, transaction recording/reversal, idempotency
completion/replay, payment processing and event enqueue/publication. WARN covers
business refusals, message quarantine and transient retries. ERROR records exhausted
database retries. DEBUG covers reads and broker confirms. Set LEDGER_LOG_LEVEL=DEBUG
for detailed application logs; default is INFO. Credentials and complete request
or payment payloads are not logged.

## Configuration

The default local profile is intentionally retained for IDE development. Both Docker
instances explicitly select prod, so they never start Compose from inside the image.
External deployments must set SPRING_PROFILES_ACTIVE=prod explicitly.
V5 persists transaction currency and adds outbox leases. Currency is no longer inferred
from account state. Account restoration validates identity/currency/precision/overdraft;
the full constructor is private. Transaction posting touches only referenced accounts.

### Linux/macOS examples

Scripts require POSIX sh and Docker only; no host Maven, jq, Python or curl is required.
Use `sh start-containers.sh` for infrastructure only and
`sh send-payment.sh MERCHANT_ACCOUNT_ID 125.50 EUR payment-42` to send a sample
through the running app container's curl binary. Check the returned `routed` flag.
The following optional host curl examples illustrate direct REST usage:

```sh
curl -X POST http://localhost:8080/api/v1/accounts \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: merchant-42' \
  -d '{"currency":"EUR","overdraftLimit":0}'
curl http://localhost:8081/api/v1/accounts/ACCOUNT_ID
curl -X POST http://localhost:8080/api/v1/transactions \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: settlement-42' \
  -d '{"description":"Settlement","entries":[{"accountId":"settlement-EUR","direction":"DEBIT","amount":"125.50"},{"accountId":"ACCOUNT_ID","direction":"CREDIT","amount":"125.50"}]}'
curl -X POST http://localhost:8081/api/v1/transactions/TRANSACTION_ID/reversals \
  -H 'Idempotency-Key: reverse-42'
```

- application.yml: shared configuration and default local profile.
- application-local.yml: local MySQL/RabbitMQ and automatic Compose startup.
- application-prod.yml: environment-supplied connections; Compose disabled.

Set SPRING_PROFILES_ACTIVE=prod and MYSQL_URL (full JDBC URL), MYSQL_USERNAME,
MYSQL_PASSWORD, RABBITMQ_HOST, RABBITMQ_USERNAME, RABBITMQ_PASSWORD. Optional:
RABBITMQ_PORT (5672), RABBITMQ_VIRTUAL_HOST (/), SERVER_PORT (8080). Configure TLS
with standard Spring properties. Prod has no credential defaults; compose-full
only demonstrates local environment injection.

For externally managed services under local, set SPRING_DOCKER_COMPOSE_ENABLED=false;
otherwise Compose connection details override datasource/broker properties.
Compose support is excluded from the executable JAR. LEDGER_OUTBOX_ENABLED=false
disables scheduled publication.

## Tests and verification

MySQL development and test containers set log_bin_trust_function_creators=1 so
Flyway can create the immutable-history triggers with schema-level permissions,
without granting the application SUPER. For external/production MySQL, have the
database administrator configure the appropriate migration privileges and binary
logging policy. Runtime credentials should be separated from migration credentials.
After changing compose.yaml, recreate the MySQL service using
`docker compose up -d mysql`; the named data volume is preserved. Testcontainers
applies the setting to each new test database automatically.

Tests are run only when explicitly requested. mvn verify, Build17.cmd and
run-all.ps1 run them when manually invoked. Application startup does not run tests.

The suite uses real MySQL/RabbitMQ and two independent Spring contexts. Coverage
includes idempotency/races, overdraft, opposite transfers, refusal atomicity,
reversal, precision, exact arithmetic, redelivery, quarantine, rollback, outbox
duplication, immutability, pagination, historical balances, running balances,
HTTP trace propagation, persisted announcement context and OpenAPI availability.
It checks zero totals per currency and reconciliation with entries.

### Shared test infrastructure

```text
src/test/java/com/ledger/
  support/UnitTestBase.java             Minimal cached Spring context and shared mocks
  support/IntegrationTestBase.java      Cached Boot context, singleton containers and peer instance
  unit/                                Service/domain/infrastructure logic tests
  integration/LedgerIntegrationTest.java  Concurrency, retries, history, HTTP and messaging guarantees
  integration/DocumentExamplesIntegrationTest.java  Literal challenge examples on real infrastructure
```

Adapter-focused unit classes extend UnitTestBase and use the exact same configuration,
allowing Spring's context cache to initialize it once per test JVM. There is no
Boot auto-configuration or real database/broker in that context. Shared Mockito
infrastructure mocks are reset before each test. These are service-level logic
tests in a minimal Spring context, rather than container-backed integration tests.

Integration subclasses inherit the same SpringBootTest configuration and dynamic
properties from IntegrationTestBase. MySQL/RabbitMQ start once per JVM, and Ryuk
cleans them up at JVM exit. A second application instance is a singleton resource
of the shared test context, required to test cross-instance behavior; it closes
when that context closes, not after each class. Tests never use DirtiesContext or
per-class container shutdown. Test data uses unique IDs without deleting immutable
ledger history. Global integrity assertions therefore include earlier test data.
Parallel execution is disabled because shared mock state and broker queue reads
would otherwise interfere. Keep the default single Maven test JVM/context cache;
separate Maven invocations naturally create fresh contexts and containers.

Unit tests cover AccountService, AccountHistoryService, LedgerService,
IdempotencyService, PaymentService, DatabaseTransactions, ConfirmedPublisher,
OutboxPublisher, PaymentConsumer, HibernateDatabase, MoneyRules, JSON fingerprinting,
trace context and HTTP trace filtering. Assertions use the challenge's exact values:
125.50 settlement, 100.00 merchant debit, 98.50 payout, 1.50 fee, 25.50 remaining,
the refused 50.00 debit yielding -24.50, and 15000/100.5 JPY examples.
DomainModelTest and MoneyRulesTest execute without Spring. LedgerUseCasePortsTest,
IsolatedUseCasesTest and ControllerPortsTest exercise core orchestration and input
mapping with ports only, without JPA/RabbitMQ setup. ArchitectureTest uses ArchUnit
to inspect dependencies, method calls, annotations and entity references throughout
the compiled code. Lombok's generated compile-time annotations are allowed; runtime
domain framework dependencies are not. Adapter-focused tests retain mocked JPA
boundaries. FailureRecoveryIntegrationTest covers invalid-account/currency quarantine,
following valid payments and retained outbox state after unroutable publication.

Explicit execution commands, when requested:

```powershell
# Unit logic only: no Docker required
mvn '-Dtest=com.ledger.unit.*Test' test
# Integration only: Docker must be running
mvn '-Dtest=com.ledger.integration.*Test' test
# Both suites
mvn verify
```

Java 17 source/test compilation succeeded with mvn test-compile. No tests or
containers were started for the shared test-infrastructure reorganization or
the Hibernate/history/OpenAPI/tracing additions.
Earlier 12-test runs passed before these changes; they do not verify this version.
All migrations need explicit execution testing.

## AI usage

See AI_USAGE.md for AI-assisted work and verification notes.

## Improvements

- Explicitly execute the current integration suite and verify all Flyway migrations.
- Process-kill/network/broker-outage tests beyond simulated failure windows.
- Production operations for parked-payment replay and lease/retry backlog monitoring.
- OpenAPI response/schema validation in CI and reference/live contract drift checks.
- Optional OpenTelemetry span export and operational trace storage.
- Authentication, settlement access policies, restricted runtime DB privileges,
  production TLS/secrets, backups and clustered MySQL/RabbitMQ.
- Retention/archiving, backlog/DLQ metrics and controlled replay tooling.
- For high volume, batch outbox publication, lock tuning and settlement contention
  measurement. Stronger event ordering requires an explicit additional contract.
