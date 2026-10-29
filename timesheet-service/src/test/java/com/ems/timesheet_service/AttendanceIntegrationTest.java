package com.ems.timesheet_service;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.sql.Timestamp;
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
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = {"ems.messaging.enabled=false", "ems.outbox.poll-interval=3600000"})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Testcontainers
class AttendanceIntegrationTest {
    @Container
    static final PostgreSQLContainer DB = new PostgreSQLContainer("postgres:17");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", DB::getJdbcUrl);
        p.add("spring.datasource.username", DB::getUsername);
        p.add("spring.datasource.password", DB::getPassword);
    }

    @Autowired
    AttendanceService service;

    @Autowired
    ProjectionStore projections;

    @Autowired
    ReviewScope scope;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MockMvc mvc;

    private final JsonMapper json = JsonMapper.builder().build();

    private void employee(long id) {
        scope.reconcile(
                id, new Models.EmployeeScope(true, null, null, Instant.now().minusSeconds(1)));
    }

    private void hr(long id) {
        employee(id);
        scope.reviewer(id, new Models.Reviewer(Instant.now().minusMillis(100), Models.ReviewerRole.HR_MANAGER));
    }

    private UUID historical(long user, String start, String end) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO attendance_session(id,employee_id,clock_in,clock_out) VALUES (?,?,?,?)",
                id,
                user,
                Timestamp.from(Instant.parse(start)),
                end == null ? null : Timestamp.from(Instant.parse(end)));
        return id;
    }

    @Test
    void concurrentClockActionsAreIdempotentAndDoNotCreateTimesheets() throws Exception {
        employee(8101);
        UUID request = UUID.randomUUID();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var ready = new CountDownLatch(2);
            var start = new CountDownLatch(1);
            List<Future<AttendanceModels.Session>> calls = new ArrayList<>();
            for (int i = 0; i < 2; i++)
                calls.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return service.clockIn(8101, request);
                }));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var first = calls.get(0).get(20, TimeUnit.SECONDS);
            var second = calls.get(1).get(20, TimeUnit.SECONDS);
            assertThat(first).isEqualTo(second);
            assertThat(first.clockIn()).isBetween(Instant.now().minusSeconds(20), Instant.now());
            assertThatThrownBy(() -> service.clockIn(8101, UUID.randomUUID())).hasMessageContaining("409");
            var a = pool.submit(() -> service.clockOut(8101, request));
            var b = pool.submit(() -> service.clockOut(8101, request));
            assertThat(a.get(20, TimeUnit.SECONDS)).isEqualTo(b.get(20, TimeUnit.SECONDS));
            var closed = service.clockOut(8101, request);
            UUID next = UUID.randomUUID();
            service.clockIn(8101, next);
            assertThat(service.clockIn(8101, request)).isEqualTo(closed);
            assertThat(service.clockOut(8101, request)).isEqualTo(closed);
            assertThat(service.current(8101).sessions().getFirst().id()).isEqualTo(next);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM timesheet WHERE employee_id=8101", Long.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM attendance_session WHERE employee_id=8101", Long.class))
                .isEqualTo(2);
    }

    @Test
    void differentConcurrentRequestsCreateOnlyOneOpenSession() throws Exception {
        employee(8102);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            Callable<Integer> call = () -> {
                start.await();
                try {
                    service.clockIn(8102, UUID.randomUUID());
                    return 200;
                } catch (org.springframework.web.server.ResponseStatusException ex) {
                    return ex.getStatusCode().value();
                }
            };
            var a = pool.submit(call);
            var b = pool.submit(call);
            start.countDown();
            assertThat(List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        }
        assertThat(service.current(8102).sessions()).hasSize(1);
    }

    @Test
    void overnightPresenceSplitsAtOrganizationMidnight() throws Exception {
        employee(8103);
        // 22:00–06:00 Asia/Colombo, crossing two local dates.
        historical(8103, "2026-10-20T16:30:00Z", "2026-10-21T00:30:00Z");
        var history = service.history(8103, null, 0, 10);
        assertThat(history.timezone()).isEqualTo("Asia/Colombo");
        assertThat(history.items().getFirst().durationSeconds()).isEqualTo(8 * 3600L);
        var summary = service.summary(8103, LocalDate.parse("2026-10-20"), LocalDate.parse("2026-10-21"));
        assertThat(summary.days())
                .extracting(AttendanceModels.Day::recordedSeconds)
                .containsExactly(7200L, 21600L);
        var otherZone = new AttendanceService(jdbc, projections, new OrganizationCalendar("America/New_York"));
        historical(8103, "2026-11-01T04:00:00Z", "2026-11-02T05:00:00Z");
        assertThat(otherZone
                        .summary(8103, LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-01"))
                        .days()
                        .getFirst()
                        .recordedSeconds())
                .isEqualTo(25 * 3600L);
        mvc.perform(get("/api/timesheets/attendance/history?page=1&size=1").header("X-User-Id", 8103))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.total").value(2));
    }

    @Test
    void correctionsRequireHrAndRetainBeforeAfterActorAndReason() throws Exception {
        employee(8104);
        employee(8105);
        hr(8199);
        UUID id = historical(8104, "2026-10-20T16:30:00Z", "2026-10-21T00:30:00Z");
        var input = new AttendanceModels.Correction(
                0L,
                Instant.parse("2026-10-20T17:30:00Z"),
                Instant.parse("2026-10-21T00:30:00Z"),
                " Verified badge log ");
        String body = json.writeValueAsString(input);
        for (long user : List.of(8104L, 8105L)) {
            mvc.perform(post("/api/timesheets/attendance/hr/" + id + "/corrections")
                            .header("X-User-Id", user)
                            .contentType("application/json")
                            .content(body))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(post("/api/timesheets/attendance/" + id + "/clock-out").header("X-User-Id", 8105))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/timesheets/attendance/hr/history?employeeId=8104").header("X-User-Id", 8105))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/timesheets/attendance/hr/" + id + "/corrections")
                        .header("X-User-Id", 8199)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));
        assertThatThrownBy(() -> service.correct(8199, id, input)).hasMessageContaining("409");
        var audit = service.audit(8104, id);
        assertThat(audit).hasSize(1);
        assertThat(audit.getFirst().actorId()).isEqualTo(8199);
        assertThat(audit.getFirst().reason()).isEqualTo("Verified badge log");
        assertThat(audit.getFirst().beforeIn()).isEqualTo(Instant.parse("2026-10-20T16:30:00Z"));
        assertThat(audit.getFirst().afterIn()).isEqualTo(input.clockIn());
        scope.reviewer(8199, new Models.Reviewer(Instant.now(), null));
        assertThatThrownBy(() -> service.correct(
                        8199, id, new AttendanceModels.Correction(1L, input.clockIn(), input.clockOut(), "Revoked HR")))
                .hasMessageContaining("403");
    }

    @Test
    void invalidCorrectionsAndDirectDatabaseOverlapAreRejected() throws Exception {
        employee(8106);
        hr(8198);
        UUID a = historical(8106, "2026-10-20T10:00:00Z", "2026-10-20T11:00:00Z");
        historical(8106, "2026-10-20T12:00:00Z", "2026-10-20T13:00:00Z");
        assertThatThrownBy(() -> service.correct(
                        8198,
                        a,
                        new AttendanceModels.Correction(
                                0L,
                                Instant.parse("2026-10-20T10:00:00Z"),
                                Instant.parse("2026-10-20T12:30:00Z"),
                                "Overlap")))
                .hasMessageContaining("409");
        assertThatThrownBy(() -> service.correct(
                        8198,
                        a,
                        new AttendanceModels.Correction(
                                0L,
                                Instant.parse("2026-10-20T10:00:00Z"),
                                Instant.parse("2026-10-20T09:00:00Z"),
                                "Negative")))
                .hasMessageContaining("400");
        assertThatThrownBy(() -> service.correct(
                        8198,
                        a,
                        new AttendanceModels.Correction(0L, Instant.parse("2026-10-20T10:00:00Z"), null, "Reopen")))
                .hasMessageContaining("400");
        mvc.perform(
                        post("/api/timesheets/attendance/hr/" + a + "/corrections")
                                .header("X-User-Id", 8198)
                                .contentType("application/json")
                                .content(
                                        "{\"version\":0,\"clockIn\":\"2026-10-20T10:00:00Z\",\"clockOut\":null,\"reason\":\"  \"}"))
                .andExpect(status().isBadRequest());
        assertThatThrownBy(() -> historical(8106, "2026-10-20T10:30:00Z", "2026-10-20T11:30:00Z"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> historical(8106, "2026-10-20T10:00:00Z", "2026-10-20T09:00:00Z"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        historical(8106, "2026-10-20T13:00:00Z", null);
        assertThatThrownBy(() -> historical(8106, "2026-10-20T14:00:00Z", null))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(service.audit(8106, a)).isEmpty();
        mvc.perform(get("/api/timesheets/attendance/history?page=-1").header("X-User-Id", 8106))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/timesheets/attendance/current")).andExpect(status().isUnauthorized());
    }
}
