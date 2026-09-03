package com.ems.notification_service.messaging;

import com.ems.common.event.EventEnvelope;
import com.ems.common.outbox.IdempotentConsumer;
import com.ems.notification_service.entity.NotificationTemplate;
import com.ems.notification_service.event.UserRegisteredPayload;
import com.ems.notification_service.mail.TemplatedMailer;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Decides what, if anything, an event should cause to be sent, and sends it.
 *
 * <p>Dispatch is on {@link EventEnvelope#type()} — the envelope's own statement of what it is,
 * not the routing key it happened to arrive under, which the retry loop rewrites.
 *
 * <p>An unrecognised type is logged and returns normally, which acknowledges the message.
 * That is the intended outcome and not a failure to be dead-lettered: {@code notification.q}
 * is bound to {@code #}, so most of what arrives here is something this service has no email
 * for, and parking all of it would bury the deliveries that genuinely did fail.
 */
@Component
public class EventConsumer {

    private static final Logger log = LoggerFactory.getLogger(EventConsumer.class);

    private final TemplatedMailer mailer;
    private final JsonMapper jsonMapper;

    public EventConsumer(TemplatedMailer mailer, JsonMapper jsonMapper) {
        this.mailer = mailer;
        this.jsonMapper = jsonMapper;
    }

    /**
     * The transaction here covers the idempotency claim rather than the email — SMTP does not
     * roll back. A crash after the mail server accepted the message but before the commit
     * therefore resends on the redelivery, which is the right way round: this pipeline is
     * at-least-once, and a duplicate welcome email is a far smaller problem than a missing one.
     */
    @IdempotentConsumer("notification.events")
    @Transactional
    public void handle(EventEnvelope<JsonNode> event) {
        switch (event.type()) {
            case UserRegisteredPayload.TYPE -> sendWelcomeEmail(event);
            default -> log.debug("No notification is defined for {}; acknowledging {}", event.type(), event.eventId());
        }
    }

    private void sendWelcomeEmail(EventEnvelope<JsonNode> event) {
        UserRegisteredPayload payload = jsonMapper.treeToValue(event.payload(), UserRegisteredPayload.class);
        mailer.send(
                NotificationTemplate.WELCOME_KEY,
                payload.email(),
                Map.of("username", payload.username(), "email", payload.email()));
        log.info("Welcomed user {} ({}) from event {}", payload.userId(), payload.email(), event.eventId());
    }
}
