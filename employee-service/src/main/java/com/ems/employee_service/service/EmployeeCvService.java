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
public class EmployeeCvService {
    public static final long MAX_SIZE = 10 * 1024 * 1024;
    public static final Set<String> TYPES = Set.of(
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    private static final String BUCKET = "ems-documents";
    private static final Duration UPLOAD_TTL = Duration.ofMinutes(5);
    private static final Duration DOWNLOAD_TTL = Duration.ofMinutes(5);
    private final EmployeeProfileRepository profiles;
    private final StorageService storage;
    private final com.ems.common.outbox.OutboxPublisher outbox;

    public EmployeeCvService(
            EmployeeProfileRepository profiles, StorageService storage, com.ems.common.outbox.OutboxPublisher outbox) {
        this.outbox = outbox;
        this.profiles = profiles;
        this.storage = storage;
    }

    public record Upload(String uploadUrl, String objectKey, Map<String, String> headers) {}

    public record Cv(String cvObjectKey, String downloadUrl) {}

    @Transactional
    public Upload requestUpload(Long id, String contentType, long size) {
        if (!TYPES.contains(contentType) || size <= 0 || size > MAX_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use a PDF, DOC or DOCX document up to 10MB");
        }
        EmployeeProfile profile = lockedProfile(id);
        String key = "employees/" + id + "/cv/" + UUID.randomUUID();
        String url = storage.presignedUpload(BUCKET, key, contentType, size, UPLOAD_TTL);
        profile.requestCv(key, contentType, size, Instant.now().plus(UPLOAD_TTL));
        return new Upload(url, key, Map.of("Content-Type", contentType, "If-None-Match", "*"));
    }

    @Transactional
    public Cv confirmUpload(Long id, String objectKey) {
        EmployeeProfile profile = lockedProfile(id);
        if (!objectKey.equals(profile.getPendingCvKey())
                || profile.getPendingCvExpiresAt() == null
                || !profile.getPendingCvExpiresAt().isAfter(Instant.now())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Upload is unknown or expired; select the CV again");
        }
        try {
            var object = storage.head(BUCKET, objectKey);
            if (!profile.getPendingCvSize().equals(object.contentLength())
                    || object.contentLength() > MAX_SIZE
                    || !profile.getPendingCvType().equals(object.contentType())) {
                storage.delete(BUCKET, objectKey);
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Uploaded document does not match the approved type and size");
            }
        } catch (S3Exception e) {
            if (e.statusCode() == 404)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Upload the document before confirming it");
            throw e;
        }
        profile.confirmCv();
        outbox.publish(
                "employee", id.toString(), "employee.profile.updated", EmployeeProfileService.profileSnapshot(profile));
        return response(profile);
    }

    @Transactional(readOnly = true)
    public Cv getCv(Long id) {
        return response(profiles.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Employee profile not found")));
    }

    private EmployeeProfile lockedProfile(Long id) {
        return profiles.findForUpdate(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Employee profile not found"));
    }

    private Cv response(EmployeeProfile p) {
        return new Cv(
                p.getCvObjectKey(),
                p.getCvObjectKey() == null
                        ? null
                        : storage.presignedDownload(BUCKET, p.getCvObjectKey(), DOWNLOAD_TTL));
    }
}
