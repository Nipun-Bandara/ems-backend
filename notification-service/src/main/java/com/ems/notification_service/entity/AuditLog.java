package com.ems.notification_service.entity;

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
 * One event as it arrived on {@code notification.q}, written whatever its type and whether
 * or not anything acted on it.
 *
 * <p>This is the record of what the service was told, which is a different question from
 * what it did about it: an event with no handler still lands here, and so does one whose
 * handler later failed and parked.
 *
 * <p>{@link #eventId} is the primary key, not a surrogate — the same choice, for the same
 * reason, as {@code outbox_event}. One event produces one audit row, so a duplicate insert
 * is a bug in the idempotency it is supposed to be protected by, and this makes it fail
 * loudly rather than quietly updating the row that was already there.
 */
@Entity
@Table(name = "audit_log")
public class AuditLog implements Persistable<UUID> {

    @Id
    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "type", nullable = false)
    private String type;

    @Column(name = "correlation_id")
    private String correlationId;

    /** The event body as JSON. Stored as jsonb so it stays queryable from psql. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false)
    private String payload;

    /**
     * When this service took delivery — deliberately not the envelope's {@code occurredAt},
     * which says when the thing happened upstream. Comparing the two is how the end-to-end
     * lag of the event pipeline is measured.
     */
    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    /**
     * Spring Data decides insert-vs-merge from whether the id is null, and ours never is.
     * Without this every audit write would issue a pointless SELECT before its INSERT, and
     * a repeat would silently become an UPDATE.
     */
    @Transient
    private boolean isNew = true;

    protected AuditLog() {
        // for JPA
    }

    public AuditLog(UUID eventId, String type, String correlationId, String payload, Instant receivedAt) {
        this.eventId = eventId;
        this.type = type;
        this.correlationId = correlationId;
        this.payload = payload;
        this.receivedAt = receivedAt;
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getType() {
        return type;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    @Override
    public UUID getId() {
        return eventId;
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
}
