package com.ems.notification_service.messaging;

import com.ems.common.messaging.QueueFactory;
import java.util.List;
import org.springframework.amqp.core.Declarables;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Claims notification-service's corner of the shared topology: {@code notification.q} and its
 * retry and parked queues. The exchanges, the message converter and the retry behaviour all
 * come from ems-common.
 *
 * <p>The binding is {@code #}, not a list of the events this service currently reacts to.
 * A notification service is subscribed to the whole system by nature — the next thing anyone
 * wants an email about is an event it is not yet bound to — and the audit log is only an audit
 * log if nothing was filtered out before it. The cost is that {@link EventConsumer} sees every
 * event in EMS and recognises a handful, which is why an unrecognised type is a normal outcome
 * there rather than an error.
 *
 * <p>{@code #} also matches the {@code <service>.retry} keys the delay queues re-publish under,
 * so another service's failed delivery passes through here on its way back to its own queue.
 * That is harmless: it carries the same envelope, and therefore the same event id, that this
 * service has already recorded as handled.
 */
@Configuration
@ConditionalOnProperty(name = "ems.messaging.enabled", havingValue = "true", matchIfMissing = true)
public class MessagingConfig {

    static final String SERVICE_NAME = "notification";

    /**
     * Queue this service consumes from. Spelled out rather than read from
     * {@link QueueFactory#workQueue(String)} because a {@code @RabbitListener} needs a
     * compile-time constant; if the two ever disagree the listener fails at startup on a
     * queue that does not exist.
     */
    static final String WORK_QUEUE = SERVICE_NAME + ".q";

    @Bean
    public Declarables notificationTopology() {
        return QueueFactory.declare(SERVICE_NAME, List.of("#"));
    }
}
