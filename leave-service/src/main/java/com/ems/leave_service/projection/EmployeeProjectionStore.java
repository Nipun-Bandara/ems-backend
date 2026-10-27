package com.ems.leave_service.projection;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class EmployeeProjectionStore {
    private final JdbcTemplate jdbc;

    public EmployeeProjectionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void lockAndCreate(long userId) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(?)", Object.class, userId);
        jdbc.update("INSERT INTO employee_projection(user_id) VALUES (?) ON CONFLICT DO NOTHING", userId);
    }

    public void status(long userId, boolean active, Instant at) {
        jdbc.update("""
                UPDATE employee_projection SET active = ?, status_at = ?
                WHERE user_id = ? AND (status_at IS NULL OR status_at < ?)
                """, active, Timestamp.from(at), userId, Timestamp.from(at));
    }

    public void assignment(long userId, Long departmentId, Instant at) {
        jdbc.update("""
                UPDATE employee_projection SET department_id = ?, assignment_at = ?
                WHERE user_id = ? AND (assignment_at IS NULL OR assignment_at < ?)
                """, departmentId, Timestamp.from(at), userId, Timestamp.from(at));
    }

    public void profile(long userId, Long managerId, Instant at) {
        jdbc.update("""
                UPDATE employee_projection SET reporting_manager_user_id = ?, profile_at = ?
                WHERE user_id = ? AND (profile_at IS NULL OR profile_at < ?)
                """, managerId, Timestamp.from(at), userId, Timestamp.from(at));
    }
}
