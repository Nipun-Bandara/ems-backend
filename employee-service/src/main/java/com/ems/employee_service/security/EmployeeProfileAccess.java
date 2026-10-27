package com.ems.employee_service.security;

import com.ems.employee_service.repository.EmployeeProfileRepository;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

@Component("employeeProfileAccess")
public class EmployeeProfileAccess {
    private final EmployeeProfileRepository profiles;

    public EmployeeProfileAccess(EmployeeProfileRepository profiles) {
        this.profiles = profiles;
    }

    public boolean canRead(Authentication auth, Long userId) {
        if (auth.getName().equals(userId.toString())) return true;
        if (hasRole(auth, "SYSTEM_ADMIN") || hasRole(auth, "HR_MANAGER")) return true;
        if (!hasRole(auth, "DEPARTMENT_HEAD")) return false;
        var caller = profiles.findById(Long.valueOf(auth.getName()));
        var target = profiles.findById(userId);
        return caller.isPresent()
                && target.isPresent()
                && caller.get().getDepartmentId() != null
                && caller.get().getDepartmentId().equals(target.get().getDepartmentId());
    }

    private boolean hasRole(Authentication auth, String role) {
        return auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_" + role));
    }
}
