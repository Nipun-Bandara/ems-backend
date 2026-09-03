package com.ems.notification_service.messaging;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.ems.common.event.EventEnvelope;
import com.ems.notification_service.entity.NotificationTemplate;
import com.ems.notification_service.event.UserRegisteredPayload;
import com.ems.notification_service.event.UserVerifiedPayload;
import com.ems.notification_service.mail.TemplatedMailer;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Covers the dispatch decision itself. The mailer is mocked because what is interesting here
 * is which events produce which mail, not what the wording turns out to be — that lives in a
 * {@code notification_template} row and is rendered by
 * {@link com.ems.notification_service.mail.TemplateRenderer}.
 *
 * <p>The verification link is the exception: it is composed here rather than stored, so it is
 * asserted here.
 */
@ExtendWith(MockitoExtension.class)
class EventConsumerTest {

    private static final String FRONTEND_URL = "http://localhost:3000";

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Mock
    private TemplatedMailer mailer;

    @Test
    void sendsTheVerificationTemplateOnUserRegistered() {
        UserRegisteredPayload payload = new UserRegisteredPayload(
                7L,
                "ada@ems.local",
                "ada",
                "3f2504e0-4f89-11d3-9a0c-0305e82c3301",
                Instant.parse("2026-09-03T10:15:30Z"));

        consumer(FRONTEND_URL).handle(envelope(UserRegisteredPayload.TYPE, payload));

        verify(mailer)
                .send(
                        eq(NotificationTemplate.VERIFY_EMAIL_KEY),
                        eq("ada@ems.local"),
                        eq(Map.of(
                                "username",
                                "ada",
                                "email",
                                "ada@ems.local",
                                "verifyUrl",
                                "http://localhost:3000/auth/verify?token=3f2504e0-4f89-11d3-9a0c-0305e82c3301")));
    }

    /**
     * The configured origin is written by whoever deploys the service, so both spellings of
     * the same host have to produce the same link rather than one with a doubled slash.
     */
    @Test
    void doesNotDoubleTheSlashWhenTheFrontendUrlHasATrailingOne() {
        UserRegisteredPayload payload = new UserRegisteredPayload(
                7L,
                "ada@ems.local",
                "ada",
                "3f2504e0-4f89-11d3-9a0c-0305e82c3301",
                Instant.parse("2026-09-03T10:15:30Z"));

        consumer("https://ems.example.com/").handle(envelope(UserRegisteredPayload.TYPE, payload));

        verify(mailer)
                .send(
                        eq(NotificationTemplate.VERIFY_EMAIL_KEY),
                        eq("ada@ems.local"),
                        eq(Map.of(
                                "username",
                                "ada",
                                "email",
                                "ada@ems.local",
                                "verifyUrl",
                                "https://ems.example.com/auth/verify?token=3f2504e0-4f89-11d3-9a0c-0305e82c3301")));
    }

    /** The welcome mail waits for the address to be confirmed; registration alone does not earn it. */
    @Test
    void sendsTheWelcomeTemplateOnUserVerified() {
        UserVerifiedPayload payload =
                new UserVerifiedPayload(7L, "ada@ems.local", "ada", Instant.parse("2026-09-03T10:15:30Z"));

        consumer(FRONTEND_URL).handle(envelope(UserVerifiedPayload.TYPE, payload));

        verify(mailer)
                .send(
                        eq(NotificationTemplate.WELCOME_KEY),
                        eq("ada@ems.local"),
                        eq(Map.of("username", "ada", "email", "ada@ems.local")));
    }

    /**
     * The queue is bound to {@code #}, so most of what arrives has no notification attached to
     * it. Returning normally is what acknowledges the message; throwing would park events that
     * were never this service's business.
     */
    @Test
    void acknowledgesAnUnknownTypeWithoutSendingOrFailing() {
        EventEnvelope<JsonNode> event = envelope("department.created", Map.of("departmentId", 3, "name", "Payroll"));

        assertThatCode(() -> consumer(FRONTEND_URL).handle(event)).doesNotThrowAnyException();
        verifyNoInteractions(mailer);
    }

    /** Dispatch is on the envelope's type, not on whatever the payload happens to look like. */
    @Test
    void ignoresAUserRegisteredShapedPayloadUnderAnotherType() {
        UserRegisteredPayload payload = new UserRegisteredPayload(
                7L,
                "ada@ems.local",
                "ada",
                "3f2504e0-4f89-11d3-9a0c-0305e82c3301",
                Instant.parse("2026-09-03T10:15:30Z"));

        consumer(FRONTEND_URL).handle(envelope("user.updated", payload));

        verifyNoInteractions(mailer);
    }

    private EventConsumer consumer(String frontendUrl) {
        return new EventConsumer(mailer, jsonMapper, frontendUrl);
    }

    private EventEnvelope<JsonNode> envelope(String type, Object payload) {
        return new EventEnvelope<>(
                UUID.randomUUID(), type, Instant.now(), "test-correlation-id", jsonMapper.valueToTree(payload));
    }
}
