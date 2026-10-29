package com.ems.claim_service;

import static com.ems.claim_service.Models.*;

import com.ems.common.outbox.OutboxPublisher;
import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional
public class ClaimService {
    private final JdbcTemplate jdbc;
    private final EmployeeScopeStore scope;
    private final OutboxPublisher outbox;
    private final String currency;

    public ClaimService(
            JdbcTemplate jdbc,
            EmployeeScopeStore scope,
            OutboxPublisher outbox,
            @Value("${ems.claims.currency:LKR}") String currency) {
        this.jdbc = jdbc;
        this.scope = scope;
        this.outbox = outbox;
        this.currency = Currency.getInstance(currency).getCurrencyCode();
        if (Currency.getInstance(currency).getDefaultFractionDigits() != 2)
            throw new IllegalArgumentException("Claims require a currency with two minor-unit digits");
    }

    public Permissions permissions(long actor) {
        scope.lock(false);
        return new Permissions(scope.active(actor), scope.reviewer(actor), currency);
    }

    public Claim create(long actor, Create input) {
        scope.lock(false);
        scope.requireActive(actor);
        if (!currency.equals(input.currency())) throw bad("Use the configured claim currency: " + currency);
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO claim(id,employee_id,currency) VALUES (?,?,?)", id, actor, currency);
        history(id, actor, "CREATED", "", 0);
        return load(id, actor, false, false);
    }

    public List<Claim> mine(long actor) {
        scope.lock(false);
        scope.requireActive(actor);
        return jdbc.query(
                "SELECT * FROM claim WHERE employee_id=? ORDER BY created_at DESC,id FOR SHARE", this::map, actor);
    }

    public Claim get(UUID id, long actor) {
        scope.lock(false);
        scope.requireActive(actor);
        return load(id, actor, false, false);
    }

    private Claim load(UUID id, long actor, boolean review, boolean lock) {
        var rows = jdbc.query(
                "SELECT * FROM claim WHERE id=?" + (review ? "" : " AND employee_id=?")
                        + (lock ? " FOR UPDATE" : " FOR SHARE"),
                this::map,
                review ? new Object[] {id} : new Object[] {id, actor});
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Claim not found");
        var claim = rows.getFirst();
        if (review) scope.require(claim.employeeId(), actor);
        return claim;
    }

    public Claim save(UUID id, long actor, Save input) {
        scope.lock(false);
        scope.requireActive(actor);
        var claim = load(id, actor, false, true);
        draft(claim, input.version());
        BigDecimal total = validate(claim.currency(), input.lines());
        jdbc.update("DELETE FROM claim_line WHERE claim_id=?", id);
        for (int i = 0; i < input.lines().size(); i++) {
            var line = input.lines().get(i);
            jdbc.update(
                    "INSERT INTO claim_line(claim_id,position,category,expense_date,description,currency,amount) VALUES (?,?,?,?,?,?,?)",
                    id,
                    i,
                    line.category().trim(),
                    line.expenseDate(),
                    line.description().trim(),
                    line.currency(),
                    new BigDecimal(line.amount()));
        }
        jdbc.update("UPDATE claim SET total=?,version=version+1,updated_at=now() WHERE id=?", total, id);
        history(id, actor, "SAVED", "", claim.version() + 1);
        return load(id, actor, false, false);
    }

    public void delete(UUID id, long actor, long version) {
        scope.lock(false);
        scope.requireActive(actor);
        var claim = load(id, actor, false, true);
        draft(claim, version);
        jdbc.update("DELETE FROM claim WHERE id=?", id);
    }

    public Claim submit(UUID id, long actor, Transition input) {
        scope.lock(false);
        scope.requireActive(actor);
        var claim = load(id, actor, false, true);
        if (claim.submittedAt() != null) return claim;
        draft(claim, input.version());
        if (claim.lines().isEmpty()) throw bad("At least one positive line item is required");
        var total = validate(claim.currency(), claim.lines());
        jdbc.update(
                "UPDATE claim SET total=?,status='SUBMITTED',submitted_at=now(),updated_at=now(),version=version+1 WHERE id=?",
                total,
                id);
        history(id, actor, "SUBMITTED", "", claim.version() + 1);
        var result = load(id, actor, false, false);
        publish(result, "submitted");
        return result;
    }

    public Claim withdraw(UUID id, long actor, Transition input) {
        scope.lock(false);
        scope.requireActive(actor);
        var claim = load(id, actor, false, true);
        if (claim.status() == Status.WITHDRAWN) return claim;
        pending(claim, input.version());
        jdbc.update("UPDATE claim SET status='WITHDRAWN',version=version+1,updated_at=now() WHERE id=?", id);
        history(id, actor, "WITHDRAWN", "", claim.version() + 1);
        return load(id, actor, false, false);
    }

    public List<Claim> inbox(long actor) {
        scope.lock(false);
        if (!scope.reviewer(actor)) throw EmployeeScopeStore.forbidden();
        return jdbc.query(
                "SELECT c.* FROM claim c WHERE status='SUBMITTED' AND " + EmployeeScopeStore.ELIGIBLE
                        + " ORDER BY created_at,id FOR SHARE OF c",
                this::map,
                actor);
    }

    public Claim detail(UUID id, long actor) {
        scope.lock(false);
        var claim = load(id, actor, true, false);
        if (claim.status() == Status.DRAFT) throw EmployeeScopeStore.forbidden();
        return claim;
    }

    public Claim decide(UUID id, long actor, Transition input, boolean reject) {
        scope.lock(false);
        var claim = load(id, actor, true, true);
        pending(claim, input.version());
        String reason = input.reason() == null ? "" : input.reason().trim();
        if (reject && reason.isBlank()) throw bad("A rejection reason is required");
        var status = reject ? Status.REJECTED : Status.APPROVED;
        int changed = jdbc.update(
                "UPDATE claim SET status=?,version=version+1,updated_at=now() WHERE id=? AND status='SUBMITTED' AND version=?",
                status.name(),
                id,
                input.version());
        if (changed != 1) throw conflict();
        history(id, actor, status.name(), reason, claim.version() + 1);
        var result = load(id, actor, true, false);
        publish(result, reject ? "rejected" : "approved");
        return result;
    }

    private void publish(Claim claim, String action) {
        outbox.publish("claim", claim.id().toString(), "claim." + action, claim);
    }

    private void pending(Claim claim, long version) {
        if (claim.status() != Status.SUBMITTED || claim.version() != version) throw conflict();
    }

    private void draft(Claim claim, long version) {
        if (claim.status() != Status.DRAFT || claim.version() != version)
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Only the current draft can be changed; reload before saving");
    }

    private BigDecimal validate(String currency, List<Line> lines) {
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        for (var l : lines) {
            if (!currency.equals(l.currency())) throw bad("All lines must use claim currency " + currency);
            BigDecimal amount = new BigDecimal(l.amount());
            if (amount.signum() <= 0 || amount.scale() > 2 || amount.precision() - amount.scale() > 17)
                throw bad("Amounts must be positive with at most two decimal places");
            total = total.add(amount);
        }
        if (total.precision() - total.scale() > 17) throw bad("Claim total is too large");
        return total;
    }

    private void history(UUID id, long actor, String action, String reason, long version) {
        jdbc.update(
                "INSERT INTO claim_history(id,claim_id,action,actor_id,reason,version) VALUES (?,?,?,?,?,?)",
                UUID.randomUUID(),
                id,
                action,
                actor,
                reason,
                version);
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException conflict() {
        return new ResponseStatusException(
                HttpStatus.CONFLICT, "Claim has changed or is no longer pending; reload before reviewing");
    }

    private Instant instant(ResultSet r, String key) throws SQLException {
        var t = r.getTimestamp(key);
        return t == null ? null : t.toInstant();
    }

    private Claim map(ResultSet r, int n) throws SQLException {
        UUID id = r.getObject("id", UUID.class);
        var lines = jdbc.query(
                "SELECT * FROM claim_line WHERE claim_id=? ORDER BY position",
                (v, i) -> new Line(
                        v.getString("category"),
                        v.getDate("expense_date").toLocalDate(),
                        v.getString("description"),
                        v.getString("currency"),
                        v.getBigDecimal("amount").toPlainString()),
                id);
        var history = jdbc.query(
                "SELECT * FROM claim_history WHERE claim_id=? ORDER BY version",
                (v, i) -> new History(
                        v.getObject("id", UUID.class),
                        v.getString("action"),
                        v.getLong("actor_id"),
                        v.getString("reason"),
                        v.getLong("version"),
                        v.getTimestamp("occurred_at").toInstant()),
                id);
        return new Claim(
                id,
                r.getLong("employee_id"),
                r.getString("currency"),
                Status.valueOf(r.getString("status")),
                r.getBigDecimal("total").toPlainString(),
                r.getLong("version"),
                lines,
                instant(r, "created_at"),
                instant(r, "updated_at"),
                instant(r, "submitted_at"),
                history);
    }
}
