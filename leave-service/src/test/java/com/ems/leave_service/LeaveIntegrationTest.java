package com.ems.leave_service;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ems.common.event.EventEnvelope;
import com.ems.leave_service.projection.EmployeeProjectionHandler;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=true"})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Testcontainers
class LeaveIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17").withDatabaseName("leave_db");

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
    JdbcTemplate jdbc;

    @Autowired
    EmployeeProjectionHandler handler;

    @Autowired
    JsonMapper json;

    @Autowired
    RabbitTemplate rabbit;

    @Autowired
    AmqpAdmin admin;

    @Autowired
    MockMvc mvc;

    @LocalServerPort
    int port;

    private static final Instant AT = Instant.parse("2026-01-01T00:00:00Z");

    private EventEnvelope<JsonNode> event(String type, Instant at, Map<String, Object> payload) {
        return new EventEnvelope<>(UUID.randomUUID(), type, at, "leave-test", json.valueToTree(payload));
    }

    private EventEnvelope<JsonNode> assignment(long userId, Long department, Instant at) {
        var payload = new HashMap<String, Object>();
        payload.put("userId", userId);
        payload.put("departmentId", department);
        payload.put("assignedAt", at);
        return event("user.assignment.updated", at, payload);
    }

    @Test
    void freshDatabaseMigratesUsingSharedOutboxSchema() {
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class))
                .isEqualTo("leave_db");
        assertThat(jdbc.queryForList(
                        "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank",
                        String.class))
                .containsExactly("1", "2");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event", Integer.class))
                .isZero();
        assertThat(admin.getQueueProperties("leave.q")).isNotNull();
        assertThat(admin.getQueueProperties("leave.retry")).isNotNull();
        assertThat(admin.getQueueProperties("leave.parked")).isNotNull();
    }

    @Test
    void brokerDeliveryDeduplicatesAndDispatchesRetriesByEnvelope() {
        var event = assignment(42L, 7L, AT);
        rabbit.convertAndSend("ems.events", event.type(), event);
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(row(42L).get("department_id")).isEqualTo(7L));
        // Same ID but changed payload proves shared claim skips all handler work.
        var duplicate = new EventEnvelope<>(
                event.eventId(),
                event.type(),
                AT.plusSeconds(10),
                null,
                assignment(42L, 8L, AT.plusSeconds(10)).payload());
        rabbit.convertAndSend("ems.events", "leave.retry", duplicate);
        var stale = assignment(42L, 6L, AT.minusSeconds(10));
        rabbit.convertAndSend("ems.events", "leave.retry", stale);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM processed_event WHERE consumer='leave.employee-projection' AND event_id IN (?, ?)",
                            Integer.class,
                            event.eventId(),
                            stale.eventId()))
                    .isEqualTo(2);
            assertThat(row(42L).get("department_id")).isEqualTo(7L);
        });
    }

    @Test
    void independentStreamsCannotOverwriteNewerStateAndNullableValuesClear() {
        handler.apply(assignment(43L, 8L, AT.plusSeconds(20)));
        handler.apply(event(
                "employee.profile.updated",
                AT.plusSeconds(10),
                Map.of("userId", 43L, "departmentId", 2L, "reportingManagerUserId", 99L)));
        handler.apply(event("user.verified", AT, Map.of("userId", 43L, "verifiedAt", AT)));
        handler.apply(event(
                "employee.profile.updated", AT.plusSeconds(5), Map.of("userId", 43L, "reportingManagerUserId", 100L)));
        handler.apply(assignment(43L, 1L, AT.plusSeconds(15)));
        assertThat(row(43L))
                .containsEntry("department_id", 8L)
                .containsEntry("reporting_manager_user_id", 99L)
                .containsEntry("active", true);
        handler.apply(assignment(43L, null, AT.plusSeconds(30)));
        var payload = new HashMap<String, Object>();
        payload.put("userId", 43L);
        payload.put("reportingManagerUserId", null);
        handler.apply(event("employee.profile.updated", AT.plusSeconds(30), payload));
        assertThat(row(43L).get("department_id")).isNull();
        assertThat(row(43L).get("reporting_manager_user_id")).isNull();
    }

    @Test
    void malformedEventRollsBackClaimAndProjectionThenSameIdCanRetry() {
        var invalid = event("user.assignment.updated", AT, Map.of("userId", 44L));
        assertThatThrownBy(() -> handler.apply(invalid)).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM employee_projection WHERE user_id=44", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM processed_event WHERE event_id=?", Integer.class, invalid.eventId()))
                .isZero();
        handler.apply(new EventEnvelope<>(
                invalid.eventId(),
                invalid.type(),
                AT,
                null,
                assignment(44L, 1L, AT).payload()));
        assertThat(row(44L).get("department_id")).isEqualTo(1L);
    }

    @Test
    void adminCanBootstrapExistingEmployeeIdempotentlyWhileOtherRolesCannot() throws Exception {
        String path = "/api/leaves/admin/employees/45";
        String snapshot = """
                {"active":false,"departmentId":5,"reportingManagerUserId":90,"asOf":"2026-01-01T00:00:10Z"}
                """;
        mvc.perform(put(path).contentType("application/json").content(snapshot)).andExpect(status().isUnauthorized());
        for (String role : List.of("EMPLOYEE", "HR_MANAGER", "DEPARTMENT_HEAD", "MANAGER"))
            mvc.perform(put(path)
                            .header("X-User-Id", "1")
                            .header("X-User-Roles", role)
                            .contentType("application/json")
                            .content(snapshot))
                    .andExpect(status().isForbidden());
        for (int i = 0; i < 2; i++)
            mvc.perform(put(path)
                            .header("X-User-Id", "1")
                            .header("X-User-Roles", "SYSTEM_ADMIN")
                            .contentType("application/json")
                            .content(snapshot))
                    .andExpect(status().isNoContent());
        handler.apply(event("user.verified", AT, Map.of("userId", 45L, "verifiedAt", AT)));
        handler.apply(assignment(45L, 1L, AT));
        assertThat(row(45L))
                .containsEntry("active", false)
                .containsEntry("department_id", 5L)
                .containsEntry("reporting_manager_user_id", 90L);
        handler.apply(assignment(45L, 6L, AT.plusSeconds(20)));
        mvc.perform(put(path)
                        .header("X-User-Id", "1")
                        .header("X-User-Roles", "SYSTEM_ADMIN")
                        .contentType("application/json")
                        .content(snapshot))
                .andExpect(status().isNoContent());
        assertThat(row(45L).get("department_id")).isEqualTo(6L);
        mvc.perform(put(path)
                        .header("X-User-Id", "1")
                        .header("X-User-Roles", "SYSTEM_ADMIN")
                        .contentType("application/json")
                        .content(snapshot.replace("90", "45")))
                .andExpect(status().isBadRequest());
        mvc.perform(put(path)
                        .header("X-User-Id", "1")
                        .header("X-User-Roles", "SYSTEM_ADMIN")
                        .contentType("application/json")
                        .content(snapshot.replace("2026-01-01", "2099-01-01")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void realGatewayAuthenticatesAndRoutesInEveryEnvironment() throws Exception {
        GatewayProbe.verify(port);
    }

    @Test
    void concurrentStaleAndNewAssignmentsConvergeAndUnknownStatusFailsClosed() throws Exception {
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 12; i++) {
                int offset = i;
                tasks.add(executor.submit(
                        () -> handler.apply(assignment(46L, (long) offset + 1, AT.plusSeconds(offset)))));
            }
            for (var task : tasks) task.get(10, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertThat(row(46L)).containsEntry("department_id", 12L).containsEntry("active", null);
        handler.apply(assignment(46L, 99L, AT.plusSeconds(11)));
        assertThat(row(46L).get("department_id")).isEqualTo(12L);
    }

    private Map<String, Object> row(long id) {
        return jdbc.queryForMap("SELECT * FROM employee_projection WHERE user_id=?", id);
    }
}
