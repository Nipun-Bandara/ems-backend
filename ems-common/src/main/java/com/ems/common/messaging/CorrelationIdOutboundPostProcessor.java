package com.ems.common.messaging;

import com.ems.common.web.CorrelationIdFilter;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;

/**
 * Carries the correlation id of the request that caused an event onto the message, so the
 * consuming service's logs join up with the caller's. The header is the same
 * {@code X-Correlation-Id} that {@link CorrelationIdFilter} uses over HTTP.
 */
public class CorrelationIdOutboundPostProcessor implements MessagePostProcessor {

    @Override
    public Message postProcessMessage(Message message) {
        String correlationId = MDC.get(CorrelationIdFilter.CORRELATION_ID_MDC_KEY);
        if (correlationId != null && !correlationId.isBlank()) {
            message.getMessageProperties().setHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, correlationId);
        }
        return message;
    }
}
