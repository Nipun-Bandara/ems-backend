package com.ems.notification_service.messaging;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.ems.common.event.EventEnvelope;
import com.ems.notification_service.entity.NotificationTemplate;
import com.ems.notification_service.event.UserRegisteredPayload;
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
 * is which events produce mail and which are simply acknowledged, not what the mail says.
 */
@ExtendWith(MockitoExtension.class)
class EventConsumerTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Mock
    private TemplatedMailer mailer;

    @Test
    void sendsTheWelcomeTemplateOnUserRegistered() {
        UserRegisteredPayload payload =
                new UserRegisteredPayload(7L, "ada@ems.local", "ada", Instant.parse("2026-09-03T10:15:30Z"));

        consumer().handle(envelope(UserRegisteredPayload.TYPE, payload));

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

        assertThatCode(() -> consumer().handle(event)).doesNotThrowAnyException();
        verifyNoInteractions(mailer);
    }

    /** Dispatch is on the envelope's type, not on whatever the payload happens to look like. */
    @Test
    void ignoresAUserRegisteredShapedPayloadUnderAnotherType() {
        UserRegisteredPayload payload =
                new UserRegisteredPayload(7L, "ada@ems.local", "ada", Instant.parse("2026-09-03T10:15:30Z"));

        consumer().handle(envelope("user.updated", payload));

        verifyNoInteractions(mailer);
    }

    private EventConsumer consumer() {
        return new EventConsumer(mailer, jsonMapper);
    }

    private EventEnvelope<JsonNode> envelope(String type, Object payload) {
        return new EventEnvelope<>(
                UUID.randomUUID(), type, Instant.now(), "test-correlation-id", jsonMapper.valueToTree(payload));
    }
}
