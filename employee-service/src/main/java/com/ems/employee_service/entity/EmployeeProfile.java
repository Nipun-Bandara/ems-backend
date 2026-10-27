package com.ems.employee_service.entity;

import com.ems.employee_service.dto.UpdateEmployeeProfileRequest;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "employee_profile")
@Getter
@NoArgsConstructor
public class EmployeeProfile {

    private String firstName;
    private String lastName;
    private String phone;
    private LocalDate dateOfBirth;
    private String address;
    private String emergencyContactName;
    private String emergencyContactPhone;
    private Long designationId;
    private LocalDate joinDate;
    private Long reportingManagerUserId;
    private Long departmentId;

    public void update(UpdateEmployeeProfileRequest r) {
        firstName = r.firstName();
        lastName = r.lastName();
        phone = r.phone();
        dateOfBirth = r.dateOfBirth();
        address = r.address();
        emergencyContactName = r.emergencyContactName();
        emergencyContactPhone = r.emergencyContactPhone();
        designationId = r.designationId();
        joinDate = r.joinDate();
        reportingManagerUserId = r.reportingManagerUserId();
    }

    private String avatarObjectKey;
    private String pendingAvatarKey;
    private String pendingAvatarType;
    private Long pendingAvatarSize;
    private Instant pendingAvatarExpiresAt;

    public void requestAvatar(String key, String type, long size, Instant expiresAt) {
        pendingAvatarKey = key;
        pendingAvatarType = type;
        pendingAvatarSize = size;
        pendingAvatarExpiresAt = expiresAt;
    }

    public void confirmAvatar() {
        avatarObjectKey = pendingAvatarKey;
        pendingAvatarKey = null;
        pendingAvatarType = null;
        pendingAvatarSize = null;
        pendingAvatarExpiresAt = null;
    }

    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false)
    private String email;

    @Column(nullable = false)
    private String username;

    @Column(nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
