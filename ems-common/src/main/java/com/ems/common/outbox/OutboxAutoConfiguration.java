package com.ems.common.outbox;

import jakarta.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import org.aspectj.weaver.Advice;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Gives any service that depends on ems-common and talks to a database a working outbox:
 * a {@link OutboxPublisher} to write events with, a {@link OutboxPoller} to drain them, and
 * {@link IdempotentConsumer} support on the way back in.
 *
 * <p>The service still has to create the two tables — see {@link OutboxSchemaMigration} —
 * but it does not have to wire anything, or know that the entities and repositories live in
 * a package its own component scan does not reach.
 *
 * <p>Backs off entirely for a service with no database — having the JPA classes on the
 * classpath is not the same as having somewhere to put an outbox — and can be turned off
 * outright with {@code ems.outbox.enabled=false}.
 */
@AutoConfiguration(after = DataSourceAutoConfiguration.class, before = DataJpaRepositoriesAutoConfiguration.class)
@ConditionalOnClass({EntityManagerFactory.class, JpaRepository.class})
@ConditionalOnBean(DataSource.class)
@ConditionalOnProperty(name = "ems.outbox.enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(OutboxProperties.class)
@Import(OutboxAutoConfiguration.OutboxPackageRegistrar.class)
public class OutboxAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public OutboxPublisher outboxPublisher(OutboxRepository repository, ObjectProvider<JsonMapper> jsonMapper) {
        return new OutboxPublisher(
                repository, jsonMapper.getIfAvailable(() -> JsonMapper.builder().build()));
    }

    /**
     * The publishing half, which is the only part that needs a broker. A service that only
     * consumes still gets the rest.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(RabbitTemplate.class)
    @EnableScheduling
    static class PollingConfiguration {

        @Bean
        @ConditionalOnMissingBean
        OutboxPoller outboxPoller(
                OutboxRepository repository,
                RabbitTemplate rabbitTemplate,
                ObjectProvider<JsonMapper> jsonMapper,
                OutboxProperties properties) {
            return new OutboxPoller(
                    repository,
                    rabbitTemplate,
                    jsonMapper.getIfAvailable(() -> JsonMapper.builder().build()),
                    properties);
        }
    }

    /** The consuming half. Needs AspectJ on the classpath, which the aspect is weaved by. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(Advice.class)
    static class IdempotencyConfiguration {

        @Bean
        @ConditionalOnMissingBean
        IdempotentConsumerAspect idempotentConsumerAspect(
                ProcessedEventRepository processedEvents, PlatformTransactionManager transactionManager) {
            return new IdempotentConsumerAspect(processedEvents, new TransactionTemplate(transactionManager));
        }
    }

    /**
     * Adds this package to the ones Boot scans for entities and repositories, so that
     * {@link OutboxEvent} and friends are picked up by a service whose own scanning starts
     * at {@code com.ems.<service>}.
     *
     * <p>Registered rather than declared with {@code @EntityScan} and
     * {@code @EnableJpaRepositories} on purpose: both of those replace the packages Boot
     * derived from the application class instead of adding to them, so a service would
     * silently lose its own entities. Registering here appends, and has to happen before
     * {@link DataJpaRepositoriesAutoConfiguration} reads the list — hence the ordering on
     * the enclosing class.
     */
    static class OutboxPackageRegistrar implements ImportBeanDefinitionRegistrar {

        @Override
        public void registerBeanDefinitions(AnnotationMetadata metadata, BeanDefinitionRegistry registry) {
            AutoConfigurationPackages.register(registry, OutboxEvent.class.getPackageName());
        }
    }
}
