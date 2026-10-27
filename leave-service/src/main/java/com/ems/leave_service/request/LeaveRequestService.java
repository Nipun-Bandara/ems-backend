package com.ems.leave_service.request;

import static com.ems.leave_service.request.RequestModels.*;

import com.ems.common.outbox.OutboxPublisher;
import com.ems.leave_service.balance.LeaveBalanceService;
import com.ems.leave_service.balance.WorkingCalendar;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class LeaveRequestService {
    private final JdbcTemplate jdbc;
    private final LeaveBalanceService balances;
    private final WorkingCalendar calendar;
    private final OutboxPublisher outbox;

    public LeaveRequestService(
            JdbcTemplate jdbc, LeaveBalanceService balances, WorkingCalendar calendar, OutboxPublisher outbox) {
        this.jdbc = jdbc;
        this.balances = balances;
        this.calendar = calendar;
        this.outbox = outbox;
    }

    public Estimate estimate(EstimateInput input) {
        if (input.startDate().getYear() < 1900 || input.endDate().getYear() > 9999)
            throw bad("Dates must be within 1900–9999");
        long days = calendar.workingDays(input.startDate(), input.endDate());
        if (days == 0) throw bad("Range contains no working days");
        if (input.dayFraction() == Fraction.HALF && !input.startDate().equals(input.endDate()))
            throw bad("Half-day requires a single working date");
        return new Estimate(
                input.dayFraction() == Fraction.HALF
                        ? "0.50"
                        : BigDecimal.valueOf(days).setScale(2).toPlainString());
    }

    @Transactional
    public LeaveRequest submit(long employee, Submit input) {
        // Same lock namespace as employee reconciliation. Serialize all types for this employee,
        // then use item 28's entitlement lock for atomic balance reservation.
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(?)", Object.class, employee);
        var previous = jdbc.query(
                "SELECT * FROM leave_request WHERE employee_id=? AND idempotency_key=?",
                this::map,
                employee,
                input.idempotencyKey());
        if (!previous.isEmpty()) {
            var old = previous.getFirst();
            if (!old.typeId().equals(input.typeId())
                    || !old.startDate().equals(input.startDate())
                    || !old.endDate().equals(input.endDate())
                    || old.dayFraction() != input.dayFraction()
                    || !old.reason().equals(input.reason().trim()))
                throw conflict("Idempotency key already used for different input");
            return old;
        }
        balances.activeEmployee(employee);
        String days = estimate(new EstimateInput(input.startDate(), input.endDate(), input.dayFraction()))
                .calculatedDays();
        if (Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM leave_request WHERE employee_id=? AND status IN ('PENDING','APPROVAL_IN_PROGRESS','APPROVED') AND start_date<=? AND end_date>=?)",
                Boolean.class,
                employee,
                input.endDate(),
                input.startDate()))) throw conflict("Dates overlap an existing leave request");
        long approver = resolve(employee);
        var entitlements = jdbc.queryForList(
                "SELECT id FROM leave_entitlement WHERE employee_id=? AND type_id=? AND year=?",
                UUID.class,
                employee,
                input.typeId(),
                input.startDate().getYear());
        if (entitlements.isEmpty()) throw conflict("No entitlement allocated for this type and year");
        UUID entitlement = entitlements.getFirst(), id = UUID.randomUUID();
        balances.reserve(entitlement, id, new BigDecimal(days), employee, input.reason());
        jdbc.update(
                "INSERT INTO leave_request(id,employee_id,type_id,entitlement_id,approver_id,start_date,end_date,day_fraction,calculated_days,reason,status,idempotency_key) VALUES (?,?,?,?,?,?,?,?,?,?,'PENDING',?)",
                id,
                employee,
                input.typeId(),
                entitlement,
                approver,
                input.startDate(),
                input.endDate(),
                input.dayFraction().name(),
                new BigDecimal(days),
                input.reason().trim(),
                input.idempotencyKey());
        var result = load(id, false);
        publish(result, employee, "SUBMITTED", input.reason().trim());
        return result;
    }

    private long resolve(long employee) {
        var manager = jdbc.queryForList(
                "SELECT m.user_id FROM employee_projection e JOIN employee_projection m ON m.user_id=e.reporting_manager_user_id AND m.active=true WHERE e.user_id=? AND m.user_id<>e.user_id",
                Long.class,
                employee);
        if (!manager.isEmpty()) return manager.getFirst();
        var fallback = jdbc.queryForList("""
            SELECT p.user_id FROM employee_projection p JOIN leave_reviewer r ON r.user_id=p.user_id
            JOIN employee_projection e ON e.user_id=? WHERE p.active=true AND p.user_id<>e.user_id
            AND (r.role='HR_MANAGER' OR (r.role='DEPARTMENT_HEAD' AND p.department_id=e.department_id))
            ORDER BY CASE WHEN r.role='DEPARTMENT_HEAD' THEN 0 ELSE 1 END,p.user_id
            """, Long.class, employee);
        if (fallback.isEmpty()) throw conflict("No eligible approver; reconcile manager or fallback reviewer");
        return fallback.getFirst();
    }

    private boolean eligible(long employee, long reviewer) {
        return employee != reviewer && Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM employee_projection e JOIN employee_projection p ON p.user_id=? AND p.active=true
              LEFT JOIN leave_reviewer r ON r.user_id=p.user_id WHERE e.user_id=? AND
              (e.reporting_manager_user_id=p.user_id OR r.role='HR_MANAGER' OR (r.role='DEPARTMENT_HEAD' AND p.department_id=e.department_id)))
            """, Boolean.class, reviewer, employee));
    }

    public boolean hrReviewer(long actor, boolean hrRole) {
        return hrRole
                && Boolean.TRUE.equals(jdbc.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM employee_projection WHERE user_id=? AND active=true)",
                        Boolean.class,
                        actor));
    }

    public LeaveRequest get(UUID id, long actor, boolean hr) {
        var r = load(id, false);
        if (r.employeeId() != actor
                && !(r.approverId() == actor && eligible(r.employeeId(), actor))
                && !hrReviewer(actor, hr)) throw forbidden();
        return r;
    }

    public List<LeaveRequest> mine(long actor) {
        return jdbc.query(
                "SELECT * FROM leave_request WHERE employee_id=? ORDER BY created_at DESC,id", this::map, actor);
    }

    public List<LeaveRequest> inbox(long actor, boolean hr) {
        // Apply the same eligibility predicate as detail access; never return an unscoped list.
        return jdbc
                .query(
                        "SELECT * FROM leave_request WHERE employee_id<>? AND status='PENDING' AND (approver_id=? OR ?) ORDER BY created_at,id",
                        this::map,
                        actor,
                        actor,
                        hrReviewer(actor, hr))
                .stream()
                .filter(r -> hrReviewer(actor, hr) || eligible(r.employeeId(), actor))
                .toList();
    }

    @Transactional
    public LeaveRequest finish(UUID id, long actor, Action input, boolean reject) {
        var r = load(id, true);
        if (reject ? r.approverId() != actor || !eligible(r.employeeId(), actor) : r.employeeId() != actor)
            throw forbidden();
        String action = reject ? "REJECTED" : "CANCELLED";
        if (retry(id, input.idempotencyKey(), actor, action, input.reason(), null)) return r;
        if (r.status() != Status.PENDING) {
            // A repeated owner cancellation is safe even if the caller generated a new key.
            if (!reject && r.status() == Status.CANCELLED) {
                record(id, input.idempotencyKey(), actor, action, input.reason(), null);
                return r;
            }
            throw conflict("Only pending requests may be rejected or cancelled in this increment");
        }
        balances.settle(r.entitlementId(), id, false, actor, input.reason());
        jdbc.update(
                "UPDATE leave_request SET status=?,version=version+1,updated_at=clock_timestamp() WHERE id=?",
                action,
                id);
        record(id, input.idempotencyKey(), actor, action, input.reason(), null);
        var result = load(id, false);
        publish(result, actor, action, input.reason().trim());
        return result;
    }

    @Transactional
    public LeaveRequest route(UUID id, long actor, Route input) {
        balances.activeEmployee(actor);
        var r = load(id, true);
        if (r.employeeId() == actor) throw forbidden();
        if (retry(id, input.idempotencyKey(), actor, "ROUTED", input.reason(), input.approverId())) return r;
        if (r.status() != Status.PENDING) throw conflict("Only pending requests may be routed");
        if (!eligible(r.employeeId(), input.approverId()))
            throw conflict("Target must be an active manager, same-department head or HR reviewer");
        if (r.approverId() == input.approverId()) throw conflict("Choose a different approver");
        jdbc.update(
                "UPDATE leave_request SET approver_id=?,version=version+1,updated_at=clock_timestamp() WHERE id=?",
                input.approverId(),
                id);
        record(id, input.idempotencyKey(), actor, "ROUTED", input.reason(), input.approverId());
        var result = load(id, false);
        publish(result, actor, "ROUTED", input.reason().trim());
        return result;
    }

    @Transactional
    public void reviewer(long user, ReviewerSnapshot input) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(?)", Object.class, user);
        balances.activeEmployee(user);
        jdbc.update(
                "INSERT INTO leave_reviewer(user_id,role,as_of) VALUES (?,?,?) ON CONFLICT(user_id) DO UPDATE SET role=excluded.role,as_of=excluded.as_of WHERE leave_reviewer.as_of<excluded.as_of",
                user,
                input.role() == null ? null : input.role().name(),
                Timestamp.from(input.asOf()));
    }

    private boolean retry(UUID id, String key, long actor, String action, String reason, Long approver) {
        var rows = jdbc.queryForList(
                "SELECT * FROM leave_request_action WHERE request_id=? AND idempotency_key=?", id, key);
        if (rows.isEmpty()) return false;
        var old = rows.getFirst();
        if (((Number) old.get("actor_id")).longValue() != actor
                || !old.get("action").equals(action)
                || !old.get("reason").equals(reason.trim())
                || !Objects.equals(old.get("approver_id"), approver))
            throw conflict("Idempotency key already used for different action");
        return true;
    }

    private void record(UUID id, String key, long actor, String action, String reason, Long approver) {
        jdbc.update(
                "INSERT INTO leave_request_action(request_id,idempotency_key,actor_id,action,reason,approver_id) VALUES (?,?,?,?,?,?)",
                id,
                key,
                actor,
                action,
                reason.trim(),
                approver);
    }

    private void publish(LeaveRequest r, long actor, String action, String reason) {
        outbox.publish(
                "leave_request",
                r.id().toString(),
                "leave.request.changed",
                Map.of("request", r, "actorId", actor, "action", action, "reason", reason));
    }

    private LeaveRequest load(UUID id, boolean lock) {
        var rows = jdbc.query("SELECT * FROM leave_request WHERE id=?" + (lock ? " FOR UPDATE" : ""), this::map, id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Leave request not found");
        return rows.getFirst();
    }

    private LeaveRequest map(ResultSet r, int n) throws SQLException {
        return new LeaveRequest(
                r.getObject("id", UUID.class),
                r.getLong("employee_id"),
                r.getObject("type_id", UUID.class),
                r.getObject("entitlement_id", UUID.class),
                r.getLong("approver_id"),
                r.getDate("start_date").toLocalDate(),
                r.getDate("end_date").toLocalDate(),
                Fraction.valueOf(r.getString("day_fraction")),
                r.getBigDecimal("calculated_days").setScale(2).toPlainString(),
                r.getString("reason"),
                Status.valueOf(r.getString("status")),
                r.getLong("version"),
                r.getTimestamp("created_at").toInstant(),
                r.getTimestamp("updated_at").toInstant());
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException forbidden() {
        return new ResponseStatusException(
                HttpStatus.FORBIDDEN, "Only the owner or eligible reviewer may perform this action");
    }
}
