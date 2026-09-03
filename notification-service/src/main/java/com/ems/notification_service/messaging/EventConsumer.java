package com.ems.notification_service.messaging;

import com.ems.common.event.EventEnvelope;
import com.ems.common.outbox.IdempotentConsumer;
import com.ems.notification_service.entity.NotificationTemplate;
import com.ems.notification_service.event.UserRegisteredPayload;
import com.ems.notification_service.event.UserVerifiedPayload;
import com.ems.notification_service.mail.TemplatedMailer;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
    private final String frontendUrl;

    public EventConsumer(
            TemplatedMailer mailer, JsonMapper jsonMapper, @Value("${app.frontend.url}") String frontendUrl) {
        this.mailer = mailer;
        this.jsonMapper = jsonMapper;
        // Trailing slash trimmed once here rather than guarded at each use: the value comes
        // from a deployment environment, and both spellings of the same host are correct.
        this.frontendUrl = frontendUrl.endsWith("/") ? frontendUrl.substring(0, frontendUrl.length() - 1) : frontendUrl;
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
            case UserRegisteredPayload.TYPE -> sendVerificationEmail(event);
            case UserVerifiedPayload.TYPE -> sendWelcomeEmail(event);
            default -> log.debug("No notification is defined for {}; acknowledging {}", event.type(), event.eventId());
        }
    }

    /**
     * The mail a registration causes: a link, and nothing that assumes the account works yet.
     * Also what a resend produces — identity-service republishes the same event type with a
     * new token, so there is deliberately no second handler for it here.
     */
    private void sendVerificationEmail(EventEnvelope<JsonNode> event) {
        UserRegisteredPayload payload = jsonMapper.treeToValue(event.payload(), UserRegisteredPayload.class);
        mailer.send(
                NotificationTemplate.VERIFY_EMAIL_KEY,
                payload.email(),
                Map.of(
                        "username", payload.username(),
                        "email", payload.email(),
                        "verifyUrl", verifyUrl(payload.verificationToken())));
        // The token is a credential for the account. It goes in the link and stays out of
        // the log, which is why this line names the event rather than what was in it.
        log.info(
                "Sent verification link to user {} ({}) from event {}",
                payload.userId(),
                payload.email(),
                event.eventId());
    }

    /** The mail a verification causes: the account is now usable, so say so. */
    private void sendWelcomeEmail(EventEnvelope<JsonNode> event) {
        UserVerifiedPayload payload = jsonMapper.treeToValue(event.payload(), UserVerifiedPayload.class);
        mailer.send(
                NotificationTemplate.WELCOME_KEY,
                payload.email(),
                Map.of("username", payload.username(), "email", payload.email()));
        log.info("Welcomed user {} ({}) from event {}", payload.userId(), payload.email(), event.eventId());
    }

    /**
     * Encoded even though the token is a UUID and has nothing to encode. What arrives here
     * came off the broker, and a value that was not what this service expected must not be
     * able to add query parameters to a link users are told to click.
     */
    private String verifyUrl(String verificationToken) {
        return "%s/auth/verify?token=%s"
                .formatted(frontendUrl, URLEncoder.encode(verificationToken, StandardCharsets.UTF_8));
    }
}
