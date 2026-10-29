package com.ems.claim_service;

import static com.ems.claim_service.Models.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional
public class EmployeeScopeStore {
    private final JdbcTemplate jdbc;

    public EmployeeScopeStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void lock(boolean write) {
        jdbc.queryForObject(
                write ? "SELECT pg_advisory_xact_lock(87,0)" : "SELECT pg_advisory_xact_lock_shared(87,0)",
                Object.class);
    }

    public void shell(long id) {
        jdbc.update("INSERT INTO employee_projection(user_id) VALUES (?) ON CONFLICT DO NOTHING", id);
    }

    public void active(long id, boolean active, Instant at) {
        shell(id);
        jdbc.update(
                "UPDATE employee_projection SET active=?,active_at=? WHERE user_id=? AND active_at<?",
                active,
                Timestamp.from(at),
                id,
                Timestamp.from(at));
    }

    public void assignment(long id, Long department, Instant at) {
        shell(id);
        jdbc.update(
                "UPDATE employee_projection SET department_id=?,assignment_at=? WHERE user_id=? AND assignment_at<?",
                department,
                Timestamp.from(at),
                id,
                Timestamp.from(at));
    }

    public void profile(long id, Long manager, Instant at) {
        if (Objects.equals(id, manager)) throw new IllegalArgumentException("Self reporting is invalid");
        shell(id);
        jdbc.update(
                "UPDATE employee_projection SET reporting_manager_user_id=?,profile_at=? WHERE user_id=? AND profile_at<?",
                manager,
                Timestamp.from(at),
                id,
                Timestamp.from(at));
    }

    public void reconcile(long id, EmployeeScope input) {
        lock(true);
        active(id, input.active(), input.asOf());
        assignment(id, input.departmentId(), input.asOf());
        profile(id, input.reportingManagerUserId(), input.asOf());
    }

    public void reviewer(long id, Reviewer input) {
        lock(true);
        shell(id);
        jdbc.update(
                "INSERT INTO claim_reviewer(user_id,role,as_of) VALUES (?,?,?) ON CONFLICT(user_id) DO UPDATE SET role=excluded.role,as_of=excluded.as_of WHERE claim_reviewer.as_of<excluded.as_of",
                id,
                input.role() == null ? null : input.role().name(),
                Timestamp.from(input.asOf()));
    }

    public boolean active(long id) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM employee_projection WHERE user_id=? AND active)", Boolean.class, id));
    }

    public void requireActive(long id) {
        if (!active(id)) throw forbidden();
    }

    static final String ELIGIBLE = """
 EXISTS(SELECT 1 FROM employee_projection e JOIN employee_projection p ON p.user_id=? AND p.active
 LEFT JOIN claim_reviewer r ON r.user_id=p.user_id
 WHERE e.user_id=c.employee_id AND e.user_id<>p.user_id
 AND (e.reporting_manager_user_id=p.user_id OR r.role='HR_MANAGER'))
 """;

    public boolean eligible(long employee, long actor) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT " + ELIGIBLE + " FROM (SELECT ?::bigint employee_id) c", Boolean.class, actor, employee));
    }

    public boolean reviewer(long actor) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM employee_projection c WHERE "
                        + ELIGIBLE.replace("c.employee_id", "c.user_id") + ")",
                Boolean.class,
                actor));
    }

    public void require(long employee, long actor) {
        if (!eligible(employee, actor)) throw forbidden();
    }

    static ResponseStatusException forbidden() {
        return new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "An active employee or scoped manager/HR reviewer is required; self-review is forbidden");
    }
}
