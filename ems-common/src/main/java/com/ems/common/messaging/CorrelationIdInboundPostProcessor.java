package com.ems.common.messaging;

import com.ems.common.web.CorrelationIdFilter;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;

/**
 * Puts the correlation id a message arrived with back into the MDC, so a listener logs
 * under the same id as the request that published the event.
 *
 * <p>Runs on the consumer thread before the listener, which is reused across deliveries.
 * A message without the header therefore has to clear the key rather than leave it alone,
 * or it would be logged under the previous message's id.
 */
public class CorrelationIdInboundPostProcessor implements MessagePostProcessor {

    @Override
    public Message postProcessMessage(Message message) {
        Object correlationId = message.getMessageProperties().getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER);
        if (correlationId != null && !correlationId.toString().isBlank()) {
            MDC.put(CorrelationIdFilter.CORRELATION_ID_MDC_KEY, correlationId.toString());
        } else {
            MDC.remove(CorrelationIdFilter.CORRELATION_ID_MDC_KEY);
        }
        return message;
    }
}
