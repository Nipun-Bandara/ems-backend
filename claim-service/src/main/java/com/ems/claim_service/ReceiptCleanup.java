package com.ems.claim_service;

import com.ems.common.storage.StorageService;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@EnableScheduling
public class ReceiptCleanup {
    private final JdbcTemplate jdbc;
    private final ObjectProvider<StorageService> storage;
    private final TransactionTemplate transaction;

    public ReceiptCleanup(JdbcTemplate jdbc, ObjectProvider<StorageService> storage, TransactionTemplate transaction) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.transaction = transaction;
    }

    @Scheduled(
            initialDelayString = "${ems.claims.receipt-cleanup-ms:60000}",
            fixedDelayString = "${ems.claims.receipt-cleanup-ms:60000}")
    public void cleanup() {
        var s = storage.getIfAvailable();
        if (s == null) return;
        var ids = jdbc.queryForList(
                "SELECT id FROM claim_receipt WHERE expires_at < now() - interval '10 minutes' AND (finalized=false OR deleted=true OR claim_id IS NULL) ORDER BY expires_at LIMIT 100",
                UUID.class);
        for (var id : ids) {
            try {
                transaction.executeWithoutResult(tx -> {
                    // Claim lock prevents concurrent draft deletion / finalization / submission.
                    var claims = jdbc.queryForList("SELECT claim_id FROM claim_receipt WHERE id=?", UUID.class, id);
                    if (!claims.isEmpty() && claims.getFirst() != null)
                        jdbc.queryForList("SELECT id FROM claim WHERE id=? FOR UPDATE", claims.getFirst());
                    var rows = jdbc.queryForList(
                            "SELECT object_key FROM claim_receipt WHERE id=? AND expires_at < now() - interval '10 minutes' AND (finalized=false OR deleted=true OR claim_id IS NULL) FOR UPDATE SKIP LOCKED",
                            id);
                    if (!rows.isEmpty()) {
                        s.delete(ReceiptService.BUCKET, (String) rows.getFirst().get("object_key"));
                        jdbc.update("DELETE FROM claim_receipt WHERE id=?", id);
                    }
                });
            } catch (RuntimeException failure) {
                org.slf4j.LoggerFactory.getLogger(getClass())
                        .warn("Receipt cleanup failed for intent {}; will retry", id);
            }
        }
    }
}
