package com.ems.leave_service.request;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/** Programmatic test/development hooks only; never exposed as an HTTP API. */
@Component
public class WorkflowFaults {
    private final boolean enabled;
    private final Set<UUID> calendarFailures = ConcurrentHashMap.newKeySet();
    private final Set<String> crashes = ConcurrentHashMap.newKeySet();

    public WorkflowFaults(Environment environment) {
        enabled = environment.acceptsProfiles(Profiles.of("test", "development"))
                && !environment.acceptsProfiles(Profiles.of("production", "staging"));
    }

    public void failCalendar(UUID request) {
        check();
        calendarFailures.add(request);
    }

    public void crashBeforeCommit(UUID request, String state) {
        check();
        crashes.add(request + ":" + state);
    }

    public void checkpoint(UUID request, String state) {
        if (enabled && crashes.remove(request + ":" + state))
            throw new IllegalStateException("Injected crash before commit");
    }

    public boolean calendarFails(UUID request) {
        return enabled && calendarFailures.contains(request);
    }

    private void check() {
        if (!enabled) throw new IllegalStateException("Fault injection disabled");
    }
}
