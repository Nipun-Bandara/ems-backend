package com.ems.timesheet_service;

import java.time.*;
import java.time.temporal.TemporalAdjusters;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class OrganizationCalendar {
    private final ZoneId zone;

    public OrganizationCalendar(@Value("${ems.timesheets.timezone}") String timezone) {
        zone = ZoneId.of(timezone);
    }

    public LocalDate date(Instant at) {
        return at.atZone(zone).toLocalDate();
    }

    public Models.Calendar current() {
        return new Models.Calendar(zone.getId(), monday(date(Instant.now())));
    }

    public LocalDate monday(LocalDate day) {
        return day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    public void validateWeek(LocalDate week) {
        if (week.getYear() < 1900 || week.getYear() > 9998 || !week.equals(monday(week)))
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Week start must be a Monday within 1900–9998 in the organization timezone");
    }
}
