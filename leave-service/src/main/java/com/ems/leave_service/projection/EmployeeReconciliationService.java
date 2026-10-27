package com.ems.leave_service.projection;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class EmployeeReconciliationService {
    private final EmployeeProjectionStore store;

    public EmployeeReconciliationService(EmployeeProjectionStore store) {
        this.store = store;
    }

    @Transactional
    public void reconcile(long userId, EmployeeSnapshot snapshot) {
        if (Long.valueOf(userId).equals(snapshot.reportingManagerUserId()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "An employee cannot report to themselves");
        store.lockAndCreate(userId);
        store.status(userId, snapshot.active(), snapshot.asOf());
        store.assignment(userId, snapshot.departmentId(), snapshot.asOf());
        store.profile(userId, snapshot.reportingManagerUserId(), snapshot.asOf());
    }
}
