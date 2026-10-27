package com.ems.employee_service.service;

import com.ems.common.outbox.OutboxPublisher;
import com.ems.employee_service.dto.UpdateEmployeeProfileRequest;
import com.ems.employee_service.repository.EmployeeProfileRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class EmployeeProfileService {
    private final EmployeeProfileRepository profiles;
    private final EmployeeAvatarService avatars;
    private final OutboxPublisher outbox;

    public EmployeeProfileService(
            EmployeeProfileRepository profiles, EmployeeAvatarService avatars, OutboxPublisher outbox) {
        this.profiles = profiles;
        this.avatars = avatars;
        this.outbox = outbox;
    }

    @Transactional
    public EmployeeAvatarService.Profile update(Long userId, UpdateEmployeeProfileRequest request) {
        var profile = profiles.findForUpdate(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Employee profile not found"));
        if (userId.equals(request.reportingManagerUserId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "An employee cannot report to themselves");
        }
        profile.update(request);
        // Publish durable domain data, never a temporary signed avatar URL.
        outbox.publish("employee", userId.toString(), "employee.profile.updated", profileSnapshot(profile));
        return avatars.getProfile(userId);
    }

    static java.util.Map<String, Object> profileSnapshot(com.ems.employee_service.entity.EmployeeProfile p) {
        var data = new java.util.LinkedHashMap<String, Object>();
        data.put("userId", p.getUserId());
        data.put("firstName", p.getFirstName());
        data.put("lastName", p.getLastName());
        data.put("phone", p.getPhone());
        data.put("dateOfBirth", p.getDateOfBirth());
        data.put("address", p.getAddress());
        data.put("emergencyContactName", p.getEmergencyContactName());
        data.put("emergencyContactPhone", p.getEmergencyContactPhone());
        data.put("designationId", p.getDesignationId());
        data.put("joinDate", p.getJoinDate());
        data.put("reportingManagerUserId", p.getReportingManagerUserId());
        data.put("departmentId", p.getDepartmentId());
        data.put("avatarObjectKey", p.getAvatarObjectKey());
        data.put("cvObjectKey", p.getCvObjectKey());
        return data;
    }
}
