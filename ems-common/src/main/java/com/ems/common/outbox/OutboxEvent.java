package com.ems.common.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

/**
 * One domain event, written to the service's own database in the same transaction as the
 * change that caused it, and published to the broker afterwards by {@link OutboxPoller}.
 *
 * <p>That ordering is the point: a row here is committed or rolled back with the business
 * write, so a crash between the two can only lose the publish attempt, never the fact that
 * the event is owed.
 *
 * <p>{@link #id} is the event id, not a surrogate key. It is minted by
 * {@link OutboxPublisher} and travels on the envelope, which is what lets a consumer
 * recognise a redelivery — see {@link ProcessedEvent}.
 */
@Entity
@Table(name = "outbox_event")
public class OutboxEvent implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "aggregate_type", nullable = false)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private String aggregateId;

    @Column(name = "type", nullable = false)
    private String type;

    /** The event body as JSON. Stored as jsonb so it stays queryable from psql. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false)
    private String payload;

    @Column(name = "correlation_id")
    private String correlationId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Null until the broker has accepted the event. The poller only ever claims nulls. */
    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    /**
     * When the poller may next try this row. Held in the database rather than in the
     * poller so that a backed-off row stays backed off across a restart and across
     * instances, which is the same reason the row itself is in the database.
     */
    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    /**
     * Spring Data decides insert-vs-merge from whether the id is null, and ours never is.
     * Without this every publish would issue a pointless SELECT before its INSERT.
     */
    @Transient
    private boolean isNew = true;

    protected OutboxEvent() {
        // for JPA
    }

    OutboxEvent(
            UUID id,
            String aggregateType,
            String aggregateId,
            String type,
            String payload,
            String correlationId,
            Instant createdAt) {
        this.id = id;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.type = type;
        this.payload = payload;
        this.correlationId = correlationId;
        this.createdAt = createdAt;
        this.attempts = 0;
        this.nextAttemptAt = createdAt;
    }

    /** Records that the broker accepted this event, taking it out of the poller's reach. */
    void markSent(Instant sentAt) {
        this.sentAt = sentAt;
        this.attempts++;
    }

    /** Records a failed publish and holds the row back until {@code nextAttemptAt}. */
    void markFailed(Instant nextAttemptAt) {
        this.attempts++;
        this.nextAttemptAt = nextAttemptAt;
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public String getAggregateId() {
        return aggregateId;
    }

    public String getType() {
        return type;
    }

    public String getPayload() {
        return payload;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }
}
