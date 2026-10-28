package com.ledger.unit;

import com.ledger.infrastructure.output.persistence.OutboxEvent;
import com.ledger.infrastructure.output.persistence.FailedPaymentMessage;
import com.ledger.support.UnitTestBase;
import jakarta.persistence.Query;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MessagingLogicTest extends UnitTestBase {
    @Test
    void publisherRequiresPositiveConfirmationAndPreservesPersistentMessageIdentity() {
        confirm(true, false);
        publisher.publish("exchange", "routing", "event-42", "{}");
        var message = ArgumentCaptor.forClass(Message.class);
        verify(rabbit).send(eq("exchange"), eq("routing"), message.capture(), any(CorrelationData.class));
        assertThat(message.getValue().getMessageProperties().getMessageId()).isEqualTo("event-42");
        assertThat(message.getValue().getMessageProperties().getDeliveryMode()).isEqualTo(org.springframework.amqp.core.MessageDeliveryMode.PERSISTENT);
        assertThat(message.getValue().getMessageProperties().getCorrelationId()).isNotBlank();
        reset(rabbit);
        confirm(false, false);
        assertThatThrownBy(() -> publisher.publish("exchange", "routing", "event", "{}")).isInstanceOf(IllegalStateException.class);
        reset(rabbit);
        confirm(true, true);
        assertThatThrownBy(() -> publisher.publish("exchange", "routing", "event", "{}")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void outboxMarksOnlyConfirmedEventsAndLeavesFailedPublicationsPending() {
        Query query = mock(Query.class);
        when(entities.createNativeQuery(anyString(), eq(OutboxEvent.class))).thenReturn(query);
        var event = new OutboxEvent("event", "{}", Instant.now(), null, "correlation", null);
        when(query.getResultList()).thenReturn(List.of(event), List.of());
        Query claimUpdate = mock(Query.class, RETURNS_SELF);
        Query publishedUpdate = mock(Query.class, RETURNS_SELF);
        when(entities.createNativeQuery(contains("SET lease_token="))).thenReturn(claimUpdate);
        when(entities.createNativeQuery(contains("SET published_at="))).thenReturn(publishedUpdate);
        confirm(true, false);
        outbox.publishPending();
        verify(claimUpdate).setParameter("id", "event");
        verify(publishedUpdate).setParameter("id", "event");
        verify(claimUpdate).executeUpdate();
        verify(publishedUpdate).executeUpdate();
        verify(entities).createNativeQuery(contains("SET published_at="));
        var pending = new OutboxEvent("pending", "{}", Instant.now(), null, "correlation", null);
        when(query.getResultList()).thenReturn(List.of(pending), List.of());
        clearInvocations(entities);
        clearInvocations(claimUpdate, publishedUpdate);
        reset(rabbit);
        confirm(false, false);
        outbox.publishPending();
        assertThat(pending.getPublishedAt()).isNull();
        verify(claimUpdate).setParameter("id", "pending");
        verify(claimUpdate).executeUpdate();
        verifyNoInteractions(publishedUpdate);
        verify(entities, never()).createNativeQuery(contains("SET published_at="));
    }

    @Test
    void malformedPaymentIsAuditedAndConfirmedToQuarantine() {
        confirm(true, false);
        consumer.receive(new Message("{broken json".getBytes(StandardCharsets.UTF_8), new MessageProperties()));
        var persisted = ArgumentCaptor.forClass(Object.class);
        verify(entities).persist(persisted.capture());
        assertThat(persisted.getValue()).isInstanceOfSatisfying(FailedPaymentMessage.class,
                failure -> assertThat(failure.getPayload()).isEqualTo("{broken json"));
        verify(rabbit).send(eq("ledger.failed-payments"), eq("payment.failed"), any(Message.class), any(CorrelationData.class));
    }

    @Test
    void quarantinePublicationFailureEscapesSoOriginalMessageIsNotAcknowledged() {
        confirm(false, false);
        assertThatThrownBy(() -> consumer.receive(new Message("null".getBytes(StandardCharsets.UTF_8), new MessageProperties())))
                .isInstanceOf(IllegalStateException.class);
    }

    private void confirm(boolean ack, boolean returned) {
        doAnswer(invocation -> {
            CorrelationData data = invocation.getArgument(3);
            if (returned) data.setReturned(new ReturnedMessage(invocation.getArgument(2), 312, "NO_ROUTE", "exchange", "routing"));
            data.getFuture().complete(new CorrelationData.Confirm(ack, ack ? null : "Rejected"));
            return null;
        }).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    }
}
