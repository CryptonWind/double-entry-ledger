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
