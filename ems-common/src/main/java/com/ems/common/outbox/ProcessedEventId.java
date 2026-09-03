package com.ems.common.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Identity of a {@link ProcessedEvent}: an event id scoped to the consumer that handled it.
 *
 * <p>The consumer name is part of the key rather than a plain column because a service may
 * have more than one handler bound to the same routing pattern. Keyed on the event id
 * alone, the first handler to run would look to every other one like proof that the event
 * was already dealt with, and the rest would silently skip it.
 */
@Embeddable
public class ProcessedEventId implements Serializable {

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "consumer", nullable = false)
    private String consumer;

    protected ProcessedEventId() {
        // for JPA
    }

    public ProcessedEventId(UUID eventId, String consumer) {
        this.eventId = eventId;
        this.consumer = consumer;
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getConsumer() {
        return consumer;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ProcessedEventId id
                && Objects.equals(eventId, id.eventId)
                && Objects.equals(consumer, id.consumer);
    }

    @Override
    public int hashCode() {
        return Objects.hash(eventId, consumer);
    }
}
