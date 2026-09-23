package com.ems.identity_service.messaging;

import com.ems.common.event.EventEnvelope;
import com.ems.common.outbox.IdempotentConsumer;
import com.ems.identity_service.event.UserRegisteredPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumes the {@value UserRegisteredPayload#TYPE} events this service itself produces —
 * {@code identity.q} is bound to {@code user.*}, so they come back around.
 *
 * <p>It only logs. It exists as the worked example of the consuming half: the handler is
 * marked {@link IdempotentConsumer}, so a redelivery — from the outbox republishing an
 * event it could not confirm, or from the listener container retrying one that threw —
 * reaches it exactly once.
 */
@Component
@ConditionalOnProperty(name = "ems.messaging.enabled", havingValue = "true", matchIfMissing = true)
public class UserRegisteredListener {

    private static final Logger log = LoggerFactory.getLogger(UserRegisteredListener.class);
    private final JsonMapper jsonMapper;

    public UserRegisteredListener(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @IdempotentConsumer("identity.user-registered")
    @Transactional
    public void onUserRegistered(EventEnvelope<JsonNode> event) {
        UserRegisteredPayload payload = jsonMapper.treeToValue(event.payload(), UserRegisteredPayload.class);
        log.info("Handled {} {} for user {} ({})", event.type(), event.eventId(), payload.userId(), payload.email());
    }
}
