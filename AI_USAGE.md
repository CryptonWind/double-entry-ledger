# AI usage

OpenCode was used to review the challenge, discuss architecture and generate
application code, migrations, container configuration, documentation and tests.
Earlier verification included compilation with Java 17 and integration tests against
actual MySQL and RabbitMQ containers, with two independent Spring application
contexts sharing the infrastructure. Fault scenarios are simulated in tests;
they are not a comprehensive process-kill or infrastructure chaos test suite.
The latest changes have not completed verification. Tests must only be executed
when explicitly requested by the user. All repository-authored documentation,
application messages and comments must be in English; conversation may be Romanian.
No repository-specific AI instruction files have been introduced.

## Architecture and coverage review

AI assistance was also used to compare requirements with source/tests, reorganize
domain/application/infrastructure, separate JPA entities from business models,
introduce ports, configure Log4j2 and document controllers using Springdoc.
References included Leandro Franchi's hexagonal Spring Boot article and Baeldung's
Spring REST OpenAPI guide.

Domain tests now have no Spring context. Isolated application/controller tests
mock ports instead of the JPA/RabbitMQ chain. Adapter tests retain a shared minimal
context; integration tests share Boot, a peer application and singleton containers.
ArchUnit rules inspect compiled architectural dependencies.

The user reported a later 80-test run with two unit failures and 22 integration
context errors. Surefire review identified a Mockito boxed-Integer default and
MySQL trigger privileges under binary logging; fixes were made. The latest suite
has not been confirmed passing. Current verification uses Java 17 source/test
compilation and static review only; no tests/containers are started automatically.
