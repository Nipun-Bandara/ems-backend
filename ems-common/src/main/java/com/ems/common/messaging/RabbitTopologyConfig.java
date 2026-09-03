package com.ems.common.messaging;

import org.aopalliance.intercept.MethodInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.amqp.rabbit.retry.RepublishMessageRecoverer;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration;
import org.springframework.boot.amqp.autoconfigure.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.expression.Expression;
import org.springframework.expression.common.LiteralExpression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import tools.jackson.databind.json.JsonMapper;

/**
 * Declares the broker topology every EMS service shares, so that no service invents its
 * own exchange names or its own idea of what happens to a message that cannot be handled.
 *
 * <p>Two exchanges are declared here: {@value #EVENTS_EXCHANGE}, which every domain event
 * is published to, and {@value #DEAD_LETTER_EXCHANGE}, which carries the failure paths.
 * The per-service queues hanging off them come from {@link QueueFactory}.
 *
 * <p>Registered before {@link RabbitAutoConfiguration} so that the {@link RabbitTemplate}
 * and listener container factory defined here win over Boot's defaults, which back off on
 * {@code @ConditionalOnMissingBean}.
 */
@AutoConfiguration(before = RabbitAutoConfiguration.class)
@ConditionalOnClass(RabbitTemplate.class)
@ConditionalOnProperty(name = "ems.messaging.enabled", havingValue = "true", matchIfMissing = true)
public class RabbitTopologyConfig {

    /** Topic exchange every domain event is published to. */
    public static final String EVENTS_EXCHANGE = "ems.events";

    /** Topic exchange carrying the retry and parking paths for failed deliveries. */
    public static final String DEAD_LETTER_EXCHANGE = "ems.dlx";

    /**
     * Total deliveries to a listener before the message is parked — the first attempt plus
     * two retries.
     */
    public static final int MAX_ATTEMPTS = 3;

    private static final long RETRY_INITIAL_INTERVAL_MS = 1_000L;
    private static final double RETRY_MULTIPLIER = 2.0;
    private static final long RETRY_MAX_INTERVAL_MS = 10_000L;

    private static final Logger log = LoggerFactory.getLogger(RabbitTopologyConfig.class);

    @Bean
    public TopicExchange emsEventsExchange() {
        return ExchangeBuilder.topicExchange(EVENTS_EXCHANGE).durable(true).build();
    }

    @Bean
    public TopicExchange emsDeadLetterExchange() {
        return ExchangeBuilder.topicExchange(DEAD_LETTER_EXCHANGE).durable(true).build();
    }

    /**
     * Jackson 3 converter, matching the {@code JsonMapper} Spring Boot 4 auto-configures.
     * Falls back to a converter with its own mapper for services that do not pull in the
     * JSON auto-configuration.
     */
    @Bean
    @ConditionalOnMissingBean(MessageConverter.class)
    public MessageConverter emsMessageConverter(ObjectProvider<JsonMapper> jsonMapper) {
        JsonMapper mapper = jsonMapper.getIfAvailable();
        return mapper != null ? new JacksonJsonMessageConverter(mapper) : new JacksonJsonMessageConverter();
    }

    @Bean
    public TopologyDeclarer emsTopologyDeclarer(ConnectionFactory connectionFactory) {
        return new TopologyDeclarer(connectionFactory);
    }

    @Bean
    public CorrelationIdOutboundPostProcessor correlationIdOutboundPostProcessor() {
        return new CorrelationIdOutboundPostProcessor();
    }

    @Bean
    public CorrelationIdInboundPostProcessor correlationIdInboundPostProcessor() {
        return new CorrelationIdInboundPostProcessor();
    }

    /**
     * Publisher confirms and returns are only useful if something looks at them, so both
     * callbacks log. {@code mandatory} is what makes the broker return an unroutable
     * message instead of dropping it silently — an event published to a pattern nothing is
     * bound to is a wiring bug, and this is what makes it visible.
     */
    @Bean
    public RabbitTemplate rabbitTemplate(
            ConnectionFactory connectionFactory,
            MessageConverter messageConverter,
            CorrelationIdOutboundPostProcessor correlationIdOutbound) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(messageConverter);
        template.setBeforePublishPostProcessors(correlationIdOutbound);
        template.setMandatory(true);
        template.setConfirmCallback((correlationData, ack, cause) -> {
            if (!ack) {
                log.error("Broker did not confirm publish of {}: {}", correlationData, cause);
            }
        });
        template.setReturnsCallback(returned -> log.error(
                "Event returned as unroutable: exchange={} routingKey={} reply={} ({})",
                returned.getExchange(),
                returned.getRoutingKey(),
                returned.getReplyCode(),
                returned.getReplyText()));
        return template;
    }

    /**
     * Retries a failed delivery in process, then hands the message to the service's
     * {@code .parked} queue rather than letting it be redelivered forever.
     *
     * <p>Stateless on purpose: the message stays unacknowledged with the consumer for the
     * whole of {@value #MAX_ATTEMPTS} attempts, so the retries never touch the broker and
     * the count cannot be lost to a requeue.
     */
    @Bean
    public MethodInterceptor emsRetryInterceptor(RabbitTemplate rabbitTemplate) {
        return RetryInterceptorBuilder.stateless()
                // total attempts = 1 initial + maxRetries
                .maxRetries(MAX_ATTEMPTS - 1)
                .backOffOptions(RETRY_INITIAL_INTERVAL_MS, RETRY_MULTIPLIER, RETRY_MAX_INTERVAL_MS)
                .recoverer(parkingRecoverer(rabbitTemplate))
                .build();
    }

    /**
     * Republishes an exhausted message to the {@code .parked} queue of whichever service is
     * consuming it, keeping the {@code x-exception-*} and {@code x-original-*} headers that
     * say why it failed.
     *
     * <p>The destination is resolved per message from the queue it was consumed from, so this
     * one bean serves every service and every queue in it without being told its own name.
     */
    private MessageRecoverer parkingRecoverer(RabbitTemplate rabbitTemplate) {
        Expression routingKey = new SpelExpressionParser()
                .parseExpression("T(%s).parkedRoutingKeyFor(messageProperties.consumerQueue)"
                        .formatted(QueueFactory.class.getName()));
        return new RepublishMessageRecoverer(rabbitTemplate, new LiteralExpression(DEAD_LETTER_EXCHANGE), routingKey);
    }

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory,
            MessageConverter messageConverter,
            CorrelationIdInboundPostProcessor correlationIdInbound,
            MethodInterceptor emsRetryInterceptor) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setMessageConverter(messageConverter);
        factory.setAfterReceivePostProcessors(correlationIdInbound);
        factory.setAdviceChain(emsRetryInterceptor);
        // Anything the interceptor does not recover from goes to the dead letter exchange
        // instead of straight back onto the queue it just failed on.
        factory.setDefaultRequeueRejected(false);
        return factory;
    }
}
