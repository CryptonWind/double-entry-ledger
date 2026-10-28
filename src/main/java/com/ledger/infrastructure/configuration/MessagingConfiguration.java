package com.ledger.infrastructure.configuration;

import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.ImmediateRequeueAmqpException;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class MessagingConfiguration {
    public static final String PAYMENT_QUEUE = "ledger.payment-settled";
    public static final String PAYMENT_EXCHANGE = "payments";
    public static final String TRANSACTION_EXCHANGE = "ledger.transactions";
    public static final String FAILED_EXCHANGE = "ledger.failed-payments";

    @Bean
    MessageRecoverer infrastructureFailureRecoverer() {
        // Permanent payload/domain failures are quarantined by PaymentConsumer.
        // Exhausted infrastructure retries must not silently discard the original delivery.
        return (message, cause) -> {
            throw new ImmediateRequeueAmqpException("Infrastructure unavailable; retain payment for redelivery", cause);
        };
    }

    @Bean
    Declarables ledgerTopology() {
        var payments = new TopicExchange(PAYMENT_EXCHANGE, true, false);
        var announcements = new TopicExchange(TRANSACTION_EXCHANGE, true, false);
        var failures = new TopicExchange(FAILED_EXCHANGE, true, false);
        var input = QueueBuilder.durable(PAYMENT_QUEUE).quorum()
                .withArgument("x-delivery-limit", -1).build();
        var recorded = QueueBuilder.durable("ledger.transaction-recorded").quorum().build();
        var failed = QueueBuilder.durable("ledger.payment-settled.dlq").quorum().build();
        return new Declarables(payments, announcements, failures, input, recorded, failed,
                BindingBuilder.bind(input).to(payments).with("payment.settled"),
                BindingBuilder.bind(recorded).to(announcements).with("transaction.recorded"),
                BindingBuilder.bind(failed).to(failures).with("payment.failed"));
    }
}
