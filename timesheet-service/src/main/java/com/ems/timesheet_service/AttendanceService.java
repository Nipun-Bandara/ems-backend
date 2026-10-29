package com.ems.timesheet_service;

import static com.ems.timesheet_service.AttendanceModels.*;

import java.sql.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional
public class AttendanceService {
    private final JdbcTemplate jdbc;
    private final ProjectionStore projections;
    private final OrganizationCalendar calendar;

    public AttendanceService(JdbcTemplate jdbc, ProjectionStore projections, OrganizationCalendar calendar) {
        this.jdbc = jdbc;
        this.projections = projections;
        this.calendar = calendar;
    }

    private Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    private void lock(long employee) {
        jdbc.queryForObject(
                "SELECT pg_advisory_xact_lock(hashtextextended('attendance:' || ?::text,0))", Object.class, employee);
    }

    public boolean hr(long actor) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM employee_projection e JOIN timesheet_reviewer r ON r.user_id=e.user_id
            WHERE e.user_id=? AND e.active AND r.role='HR_MANAGER')
            """, Boolean.class, actor));
    }

    private void requireHr(long actor) {
        if (!hr(actor))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "An active reconciled HR reviewer is required");
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public State current(long actor) {
        return new State(
                calendar.current().timezone(),
                now(),
                hr(actor),
                jdbc.query(
                        "SELECT * FROM attendance_session WHERE employee_id=? AND clock_out IS NULL",
                        this::map,
                        actor));
    }

    public Session clockIn(long actor, UUID requestId) {
        projections.lock(false);
        projections.active(actor);
        lock(actor);
        var previous = jdbc.query("SELECT * FROM attendance_session WHERE id=?", this::map, requestId);
        if (!previous.isEmpty()) {
            if (previous.getFirst().employeeId() != actor) throw conflict("Operation ID already used");
            return previous.getFirst();
        }
        if (!current(actor).sessions().isEmpty()) throw conflict("Already clocked in; reload current session");
        Instant at = now();
        // A reviewed historical correction may have placed a later session in the timeline.
        if (Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM attendance_session WHERE employee_id=? AND (clock_out IS NULL OR clock_out>?))",
                Boolean.class,
                actor,
                Timestamp.from(at)))) throw conflict("Session overlaps existing attendance");
        jdbc.update(
                "INSERT INTO attendance_session(id,employee_id,clock_in) VALUES (?,?,?)",
                requestId,
                actor,
                Timestamp.from(at));
        return load(requestId);
    }

    public Session clockOut(long actor, UUID id) {
        projections.lock(false);
        lock(actor);
        Session session = load(id);
        if (session.employeeId() != actor) throw missing();
        // Including after a correction: a replay must never overwrite the persisted close time.
        if (session.clockOut() != null) return session;
        Instant at = now();
        if (at.isBefore(session.clockIn())) throw conflict("Server time precedes clock-in");
        jdbc.update(
                "UPDATE attendance_session SET clock_out=?,version=version+1 WHERE id=? AND clock_out IS NULL",
                Timestamp.from(at),
                id);
        return load(id);
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public History history(long actor, Long employee, int page, int size) {
        long selected = employee == null ? actor : employee;
        if (selected != actor) requireHr(actor);
        if (page < 0 || size < 1 || size > 100) throw bad("Page must be nonnegative; size must be 1–100");
        return new History(
                calendar.current().timezone(),
                jdbc.query(
                        "SELECT * FROM attendance_session WHERE employee_id=? ORDER BY clock_in DESC,id LIMIT ? OFFSET ?",
                        this::map,
                        selected,
                        size,
                        (long) page * size),
                page,
                size,
                jdbc.queryForObject(
                        "SELECT count(*) FROM attendance_session WHERE employee_id=?", Long.class, selected));
    }

    public Session correct(long actor, UUID id, Correction input) {
        projections.lock(false);
        requireHr(actor);
        Session initial = load(id);
        lock(initial.employeeId());
        Session before = load(id);
        if (before.version() != input.version()) throw conflict("Attendance changed; reload before correcting");
        Instant start = input.clockIn().truncatedTo(ChronoUnit.MICROS);
        Instant end = input.clockOut() == null ? null : input.clockOut().truncatedTo(ChronoUnit.MICROS);
        if (end != null && end.isBefore(start)) throw bad("Clock-out must not precede clock-in");
        if (start.isAfter(now()) || (end != null && end.isAfter(now()))) throw bad("Future attendance is invalid");
        // Closing is permitted; reopening is not. A delayed clock-out must not close a reopened session.
        if (before.clockOut() != null && end == null) throw bad("A closed session cannot be reopened");
        if (Boolean.TRUE.equals(jdbc.queryForObject(
                """
            SELECT EXISTS(SELECT 1 FROM attendance_session WHERE employee_id=? AND id<>?
            AND tstzrange(clock_in,clock_out,'[)') && tstzrange(?::timestamptz,?::timestamptz,'[)'))
            """, Boolean.class, before.employeeId(), id, Timestamp.from(start), timestamp(end))))
            throw conflict("Correction overlaps another session");
        jdbc.update(
                "UPDATE attendance_session SET clock_in=?,clock_out=?,version=version+1 WHERE id=?",
                Timestamp.from(start),
                timestamp(end),
                id);
        jdbc.update(
                """
            INSERT INTO attendance_correction_audit(id,session_id,actor_id,reason,before_in,before_out,after_in,after_out,version)
            VALUES (?,?,?,?,?,?,?,?,?)
            """,
                UUID.randomUUID(),
                id,
                actor,
                input.reason().trim(),
                Timestamp.from(before.clockIn()),
                timestamp(before.clockOut()),
                Timestamp.from(start),
                timestamp(end),
                before.version() + 1);
        return load(id);
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public List<Audit> audit(long actor, UUID id) {
        Session session = load(id);
        if (session.employeeId() != actor) requireHr(actor);
        return jdbc.query(
                "SELECT * FROM attendance_correction_audit WHERE session_id=? ORDER BY version",
                (r, n) -> new Audit(
                        r.getObject("id", UUID.class),
                        r.getLong("actor_id"),
                        instant(r, "corrected_at"),
                        r.getString("reason"),
                        instant(r, "before_in"),
                        instant(r, "before_out"),
                        instant(r, "after_in"),
                        instant(r, "after_out"),
                        r.getLong("version")),
                id);
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Summary summary(long actor, LocalDate from, LocalDate to) {
        if (from.getYear() < 1900 || to.getYear() > 9998 || to.isBefore(from) || to.isAfter(from.plusDays(365)))
            throw bad("Choose an inclusive range of at most 366 days within 1900–9998");
        ZoneId zone = ZoneId.of(calendar.current().timezone());
        Instant lower = from.atStartOfDay(zone).toInstant(),
                upper = to.plusDays(1).atStartOfDay(zone).toInstant();
        var sessions = jdbc.query("""
            SELECT * FROM attendance_session WHERE employee_id=? AND clock_in<? AND (clock_out IS NULL OR clock_out>?)
            ORDER BY clock_in
            """, this::map, actor, Timestamp.from(upper), Timestamp.from(lower));
        List<Day> days = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            Instant dayStart = date.atStartOfDay(zone).toInstant(),
                    dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant();
            // Each local day reports whole seconds after aggregation, with subsecond remainder truncated.
            long nanos = 0;
            for (Session s : sessions) {
                if (s.clockOut() == null) continue;
                Instant a = s.clockIn().isAfter(dayStart) ? s.clockIn() : dayStart;
                Instant b = s.clockOut().isBefore(dayEnd) ? s.clockOut() : dayEnd;
                if (b.isAfter(a)) nanos += Duration.between(a, b).toNanos();
            }
            days.add(new Day(date, nanos / 1_000_000_000));
        }
        return new Summary(zone.getId(), from, to, days, (int)
                sessions.stream().filter(s -> s.clockOut() == null).count());
    }

    private Session load(UUID id) {
        var rows = jdbc.query("SELECT * FROM attendance_session WHERE id=?", this::map, id);
        if (rows.isEmpty()) throw missing();
        return rows.getFirst();
    }

    private Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private Instant instant(ResultSet r, String field) throws SQLException {
        Timestamp value = r.getTimestamp(field);
        return value == null ? null : value.toInstant();
    }

    private Session map(ResultSet r, int row) throws SQLException {
        Instant start = instant(r, "clock_in"), end = instant(r, "clock_out");
        return new Session(
                r.getObject("id", UUID.class),
                r.getLong("employee_id"),
                start,
                end,
                r.getLong("version"),
                end == null ? null : Duration.between(start, end).getSeconds());
    }

    private ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Attendance session not found");
    }

    private ResponseStatusException conflict(String reason) {
        return new ResponseStatusException(HttpStatus.CONFLICT, reason);
    }

    private ResponseStatusException bad(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}
