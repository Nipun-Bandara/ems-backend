package com.ems.timesheet_service;

import static com.ems.timesheet_service.Models.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ReviewScope {
    private final JdbcTemplate jdbc;
    private final ProjectionStore projections;

    public ReviewScope(JdbcTemplate jdbc, ProjectionStore projections) {
        this.jdbc = jdbc;
        this.projections = projections;
    }

    // This exact predicate scopes inbox, detail, decisions, and summaries in SQL.
    static final String ELIGIBLE = """
        EXISTS(SELECT 1 FROM employee_projection e
        JOIN employee_projection p ON p.user_id=? AND p.active
        LEFT JOIN timesheet_reviewer r ON r.user_id=p.user_id
        WHERE e.user_id=t.employee_id AND e.user_id<>p.user_id AND
        (e.reporting_manager_user_id=p.user_id OR r.role='HR_MANAGER'
        OR (r.role='DEPARTMENT_HEAD' AND p.department_id=e.department_id)))
        """;

    public boolean eligible(long employee, long actor) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT " + ELIGIBLE + " FROM (SELECT ?::bigint employee_id) t", Boolean.class, actor, employee));
    }

    public void require(long employee, long actor) {
        if (!eligible(employee, actor)) throw forbidden();
    }

    public boolean reviewer(long actor) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM (SELECT user_id employee_id FROM employee_projection) t WHERE " + ELIGIBLE
                        + ")",
                Boolean.class,
                actor));
    }

    public boolean active(long actor) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM employee_projection WHERE user_id=? AND active)", Boolean.class, actor));
    }

    public void requireReviewer(long actor) {
        if (!reviewer(actor)) throw forbidden();
    }

    public void department(long department, long actor) {
        if (!Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM employee_projection p JOIN timesheet_reviewer r ON r.user_id=p.user_id
            WHERE p.user_id=? AND p.active AND (r.role='HR_MANAGER' OR (r.role='DEPARTMENT_HEAD' AND p.department_id=?)))
            """, Boolean.class, actor, department))) throw forbidden();
    }

    public void shell(long user) {
        jdbc.update(
                "INSERT INTO employee_projection(user_id,active,version) VALUES (?,false,'-infinity') ON CONFLICT DO NOTHING",
                user);
    }

    public void assignment(long user, Long department, Instant at) {
        shell(user);
        jdbc.update(
                "UPDATE employee_projection SET department_id=?,assignment_at=? WHERE user_id=? AND (assignment_at IS NULL OR assignment_at<?)",
                department,
                Timestamp.from(at),
                user,
                Timestamp.from(at));
    }

    public void profile(long user, Long manager, Instant at) {
        if (Objects.equals(user, manager)) throw new IllegalArgumentException("Self reporting is invalid");
        shell(user);
        jdbc.update(
                "UPDATE employee_projection SET reporting_manager_user_id=?,profile_at=? WHERE user_id=? AND (profile_at IS NULL OR profile_at<?)",
                manager,
                Timestamp.from(at),
                user,
                Timestamp.from(at));
    }

    @Transactional
    public void reconcile(long user, EmployeeScope input) {
        if (Objects.equals(user, input.reportingManagerUserId()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Self reporting is invalid");
        projections.lock(true);
        projections.employee(user, input.active(), input.asOf());
        assignment(user, input.departmentId(), input.asOf());
        profile(user, input.reportingManagerUserId(), input.asOf());
    }

    @Transactional
    public void reviewer(long user, Reviewer input) {
        projections.lock(true);
        projections.active(user);
        jdbc.update(
                "INSERT INTO timesheet_reviewer(user_id,role,as_of) VALUES (?,?,?) ON CONFLICT(user_id) DO UPDATE SET role=excluded.role,as_of=excluded.as_of WHERE timesheet_reviewer.as_of<excluded.as_of",
                user,
                input.role() == null ? null : input.role().name(),
                Timestamp.from(input.asOf()));
    }

    static ResponseStatusException forbidden() {
        return new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "An active reporting manager, same-department head or HR reviewer is required; self-review is forbidden");
    }
}
