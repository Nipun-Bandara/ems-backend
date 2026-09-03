package com.ems.common.outbox;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a listener method that must run at most once per event, however many times the
 * broker delivers it.
 *
 * <p>Delivery is at-least-once: the outbox republishes anything it is not certain the
 * broker took, and the listener container redelivers anything a handler threw on. Both are
 * the right behaviour, and both mean a handler will see the same event twice sooner or
 * later.
 *
 * <pre>{@code
 * @RabbitListener(queues = "identity.q")
 * @IdempotentConsumer("identity.user-registered")
 * public void on(EventEnvelope<UserRegistered> event) { ... }
 * }</pre>
 *
 * <p>The method must take an {@link com.ems.common.event.EventEnvelope} argument — that is
 * where the event id comes from — and its work must be transactional for the guarantee to
 * hold, since {@link IdempotentConsumerAspect} commits the "handled" record with it.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface IdempotentConsumer {

    /**
     * Name this handler is recorded under, unique within the service. Two handlers of the
     * same event need two names, or the first to run will look to the second like a
     * duplicate.
     */
    String value();
}
