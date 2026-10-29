package com.ems.claim_service;

import com.ems.common.storage.StorageService;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional
public class ReceiptService {
    static final String BUCKET = "ems-attachments";
    static final long MAX_SIZE = 10 * 1024 * 1024;
    static final Set<String> TYPES = Set.of("application/pdf", "image/jpeg", "image/png", "image/webp");
    private final JdbcTemplate jdbc;
    private final EmployeeScopeStore scope;
    private final ObjectProvider<StorageService> storage;

    public ReceiptService(JdbcTemplate jdbc, EmployeeScopeStore scope, ObjectProvider<StorageService> storage) {
        this.jdbc = jdbc;
        this.scope = scope;
        this.storage = storage;
    }

    public record Upload(
            @NotBlank @Size(max = 200) String filename,
            @NotBlank String contentType,
            @Min(1) @Max(MAX_SIZE) long size) {}

    public record Intent(
            UUID intentId, String uploadUrl, Map<String, String> headers, long maxSize, Instant expiresAt) {}

    public record Receipt(UUID id, String filename, String contentType, long size) {}

    public record Download(String url, Instant expiresAt) {}

    private StorageService storage() {
        var result = storage.getIfAvailable();
        if (result == null) throw error(HttpStatus.SERVICE_UNAVAILABLE, "Receipt storage is unavailable");
        return result;
    }

    private void authorize(UUID claim, long actor, boolean edit) {
        scope.lock(false);
        scope.requireActive(actor);
        // Lock order is always scope, claim, receipt (including the cleanup worker).
        var c = jdbc.queryForList("SELECT employee_id,status FROM claim WHERE id=? FOR UPDATE", claim);
        if (c.isEmpty()) throw error(HttpStatus.NOT_FOUND, "Claim not found");
        long owner = ((Number) c.getFirst().get("employee_id")).longValue();
        String status = (String) c.getFirst().get("status");
        if (actor != owner) {
            if (edit || status.equals("DRAFT")) throw error(HttpStatus.NOT_FOUND, "Claim not found");
            scope.require(owner, actor);
        }
        if (edit && !status.equals("DRAFT"))
            throw error(HttpStatus.CONFLICT, "Only draft receipts can be changed; submitted claims retain receipts");
    }

    public Intent intent(UUID claim, long actor, Upload input) {
        authorize(claim, actor, true);
        if (!TYPES.contains(input.contentType()) || input.size() <= 0 || input.size() > MAX_SIZE)
            throw error(HttpStatus.BAD_REQUEST, "Receipt must be PDF, JPEG, PNG or WebP, at most 10 MiB");
        UUID id = UUID.randomUUID();
        String key = "claims/" + actor + "/" + claim + "/" + id;
        Instant expiry = Instant.now().plusSeconds(300);
        String url = storage().presignedUpload(BUCKET, key, input.contentType(), input.size(), Duration.ofMinutes(5));
        jdbc.update(
                "INSERT INTO claim_receipt(id,claim_id,owner_id,object_key,filename,content_type,size,expires_at) VALUES (?,?,?,?,?,?,?,?)",
                id,
                claim,
                actor,
                key,
                input.filename(),
                input.contentType(),
                input.size(),
                java.sql.Timestamp.from(expiry));
        return new Intent(id, url, Map.of("Content-Type", input.contentType(), "If-None-Match", "*"), MAX_SIZE, expiry);
    }

    public List<Receipt> list(UUID claim, long actor) {
        authorize(claim, actor, false);
        return jdbc.query(
                "SELECT * FROM claim_receipt WHERE claim_id=? AND finalized=true AND deleted=false ORDER BY created_at,id",
                (r, n) -> new Receipt(
                        r.getObject("id", UUID.class),
                        r.getString("filename"),
                        r.getString("content_type"),
                        r.getLong("size")),
                claim);
    }

    private Map<String, Object> receipt(UUID claim, UUID id) {
        var rows = jdbc.queryForList(
                "SELECT * FROM claim_receipt WHERE claim_id=? AND id=? AND deleted=false FOR UPDATE", claim, id);
        if (rows.isEmpty()) throw error(HttpStatus.NOT_FOUND, "Receipt intent not found");
        return rows.getFirst();
    }

    public Receipt finalizeUpload(UUID claim, UUID id, long actor) {
        authorize(claim, actor, true);
        var r = receipt(claim, id);
        if (!Boolean.TRUE.equals(r.get("finalized"))) {
            if (!((java.sql.Timestamp) r.get("expires_at")).toInstant().isAfter(Instant.now()))
                throw error(HttpStatus.GONE, "Receipt upload intent expired; choose the file again");
            software.amazon.awssdk.services.s3.model.HeadObjectResponse head;
            try {
                head = storage().head(BUCKET, (String) r.get("object_key"));
            } catch (software.amazon.awssdk.services.s3.model.S3Exception failure) {
                if (failure.statusCode() == 404)
                    throw error(
                            HttpStatus.BAD_REQUEST,
                            "Upload the receipt before finalizing; retry after upload completes");
                throw error(HttpStatus.SERVICE_UNAVAILABLE, "Receipt verification unavailable; retry finalization");
            } catch (software.amazon.awssdk.core.exception.SdkException failure) {
                throw error(HttpStatus.SERVICE_UNAVAILABLE, "Receipt verification unavailable; retry finalization");
            }
            long size = ((Number) r.get("size")).longValue();
            if (head.contentLength() == null
                    || head.contentLength() != size
                    || size > MAX_SIZE
                    || !r.get("content_type").equals(head.contentType()))
                throw error(
                        HttpStatus.BAD_REQUEST,
                        "Uploaded receipt size or type does not match the intent (maximum 10 MiB)");
            if (jdbc.update("UPDATE claim_receipt SET finalized=true WHERE id=? AND expires_at>clock_timestamp()", id)
                    != 1) throw error(HttpStatus.GONE, "Receipt upload intent expired; choose the file again");
        }
        return new Receipt(
                id, (String) r.get("filename"), (String) r.get("content_type"), ((Number) r.get("size")).longValue());
    }

    public Download download(UUID claim, UUID id, long actor) {
        authorize(claim, actor, false);
        var r = receipt(claim, id);
        if (!Boolean.TRUE.equals(r.get("finalized"))) throw error(HttpStatus.NOT_FOUND, "Receipt not finalized");
        return new Download(
                storage().presignedDownload(BUCKET, (String) r.get("object_key"), Duration.ofMinutes(5)),
                Instant.now().plusSeconds(300));
    }

    public void delete(UUID claim, UUID id, long actor) {
        authorize(claim, actor, true);
        receipt(claim, id);
        // Tombstones survive outstanding PUT URLs; cleanup retries storage failures.
        jdbc.update("UPDATE claim_receipt SET deleted=true,finalized=false WHERE id=?", id);
    }

    private static ResponseStatusException error(HttpStatus status, String message) {
        return new ResponseStatusException(status, message);
    }
}
