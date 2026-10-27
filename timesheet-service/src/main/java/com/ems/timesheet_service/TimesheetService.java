package com.ems.timesheet_service;

import static com.ems.timesheet_service.Models.*;

import com.ems.common.outbox.OutboxPublisher;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional
public class TimesheetService {
    private final JdbcTemplate jdbc;
    private final ProjectionStore projections;
    private final OrganizationCalendar calendar;
    private final OutboxPublisher outbox;
    private final ReviewScope scope;

    public TimesheetService(
            JdbcTemplate jdbc,
            ProjectionStore projections,
            OrganizationCalendar calendar,
            OutboxPublisher outbox,
            ReviewScope scope) {
        this.jdbc = jdbc;
        this.projections = projections;
        this.calendar = calendar;
        this.outbox = outbox;
        this.scope = scope;
    }

    private void actorLock(long actor) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(?)", Object.class, actor);
    }

    public Sheet create(long actor, Create input) {
        calendar.validateWeek(input.weekStart());
        actorLock(actor);
        projections.lock(false);
        projections.active(actor);
        var existing = mine(actor, input.weekStart());
        if (!existing.isEmpty()) return existing.getFirst();
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO timesheet(id,employee_id,week_start,created_by,updated_by) VALUES (?,?,?,?,?)",
                id,
                actor,
                input.weekStart(),
                actor,
                actor);
        audit(id, actor, "CREATED", 0);
        return get(id, actor);
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public List<Sheet> mine(long actor, LocalDate week) {
        calendar.validateWeek(week);
        return jdbc.query("SELECT * FROM timesheet WHERE employee_id=? AND week_start=?", this::map, actor, week);
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Sheet get(UUID id, long actor) {
        return load(id, actor, false);
    }

    private Sheet load(UUID id, long actor, boolean lock) {
        var rows = jdbc.query(
                "SELECT * FROM timesheet WHERE id=? AND employee_id=?" + (lock ? " FOR UPDATE" : ""),
                this::map,
                id,
                actor);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Timesheet not found");
        return rows.getFirst();
    }

    public Sheet save(UUID id, long actor, Save input) {
        actorLock(actor);
        Sheet sheet = load(id, actor, true);
        draft(sheet, input.version());
        projections.lock(false);
        projections.active(actor);
        validate(sheet, input.entries());
        jdbc.update("DELETE FROM timesheet_entry WHERE sheet_id=?", id);
        for (int i = 0; i < input.entries().size(); i++) {
            Entry e = input.entries().get(i);
            jdbc.update(
                    "INSERT INTO timesheet_entry(sheet_id,position,entry_date,project_id,minutes,description) VALUES (?,?,?,?,?,?)",
                    id,
                    i,
                    e.date(),
                    e.projectId(),
                    e.minutes(),
                    e.description().trim());
        }
        jdbc.update("UPDATE timesheet SET version=version+1,updated_at=now(),updated_by=? WHERE id=?", actor, id);
        audit(id, actor, "SAVED", sheet.version() + 1);
        return get(id, actor);
    }

    public void delete(UUID id, long actor, long version) {
        actorLock(actor);
        Sheet sheet = load(id, actor, true);
        draft(sheet, version);
        if (!sheet.decisions().isEmpty())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Reviewed sheets must be retained for audit");
        audit(id, actor, "DELETED", sheet.version());
        jdbc.update("DELETE FROM timesheet WHERE id=?", id);
    }

    public Sheet submit(UUID id, long actor, Submit input) {
        actorLock(actor);
        Sheet sheet = load(id, actor, true);
        // A retry after a lost response returns the immutable result, even with the original version.
        if (sheet.status() != Status.DRAFT && sheet.submittedAt() != null) return sheet;
        draft(sheet, input.version());
        projections.lock(false);
        projections.active(actor);
        validate(sheet, sheet.entries());
        if (sheet.entries().stream().noneMatch(e -> e.minutes() > 0))
            throw bad("At least one positive entry is required for submission");
        jdbc.update(
                "UPDATE timesheet SET status='SUBMITTED',version=version+1,updated_at=now(),updated_by=?,submitted_at=now(),submitted_by=? WHERE id=?",
                actor,
                actor,
                id);
        Sheet result = get(id, actor);
        audit(id, actor, "SUBMITTED", result.version());
        outbox.publish("timesheet", id.toString(), "timesheet.submitted", result);
        return result;
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public List<Sheet> inbox(long actor) {
        scope.requireReviewer(actor);
        return jdbc.query(
                "SELECT t.* FROM timesheet t WHERE status='SUBMITTED' AND " + ReviewScope.ELIGIBLE
                        + " ORDER BY week_start,id",
                this::map,
                actor);
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Sheet reviewDetail(UUID id, long actor) {
        Sheet sheet = reviewLoad(id, false);
        scope.require(sheet.employeeId(), actor);
        if (sheet.status() == Status.DRAFT)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Draft entries are private to the owner");
        return sheet;
    }

    private Sheet reviewLoad(UUID id, boolean lock) {
        var rows = jdbc.query("SELECT * FROM timesheet WHERE id=?" + (lock ? " FOR UPDATE" : ""), this::map, id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Timesheet not found");
        return rows.getFirst();
    }

    public Sheet decide(UUID id, long actor, Review input, boolean reject) {
        Sheet sheet = reviewLoad(id, true);
        projections.lock(false);
        scope.require(sheet.employeeId(), actor);
        String reason = input.reason() == null ? "" : input.reason().trim();
        if (reject && reason.isBlank()) throw bad("A rejection reason is required");
        if (sheet.status() != Status.SUBMITTED || sheet.version() != input.version())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Timesheet changed; reload before reviewing");
        String outcome = reject ? "REJECTED" : "APPROVED";
        int changed = jdbc.update(
                "UPDATE timesheet SET status=?,version=version+1,updated_at=now(),updated_by=?,reviewed_at=now(),reviewed_by=?,review_reason=? WHERE id=? AND status='SUBMITTED' AND version=?",
                outcome,
                actor,
                actor,
                reason,
                id,
                input.version());
        if (changed != 1)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Timesheet changed; reload before reviewing");
        UUID decision = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO timesheet_decision(id,sheet_id,outcome,reviewer_id,reason,version) VALUES (?,?,?,?,?,?)",
                decision,
                id,
                outcome,
                actor,
                reason,
                sheet.version() + 1);
        Sheet result = reviewLoad(id, false);
        audit(id, actor, outcome, result.version());
        outbox.publish(
                "timesheet",
                id.toString(),
                "timesheet.decided",
                Map.of(
                        "decisionId",
                        decision,
                        "sheetId",
                        id,
                        "employeeId",
                        sheet.employeeId(),
                        "weekStart",
                        sheet.weekStart(),
                        "outcome",
                        outcome,
                        "reviewerId",
                        actor,
                        "decidedAt",
                        result.reviewedAt(),
                        "reason",
                        reason,
                        "version",
                        result.version()));
        return result;
    }

    public Sheet reopen(UUID id, long actor, Submit input) {
        actorLock(actor);
        Sheet sheet = load(id, actor, true);
        projections.lock(false);
        projections.active(actor);
        if (sheet.status() != Status.REJECTED || sheet.version() != input.version())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only the current rejected sheet may be reopened");
        jdbc.update(
                "UPDATE timesheet SET status='DRAFT',version=version+1,updated_at=now(),updated_by=?,submitted_at=NULL,submitted_by=NULL,reviewed_at=NULL,reviewed_by=NULL,review_reason=NULL WHERE id=?",
                actor,
                id);
        audit(id, actor, "REOPENED", sheet.version() + 1);
        return get(id, actor);
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public List<Summary> summary(long actor, LocalDate from, LocalDate to, Long employee, Long department) {
        calendar.validateWeek(from);
        calendar.validateWeek(to);
        if (to.isBefore(from) || to.isAfter(from.plusWeeks(52))) throw bad("Choose a range of at most 53 weeks");
        if (employee != null && department != null) throw bad("Choose employee or department scope");
        projections.active(actor);
        long selected = employee == null ? actor : employee;
        if (department != null) scope.department(department, actor);
        else if (selected != actor) scope.require(selected, actor);
        return jdbc.query(
                "SELECT t.employee_id,t.week_start,coalesce(sum(e.minutes),0) minutes FROM timesheet t JOIN employee_projection p ON p.user_id=t.employee_id LEFT JOIN timesheet_entry e ON e.sheet_id=t.id WHERE t.status='APPROVED' AND t.week_start BETWEEN ? AND ? AND "
                        + (department == null ? "t.employee_id=?" : "p.department_id=?")
                        + " GROUP BY t.employee_id,t.week_start ORDER BY t.employee_id,t.week_start",
                (r, n) -> new Summary(
                        r.getLong("employee_id"), r.getDate("week_start").toLocalDate(), r.getLong("minutes")),
                from,
                to,
                department == null ? selected : department);
    }

    private void draft(Sheet sheet, long version) {
        if (sheet.status() != Status.DRAFT)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Submitted entries are frozen");
        if (sheet.version() != version)
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Draft changed in another session. Reload before saving; your edits have been retained.");
    }

    private void validate(Sheet sheet, List<Entry> entries) {
        Map<LocalDate, Long> totals = new HashMap<>();
        var eligible = projections.projects(sheet.employeeId(), sheet.weekStart());
        for (Entry e : entries) {
            if (e.date() == null
                    || e.date().isBefore(sheet.weekStart())
                    || e.date().isAfter(sheet.weekStart().plusDays(6)))
                throw bad("Entry dates must be within the timesheet week");
            if (e.minutes() < 0 || e.minutes() > 1440) throw bad("Minutes must be between 0 and 1440");
            if (e.description() == null
                    || e.description().isBlank()
                    || e.description().length() > 240) throw bad("A description of 1–240 characters is required");
            if (totals.merge(e.date(), (long) e.minutes(), Long::sum) > 1440)
                throw bad("Daily total cannot exceed 1440 minutes (24 hours): " + e.date());
            if (eligible.stream()
                    .noneMatch(p -> p.id() == e.projectId() && p.eligibleDates().contains(e.date())))
                throw new ResponseStatusException(
                        HttpStatus.FORBIDDEN, "Project membership is required on entry date: " + e.date());
        }
    }

    private ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private void audit(UUID id, long actor, String action, long version) {
        jdbc.update(
                "INSERT INTO timesheet_audit(sheet_id,actor_id,action,version) VALUES (?,?,?,?)",
                id,
                actor,
                action,
                version);
    }

    private Instant instant(ResultSet rs, String field) throws SQLException {
        Timestamp value = rs.getTimestamp(field);
        return value == null ? null : value.toInstant();
    }

    private Sheet map(ResultSet rs, int row) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        List<Entry> entries = jdbc.query(
                "SELECT * FROM timesheet_entry WHERE sheet_id=? ORDER BY position",
                (r, n) -> new Entry(
                        r.getDate("entry_date").toLocalDate(),
                        r.getLong("project_id"),
                        r.getInt("minutes"),
                        r.getString("description")),
                id);
        return new Sheet(
                id,
                rs.getLong("employee_id"),
                rs.getDate("week_start").toLocalDate(),
                Status.valueOf(rs.getString("status")),
                rs.getLong("version"),
                entries,
                instant(rs, "created_at"),
                instant(rs, "updated_at"),
                instant(rs, "submitted_at"),
                rs.getLong("created_by"),
                rs.getLong("updated_by"),
                rs.getObject("submitted_by", Long.class),
                instant(rs, "reviewed_at"),
                rs.getObject("reviewed_by", Long.class),
                rs.getString("review_reason"),
                jdbc.query(
                        "SELECT * FROM timesheet_decision WHERE sheet_id=? ORDER BY version",
                        (r, n) -> new Decision(
                                r.getObject("id", UUID.class),
                                Status.valueOf(r.getString("outcome")),
                                r.getLong("reviewer_id"),
                                r.getTimestamp("decided_at").toInstant(),
                                r.getString("reason"),
                                r.getLong("version")),
                        id));
    }
}
