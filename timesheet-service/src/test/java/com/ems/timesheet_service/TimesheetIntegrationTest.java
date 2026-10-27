package com.ems.timesheet_service;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.ems.common.event.EventEnvelope;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Testcontainers
class TimesheetIntegrationTest {
    @Container
    static final PostgreSQLContainer DB = new PostgreSQLContainer("postgres:17").withDatabaseName("timesheet_db");

    @Container
    static final org.testcontainers.rabbitmq.RabbitMQContainer RABBIT =
            new org.testcontainers.rabbitmq.RabbitMQContainer("rabbitmq:4-management");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry p) {
        p.add("spring.rabbitmq.host", RABBIT::getHost);
        p.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        p.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        p.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
        p.add("spring.datasource.url", DB::getJdbcUrl);
        p.add("spring.datasource.username", DB::getUsername);
        p.add("spring.datasource.password", DB::getPassword);
    }

    @org.springframework.boot.test.web.server.LocalServerPort
    int port;

    @Autowired
    org.springframework.amqp.rabbit.core.RabbitTemplate rabbit;

    @Autowired
    org.springframework.amqp.core.AmqpAdmin broker;

    @org.junit.jupiter.api.BeforeEach
    void submissionsHaveADownstreamSubscriber() {
        broker.declareQueue(new org.springframework.amqp.core.Queue("timesheet.test-decisions", true));
        broker.declareBinding(new org.springframework.amqp.core.Binding(
                "timesheet.test-decisions",
                org.springframework.amqp.core.Binding.DestinationType.QUEUE,
                "ems.events",
                "timesheet.decided",
                null));
        broker.declareQueue(new org.springframework.amqp.core.Queue("timesheet.test-submissions", true));
        broker.declareBinding(new org.springframework.amqp.core.Binding(
                "timesheet.test-submissions",
                org.springframework.amqp.core.Binding.DestinationType.QUEUE,
                "ems.events",
                "timesheet.submitted",
                null));
    }

    @Autowired
    ReviewScope reviewScope;

    private void scope(long user, Long department, Long manager) {
        reviewScope.reconcile(user, new Models.EmployeeScope(true, department, manager, AT.plusSeconds(10)));
    }

    private String submitted(long user, long project) throws Exception {
        seed(user, project);
        String id = create(user).path("id").asText();
        mvc.perform(put("/api/timesheets/" + id)
                        .header("X-User-Id", user)
                        .contentType("application/json")
                        .content(entries(project, 60, 30)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/timesheets/" + id + "/submit")
                        .header("X-User-Id", user)
                        .contentType("application/json")
                        .content("{\"version\":1}"))
                .andExpect(status().isOk());
        return id;
    }

    private int decision(String id, long actor, String action, long version, String reason) throws Exception {
        return mvc.perform(post("/api/timesheets/" + id + "/" + action)
                        .header("X-User-Id", actor)
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of("version", version, "reason", reason))))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    @Test
    void managerScopeAtomicDecisionAndSummary() throws Exception {
        String id = submitted(401, 501);
        scope(402, 10L, null);
        scope(403, 20L, null);
        scope(401, 10L, 402L);
        assertThat(decision(id, 401, "approve", 2, "")).isEqualTo(403);
        assertThat(decision(id, 403, "approve", 2, "")).isEqualTo(403);
        mvc.perform(get("/api/timesheets/reviewer/" + id).header("X-User-Id", 403))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/timesheets/reviewer/inbox").header("X-User-Id", 402))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + id + "')]").isNotEmpty());
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            List<Future<Integer>> results = new ArrayList<>();
            for (String action : List.of("approve", "reject"))
                results.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return decision(id, 402, action, 2, "Concurrent review");
                }));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(
                            results.get(0).get(20, TimeUnit.SECONDS),
                            results.get(1).get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        }
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM timesheet_decision WHERE sheet_id=?", Long.class, UUID.fromString(id)))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM outbox_event WHERE aggregate_id=? AND type='timesheet.decided'",
                        Long.class,
                        id))
                .isEqualTo(1);
        mvc.perform(get("/api/timesheets/summary?from=2026-10-19&to=2026-10-19&employeeId=401")
                        .header("X-User-Id", 403))
                .andExpect(status().isForbidden());
        String state =
                jdbc.queryForObject("SELECT status FROM timesheet WHERE id=?", String.class, UUID.fromString(id));
        assertThat(service.summary(402, WEEK, WEEK, 401L, null)).hasSize(state.equals("APPROVED") ? 1 : 0);
        if (state.equals("APPROVED"))
            assertThat(service.summary(402, WEEK, WEEK, 401L, null).getFirst().approvedMinutes())
                    .isEqualTo(90);
    }

    @Autowired
    TimesheetService service;

    @Test
    void rejectedSheetReopensAndResubmitsWithImmutableHistory() throws Exception {
        String id = submitted(411, 511);
        scope(412, 10L, null);
        scope(411, 10L, 412L);
        assertThat(decision(id, 412, "reject", 2, "   ")).isEqualTo(400);
        assertThat(decision(id, 412, "reject", 1, "Fix description")).isEqualTo(409);
        assertThat(decision(id, 412, "reject", 2, " Fix description ")).isEqualTo(200);
        assertThat(decision(id, 412, "reject", 2, "Fix description")).isEqualTo(409);
        mvc.perform(post("/api/timesheets/" + id + "/reopen")
                        .header("X-User-Id", 412)
                        .contentType("application/json")
                        .content("{\"version\":3}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/timesheets/" + id + "/reopen")
                        .header("X-User-Id", 411)
                        .contentType("application/json")
                        .content("{\"version\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.decisions[0].reason").value("Fix description"));
        mvc.perform(get("/api/timesheets/reviewer/" + id).header("X-User-Id", 412))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/timesheets/" + id + "?version=4").header("X-User-Id", 411))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/timesheets/" + id + "/submit")
                        .header("X-User-Id", 411)
                        .contentType("application/json")
                        .content("{\"version\":1}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/timesheets/" + id + "/submit")
                        .header("X-User-Id", 411)
                        .contentType("application/json")
                        .content("{\"version\":4}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(5));
        assertThat(decision(id, 412, "approve", 2, "")).isEqualTo(409);
        assertThat(decision(id, 412, "approve", 5, "")).isEqualTo(200);
        mvc.perform(get("/api/timesheets/" + id).header("X-User-Id", 411))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decisions.length()").value(2))
                .andExpect(jsonPath("$.decisions[0].outcome").value("REJECTED"))
                .andExpect(jsonPath("$.decisions[1].outcome").value("APPROVED"));
        assertThat(service.summary(411, WEEK, WEEK, null, null).getFirst().approvedMinutes())
                .isEqualTo(90);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM outbox_event WHERE aggregate_id=? AND type='timesheet.submitted'",
                        Long.class,
                        id))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM outbox_event WHERE aggregate_id=? AND type='timesheet.decided'",
                        Long.class,
                        id))
                .isEqualTo(2);
    }

    @Test
    void departmentAndHrScopesAreCurrentActiveAndProjectionClocksAreIndependent() throws Exception {
        String id = submitted(421, 521);
        scope(421, 10L, 422L);
        scope(422, 10L, null);
        scope(423, 10L, null);
        scope(424, 20L, null);
        scope(425, 20L, null);
        reviewScope.reviewer(423, new Models.Reviewer(AT, Models.ReviewerRole.DEPARTMENT_HEAD));
        reviewScope.reviewer(424, new Models.Reviewer(AT, Models.ReviewerRole.DEPARTMENT_HEAD));
        reviewScope.reviewer(425, new Models.Reviewer(AT, Models.ReviewerRole.HR_MANAGER));
        mvc.perform(get("/api/timesheets/reviewer/" + id).header("X-User-Id", 423))
                .andExpect(status().isOk());
        mvc.perform(get("/api/timesheets/reviewer/" + id).header("X-User-Id", 424))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/timesheets/reviewer/" + id).header("X-User-Id", 425))
                .andExpect(status().isOk());
        handler.apply(event(
                "employee.profile.updated",
                "2026-10-01T00:00:20Z",
                Map.of("userId", 421, "reportingManagerUserId", 425, "departmentId", 20)));
        handler.apply(event(
                "employee.profile.updated",
                "2026-10-01T00:00:15Z",
                Map.of("userId", 421, "reportingManagerUserId", 422)));
        assertThat(reviewScope.eligible(421, 422)).isFalse();
        assertThat(reviewScope.eligible(421, 423)).isTrue();
        handler.apply(event(
                "user.assignment.updated",
                "2026-10-01T00:00:30Z",
                Map.of("userId", 421, "departmentId", 20, "assignedAt", "2026-10-01T00:00:30Z")));
        assertThat(reviewScope.eligible(421, 423)).isFalse();
        assertThat(reviewScope.eligible(421, 424)).isTrue();
        reviewScope.reconcile(424, new Models.EmployeeScope(false, 20L, null, AT.plusSeconds(40)));
        assertThat(decision(id, 424, "approve", 2, "")).isEqualTo(403);
        assertThat(decision(id, 425, "approve", 2, "")).isEqualTo(200);
        assertThat(service.summary(425, WEEK, WEEK, null, 20L))
                .anyMatch(v -> v.employeeId() == 421 && v.approvedMinutes() == 90);
        mvc.perform(get("/api/timesheets/summary?from=2026-10-19&to=2026-10-19&departmentId=20")
                        .header("X-User-Id", 423))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/timesheets/admin/reviewers/422")
                        .header("X-User-Id", 422)
                        .contentType("application/json")
                        .content("{\"asOf\":\"2026-10-01T00:00:00Z\",\"role\":\"HR_MANAGER\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void projectionBackfillIsAdminOnlyAndVersioned() throws Exception {
        String input =
                "{\"asOf\":\"2026-10-01T00:00:00Z\",\"effectiveDate\":\"2026-10-01\",\"employees\":[{\"userId\":108,\"active\":true}],\"projects\":[{\"id\":208,\"teamId\":208,\"name\":\"Backfilled project\",\"deleted\":false}],\"memberships\":[{\"teamId\":208,\"userId\":108,\"member\":true}]}";
        for (int i = 0; i < 2; i++)
            mvc.perform(put("/api/timesheets/admin/projections")
                            .header("X-User-Id", 1)
                            .header("X-User-Roles", "SYSTEM_ADMIN")
                            .contentType("application/json")
                            .content(input))
                    .andExpect(status().isNoContent());
        assertThat(projections.projects(108, WEEK).getFirst().name()).isEqualTo("Backfilled project");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM membership_history WHERE user_id=108", Long.class))
                .isEqualTo(1);
        projections.backfill(
                1,
                new Models.Backfill(
                        AT.plusSeconds(1),
                        LocalDate.parse("2026-10-01"),
                        List.of(new Models.Employee(108, false)),
                        List.of(),
                        List.of()));
        handler.apply(event("user.verified", "2026-10-01T00:00:00Z", Map.of("userId", 108)));
        assertThat(jdbc.queryForObject("SELECT active FROM employee_projection WHERE user_id=108", Boolean.class))
                .isFalse();
    }

    @Test
    void gatewayProfilesAuthenticateAndRoute() throws Exception {
        TimesheetGatewayProbe.verify(port);
    }

    @Test
    void brokerDeliversAndDeduplicatesProjectionEvents() {
        var e = event("user.verified", "2026-10-01T00:00:00Z", Map.of("userId", 107));
        rabbit.convertAndSend("ems.events", e.type(), e);
        rabbit.convertAndSend("ems.events", e.type(), e);
        org.awaitility.Awaitility.await()
                .atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(jdbc.queryForObject(
                                "SELECT count(*) FROM employee_projection WHERE user_id=107", Long.class))
                        .isEqualTo(1));
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM processed_event WHERE event_id=?", Long.class, e.eventId()))
                .isEqualTo(1);
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JsonMapper json;

    @Autowired
    ProjectionHandler handler;

    @Autowired
    ProjectionStore projections;

    @Autowired
    OrganizationCalendar calendar;

    private static final LocalDate WEEK = LocalDate.parse("2026-10-19");
    private static final Instant AT = Instant.parse("2026-10-01T00:00:00Z");

    private void seed(long user, long project) {
        projections.backfill(
                1,
                new Models.Backfill(
                        AT,
                        LocalDate.parse("2026-10-01"),
                        List.of(new Models.Employee(user, true)),
                        List.of(new Models.ProjectSnapshot(project, project, "Existing project", false, null, null)),
                        List.of(new Models.Membership(project, user, true))));
    }

    private JsonNode create(long actor) throws Exception {
        return json.readTree(mvc.perform(post("/api/timesheets")
                        .header("X-User-Id", actor)
                        .contentType("application/json")
                        .content("{\"weekStart\":\"2026-10-19\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    }

    private String entries(long project, int a, int b) {
        return "{\"version\":0,\"entries\":[{\"date\":\"2026-10-19\",\"projectId\":" + project + ",\"minutes\":" + a
                + ",\"description\":\"Build\"},{\"date\":\"2026-10-19\",\"projectId\":" + project + ",\"minutes\":" + b
                + ",\"description\":\"Review\"}]}";
    }

    @Test
    void draftReloadSubmitAndImmutableIdempotentOutbox() throws Exception {
        seed(101, 201);
        String id = create(101).path("id").asText();
        assertThat(create(101).path("id").asText()).isEqualTo(id);
        mvc.perform(put("/api/timesheets/" + id)
                        .header("X-User-Id", 101)
                        .contentType("application/json")
                        .content(entries(201, 60, 30)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));
        mvc.perform(get("/api/timesheets/" + id).header("X-User-Id", 101))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].minutes").value(60));
        for (int i = 0; i < 2; i++)
            mvc.perform(post("/api/timesheets/" + id + "/submit")
                            .header("X-User-Id", 101)
                            .contentType("application/json")
                            .content("{\"version\":1}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("SUBMITTED"))
                    .andExpect(jsonPath("$.version").value(2));
        mvc.perform(put("/api/timesheets/" + id)
                        .header("X-User-Id", 101)
                        .contentType("application/json")
                        .content(entries(201, 1, 1)))
                .andExpect(status().isConflict());
        org.awaitility.Awaitility.await()
                .atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(jdbc.queryForObject(
                                "SELECT sent_at IS NOT NULL FROM outbox_event WHERE aggregate_id=?", Boolean.class, id))
                        .isTrue());
        mvc.perform(delete("/api/timesheets/" + id + "?version=2").header("X-User-Id", 101))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM outbox_event WHERE aggregate_id=? AND type='timesheet.submitted'",
                        Long.class,
                        id))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM timesheet_audit WHERE sheet_id=?", Long.class, UUID.fromString(id)))
                .isEqualTo(3);
    }

    @Test
    void authorizationAndDailyTotalsAndIntegerMinutes() throws Exception {
        seed(102, 202);
        seed(103, 203);
        String id = create(102).path("id").asText();
        mvc.perform(get("/api/timesheets/" + id).header("X-User-Id", 103)).andExpect(status().isNotFound());
        mvc.perform(put("/api/timesheets/" + id)
                        .header("X-User-Id", 103)
                        .contentType("application/json")
                        .content(entries(202, 1, 1)))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/timesheets/" + id + "/submit")
                        .header("X-User-Id", 103)
                        .contentType("application/json")
                        .content("{\"version\":0}"))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/timesheets/" + id + "?version=0").header("X-User-Id", 103))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/timesheets/" + id)
                        .header("X-User-Id", 102)
                        .contentType("application/json")
                        .content(entries(203, 1, 1)))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/timesheets/" + id)
                        .header("X-User-Id", 102)
                        .contentType("application/json")
                        .content(entries(202, 1000, 441)))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/timesheets/" + id)
                        .header("X-User-Id", 102)
                        .contentType("application/json")
                        .content(entries(202, -1, 0)))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/timesheets/" + id)
                        .header("X-User-Id", 102)
                        .contentType("application/json")
                        .content(entries(202, 1, 0).replace("\"minutes\":1", "\"minutes\":1.5")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/timesheets/" + id + "/submit")
                        .header("X-User-Id", 102)
                        .contentType("application/json")
                        .content("{\"version\":0}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/timesheets/" + id)
                        .header("X-User-Id", 102)
                        .contentType("application/json")
                        .content(entries(202, 1000, 440)))
                .andExpect(status().isOk());
        mvc.perform(put("/api/timesheets/" + id)
                        .header("X-User-Id", 102)
                        .contentType("application/json")
                        .content(entries(202, 0, 0)))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/timesheets/me?weekStart=2026-10-19")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/timesheets")
                        .header("X-User-Id", 102)
                        .contentType("application/json")
                        .content("{\"weekStart\":\"2026-10-20\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        put("/api/timesheets/admin/projections")
                                .header("X-User-Id", 102)
                                .contentType("application/json")
                                .content(
                                        "{\"asOf\":\"2026-10-01T00:00:00Z\",\"effectiveDate\":\"2026-10-01\",\"employees\":[],\"projects\":[],\"memberships\":[]}"))
                .andExpect(status().isForbidden());
    }

    private EventEnvelope<JsonNode> event(String type, String at, Map<String, Object> payload) {
        return new EventEnvelope<>(UUID.randomUUID(), type, Instant.parse(at), "test", json.valueToTree(payload));
    }

    @Test
    void outOfOrderHistoryBackfillAndLocalReadsWithoutOrgService() throws Exception {
        seed(104, 204);
        var removal = event("team.member.removed", "2026-10-21T00:00:00Z", Map.of("teamId", 204, "userId", 104));
        handler.apply(removal);
        handler.apply(removal);
        handler.apply(event("team.member.added", "2026-10-18T00:00:00Z", Map.of("teamId", 204, "userId", 104)));
        var project = projections.projects(104, WEEK).getFirst();
        assertThat(project.eligibleDates()).containsExactly(WEEK, WEEK.plusDays(1));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM membership_history WHERE team_id=204", Long.class))
                .isEqualTo(3);
        String id = create(104).path("id").asText();
        mvc.perform(put("/api/timesheets/" + id)
                        .header("X-User-Id", 104)
                        .contentType("application/json")
                        .content(entries(204, 60, 0)))
                .andExpect(status().isOk());
        // No org-service exists in this test. Both selection and sheet reads use local storage.
        mvc.perform(get("/api/timesheets/projects?weekStart=2026-10-19").header("X-User-Id", 104))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Existing project"));
        mvc.perform(get("/api/timesheets/" + id).header("X-User-Id", 104)).andExpect(status().isOk());
        mvc.perform(put("/api/timesheets/" + id)
                        .header("X-User-Id", 104)
                        .contentType("application/json")
                        .content(entries(204, 60, 0)
                                .replace("\"version\":0", "\"version\":1")
                                .replace("2026-10-19", "2026-10-21")))
                .andExpect(status().isForbidden());
        assertThat(calendar.date(Instant.parse("2026-10-18T19:00:00Z"))).isEqualTo(WEEK);
    }

    @Test
    void concurrentSubmissionCreatesOneEventAndDraftDeletionWorks() throws Exception {
        seed(105, 205);
        String id = create(105).path("id").asText();
        mvc.perform(put("/api/timesheets/" + id)
                        .header("X-User-Id", 105)
                        .contentType("application/json")
                        .content(entries(205, 60, 0)))
                .andExpect(status().isOk());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> results = new ArrayList<>();
            for (int i = 0; i < 4; i++)
                results.add(executor.submit(() -> {
                    try {
                        mvc.perform(post("/api/timesheets/" + id + "/submit")
                                        .header("X-User-Id", 105)
                                        .contentType("application/json")
                                        .content("{\"version\":1}"))
                                .andExpect(status().isOk());
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }));
            for (var result : results) result.get();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregate_id=?", Long.class, id))
                .isEqualTo(1);
        seed(106, 206);
        String draft = create(106).path("id").asText();
        mvc.perform(delete("/api/timesheets/" + draft + "?version=0").header("X-User-Id", 106))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/timesheets/" + draft).header("X-User-Id", 106)).andExpect(status().isNotFound());
    }
}
