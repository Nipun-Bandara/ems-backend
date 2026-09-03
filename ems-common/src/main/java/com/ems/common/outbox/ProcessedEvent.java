package com.ems.common.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * The record that a consumer has already handled an event, which is what makes a
 * redelivery harmless.
 *
 * <p>Written by {@link IdempotentConsumerAspect} in the same transaction as the handler, so
 * the two commit or roll back together: a handler that fails leaves no mark and gets to run
 * again on the next delivery.
 */
@Entity
@Table(name = "processed_event")
public class ProcessedEvent {

    @EmbeddedId
    private ProcessedEventId id;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    protected ProcessedEvent() {
        // for JPA
    }

    public ProcessedEvent(ProcessedEventId id, Instant processedAt) {
        this.id = id;
        this.processedAt = processedAt;
    }

    public ProcessedEventId getId() {
        return id;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }
}
