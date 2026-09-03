package com.ems.notification_service.messaging;

import com.ems.common.event.EventEnvelope;
import com.ems.common.outbox.IdempotentConsumer;
import com.ems.notification_service.entity.AuditLog;
import com.ems.notification_service.repository.AuditLogRepository;
import java.time.Instant;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Writes every envelope that reaches {@code notification.q} to {@code audit_log}, whatever
 * its type and whether or not {@link EventConsumer} has anything to do with it.
 *
 * <p>The second of the two consumers behind {@link EventRouter}, and the reason
 * {@link IdempotentConsumer} takes a name: the aspect keys on event id and consumer name
 * together, so this and {@code notification.events} are each allowed to run once for the same
 * event instead of the first one to commit locking the other out.
 */
@Component
public class AuditConsumer {

    private final AuditLogRepository auditLog;

    public AuditConsumer(AuditLogRepository auditLog) {
        this.auditLog = auditLog;
    }

    @IdempotentConsumer("notification.audit")
    @Transactional
    public void record(EventEnvelope<JsonNode> event) {
        auditLog.save(new AuditLog(
                event.eventId(),
                event.type(),
                event.correlationId(),
                // Already a tree, so this is a re-serialisation of what arrived rather than
                // a fresh rendering of some object: what lands in the jsonb column is the
                // payload the publisher sent.
                event.payload().toString(),
                Instant.now()));
    }
}
