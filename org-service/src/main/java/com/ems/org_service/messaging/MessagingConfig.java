package com.ems.org_service.messaging;

import com.ems.common.messaging.QueueFactory;
import java.util.List;
import org.springframework.amqp.core.Declarables;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "ems.messaging.enabled", havingValue = "true", matchIfMissing = true)
public class MessagingConfig {

    static final String SERVICE_NAME = "org";

    @Bean
    public Declarables orgTopology() {
        return QueueFactory.declare(SERVICE_NAME, List.of());
    }
}
