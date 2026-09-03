package com.ems.common.outbox;

import com.ems.common.event.EventEnvelope;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.UUID;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Makes {@link IdempotentConsumer} handlers run at most once per event, by recording the
 * event as handled before the handler runs and skipping it if that record already exists.
 *
 * <p>The record and the handler's own work share one transaction. That is the whole
 * guarantee: a handler that throws rolls the record back with everything else it did, so
 * the redelivery gets a real second attempt, while a handler that commits can never be
 * asked to do its work again.
 *
 * <p>The transaction is started here, with {@code REQUIRED}, instead of relying on the
 * handler's own {@code @Transactional}. Advice ordering between two independent aspects is
 * not something a library can settle, and this way it does not matter: whether the
 * handler's transaction advice runs outside this or inside it, both end up in the same
 * physical transaction.
 */
@Aspect
public class IdempotentConsumerAspect {

    private static final Logger log = LoggerFactory.getLogger(IdempotentConsumerAspect.class);

    private final ProcessedEventRepository processedEvents;
    private final TransactionTemplate transactionTemplate;

    public IdempotentConsumerAspect(ProcessedEventRepository processedEvents, TransactionTemplate transactionTemplate) {
        this.processedEvents = processedEvents;
        this.transactionTemplate = transactionTemplate;
    }

    @Around("@annotation(idempotentConsumer)")
    public Object skipIfAlreadyHandled(ProceedingJoinPoint joinPoint, IdempotentConsumer idempotentConsumer) {
        UUID eventId = eventIdOf(joinPoint);
        String consumer = idempotentConsumer.value();

        return transactionTemplate.execute(status -> {
            if (processedEvents.claim(eventId, consumer) == 0) {
                log.debug("Consumer {} has already handled event {}; skipping", consumer, eventId);
                return null;
            }
            try {
                return joinPoint.proceed();
            } catch (RuntimeException | Error ex) {
                throw ex;
            } catch (Throwable ex) {
                // A checked exception from the handler still has to reach the listener
                // container so the delivery is retried and eventually parked.
                throw new UndeclaredThrowableException(ex);
            }
        });
    }

    private UUID eventIdOf(ProceedingJoinPoint joinPoint) {
        for (Object argument : joinPoint.getArgs()) {
            if (argument instanceof EventEnvelope<?> envelope) {
                return envelope.eventId();
            }
        }
        throw new IllegalStateException(
                "@IdempotentConsumer method %s takes no EventEnvelope argument, so there is no event id to deduplicate on"
                        .formatted(joinPoint.getSignature().toShortString()));
    }
}
