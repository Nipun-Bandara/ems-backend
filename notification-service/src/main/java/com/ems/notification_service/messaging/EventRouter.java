package com.ems.notification_service.messaging;

import com.ems.common.event.EventEnvelope;
import com.ems.common.outbox.IdempotentConsumer;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * The service's single point of delivery from {@code notification.q}, which hands every
 * message to both of the consumers that want it.
 *
 * <p>This class exists because "two consumers on one queue" is not something the broker can
 * give us: a queue delivers each message to exactly one of its consumers, so two
 * {@code @RabbitListener} methods on {@code notification.q} would round-robin between them
 * and the audit log would hold roughly half the events. One listener fanning out in process
 * is what makes both consumers see all of it.
 *
 * <p>They stay independent all the same. Each is a separate bean carrying its own
 * {@link IdempotentConsumer} name, so each is claimed, committed and — on a redelivery —
 * skipped on its own: an audit write that has already committed is not repeated because the
 * email failed, and the email is not lost because the audit write failed. Calling them
 * through injected beans rather than as methods of this class is also what puts the
 * idempotency aspect's proxy in the path at all.
 *
 * <p>The payload is left as a {@link JsonNode}. This queue is bound to {@code #}, so there is
 * no one payload type to deserialise into here; the audit writer wants the JSON as it arrived
 * anyway, and {@link EventConsumer} converts it only once it knows what the event is.
 */
@Component
@ConditionalOnProperty(name = "ems.messaging.enabled", havingValue = "true", matchIfMissing = true)
public class EventRouter {

    private final AuditConsumer auditConsumer;
    private final EventConsumer eventConsumer;

    public EventRouter(AuditConsumer auditConsumer, EventConsumer eventConsumer) {
        this.auditConsumer = auditConsumer;
        this.eventConsumer = eventConsumer;
    }

    /**
     * Audit first, so that an event is recorded as having arrived before anything is done
     * about it — if the handling throws and the message eventually parks, the audit row is
     * still there to say what parked.
     */
    @RabbitListener(queues = MessagingConfig.WORK_QUEUE)
    public void onEvent(EventEnvelope<JsonNode> event) {
        auditConsumer.record(event);
        eventConsumer.handle(event);
    }
}
