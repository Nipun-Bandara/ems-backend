package com.ems.employee_service.messaging;

import com.ems.common.messaging.QueueFactory;
import com.ems.employee_service.event.UserVerifiedPayload;
import java.util.List;
import org.springframework.amqp.core.Declarables;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "ems.messaging.enabled", havingValue = "true", matchIfMissing = true)
public class MessagingConfig {

    static final String SERVICE_NAME = "employee";
    public static final String WORK_QUEUE = "employee.q";
    static final String USER_REGISTERED = "user.registered";

    @Bean
    public Declarables employeeTopology() {
        return QueueFactory.declare(SERVICE_NAME, List.of(UserVerifiedPayload.TYPE, USER_REGISTERED));
    }
}
