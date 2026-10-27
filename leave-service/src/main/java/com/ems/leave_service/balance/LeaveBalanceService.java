package com.ems.leave_service.balance;

import static com.ems.leave_service.balance.LeaveModels.*;

import com.ems.common.outbox.OutboxPublisher;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class LeaveBalanceService {
    private final JdbcTemplate jdbc;
    private final OutboxPublisher outbox;

    public LeaveBalanceService(JdbcTemplate jdbc, OutboxPublisher outbox) {
        this.jdbc = jdbc;
        this.outbox = outbox;
    }

    public List<Type> types() {
        return jdbc.query(
                "SELECT * FROM leave_type ORDER BY code",
                (r, n) -> new Type(
                        r.getObject("id", UUID.class),
                        r.getString("code"),
                        r.getString("name"),
                        r.getBoolean("active")));
    }

    @Transactional
    public Type saveType(UUID id, TypeInput input) {
        UUID target = id == null ? UUID.randomUUID() : id;
        // Serialize code uniqueness so conflicts remain domain errors rather than SQL errors.
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(734812)", Object.class);
        if (Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM leave_type WHERE code=? AND id<>?)", Boolean.class, input.code(), target)))
            throw conflict("Leave type code already exists");
        if (id == null)
            jdbc.update(
                    "INSERT INTO leave_type(id,code,name,active) VALUES (?,?,?,?)",
                    target,
                    input.code(),
                    input.name().trim(),
                    input.active());
        else if (jdbc.update(
                        "UPDATE leave_type SET code=?, name=?, active=? WHERE id=?",
                        input.code(),
                        input.name().trim(),
                        input.active(),
                        target)
                == 0) throw missing();
        return new Type(target, input.code(), input.name().trim(), input.active());
    }

    @Transactional
    public Balance allocate(Allocation input, long actor) {
        // Existing projection lock also serializes reconciliation/status changes.
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(?)", Object.class, input.employeeId());
        activeEmployee(input.employeeId());
        var type =
                jdbc.queryForList("SELECT active FROM leave_type WHERE id=? FOR UPDATE", Boolean.class, input.typeId());
        if (type.isEmpty()) throw missing();
        if (!type.getFirst()) throw conflict("Leave type is inactive");
        UUID id = UUID.randomUUID();
        int inserted = jdbc.update(
                """
                INSERT INTO leave_entitlement(id,employee_id,type_id,year,days,actor_id,reason)
                VALUES (?,?,?,?,?,?,?) ON CONFLICT(employee_id,type_id,year) DO NOTHING
                """,
                id,
                input.employeeId(),
                input.typeId(),
                input.year(),
                decimal(input.days()),
                actor,
                input.reason().trim());
        if (inserted == 0) throw conflict("Yearly entitlement already allocated; use an adjustment");
        outbox.publish(
                "leave_entitlement",
                id.toString(),
                "leave.entitlement.allocated",
                Map.of(
                        "entitlementId",
                        id,
                        "employeeId",
                        input.employeeId(),
                        "typeId",
                        input.typeId(),
                        "year",
                        input.year(),
                        "days",
                        decimal(input.days()).toPlainString(),
                        "actorId",
                        actor,
                        "reason",
                        input.reason().trim()));
        return balance(id);
    }

    public void activeEmployee(long employee) {
        if (!Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM employee_projection WHERE user_id=? AND active=true)",
                Boolean.class,
                employee)))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Active employee projection required");
    }

    public List<Balance> balances(long employee, int year) {
        return jdbc
                .query(
                        "SELECT id FROM leave_entitlement WHERE employee_id=? AND year=? ORDER BY type_id",
                        (r, n) -> r.getObject(1, UUID.class),
                        employee,
                        year)
                .stream()
                .map(this::balance)
                .toList();
    }

    public Balance balance(UUID id) {
        var rows = jdbc.query(
                """
                SELECT e.*, t.name AS type_name,
                  COALESCE((SELECT sum(days) FROM leave_balance_entry WHERE entitlement_id=e.id AND kind='ADJUSTMENT'),0) AS adjustments,
                  COALESCE((SELECT -sum(days) FROM leave_balance_entry WHERE entitlement_id=e.id AND kind='TAKEN'),0) AS taken,
                  COALESCE((SELECT sum(days) FROM leave_reservation WHERE entitlement_id=e.id AND status='ACTIVE'),0) AS reserved
                FROM leave_entitlement e JOIN leave_type t ON t.id=e.type_id WHERE e.id=?
                """,
                (r, n) -> {
                    BigDecimal entitlement = r.getBigDecimal("days"),
                            adjustments = r.getBigDecimal("adjustments"),
                            taken = r.getBigDecimal("taken"),
                            reserved = r.getBigDecimal("reserved");
                    return new Balance(
                            id,
                            r.getLong("employee_id"),
                            r.getObject("type_id", UUID.class),
                            r.getString("type_name"),
                            r.getInt("year"),
                            text(entitlement),
                            text(adjustments),
                            text(taken),
                            text(reserved),
                            text(entitlement.add(adjustments).subtract(taken).subtract(reserved)));
                },
                id);
        if (rows.isEmpty()) throw missing();
        return rows.getFirst();
    }

    private void lock(UUID id) {
        if (jdbc.queryForList("SELECT id FROM leave_entitlement WHERE id=? FOR UPDATE", UUID.class, id)
                .isEmpty()) throw missing();
    }

    @Transactional
    public Entry adjust(UUID id, Adjustment input, long actor) {
        if (input.idempotencyKey().startsWith("reservation:"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The reservation: key namespace is reserved");
        lock(id);
        var existing = jdbc.query(
                "SELECT * FROM leave_balance_entry WHERE entitlement_id=? AND idempotency_key=?",
                this::entry,
                id,
                input.idempotencyKey());
        if (!existing.isEmpty()) {
            Entry old = existing.getFirst();
            if (!old.kind().equals("ADJUSTMENT")
                    || new BigDecimal(old.days()).compareTo(input.days()) != 0
                    || !old.reason().equals(input.reason().trim())
                    || old.actorId() != actor) throw conflict("Idempotency key was already used for different input");
            return old;
        }
        BigDecimal delta = decimal(input.days());
        if (delta.signum() == 0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Adjustment must be nonzero");
        if (new BigDecimal(balance(id).available()).add(delta).signum() < 0)
            throw conflict("Insufficient available balance");
        Entry entry = insertEntry(
                id,
                "ADJUSTMENT",
                delta,
                input.idempotencyKey(),
                actor,
                input.reason().trim());
        outbox.publish("leave_entitlement", id.toString(), "leave.balance.adjusted", entry);
        return entry;
    }

    public List<Entry> entries(UUID id) {
        balance(id);
        return jdbc.query(
                "SELECT * FROM leave_balance_entry WHERE entitlement_id=? ORDER BY created_at,id", this::entry, id);
    }

    private Entry insertEntry(UUID id, String kind, BigDecimal days, String key, long actor, String reason) {
        UUID entryId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO leave_balance_entry(id,entitlement_id,kind,days,idempotency_key,actor_id,reason) VALUES (?,?,?,?,?,?,?)",
                entryId,
                id,
                kind,
                days,
                key,
                actor,
                reason);
        return jdbc.queryForObject("SELECT * FROM leave_balance_entry WHERE id=?", this::entry, entryId);
    }

    private Entry entry(ResultSet r, int n) throws SQLException {
        return new Entry(
                r.getObject("id", UUID.class),
                r.getObject("entitlement_id", UUID.class),
                r.getString("kind"),
                text(r.getBigDecimal("days")),
                r.getString("idempotency_key"),
                r.getLong("actor_id"),
                r.getString("reason"),
                r.getTimestamp("created_at").toInstant());
    }
    /** Internal integration seam for the later request workflow; no public reservation HTTP API. */
    @Transactional
    public UUID reserve(UUID entitlementId, UUID reservationId, BigDecimal requestedDays, long actor, String reason) {
        lock(entitlementId);
        BigDecimal days = decimal(requestedDays);
        if (days.signum() <= 0 || actor <= 0 || reason == null || reason.isBlank() || reason.length() > 500)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Positive days, actor and reason required");
        var old = jdbc.queryForList("SELECT * FROM leave_reservation WHERE id=?", reservationId);
        if (!old.isEmpty()) {
            var row = old.getFirst();
            if (!row.get("entitlement_id").equals(entitlementId)
                    || ((BigDecimal) row.get("days")).compareTo(days) != 0
                    || ((Number) row.get("actor_id")).longValue() != actor
                    || !row.get("reason").equals(reason.trim()))
                throw conflict("Reservation ID was already used for different input");
            return reservationId;
        }
        Balance balance = balance(entitlementId);
        activeEmployee(balance.employeeId());
        if (!Boolean.TRUE.equals(
                jdbc.queryForObject("SELECT active FROM leave_type WHERE id=?", Boolean.class, balance.typeId())))
            throw conflict("Leave type is inactive");
        if (new BigDecimal(balance.available()).compareTo(days) < 0) throw conflict("Insufficient available balance");
        jdbc.update(
                "INSERT INTO leave_reservation(id,entitlement_id,days,status,actor_id,reason) VALUES (?,?,?,'ACTIVE',?,?)",
                reservationId,
                entitlementId,
                days,
                actor,
                reason.trim());
        return reservationId;
    }

    @Transactional
    public void settle(UUID entitlementId, UUID reservationId, boolean taken, long actor, String reason) {
        lock(entitlementId);
        if (actor <= 0 || reason == null || reason.isBlank() || reason.length() > 500)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Actor and reason required");
        var rows = jdbc.queryForList(
                "SELECT * FROM leave_reservation WHERE id=? AND entitlement_id=?", reservationId, entitlementId);
        if (rows.isEmpty()) throw missing();
        var row = rows.getFirst();
        String target = taken ? "TAKEN" : "RELEASED";
        if (row.get("status").equals(target)) return;
        if (!row.get("status").equals("ACTIVE")) throw conflict("Reservation already settled differently");
        jdbc.update("UPDATE leave_reservation SET status=? WHERE id=?", target, reservationId);
        if (taken)
            insertEntry(
                    entitlementId,
                    "TAKEN",
                    ((BigDecimal) row.get("days")).negate(),
                    "reservation:" + reservationId,
                    actor,
                    reason.trim());
    }

    private static BigDecimal decimal(BigDecimal days) {
        try {
            if (days == null || days.precision() - days.scale() > 7) throw new ArithmeticException();
            return days.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ex) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Days require at most seven integer and two decimal digits");
        }
    }

    private static String text(BigDecimal days) {
        return days.setScale(2).toPlainString();
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private static ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Leave resource not found");
    }
}
