package com.ems.common.outbox;

import com.ems.common.event.EventEnvelope;
import com.ems.common.messaging.RabbitTopologyConfig;
import com.ems.common.web.CorrelationIdFilter;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Drains {@code outbox_event} onto {@value RabbitTopologyConfig#EVENTS_EXCHANGE}, turning
 * rows that a business transaction committed into messages on the broker.
 *
 * <p>Each pass claims a batch with {@code FOR UPDATE SKIP LOCKED} and publishes it inside
 * one transaction, so several instances of a service can poll at once without duplicating
 * work, and an instance that dies mid-batch releases its rows for the next poll rather than
 * stranding them.
 *
 * <p>Delivery is at-least-once by design. A crash between the broker accepting a message
 * and the transaction committing leaves {@code sent_at} null and the event is published
 * again — which is why consumers get {@link IdempotentConsumer}.
 */
public class OutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);

    private final OutboxRepository repository;
    private final RabbitTemplate rabbitTemplate;
    private final JsonMapper jsonMapper;
    private final OutboxProperties properties;

    public OutboxPoller(
            OutboxRepository repository,
            RabbitTemplate rabbitTemplate,
            JsonMapper jsonMapper,
            OutboxProperties properties) {
        this.repository = repository;
        this.rabbitTemplate = rabbitTemplate;
        this.jsonMapper = jsonMapper;
        this.properties = properties;
    }

    /**
     * Publishes everything currently due.
     *
     * <p>A failure is handled per row and never rethrown: the whole point of the pass is to
     * commit the {@code attempts} and {@code next_attempt_at} it worked out, and rolling
     * back would throw that away and leave the poller retrying a broken row at full speed.
     * With the broker down every row in the batch simply fails and backs off together.
     */
    @Scheduled(fixedDelayString = "${ems.outbox.poll-interval:2s}")
    @Transactional
    public void publishPending() {
        List<OutboxEvent> due = repository.claimDue(properties.getMaxAttempts(), properties.getBatchSize());
        if (due.isEmpty()) {
            return;
        }

        int sent = 0;
        for (OutboxEvent event : due) {
            if (publish(event)) {
                sent++;
            }
        }
        log.debug("Outbox pass published {} of {} claimed events", sent, due.size());
    }

    private boolean publish(OutboxEvent event) {
        Instant now = Instant.now();
        try {
            // The poller runs on a scheduler thread with an empty MDC, so the id is put
            // back for the duration of the send: it is what the outbound post processor
            // copies onto the message, and what ties the consumer's logs back to the
            // original request.
            withCorrelationId(
                    event.getCorrelationId(),
                    () -> rabbitTemplate.convertAndSend(
                            RabbitTopologyConfig.EVENTS_EXCHANGE, event.getType(), envelopeFor(event)));
            event.markSent(now);
            return true;
        } catch (RuntimeException ex) {
            event.markFailed(properties.nextAttemptAfter(event.getAttempts() + 1, now));
            log.warn(
                    "Could not publish outbox event {} ({}), attempt {} of {}; next attempt at {}",
                    event.getId(),
                    event.getType(),
                    event.getAttempts(),
                    properties.getMaxAttempts(),
                    event.getNextAttemptAt(),
                    ex);
            return false;
        }
    }

    /**
     * Rebuilds the envelope {@link OutboxPublisher} would have sent, with the payload as a
     * tree so it lands in the message as the object it was, not as an escaped string.
     */
    private EventEnvelope<JsonNode> envelopeFor(OutboxEvent event) {
        return new EventEnvelope<>(
                event.getId(),
                event.getType(),
                event.getCreatedAt(),
                event.getCorrelationId(),
                jsonMapper.readTree(event.getPayload()));
    }

    private void withCorrelationId(String correlationId, Runnable action) {
        if (correlationId == null || correlationId.isBlank()) {
            action.run();
            return;
        }
        MDC.put(CorrelationIdFilter.CORRELATION_ID_MDC_KEY, correlationId);
        try {
            action.run();
        } finally {
            MDC.remove(CorrelationIdFilter.CORRELATION_ID_MDC_KEY);
        }
    }
}
