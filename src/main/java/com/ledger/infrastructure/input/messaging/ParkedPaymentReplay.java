package com.ledger.infrastructure.input.messaging;

import com.rabbitmq.client.AMQP;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/** Explicit one-shot operational mode; publisher confirmation always precedes the original ACK. */
@Component
@RequiredArgsConstructor
@Log4j2
@ConditionalOnProperty(name = "ledger.payments.replay", havingValue = "true")
public class ParkedPaymentReplay implements ApplicationRunner {
    private final RabbitTemplate rabbit;
    private final ConfigurableApplicationContext context;
    @org.springframework.beans.factory.annotation.Value("${ledger.payments.replay-limit:100}")
    private int limit;

    @Override public void run(ApplicationArguments args) {
        if (limit < 1 || limit > 10000) throw new IllegalArgumentException("Replay limit must be 1-10000");
        Integer replayed = rabbit.execute(channel -> {
            channel.confirmSelect();
            // Snapshot the ready count so concurrent arrivals do not extend this bounded replay.
            int available = Math.min(limit, channel.queueDeclarePassive("ledger.payment-settled.parked").getMessageCount());
            int completed = 0;
            var returned = new java.util.concurrent.atomic.AtomicBoolean();
            channel.addReturnListener((replyCode, replyText, exchange, routingKey, properties, body) -> returned.set(true));
            for (int i = 0; i < available; i++) {
                var original = channel.basicGet("ledger.payment-settled.parked", false);
                if (original == null) break;
                long tag = original.getEnvelope().getDeliveryTag();
                try {
                    // Publish to the known durable input queue via the default exchange.
                    channel.queueDeclarePassive("ledger.payment-settled");
                    var headers = new java.util.HashMap<String, Object>();
                    if (original.getProps().getHeaders() != null) headers.putAll(original.getProps().getHeaders());
                    headers.remove("ledger-retry-count");
                    headers.remove("x-death");
                    headers.put("ledger-replayed-at", java.time.Instant.now().toString());
                    AMQP.BasicProperties properties = original.getProps().builder().headers(headers).deliveryMode(2).build();
                    returned.set(false);
                    channel.basicPublish("", "ledger.payment-settled", true, properties, original.getBody());
                    channel.waitForConfirmsOrDie(10000);
                    if (returned.get()) throw new IllegalStateException("Input queue disappeared; replay publication was returned");
                    channel.basicAck(tag, false);
                    completed++;
                } catch (Exception e) {
                    if (channel.isOpen()) channel.basicNack(tag, false, true);
                    throw new IllegalStateException("Replay interrupted; original retained, duplicate possible", e);
                }
            }
            return completed;
        });
        log.info("Parked payment replay completed count={}", replayed);
        SpringApplication.exit(context, () -> 0);
    }
}
