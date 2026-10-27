package com.ems.employee_service.service;

import com.ems.common.storage.StorageService;
import com.ems.employee_service.entity.EmployeeProfile;
import com.ems.employee_service.repository.EmployeeProfileRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.services.s3.model.S3Exception;

@Service
public class EmployeeAvatarService {
    public static final long MAX_SIZE = 5 * 1024 * 1024;
    public static final Set<String> TYPES = Set.of("image/jpeg", "image/png", "image/webp");
    private static final String BUCKET = "ems-avatars";
    private static final Duration UPLOAD_TTL = Duration.ofMinutes(5);
    private static final Duration DOWNLOAD_TTL = Duration.ofMinutes(5);
    private final EmployeeProfileRepository profiles;
    private final StorageService storage;
    private final com.ems.common.outbox.OutboxPublisher outbox;

    public EmployeeAvatarService(
            EmployeeProfileRepository profiles, StorageService storage, com.ems.common.outbox.OutboxPublisher outbox) {
        this.outbox = outbox;
        this.profiles = profiles;
        this.storage = storage;
    }

    public record Upload(String uploadUrl, String objectKey, Map<String, String> headers) {}

    public record Profile(
            Long userId,
            String username,
            String email,
            String status,
            String avatarUrl,
            String firstName,
            String lastName,
            String phone,
            java.time.LocalDate dateOfBirth,
            String address,
            String emergencyContactName,
            String emergencyContactPhone,
            Long designationId,
            java.time.LocalDate joinDate,
            Long reportingManagerUserId,
            Long departmentId,
            String avatarObjectKey) {}

    @Transactional
    public Upload requestUpload(Long id, String contentType, long size) {
        if (!TYPES.contains(contentType) || size <= 0 || size > MAX_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use a JPEG, PNG or WebP image up to 5MB");
        }
        EmployeeProfile profile = lockedProfile(id);
        String key = "employees/" + id + "/avatars/" + UUID.randomUUID();
        String url = storage.presignedUpload(BUCKET, key, contentType, size, UPLOAD_TTL);
        profile.requestAvatar(key, contentType, size, Instant.now().plus(UPLOAD_TTL));
        return new Upload(url, key, Map.of("Content-Type", contentType, "If-None-Match", "*"));
    }

    @Transactional
    public Profile confirmUpload(Long id, String objectKey) {
        EmployeeProfile profile = lockedProfile(id);
        if (!objectKey.equals(profile.getPendingAvatarKey())
                || profile.getPendingAvatarExpiresAt() == null
                || !profile.getPendingAvatarExpiresAt().isAfter(Instant.now())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Upload is unknown or expired; select the photo again");
        }
        try {
            var object = storage.head(BUCKET, objectKey);
            if (!profile.getPendingAvatarSize().equals(object.contentLength())
                    || object.contentLength() > MAX_SIZE
                    || !profile.getPendingAvatarType().equals(object.contentType())) {
                storage.delete(BUCKET, objectKey);
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Uploaded image does not match the approved type and size");
            }
        } catch (S3Exception e) {
            if (e.statusCode() == 404)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Upload the image before confirming it");
            throw e;
        }
        profile.confirmAvatar();
        outbox.publish(
                "employee", id.toString(), "employee.profile.updated", EmployeeProfileService.profileSnapshot(profile));
        return response(profile);
    }

    @Transactional(readOnly = true)
    public Profile getProfile(Long id) {
        return response(profiles.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Employee profile not found")));
    }

    private EmployeeProfile lockedProfile(Long id) {
        return profiles.findForUpdate(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Employee profile not found"));
    }

    private Profile response(EmployeeProfile p) {
        return new Profile(
                p.getUserId(),
                p.getUsername(),
                p.getEmail(),
                p.getStatus(),
                p.getAvatarObjectKey() == null
                        ? null
                        : storage.presignedDownload(BUCKET, p.getAvatarObjectKey(), DOWNLOAD_TTL),
                p.getFirstName(),
                p.getLastName(),
                p.getPhone(),
                p.getDateOfBirth(),
                p.getAddress(),
                p.getEmergencyContactName(),
                p.getEmergencyContactPhone(),
                p.getDesignationId(),
                p.getJoinDate(),
                p.getReportingManagerUserId(),
                p.getDepartmentId(),
                p.getAvatarObjectKey());
    }
}
