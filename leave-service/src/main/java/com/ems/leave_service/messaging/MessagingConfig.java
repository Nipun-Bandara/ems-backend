package com.ems.leave_service.messaging;

import com.ems.common.messaging.QueueFactory;
import java.util.List;
import org.springframework.amqp.core.Declarables;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "ems.messaging.enabled", havingValue = "true", matchIfMissing = true)
public class MessagingConfig {
    public static final String WORK_QUEUE = "leave.q";

    @Bean
    public Declarables leaveTopology() {
        return QueueFactory.declare(
                "leave", List.of("user.verified", "user.assignment.updated", "employee.profile.updated"));
    }
}
