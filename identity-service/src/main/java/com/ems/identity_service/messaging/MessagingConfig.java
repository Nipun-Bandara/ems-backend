package com.ems.identity_service.messaging;

import com.ems.common.messaging.QueueFactory;
import java.util.List;
import org.springframework.amqp.core.Declarables;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Claims identity-service's corner of the shared topology: {@code identity.q} and its retry
 * and parked queues, subscribed to the events this service owns. The exchanges, the message
 * converter and the retry behaviour all come from ems-common.
 *
 * <p>Declaring the queues is separate from using them. Nothing publishes to or consumes from
 * them yet; this only makes sure they exist before the first listener needs them.
 */
@Configuration
@ConditionalOnProperty(name = "ems.messaging.enabled", havingValue = "true", matchIfMissing = true)
public class MessagingConfig {

    static final String SERVICE_NAME = "identity";

    @Bean
    public Declarables identityTopology() {
        return QueueFactory.declare(SERVICE_NAME, List.of("user.*", "department.*"));
    }
}
