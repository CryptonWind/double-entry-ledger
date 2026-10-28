package com.ledger.infrastructure.configuration;

import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
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
    Declarables ledgerTopology() {
        var payments = new TopicExchange(PAYMENT_EXCHANGE, true, false);
        var announcements = new TopicExchange(TRANSACTION_EXCHANGE, true, false);
        var failures = new TopicExchange(FAILED_EXCHANGE, true, false);
        var input = QueueBuilder.durable(PAYMENT_QUEUE).quorum()
                .withArgument("x-delivery-limit", -1).build();
        var recorded = QueueBuilder.durable("ledger.transaction-recorded").quorum().build();
        var failed = QueueBuilder.durable("ledger.payment-settled.dlq").quorum().build();
        var recovery = new TopicExchange("ledger.payment-recovery", true, false);
        var parked = QueueBuilder.durable("ledger.payment-settled.parked").quorum().build();
        var declarations = new java.util.ArrayList<org.springframework.amqp.core.Declarable>();
        declarations.addAll(java.util.List.of(payments, announcements, failures, input, recorded, failed, recovery, parked,
                BindingBuilder.bind(parked).to(recovery).with("parked"),
                BindingBuilder.bind(input).to(payments).with("payment.settled"),
                BindingBuilder.bind(recorded).to(announcements).with("transaction.recorded"),
                BindingBuilder.bind(failed).to(failures).with("payment.failed")));
        int[] delays = {5000, 30000, 120000};
        for (int i = 0; i < delays.length; i++) {
            var retry = QueueBuilder.durable("ledger.payment-settled.retry." + (i + 1)).quorum()
                    .ttl(delays[i]).deadLetterExchange(PAYMENT_EXCHANGE).deadLetterRoutingKey("payment.settled")
                    .withArgument("x-dead-letter-strategy", "at-least-once")
                    .withArgument("x-overflow", "reject-publish").build();
            declarations.add(retry);
            declarations.add(BindingBuilder.bind(retry).to(recovery).with("retry." + (i + 1)));
        }
        return new Declarables(declarations);
    }
}
