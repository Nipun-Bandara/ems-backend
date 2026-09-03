package com.ems.common.messaging;

import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Turns on the connection factory half of publisher confirms and returns.
 *
 * <p>The callbacks on the {@link RabbitTopologyConfig#rabbitTemplate} bean never fire
 * unless the connection factory is put into confirm mode, and that is driven by properties
 * Boot reads before any bean exists. Defaulting them here keeps the setting with the rest
 * of the topology instead of copied into every service's {@code application.yml}.
 *
 * <p>Added last, so anything a service or a test sets explicitly still wins.
 */
public class MessagingDefaultsEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final String PROPERTY_SOURCE_NAME = "emsMessagingDefaults";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, Object> defaults = Map.of(
                "spring.rabbitmq.publisher-confirm-type", "correlated",
                "spring.rabbitmq.publisher-returns", "true");
        environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, defaults));
    }
}
