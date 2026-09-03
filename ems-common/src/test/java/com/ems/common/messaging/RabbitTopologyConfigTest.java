package com.ems.common.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

class RabbitTopologyConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RabbitAutoConfiguration.class, RabbitTopologyConfig.class));

    @Test
    void declaresTheSharedTopologyByDefault() {
        runner.run(context -> {
            assertThat(context.getBean("emsEventsExchange", TopicExchange.class).getName())
                    .isEqualTo(RabbitTopologyConfig.EVENTS_EXCHANGE);
            assertThat(context.getBean("emsDeadLetterExchange", TopicExchange.class)
                            .getName())
                    .isEqualTo(RabbitTopologyConfig.DEAD_LETTER_EXCHANGE);
            assertThat(context)
                    .hasSingleBean(SimpleRabbitListenerContainerFactory.class)
                    .hasSingleBean(CorrelationIdOutboundPostProcessor.class)
                    .hasSingleBean(CorrelationIdInboundPostProcessor.class);
        });
    }

    @Test
    void suppliesTheOneRabbitTemplate() {
        runner.run(context -> {
            assertThat(context)
                    .as("Boot's own template should back off in favour of this one")
                    .hasSingleBean(RabbitTemplate.class);
            assertThat(context.getBean(RabbitTemplate.class).isMandatoryFor(null))
                    .as("mandatory must be on or the returns callback never fires")
                    .isTrue();
        });
    }

    @Test
    void backsOffWhenMessagingIsDisabled() {
        // Boot's own Rabbit beans stay — the flag switches off the EMS topology, and a
        // connection is only opened when something actually publishes or consumes.
        runner.withPropertyValues("ems.messaging.enabled=false")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(TopicExchange.class)
                        .doesNotHaveBean(CorrelationIdOutboundPostProcessor.class)
                        .doesNotHaveBean(CorrelationIdInboundPostProcessor.class));
    }

    @Test
    void defaultsPublisherConfirmsAndReturnsOn() {
        MockEnvironment environment = new MockEnvironment();

        new MessagingDefaultsEnvironmentPostProcessor().postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("spring.rabbitmq.publisher-confirm-type"))
                .isEqualTo("correlated");
        assertThat(environment.getProperty("spring.rabbitmq.publisher-returns")).isEqualTo("true");
    }
}
