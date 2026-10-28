package com.ledger.infrastructure.input.scheduler;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.log4j.Log4j2;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "ledger.payments.replay", havingValue = "false", matchIfMissing = true)
@Log4j2
public class ParkedPaymentMonitor {
    private final RabbitTemplate rabbit;
    private final AtomicLong count = new AtomicLong();

    public ParkedPaymentMonitor(RabbitTemplate rabbit, MeterRegistry metrics) {
        this.rabbit = rabbit;
        metrics.gauge("ledger.payments.parked.ready", count);
    }

    @Scheduled(fixedDelayString = "${ledger.payments.monitor-delay:60000}", initialDelay = 60000)
    public void inspect() {
        try {
            Integer ready = rabbit.execute(channel -> channel.queueDeclarePassive("ledger.payment-settled.parked").getMessageCount());
            // The broker API returns an unsigned count represented by an int.
            count.set(ready == null ? 0 : Integer.toUnsignedLong(ready));
            if (count.get() > 0) log.warn("Parked payments awaiting operator replay ready={}", count.get());
        } catch (RuntimeException e) {
            log.warn("Cannot inspect parked payment queue: {}", e.getMessage());
        }
    }
}
