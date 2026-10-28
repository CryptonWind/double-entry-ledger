package com.ledger.support;

import com.ledger.LedgerApplication;
import com.ledger.application.*;
import com.ledger.infrastructure.output.messaging.ConfirmedPublisher;
import com.ledger.infrastructure.output.persistence.HibernateDatabase;
import com.ledger.infrastructure.output.serialization.JsonSupport;
import com.ledger.application.model.LedgerModels.*;
import com.ledger.domain.model.Direction;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

import static org.assertj.core.api.Assertions.assertThat;

/** All subclasses share the same cached Spring context and JVM-scoped containers. */
@SpringBootTest(classes = LedgerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.profiles.active=integration", "spring.docker.compose.enabled=false", "ledger.outbox.enabled=false"})
@Import(IntegrationTestBase.PeerConfiguration.class)
public abstract class IntegrationTestBase {
    private static final class Infrastructure {
        // MySQL binary logging otherwise requires SUPER for CREATE TRIGGER even with schema-level TRIGGER rights.
        static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
                .withCommand("--log-bin-trust-function-creators=1");
        static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.1-management");
        static {
            MYSQL.start();
            RABBIT.start();
            // JVM-scoped lifecycle: Ryuk removes containers when the test JVM exits.
        }
    }

    @DynamicPropertySource
    static void connections(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", Infrastructure.MYSQL::getJdbcUrl);
        properties.add("spring.datasource.username", Infrastructure.MYSQL::getUsername);
        properties.add("spring.datasource.password", Infrastructure.MYSQL::getPassword);
        properties.add("spring.rabbitmq.host", Infrastructure.RABBIT::getHost);
        properties.add("spring.rabbitmq.port", Infrastructure.RABBIT::getAmqpPort);
        properties.add("spring.rabbitmq.username", Infrastructure.RABBIT::getAdminUsername);
        properties.add("spring.rabbitmq.password", Infrastructure.RABBIT::getAdminPassword);
    }

    /** A second application instance is created once, not once per test class. */
    @TestConfiguration(proxyBeanMethods = false)
    static class PeerConfiguration {
        @Bean(destroyMethod = "close")
        PeerInstance peerInstance() {
            var mysql = Infrastructure.MYSQL;
            var rabbit = Infrastructure.RABBIT;
            var context = new SpringApplicationBuilder(LedgerApplication.class).run(
                    "--spring.profiles.active=integration", "--server.port=0", "--spring.docker.compose.enabled=false",
                    "--ledger.outbox.enabled=false", "--spring.rabbitmq.listener.simple.auto-startup=false",
                    "--spring.datasource.url=" + mysql.getJdbcUrl(),
                    "--spring.datasource.username=" + mysql.getUsername(), "--spring.datasource.password=" + mysql.getPassword(),
                    "--spring.rabbitmq.host=" + rabbit.getHost(), "--spring.rabbitmq.port=" + rabbit.getAmqpPort(),
                    "--spring.rabbitmq.username=" + rabbit.getAdminUsername(), "--spring.rabbitmq.password=" + rabbit.getAdminPassword());
            return new PeerInstance(context);
        }
    }

    public record PeerInstance(ConfigurableApplicationContext context) implements AutoCloseable {
        public <T> T getBean(Class<T> type) { return context.getBean(type); }
        @Override public void close() { context.close(); }
    }

    @Autowired protected IdempotencyService idempotency;
    @Autowired protected AccountService accounts;
    @Autowired protected LedgerService ledger;
    @Autowired protected JsonSupport json;
    @Autowired protected HibernateDatabase database;
    @Autowired protected EntityManager entities;
    @Autowired protected AccountHistoryService history;
    @Autowired protected PaymentService payments;
    @Autowired protected RabbitTemplate rabbit;
    @Autowired protected ConfirmedPublisher publisher;
    @Autowired protected TransactionTemplate transactionTemplate;
    @Autowired protected PeerInstance second;
    @LocalServerPort protected int port;
    protected final HttpClient http = HttpClient.newHttpClient();

    protected String account(String currency) {
        var request = new OpenAccount(currency, null);
        return json.read(idempotency.execute("open-account", key(), request, () -> accounts.open(request)).body(), AccountView.class).id();
    }

    protected IdempotencyService.StoredResponse record(RecordTransaction request) {
        return idempotency.execute("record-transaction", key(), request, () -> ledger.record(request));
    }

    protected RecordTransaction transfer(String from, String to, String amount) {
        return new RecordTransaction("Transfer", List.of(new EntryInput(from, Direction.DEBIT, new BigDecimal(amount)),
                new EntryInput(to, Direction.CREDIT, new BigDecimal(amount))));
    }

    protected HttpResponse<String> post(String path, String key, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json").header("Idempotency-Key", key)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    protected String key() { return UUID.randomUUID().toString(); }
    protected long transactionCount() { return database.queryForObject("SELECT COUNT(*) FROM ledger_transactions", Long.class); }

    protected void assertIntegrity() {
        database.queryForList("SELECT SUM(balance) FROM accounts GROUP BY currency", BigDecimal.class)
                .forEach(total -> assertThat(total).isEqualByComparingTo(BigDecimal.ZERO));
        assertThat(database.queryForObject("SELECT COUNT(*) FROM accounts a WHERE a.balance <> "
                + "COALESCE((SELECT SUM(CASE WHEN e.direction = 'CREDIT' THEN e.amount ELSE -e.amount END) "
                + "FROM ledger_entries e WHERE e.account_id = a.id), 0)", Long.class)).isZero();
    }

    protected <T> List<T> race(int count, java.util.function.IntFunction<T> operation) throws Exception {
        var pool = Executors.newFixedThreadPool(count);
        var ready = new java.util.concurrent.CountDownLatch(count);
        var start = new java.util.concurrent.CountDownLatch(1);
        try {
            List<Callable<T>> actions = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                int index = i;
                actions.add(() -> { ready.countDown(); start.await(); return operation.apply(index); });
            }
            var futures = actions.stream().map(pool::submit).toList();
            assertThat(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<T> results = new ArrayList<>();
            for (var future : futures) results.add(future.get(45, java.util.concurrent.TimeUnit.SECONDS));
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
