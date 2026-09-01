package com.ems.common.event;

import com.ems.common.web.CorrelationIdFilter;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.MDC;

/**
 * Wrapper every EMS domain event is published in, so consumers get identity, ordering and
 * request correlation without each payload having to carry them.
 */
public record EventEnvelope<T>(UUID eventId, String type, Instant occurredAt, String correlationId, T payload) {

    /**
     * Builds an envelope for the current request, picking up the correlation id that
     * {@link CorrelationIdFilter} put in the MDC.
     */
    public static <T> EventEnvelope<T> of(String type, T payload) {
        return new EventEnvelope<>(
                UUID.randomUUID(), type, Instant.now(), MDC.get(CorrelationIdFilter.CORRELATION_ID_MDC_KEY), payload);
    }
}
