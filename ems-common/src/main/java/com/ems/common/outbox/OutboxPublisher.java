package com.ems.common.outbox;

import com.ems.common.event.EventEnvelope;
import java.util.UUID;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * How a service emits a domain event: one INSERT into {@code outbox_event}, and nothing
 * else. No broker call happens here, so a caller can publish from the middle of its own
 * transaction without a slow or unreachable broker being able to fail — or slow down — the
 * business write.
 *
 * <pre>{@code
 * @Transactional
 * public User register(RegisterRequest request) {
 *     User saved = users.save(...);
 *     outbox.publish("user", saved.getId().toString(), "user.registered", payload);
 *     return saved;
 * }
 * }</pre>
 *
 * <p>{@link Propagation#MANDATORY} enforces the half of the pattern that a caller can get
 * wrong. An outbox row committed on its own is a promise the service may not be able to
 * keep, so this fails loudly rather than accepting a publish with no transaction to join.
 */
public class OutboxPublisher {

    private final OutboxRepository repository;
    private final JsonMapper jsonMapper;

    public OutboxPublisher(OutboxRepository repository, JsonMapper jsonMapper) {
        this.repository = repository;
        this.jsonMapper = jsonMapper;
    }

    /**
     * Records an event for publication, joining the caller's transaction.
     *
     * @param aggregateType the kind of thing the event is about, for example {@code "user"}
     * @param aggregateId its id, as a string
     * @param type the event type, which is also the routing key it is published under
     * @param payload the event body, serialised to JSON as-is
     * @return the event id, which is what a consumer deduplicates on
     * @throws org.springframework.transaction.IllegalTransactionStateException if called
     *     without an active transaction
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID publish(String aggregateType, String aggregateId, String type, Object payload) {
        // Built through the envelope so the id, timestamp and correlation id are minted
        // exactly the way a directly published event's would be; the poller rebuilds this
        // same envelope from the row.
        EventEnvelope<Object> envelope = EventEnvelope.of(type, payload);
        OutboxEvent event = new OutboxEvent(
                envelope.eventId(),
                aggregateType,
                aggregateId,
                type,
                jsonMapper.writeValueAsString(payload),
                envelope.correlationId(),
                envelope.occurredAt());
        repository.save(event);
        return event.getId();
    }
}
