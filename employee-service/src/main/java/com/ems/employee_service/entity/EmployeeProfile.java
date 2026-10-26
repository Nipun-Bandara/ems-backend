package com.ems.employee_service.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "employee_profile")
@Getter
@NoArgsConstructor
public class EmployeeProfile {

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
