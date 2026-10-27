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
                .containsExactly("1", "2", "3", "4");
        assertThat(jdbc.queryForObject("SELECT to_regclass('outbox_event')::text", String.class))
                .isEqualTo("outbox_event");
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

    @org.junit.jupiter.api.BeforeEach
    void bindLeaveAuditEvents() {
        admin.declareQueue(new org.springframework.amqp.core.Queue("leave.balance-test", true, false, false));
        admin.declareBinding(new org.springframework.amqp.core.Binding(
                "leave.balance-test",
                org.springframework.amqp.core.Binding.DestinationType.QUEUE,
                "ems.events",
                "leave.#",
                null));
    }

    @Autowired
    com.ems.leave_service.balance.LeaveBalanceService balances;

    @Autowired
    com.ems.leave_service.balance.WorkingCalendar calendar;

    private UUID entitlement(long employee, String days) {
        jdbc.update("INSERT INTO employee_projection(user_id,active) VALUES (?,true) ON CONFLICT DO NOTHING", employee);
        var type = balances.saveType(
                null,
                new com.ems.leave_service.balance.LeaveModels.TypeInput("T" + employee, "Annual " + employee, true));
        return balances.allocate(
                        new com.ems.leave_service.balance.LeaveModels.Allocation(
                                employee, type.id(), 2026, new java.math.BigDecimal(days), "Annual allocation"),
                        1)
                .id();
    }

    @Test
    void allocationAdjustmentReloadAuthorizationAndOutbox() throws Exception {
        UUID id = entitlement(5001, "10.00");
        String path = "/api/leaves/entitlements/" + id + "/adjustments";
        String body =
                "{\"days\":\"2.00\",\"idempotencyKey\":\"annual-correction\",\"reason\":\"Contract grants two extra days\"}";
        for (int i = 0; i < 2; i++)
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                            .header("X-User-Id", "1")
                            .header("X-User-Roles", "HR_MANAGER")
                            .contentType("application/json")
                            .content(body))
                    .andExpect(status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                "/api/leaves/balances/me?year=2026")
                        .header("X-User-Id", "5001")
                        .header("X-User-Roles", "EMPLOYEE"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].available")
                        .value("12.00"));
        assertThat(balances.balance(id).available()).isEqualTo("12.00");
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(jdbc.queryForObject(
                                "SELECT count(*) FROM outbox_event WHERE aggregate_id=? AND sent_at IS NOT NULL",
                                Integer.class,
                                id.toString()))
                        .isEqualTo(2));
        var delivered = new ArrayList<String>();
        org.springframework.amqp.core.Message message;
        while ((message = rabbit.receive("leave.balance-test", 1000)) != null) {
            String content = new String(message.getBody(), java.nio.charset.StandardCharsets.UTF_8);
            if (content.contains(id.toString())) delivered.add(content);
        }
        assertThat(delivered).hasSize(2);
        assertThat(delivered).anyMatch(v -> v.contains("leave.entitlement.allocated"));
        assertThat(delivered)
                .anyMatch(v -> v.contains("leave.balance.adjusted") && v.contains("Contract grants two extra days"));
        assertThat(balances.entries(id)).hasSize(1);
        var entry = balances.entries(id).getFirst();
        assertThat(entry.actorId()).isEqualTo(1);
        assertThat(entry.reason()).isEqualTo("Contract grants two extra days");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM outbox_event WHERE aggregate_id=?", Integer.class, id.toString()))
                .isEqualTo(2);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                        .header("X-User-Id", "1")
                        .header("X-User-Roles", "HR_MANAGER")
                        .contentType("application/json")
                        .content(body.replace("2.00", "3.00")))
                .andExpect(status().isConflict());
        for (String role : List.of("EMPLOYEE", "USER", "DEPARTMENT_HEAD", "FINANCE_MANAGER")) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                            .header("X-User-Id", "5001")
                            .header("X-User-Roles", role)
                            .contentType("application/json")
                            .content(body))
                    .andExpect(status().isForbidden());
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                    "/api/leaves/entitlements?employeeId=5001&year=2026")
                            .header("X-User-Id", "5002")
                            .header("X-User-Roles", role))
                    .andExpect(status().isForbidden());
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                                    "/api/leaves/entitlements")
                            .header("X-User-Id", "5001")
                            .header("X-User-Roles", role)
                            .contentType("application/json")
                            .content("{\"employeeId\":5001,\"typeId\":\""
                                    + balances.balance(id).typeId()
                                    + "\",\"year\":2026,\"days\":\"10.00\",\"reason\":\"Forbidden allocation\"}"))
                    .andExpect(status().isForbidden());
        }
        jdbc.update("INSERT INTO employee_projection(user_id,active) VALUES (5002,true)");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                "/api/leaves/balances/me?year=2026&employeeId=5001")
                        .header("X-User-Id", "5002")
                        .header("X-User-Roles", "EMPLOYEE"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .json("[]"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                        "/api/leaves/balances/me?year=2026"))
                .andExpect(status().isUnauthorized());
        assertThatThrownBy(
                        () -> jdbc.update("UPDATE leave_balance_entry SET reason='rewrite' WHERE entitlement_id=?", id))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM leave_balance_entry WHERE entitlement_id=?", id))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    void lastDayConcurrentReservationAndSettlementAreAtomic() throws Exception {
        UUID id = entitlement(5003, "1.00");
        var start = new java.util.concurrent.CountDownLatch(1);
        var succeeded = new java.util.concurrent.atomic.AtomicInteger();
        var won = new java.util.concurrent.atomic.AtomicReference<UUID>();
        try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 2; i++)
                tasks.add(pool.submit(() -> {
                    start.await();
                    UUID reservation = UUID.randomUUID();
                    try {
                        balances.reserve(id, reservation, java.math.BigDecimal.ONE, 5003, "Pending leave");
                        won.set(reservation);
                        succeeded.incrementAndGet();
                    } catch (org.springframework.web.server.ResponseStatusException ex) {
                        assertThat(ex.getStatusCode().value()).isEqualTo(409);
                    }
                    return null;
                }));
            start.countDown();
            for (var task : tasks) task.get(10, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertThat(succeeded.get()).isEqualTo(1);
        assertThat(balances.balance(id).reserved()).isEqualTo("1.00");
        assertThat(balances.balance(id).available()).isEqualTo("0.00");
        assertThatThrownBy(() -> balances.adjust(
                        id,
                        new com.ems.leave_service.balance.LeaveModels.Adjustment(
                                new java.math.BigDecimal("-0.01"), "negative", "Cannot overdraw"),
                        1))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        balances.settle(id, won.get(), true, 1, "Approved leave");
        balances.settle(id, won.get(), true, 1, "Retry approval");
        assertThat(balances.balance(id).taken()).isEqualTo("1.00");
        assertThat(balances.balance(id).reserved()).isEqualTo("0.00");
        assertThat(balances.balance(id).available()).isEqualTo("0.00");
        assertThat(balances.entries(id)).hasSize(1);
    }

    @Test
    void concurrentAdjustmentRetriesAllocateOnlyOnceAndKeepPrecision() throws Exception {
        UUID id = entitlement(5004, "10.00");
        var input = new com.ems.leave_service.balance.LeaveModels.Adjustment(
                new java.math.BigDecimal("2.25"), "same-key", "Correction");
        try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 8; i++) tasks.add(pool.submit(() -> balances.adjust(id, input, 1)));
            for (var task : tasks) task.get(10, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertThat(balances.entries(id)).hasSize(1);
        assertThat(balances.balance(id).available()).isEqualTo("12.25");
        assertThatThrownBy(() -> balances.adjust(
                        id,
                        new com.ems.leave_service.balance.LeaveModels.Adjustment(
                                new java.math.BigDecimal("0.001"), "precision", "Too precise"),
                        1))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        var b = balances.balance(id);
        assertThatThrownBy(() -> balances.allocate(
                        new com.ems.leave_service.balance.LeaveModels.Allocation(
                                5004, b.typeId(), 2026, java.math.BigDecimal.TEN, "Duplicate"),
                        1))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test
    void calendarExcludesConfiguredHolidayAndWeekendAndReleaseRestoresBalance() {
        assertThat(calendar.policy().timezone()).isEqualTo("Asia/Colombo");
        var monday = java.time.LocalDate.of(2099, 1, 5);
        calendar.saveHoliday(
                new com.ems.leave_service.balance.WorkingCalendar.Holiday(monday, "Synthetic test closure"));
        assertThat(calendar.workingDays(monday, monday.plusDays(6))).isEqualTo(4);
        calendar.deleteHoliday(monday);
        assertThat(calendar.workingDays(monday, monday.plusDays(6))).isEqualTo(5);
        assertThatThrownBy(() -> calendar.workingDays(monday, monday.minusDays(1)))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        UUID id = entitlement(5005, "0.50"), reservation = UUID.randomUUID();
        balances.reserve(id, reservation, new java.math.BigDecimal("0.50"), 5005, "Half day pending");
        balances.settle(id, reservation, false, 5005, "Cancelled");
        balances.settle(id, reservation, false, 5005, "Retry");
        assertThat(balances.balance(id).available()).isEqualTo("0.50");
        assertThat(balances.balance(id).taken()).isEqualTo("0.00");
    }

    private Map<String, Object> row(long id) {
        return jdbc.queryForMap("SELECT * FROM employee_projection WHERE user_id=?", id);
    }

    @Autowired
    com.ems.leave_service.request.LeaveRequestService requests;

    private com.ems.leave_service.request.RequestModels.Submit request(
            UUID type, String start, String end, String key) {
        return new com.ems.leave_service.request.RequestModels.Submit(
                type,
                java.time.LocalDate.parse(start),
                java.time.LocalDate.parse(end),
                com.ems.leave_service.request.RequestModels.Fraction.FULL,
                "Family leave",
                key);
    }

    private UUID requestEmployee(long employee, String days) {
        UUID entitlement = entitlement(employee, days);
        jdbc.update(
                "INSERT INTO employee_projection(user_id,active,department_id) VALUES (?,true,77) ON CONFLICT DO NOTHING",
                employee + 100);
        jdbc.update(
                "UPDATE employee_projection SET reporting_manager_user_id=?,department_id=77 WHERE user_id=?",
                employee + 100,
                employee);
        return entitlement;
    }

    @Test
    void requestCalendarAccessRejectionAndCancellation() throws Exception {
        var entitlement = requestEmployee(7001, "10.00");
        var type = balances.balance(entitlement).typeId();
        var holiday = java.time.LocalDate.parse("2026-11-02");
        calendar.saveHoliday(
                new com.ems.leave_service.balance.WorkingCalendar.Holiday(holiday, "Synthetic request holiday"));
        var input = request(type, "2026-10-30", "2026-11-03", "weekend");
        var r = requests.submit(7001, input);
        assertThat(r.calculatedDays()).isEqualTo("2.00");
        assertThat(r.status().name()).isEqualTo("PENDING");
        assertThat(requests.submit(7001, input).id()).isEqualTo(r.id());
        assertThat(balances.balance(entitlement).available()).isEqualTo("8.00");
        String path = "/api/leaves/requests/" + r.id();
        for (long actor : List.of(7001L, 7101L))
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)
                            .header("X-User-Id", actor)
                            .header("X-User-Roles", "EMPLOYEE"))
                    .andExpect(status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)
                        .header("X-User-Id", "7999")
                        .header("X-User-Roles", "DEPARTMENT_HEAD"))
                .andExpect(status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                "/api/leaves/requests/inbox")
                        .header("X-User-Id", "7999")
                        .header("X-User-Roles", "EMPLOYEE"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .json("[]"));
        String action = "{\"idempotencyKey\":\"reject\",\"reason\":\"Coverage needed\"}";
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path + "/reject")
                        .header("X-User-Id", "7001")
                        .header("X-User-Roles", "HR_MANAGER")
                        .contentType("application/json")
                        .content(action))
                .andExpect(status().isForbidden());
        for (int i = 0; i < 2; i++)
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path + "/reject")
                            .header("X-User-Id", "7101")
                            .header("X-User-Roles", "EMPLOYEE")
                            .contentType("application/json")
                            .content(action))
                    .andExpect(status().isOk());
        assertThat(balances.balance(entitlement).available()).isEqualTo("10.00");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM outbox_event WHERE aggregate_id=?", Integer.class, r.id().toString()))
                .isEqualTo(2);
        var next = requests.submit(7001, request(type, "2026-10-30", "2026-11-03", "new"));
        var cancel = new com.ems.leave_service.request.RequestModels.Action("cancel", "Plans changed");
        requests.finish(next.id(), 7001, cancel, false);
        requests.finish(next.id(), 7001, cancel, false);
        requests.finish(
                next.id(),
                7001,
                new com.ems.leave_service.request.RequestModels.Action("another", "Already cancelled"),
                false);
        assertThat(balances.balance(entitlement).available()).isEqualTo("10.00");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM outbox_event WHERE aggregate_id=?",
                        Integer.class,
                        next.id().toString()))
                .isEqualTo(2);
        assertThatThrownBy(() -> requests.finish(
                        next.id(),
                        7001,
                        new com.ems.leave_service.request.RequestModels.Action("cancel", "Changed payload"),
                        false))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(() -> requests.submit(7001, request(type, "2026-10-29", "2026-10-29", "weekend")))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        calendar.deleteHoliday(holiday);
    }

    @Test
    void concurrentRequestOverlapAndOverdrawNeverLeaveOrphanReservations() throws Exception {
        var entitlement = requestEmployee(7002, "1.00");
        var type = balances.balance(entitlement).typeId();
        var gate = new java.util.concurrent.CountDownLatch(1);
        var successes = new java.util.concurrent.atomic.AtomicInteger();
        try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 6; i++) {
                final int n = i;
                tasks.add(pool.submit(() -> {
                    gate.await();
                    try {
                        requests.submit(7002, request(type, "2026-12-01", "2026-12-01", "parallel" + n));
                        successes.incrementAndGet();
                    } catch (org.springframework.web.server.ResponseStatusException ex) {
                        assertThat(ex.getStatusCode().value()).isEqualTo(409);
                    }
                    return null;
                }));
            }
            gate.countDown();
            for (var task : tasks) task.get(10, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertThat(successes.get()).isEqualTo(1);
        assertThatThrownBy(() -> requests.submit(7002, request(type, "2026-12-02", "2026-12-02", "no-balance")))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM leave_reservation WHERE entitlement_id=?", Integer.class, entitlement))
                .isEqualTo(1);
        assertThat(requests.mine(7002)).hasSize(1);
        assertThat(balances.balance(entitlement).available()).isEqualTo("0.00");
        // Concurrent identical retries return one request and reserve only once.
        var e = requestEmployee(7003, "2.00");
        var input = request(balances.balance(e).typeId(), "2026-12-03", "2026-12-03", "same");
        try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = new ArrayList<java.util.concurrent.Future<UUID>>();
            for (int i = 0; i < 4; i++)
                tasks.add(pool.submit(() -> requests.submit(7003, input).id()));
            var ids = new HashSet<UUID>();
            for (var task : tasks) ids.add(task.get(10, java.util.concurrent.TimeUnit.SECONDS));
            assertThat(ids).hasSize(1);
        }
        assertThat(balances.balance(e).reserved()).isEqualTo("1.00");
    }

    @Test
    void requestValidationFallbackRoutingAndFutureStates() throws Exception {
        var e = entitlement(7004, "10.00");
        var type = balances.balance(e).typeId();
        for (var input : List.of(
                request(type, "2026-12-02", "2026-12-01", "reverse"),
                request(type, "2026-12-05", "2026-12-06", "weekend")))
            assertThatThrownBy(() -> requests.submit(7004, input))
                    .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(() -> requests.submit(7004, request(type, "2026-12-01", "2026-12-01", "missing-reviewer")))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        jdbc.update(
                "INSERT INTO employee_projection(user_id,active,department_id) VALUES (7104,true,88),(7105,true,99),(7106,true,88)");
        jdbc.update("UPDATE employee_projection SET department_id=88 WHERE user_id=7004");
        var at = Instant.now().minusSeconds(5);
        requests.reviewer(
                7104,
                new com.ems.leave_service.request.RequestModels.ReviewerSnapshot(
                        com.ems.leave_service.request.RequestModels.ReviewerRole.DEPARTMENT_HEAD, at));
        requests.reviewer(
                7105,
                new com.ems.leave_service.request.RequestModels.ReviewerSnapshot(
                        com.ems.leave_service.request.RequestModels.ReviewerRole.HR_MANAGER, at));
        requests.reviewer(
                7106,
                new com.ems.leave_service.request.RequestModels.ReviewerSnapshot(
                        com.ems.leave_service.request.RequestModels.ReviewerRole.DEPARTMENT_HEAD, at));
        var r = requests.submit(7004, request(type, "2026-12-01", "2026-12-01", "fallback"));
        assertThat(r.approverId()).isEqualTo(7104);
        var route = new com.ems.leave_service.request.RequestModels.Route(7106, "route", "Coverage delegation");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                                "/api/leaves/requests/" + r.id() + "/route")
                        .header("X-User-Id", "7104")
                        .header("X-User-Roles", "DEPARTMENT_HEAD")
                        .contentType("application/json")
                        .content(json.writeValueAsString(route)))
                .andExpect(status().isForbidden());
        requests.route(r.id(), 7105, route);
        assertThat(requests.inbox(7104, false)).isEmpty();
        assertThat(requests.inbox(7106, false)).extracting(v -> v.id()).contains(r.id());
        assertThatThrownBy(() -> requests.get(r.id(), 7104, false))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(() -> requests.route(
                        r.id(),
                        7105,
                        new com.ems.leave_service.request.RequestModels.Route(7004, "self", "Invalid self")))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        for (String state : List.of("APPROVAL_IN_PROGRESS", "APPROVED", "APPROVAL_FAILED")) {
            jdbc.update("UPDATE leave_request SET status=? WHERE id=?", state, r.id());
            assertThatThrownBy(() -> requests.finish(
                            r.id(),
                            7004,
                            new com.ems.leave_service.request.RequestModels.Action("cancel-" + state, "Deferred"),
                            false))
                    .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        }
        assertThat(balances.balance(e).reserved()).isEqualTo("1.00");
        var half = new com.ems.leave_service.request.RequestModels.EstimateInput(
                java.time.LocalDate.parse("2026-12-02"),
                java.time.LocalDate.parse("2026-12-02"),
                com.ems.leave_service.request.RequestModels.Fraction.HALF);
        assertThat(requests.estimate(half).calculatedDays()).isEqualTo("0.50");
        assertThatThrownBy(() -> requests.estimate(new com.ems.leave_service.request.RequestModels.EstimateInput(
                        half.startDate(), half.endDate().plusDays(1), half.dayFraction())))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
}
