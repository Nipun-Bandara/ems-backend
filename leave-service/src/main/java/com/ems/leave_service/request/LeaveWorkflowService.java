package com.ems.leave_service.request;

import static com.ems.leave_service.request.WorkflowModels.*;

import com.ems.common.outbox.OutboxPublisher;
import com.ems.leave_service.balance.LeaveBalanceService;
import com.ems.leave_service.balance.WorkingCalendar;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Persisted state machine. Every step and its next command commit with the local effect.
 * The command contains a fencing step ID; delivery order and broker availability are irrelevant.
 * Request-row then entitlement-row is the lock order used by all request mutations.
 */
@Service
public class LeaveWorkflowService {
    private final JdbcTemplate jdbc;
    private final LeaveRequestService requests;
    private final LeaveBalanceService balances;
    private final OutboxPublisher outbox;
    private final WorkflowFaults faults;
    private final WorkingCalendar calendar;

    public LeaveWorkflowService(
            JdbcTemplate jdbc,
            LeaveRequestService requests,
            LeaveBalanceService balances,
            OutboxPublisher outbox,
            WorkflowFaults faults,
            WorkingCalendar calendar) {
        this.jdbc = jdbc;
        this.requests = requests;
        this.balances = balances;
        this.outbox = outbox;
        this.faults = faults;
        this.calendar = calendar;
    }

    @Transactional
    public Workflow start(UUID requestId, long actor, Start input, boolean cancel) {
        var r = requests.load(requestId, true);
        if (cancel ? r.employeeId() != actor : r.approverId() != actor || !requests.eligible(r.employeeId(), actor))
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Owner or assigned eligible reviewer required; self-approval is forbidden");
        String operation = cancel ? "CANCEL" : "APPROVE";
        var old = jdbc.query(
                "SELECT * FROM leave_workflow WHERE request_id=? AND idempotency_key=?",
                this::map,
                requestId,
                input.idempotencyKey());
        if (!old.isEmpty()) {
            var w = old.getFirst();
            var row = jdbc.queryForMap("SELECT * FROM leave_workflow WHERE id=?", w.id());
            if (!w.operation().equals(operation)
                    || ((Number) row.get("actor_id")).longValue() != actor
                    || !row.get("reason").equals(input.reason().trim())
                    || ((Number) row.get("expected_version")).longValue() != input.version())
                throw conflict("Idempotency key already used for different input");
            return w;
        }
        if (jdbc.queryForObject(
                        "SELECT count(*) FROM leave_request_action WHERE request_id=? AND idempotency_key=?",
                        Integer.class,
                        requestId,
                        input.idempotencyKey())
                > 0) throw conflict("Idempotency key already used for another action");
        if (jdbc.queryForObject(
                        "SELECT count(*) FROM leave_workflow WHERE request_id=? AND operation=?",
                        Integer.class,
                        requestId,
                        operation)
                > 0) throw conflict("Workflow already exists; read request workflow progress");
        if (r.version() != input.version()) throw conflict("Request changed; refresh before confirming");
        if (cancel
                ? !Set.of("APPROVAL_IN_PROGRESS", "APPROVED")
                        .contains(r.status().name())
                : !r.status().name().equals("PENDING"))
            throw conflict("Request cannot start this workflow in its current state");
        // Cancellation fences the old approval before starting any reversal.
        if (cancel) {
            var approval = jdbc.query(
                    "SELECT * FROM leave_workflow WHERE request_id=? AND operation='APPROVE'", this::map, requestId);
            if (approval.isEmpty()) throw conflict("Missing approval workflow");
            if (!terminal(approval.getFirst().state())) transition(approval.getFirst(), "CANCELLED", null);
        }
        UUID id = UUID.randomUUID(), step = UUID.randomUUID();
        String state = cancel ? "REMOVE_CALENDAR" : "DEBIT";
        jdbc.update(
                "INSERT INTO leave_workflow(id,request_id,operation,state,step_id,actor_id,reason,expected_version,idempotency_key) VALUES (?,?,?,?,?,?,?,?,?)",
                id,
                requestId,
                operation,
                state,
                step,
                actor,
                input.reason().trim(),
                input.version(),
                input.idempotencyKey());
        jdbc.update(
                "INSERT INTO leave_request_action(request_id,idempotency_key,actor_id,action,reason) VALUES (?,?,?,?,?)",
                requestId,
                input.idempotencyKey(),
                actor,
                operation,
                input.reason().trim());
        if (!cancel)
            jdbc.update(
                    "UPDATE leave_request SET status='APPROVAL_IN_PROGRESS',version=version+1,updated_at=clock_timestamp() WHERE id=? AND version=?",
                    requestId,
                    input.version());
        else
            jdbc.update(
                    "UPDATE leave_request SET version=version+1,updated_at=clock_timestamp() WHERE id=?", requestId);
        var result = load(id);
        audit(result);
        command(result);
        return result;
    }

    public Workflow get(UUID id, long actor, boolean hr) {
        var w = load(id);
        requests.get(w.requestId(), actor, hr);
        return w;
    }

    public List<Workflow> forRequest(UUID request, long actor, boolean hr) {
        requests.get(request, actor, hr);
        return jdbc.query("SELECT * FROM leave_workflow WHERE request_id=? ORDER BY created_at,id", this::map, request);
    }

    public Workflow load(UUID id) {
        var rows = jdbc.query("SELECT * FROM leave_workflow WHERE id=?", this::map, id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Workflow not found");
        return rows.getFirst();
    }

    public List<Workflow> due() {
        return jdbc.query(
                "SELECT * FROM leave_workflow WHERE state NOT IN ('APPROVED','APPROVAL_FAILED','CANCELLED') AND next_attempt<=now() ORDER BY next_attempt LIMIT 100",
                this::map);
    }

    @Transactional
    public void advance(UUID id, UUID step) {
        var initial = load(id);
        var r = requests.load(initial.requestId(), true);
        var w = load(id);
        if (terminal(w.state()) || !w.stepId().equals(step) || w.nextAttempt().isAfter(Instant.now())) return;
        var row = jdbc.queryForMap("SELECT * FROM leave_workflow WHERE id=?", id);
        long actor = ((Number) row.get("actor_id")).longValue();
        String reason = (String) row.get("reason");
        if (w.operation().equals("APPROVE")
                && (w.deadline().isBefore(Instant.now()) || w.attempts() >= 3)
                && !w.state().equals("COMPENSATE")) {
            transition(w, "COMPENSATE", "Required approval step exceeded its deadline or retries");
            return;
        }
        jdbc.update("UPDATE leave_workflow SET attempts=attempts+1 WHERE id=?", id);
        switch (w.state()) {
            case "DEBIT" -> {
                balances.settle(r.entitlementId(), r.id(), true, actor, reason);
                transition(w, "CALENDAR", null);
            }
            case "CALENDAR" -> {
                if (faults.calendarFails(r.id())) {
                    transition(w, "COMPENSATE", "Permanent calendar failure (injected)");
                    break;
                }
                jdbc.update(
                        "INSERT INTO leave_absence(request_id,workflow_id,employee_id,start_date,end_date,day_fraction) VALUES (?,?,?,?,?,?) ON CONFLICT(request_id) DO NOTHING",
                        r.id(),
                        w.id(),
                        r.employeeId(),
                        r.startDate(),
                        r.endDate(),
                        r.dayFraction().name());
                transition(w, "FINALIZE", null);
            }
            case "FINALIZE" -> {
                jdbc.update(
                        "UPDATE leave_request SET status='APPROVED',version=version+1,updated_at=clock_timestamp() WHERE id=?",
                        r.id());
                transition(w, "APPROVED", null);
                outbox.publish(
                        "leave_request",
                        r.id().toString(),
                        "leave.approved",
                        Map.of(
                                "workflowId",
                                id,
                                "requestId",
                                r.id(),
                                "employeeId",
                                r.employeeId(),
                                "startDate",
                                r.startDate(),
                                "endDate",
                                r.endDate()));
            }
            case "REMOVE_CALENDAR" -> {
                jdbc.update("DELETE FROM leave_absence WHERE request_id=?", r.id());
                transition(w, "RESTORE", null);
            }
            case "RESTORE", "COMPENSATE" -> {
                jdbc.update("DELETE FROM leave_absence WHERE request_id=?", r.id());
                balances.restore(r.entitlementId(), r.id(), actor, reason);
                String state = w.operation().equals("CANCEL") ? "CANCELLED" : "APPROVAL_FAILED";
                jdbc.update(
                        "UPDATE leave_request SET status=?,version=version+1,updated_at=clock_timestamp() WHERE id=?",
                        state,
                        r.id());
                transition(w, state, w.error());
                outbox.publish(
                        "leave_request",
                        r.id().toString(),
                        "leave.balance.restored",
                        Map.of("workflowId", id, "requestId", r.id(), "employeeId", r.employeeId(), "outcome", state));
            }
            default -> throw new IllegalStateException("Unknown persisted workflow state");
        }
        faults.checkpoint(r.id(), w.state());
    }

    /** Called in a separate transaction after a step rollback. Compensation retries without a limit. */
    @Transactional
    public void failed(UUID id, UUID step) {
        var initial = load(id);
        requests.load(initial.requestId(), true);
        var w = load(id);
        if (terminal(w.state()) || !w.stepId().equals(step)) return;
        jdbc.update(
                "UPDATE leave_workflow SET attempts=attempts+1,next_attempt=now()+interval '2 seconds',error='Step rolled back; retry scheduled',updated_at=clock_timestamp() WHERE id=?",
                id);
        audit(load(id));
    }

    private void transition(Workflow w, String state, String error) {
        audit(w);
        jdbc.update(
                "UPDATE leave_workflow SET state=?,step_id=?,attempts=0,deadline=now()+interval '30 seconds',next_attempt=now(),error=?,updated_at=clock_timestamp() WHERE id=?",
                state,
                UUID.randomUUID(),
                error,
                w.id());
        var next = load(w.id());
        audit(next);
        if (!terminal(state)) command(next);
    }

    private void audit(Workflow w) {
        jdbc.update(
                "INSERT INTO leave_workflow_audit(workflow_id,step_id,state,actor_id,reason,attempts,deadline,next_attempt,error) SELECT id,step_id,state,actor_id,reason,attempts,deadline,next_attempt,error FROM leave_workflow WHERE id=?",
                w.id());
    }

    private void command(Workflow w) {
        outbox.publish(
                "leave_workflow",
                w.id().toString(),
                "leave.workflow.step",
                Map.of("workflowId", w.id(), "stepId", w.stepId()));
    }

    public static boolean terminal(String state) {
        return Set.of("APPROVED", "APPROVAL_FAILED", "CANCELLED").contains(state);
    }

    private Workflow map(ResultSet r, int n) throws SQLException {
        return new Workflow(
                r.getObject("id", UUID.class),
                r.getObject("request_id", UUID.class),
                r.getString("operation"),
                r.getString("state"),
                r.getObject("step_id", UUID.class),
                r.getInt("attempts"),
                r.getTimestamp("deadline").toInstant(),
                r.getTimestamp("next_attempt").toInstant(),
                r.getString("error"));
    }

    public WorkflowModels.Calendar calendar(long actor, boolean hr, Long employee, Long department) {
        balances.activeEmployee(actor);
        if (employee != null && department != null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose employee or department scope");
        long selected = employee == null ? actor : employee;
        if (department != null) {
            boolean allowed = hr && requests.hrReviewer(actor, true)
                    || Boolean.TRUE.equals(jdbc.queryForObject(
                            "SELECT EXISTS(SELECT 1 FROM employee_projection p JOIN leave_reviewer r ON r.user_id=p.user_id WHERE p.user_id=? AND p.department_id=? AND r.role='DEPARTMENT_HEAD')",
                            Boolean.class,
                            actor,
                            department));
            if (!allowed)
                throw new ResponseStatusException(
                        HttpStatus.FORBIDDEN, "Department calendar requires HR or current department head");
        } else if (selected != actor && !(requests.hrReviewer(actor, hr) || requests.eligible(selected, actor)))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Employee calendar is outside your scope");
        var absences = jdbc.query(
                "SELECT a.* FROM leave_absence a JOIN leave_request q ON q.id=a.request_id JOIN employee_projection p ON p.user_id=a.employee_id WHERE q.status='APPROVED' AND "
                        + (department == null ? "a.employee_id=?" : "p.department_id=?")
                        + " ORDER BY a.start_date,a.request_id",
                (r, n) -> new Absence(
                        r.getObject("request_id", UUID.class),
                        r.getLong("employee_id"),
                        r.getDate("start_date").toLocalDate(),
                        r.getDate("end_date").toLocalDate(),
                        r.getString("day_fraction")),
                department == null ? selected : department);
        return new WorkflowModels.Calendar(
                calendar.policy().timezone(), calendar.policy().workweek(), absences);
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
