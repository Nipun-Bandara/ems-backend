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

@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@ActiveProfiles("test")
@Testcontainers
class EmployeeProfileIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-management");

    @Container
    static final org.testcontainers.containers.GenericContainer<?> MINIO =
            new org.testcontainers.containers.GenericContainer<>("minio/minio:latest")
                    .withEnv("MINIO_ROOT_USER", "ems_minio")
                    .withEnv("MINIO_ROOT_PASSWORD", "ems_local_dev")
                    .withCommand("server", "/data")
                    .withExposedPorts(9000)
                    .waitingFor(org.testcontainers.containers.wait.strategy.Wait.forHttp("/minio/health/ready")
                            .forPort(9000));

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry properties) {
        properties.add("ems.storage.endpoint", () -> "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000));
        properties.add(
                "ems.storage.public-endpoint", () -> "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000));
        properties.add("ems.storage.access-key", () -> "ems_minio");
        properties.add("ems.storage.secret-key", () -> "ems_local_dev");
        properties.add("ems.storage.path-style-access", () -> "true");
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

    @Autowired
    org.springframework.test.web.servlet.MockMvc mvc;

    @Autowired
    com.ems.common.storage.StorageService storage;

    @Test
    void avatarUploadsDirectlyToMinioThenProfileReturnsDownloadUrl() throws Exception {
        handler.onUserVerified(verified(UUID.randomUUID(), "ada@ems.local"));
        byte[] image = java.util.Base64.getDecoder()
                .decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=");
        String body = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                                "/api/employees/42/avatar/upload-url")
                        .header("X-User-Id", "42")
                        .contentType("application/json")
                        .content("{\"contentType\":\"image/png\",\"size\":" + image.length + "}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                        .isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        var upload = json.readTree(body);
        String key = upload.get("objectKey").asText();
        try {
            var http = java.net.http.HttpClient.newHttpClient();
            var put = java.net.http.HttpRequest.newBuilder(
                            java.net.URI.create(upload.get("uploadUrl").asText()))
                    .header("Content-Type", "image/png")
                    .header("If-None-Match", "*")
                    .PUT(java.net.http.HttpRequest.BodyPublishers.ofByteArray(image))
                    .build();
            assertThat(http.send(put, java.net.http.HttpResponse.BodyHandlers.discarding())
                            .statusCode())
                    .isEqualTo(200);
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                                    "/api/employees/42/avatar")
                            .header("X-User-Id", "42")
                            .contentType("application/json")
                            .content(json.writeValueAsString(Map.of("objectKey", key))))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                            .isOk());
            String profile = mvc.perform(
                            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/employees/42")
                                    .header("X-User-Id", "42"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                            .isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            var result = json.readTree(profile);
            assertThat(result.get("avatarObjectKey").asText()).isEqualTo(key);
            var get = java.net.http.HttpRequest.newBuilder(
                            java.net.URI.create(result.get("avatarUrl").asText()))
                    .GET()
                    .build();
            var downloaded = http.send(get, java.net.http.HttpResponse.BodyHandlers.ofByteArray());
            assertThat(downloaded.statusCode()).isEqualTo(200);
            assertThat(downloaded.body()).isEqualTo(image);
            assertThat(profiles.findById(42L).orElseThrow().getAvatarObjectKey())
                    .isEqualTo(key);
        } finally {
            storage.delete("ems-avatars", key);
        }
    }

    private static final Instant VERIFIED_AT = Instant.parse("2026-10-01T10:00:00Z");

    @BeforeEach
    void clearDatabase() {
        jdbc.update("DELETE FROM processed_event");
        profiles.deleteAll();
        jdbc.update("DELETE FROM employee_assignment");
        jdbc.update("DELETE FROM outbox_event");
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

    @Test
    void completesProfilePersistsAndPublishesWhileOtherEmployeeIsForbidden() throws Exception {
        handler.onUserVerified(verified(UUID.randomUUID(), "ada@ems.local"));
        var body = """
                {"firstName":"Ada","lastName":"Lovelace","phone":"+94771234567",
                 "dateOfBirth":"1990-12-10","address":"Colombo","emergencyContactName":"Grace",
                 "emergencyContactPhone":"+94771234568","designationId":3,"joinDate":"2020-01-01",
                 "reportingManagerUserId":99}
                """;
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/employees/me")
                        .header("X-User-Id", "42")
                        .contentType("application/json")
                        .content(body))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                        .isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/employees/me")
                        .header("X-User-Id", "42"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.firstName")
                        .value("Ada"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.address")
                        .value("Colombo"));
        assertThat(profiles.findById(42L).orElseThrow().getReportingManagerUserId())
                .isEqualTo(99L);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM outbox_event WHERE type = 'employee.profile.updated'", Integer.class))
                .isEqualTo(1);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/employees/42")
                        .header("X-User-Id", "43")
                        .header("X-User-Roles", "EMPLOYEE"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                        .isForbidden());
    }

    @Test
    void assignmentBeforeVerificationAndStaleEventsAreHandled() {
        assignment(7L, VERIFIED_AT);
        handler.onUserVerified(verified(UUID.randomUUID(), "ada@ems.local"));
        assertThat(profiles.findById(42L).orElseThrow().getDepartmentId()).isEqualTo(7L);
        assignment(8L, VERIFIED_AT.plusSeconds(10));
        assignment(9L, VERIFIED_AT.plusSeconds(5));
        assertThat(profiles.findById(42L).orElseThrow().getDepartmentId()).isEqualTo(8L);
        assignment(null, VERIFIED_AT.plusSeconds(20));
        assertThat(profiles.findById(42L).orElseThrow().getDepartmentId()).isNull();
    }

    private void assignment(Long departmentId, Instant at) {
        handler.onAssignmentUpdated(new EventEnvelope<>(
                UUID.randomUUID(),
                "user.assignment.updated",
                at,
                null,
                json.valueToTree(
                        new com.ems.employee_service.event.UserAssignmentUpdatedPayload(42L, departmentId, at))));
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
