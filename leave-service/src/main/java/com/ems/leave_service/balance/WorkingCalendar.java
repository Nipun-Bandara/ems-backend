package com.ems.leave_service.balance;

import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class WorkingCalendar {
    private final JdbcTemplate jdbc;
    private final ZoneId timezone;
    private final Set<DayOfWeek> workweek;

    public WorkingCalendar(
            JdbcTemplate jdbc,
            @Value("${ems.leave.timezone:Asia/Colombo}") String timezone,
            @Value("${ems.leave.workweek:MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY}") String workweek) {
        this.jdbc = jdbc;
        this.timezone = ZoneId.of(timezone);
        var days = EnumSet.noneOf(DayOfWeek.class);
        for (String day : workweek.split(",")) days.add(DayOfWeek.valueOf(day.trim()));
        if (days.isEmpty()) throw new IllegalArgumentException("Workweek must not be empty");
        this.workweek = Collections.unmodifiableSet(days);
    }

    public record Holiday(
            @NotNull LocalDate date,
            @NotBlank @Size(max = 100) String name) {}

    public record Policy(String timezone, List<String> workweek) {}

    public Policy policy() {
        return new Policy(timezone.getId(), workweek.stream().map(Enum::name).toList());
    }

    public int currentYear() {
        return LocalDate.now(timezone).getYear();
    }

    public List<Holiday> holidays(int year) {
        return jdbc.query(
                "SELECT date,name FROM organization_holiday WHERE date>=? AND date<? ORDER BY date",
                (r, n) -> new Holiday(r.getDate(1).toLocalDate(), r.getString(2)),
                LocalDate.of(year, 1, 1),
                LocalDate.of(year + 1, 1, 1));
    }

    public void saveHoliday(Holiday input) {
        jdbc.update(
                "INSERT INTO organization_holiday(date,name) VALUES (?,?) ON CONFLICT(date) DO UPDATE SET name=excluded.name",
                input.date(),
                input.name().trim());
    }

    public void deleteHoliday(LocalDate date) {
        jdbc.update("DELETE FROM organization_holiday WHERE date=?", date);
    }
    /** Inclusive local dates, bounded to one entitlement year; no instant-to-UTC date conversion. */
    public long workingDays(LocalDate start, LocalDate end) {
        if (start == null || end == null || end.isBefore(start) || start.getYear() != end.getYear())
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Dates must be ordered within one entitlement year");
        Set<LocalDate> excluded = new HashSet<>(
                holidays(start.getYear()).stream().map(Holiday::date).toList());
        return start.datesUntil(end.plusDays(1))
                .filter(d -> workweek.contains(d.getDayOfWeek()) && !excluded.contains(d))
                .count();
    }
}
