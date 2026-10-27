package com.ems.timesheet_service;

import static com.ems.timesheet_service.Models.*;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ProjectionStore {
    private final JdbcTemplate jdbc;
    private final OrganizationCalendar calendar;

    public ProjectionStore(JdbcTemplate jdbc, OrganizationCalendar calendar) {
        this.jdbc = jdbc;
        this.calendar = calendar;
    }

    public void lock(boolean write) {
        jdbc.queryForObject(
                write ? "SELECT pg_advisory_xact_lock(86,0)" : "SELECT pg_advisory_xact_lock_shared(86,0)",
                Object.class);
    }

    public void employee(long id, boolean active, Instant version) {
        jdbc.update(
                "INSERT INTO employee_projection(user_id,active,version) VALUES (?,?,?) ON CONFLICT(user_id) DO UPDATE SET active=excluded.active,version=excluded.version WHERE employee_projection.version<excluded.version",
                id,
                active,
                Timestamp.from(version));
    }

    public void project(ProjectSnapshot p, Instant version, LocalDate effective) {
        jdbc.update(
                "INSERT INTO project_history(project_id,team_id,name,deleted,start_date,end_date,version,effective_date) VALUES (?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING",
                p.id(),
                p.teamId(),
                p.name(),
                p.deleted(),
                p.startDate(),
                p.endDate(),
                Timestamp.from(version),
                effective);
    }

    public void membership(Membership m, Instant version, LocalDate effective) {
        jdbc.update(
                "INSERT INTO membership_history(team_id,user_id,member,version,effective_date) VALUES (?,?,?,?,?) ON CONFLICT DO NOTHING",
                m.teamId(),
                m.userId(),
                m.member(),
                Timestamp.from(version),
                effective);
    }

    public void active(long employee) {
        if (!Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM employee_projection WHERE user_id=? AND active)",
                Boolean.class,
                employee)))
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "An active employee projection is required; contact your administrator");
    }

    public List<Project> projects(long employee, LocalDate week) {
        calendar.validateWeek(week);
        Map<Long, String> names = new LinkedHashMap<>();
        Map<Long, List<LocalDate>> dates = new LinkedHashMap<>();
        for (int i = 0; i < 7; i++) {
            LocalDate day = week.plusDays(i);
            jdbc.query(
                    """
    SELECT p.project_id,p.name FROM
    (SELECT DISTINCT ON(project_id) * FROM project_history WHERE effective_date<=?
      ORDER BY project_id,effective_date DESC,version DESC) p
    JOIN LATERAL (SELECT member FROM membership_history WHERE team_id=p.team_id AND user_id=? AND effective_date<=?
      ORDER BY effective_date DESC,version DESC LIMIT 1) m ON m.member
    WHERE NOT p.deleted AND (p.start_date IS NULL OR p.start_date<=?) AND (p.end_date IS NULL OR p.end_date>=?)
    ORDER BY p.name,p.project_id
    """,
                    (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                        long id = rs.getLong(1);
                        names.put(id, rs.getString(2));
                        dates.computeIfAbsent(id, k -> new ArrayList<>()).add(day);
                    },
                    day,
                    employee,
                    day,
                    day,
                    day);
        }
        return names.entrySet().stream()
                .map(e -> new Project(e.getKey(), e.getValue(), dates.get(e.getKey())))
                .toList();
    }

    @Transactional
    public void backfill(long actor, Backfill input) {
        if (input.effectiveDate().isAfter(calendar.date(input.asOf())))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Effective date cannot follow snapshot date");
        lock(true);
        input.employees().forEach(e -> employee(e.userId(), e.active(), input.asOf()));
        input.projects().forEach(p -> project(p, input.asOf(), input.effectiveDate()));
        input.memberships().forEach(m -> membership(m, input.asOf(), input.effectiveDate()));
        jdbc.update(
                "INSERT INTO projection_audit(actor_id,as_of,employees,projects,memberships) VALUES (?,?,?,?,?)",
                actor,
                Timestamp.from(input.asOf()),
                input.employees().size(),
                input.projects().size(),
                input.memberships().size());
    }
}
