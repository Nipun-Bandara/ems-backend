package com.ems.employee_service.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.ems.common.event.EventEnvelope;
import com.ems.common.messaging.RabbitTopologyConfig;
import com.ems.employee_service.event.UserVerifiedPayload;
import com.ems.employee_service.repository.EmployeeProfileRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@ActiveProfiles("test")
@Testcontainers
class EmployeeProfileIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-management");

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("spring.rabbitmq.host", RABBIT::getHost);
        properties.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        properties.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        properties.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
    }

    @Autowired
    RabbitTemplate rabbit;

    @Autowired
    EmployeeProfileRepository profiles;

    @Autowired
    EmployeeProfileHandler handler;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JsonMapper json;

    private static final Instant VERIFIED_AT = Instant.parse("2026-10-01T10:00:00Z");

    @BeforeEach
    void clearDatabase() {
        jdbc.update("DELETE FROM processed_event");
        profiles.deleteAll();
    }

    @Test
    void brokerDeliveryCreatesProfileAndDeduplicatesWithoutOverwritingIt() {
        var event = verified(UUID.randomUUID(), "ada@ems.local");
        publish(event, "user.verified");
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            var profile = profiles.findById(42L).orElseThrow();
            assertThat(profile.getEmail()).isEqualTo("ada@ems.local");
            assertThat(profile.getUsername()).isEqualTo("ada");
            assertThat(profile.getStatus()).isEqualTo("ACTIVE");
            assertThat(profile.getCreatedAt()).isEqualTo(VERIFIED_AT);
            assertThat(claims()).isEqualTo(1);
        });

        // A duplicate with changed data proves the aspect skips the handler entirely.
        // A fresh ID for the same user proves profile creation is also safe on replay.
        publish(verified(event.eventId(), "duplicate@ems.local"), "user.verified");
        publish(verified(UUID.randomUUID(), "replay@ems.local"), "user.verified");
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(claims()).isEqualTo(2);
            assertThat(profiles.count()).isEqualTo(1);
            assertThat(profiles.findById(42L).orElseThrow().getEmail()).isEqualTo("ada@ems.local");
        });
    }

    @Test
    void registrationAndUnknownEventsDoNotCreateProfiles() {
        var router = new EmployeeEventRouter(handler);
        router.onEvent(new EventEnvelope<>(
                UUID.randomUUID(),
                "user.registered",
                VERIFIED_AT,
                null,
                json.valueToTree(Map.of("userId", 42L, "verificationToken", "unused"))));
        router.onEvent(new EventEnvelope<>(UUID.randomUUID(), "other.event", VERIFIED_AT, null, json.nullNode()));
        assertThat(profiles.count()).isZero();
        assertThat(claims()).isZero();
    }

    @Test
    void failureRollsBackIdempotencyClaimSoCorrectedDeliveryCanSucceed() {
        var id = UUID.randomUUID();
        var malformed = new EventEnvelope<JsonNode>(
                id, "user.verified", VERIFIED_AT, null, json.valueToTree(Map.of("userId", 42L)));
        assertThatThrownBy(() -> handler.onUserVerified(malformed)).isInstanceOf(RuntimeException.class);
        assertThat(claims()).isZero();
        assertThat(profiles.count()).isZero();

        handler.onUserVerified(verified(id, "ada@ems.local"));
        assertThat(claims()).isEqualTo(1);
        assertThat(profiles.count()).isEqualTo(1);
    }

    @Test
    void retryRoutingUsesEnvelopeType() {
        publish(verified(UUID.randomUUID(), "ada@ems.local"), "employee.retry");
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(profiles.count()).isEqualTo(1));
    }

    private EventEnvelope<JsonNode> verified(UUID id, String email) {
        return new EventEnvelope<>(
                id,
                "user.verified",
                VERIFIED_AT,
                "employee-integration",
                json.valueToTree(new UserVerifiedPayload(42L, email, "ada", VERIFIED_AT)));
    }

    private void publish(EventEnvelope<JsonNode> event, String routingKey) {
        rabbit.convertAndSend(RabbitTopologyConfig.EVENTS_EXCHANGE, routingKey, event);
    }

    private int claims() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM processed_event WHERE consumer = 'employee.user-verified'", Integer.class);
    }
}
