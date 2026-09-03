package com.ems.common.outbox;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Takes a batch of events that are due to be published and locks them for the calling
     * transaction, so two poller instances never publish the same row.
     *
     * <p>{@code SKIP LOCKED} is what makes that cheap: a second poller passes over the rows
     * the first one holds and picks up the next ones instead of blocking behind them. The
     * lock lives as long as the transaction, so it is released — and the row freed for
     * another attempt — even if the instance holding it dies mid-publish.
     *
     * <p>Rows that have exhausted {@code maxAttempts} are left alone rather than deleted;
     * they are the record of what could not be delivered.
     */
    @Query(value = """
                    SELECT * FROM outbox_event
                    WHERE sent_at IS NULL
                      AND attempts < :maxAttempts
                      AND next_attempt_at <= now()
                    ORDER BY created_at, id
                    LIMIT :batchSize
                    FOR UPDATE SKIP LOCKED
                    """, nativeQuery = true)
    List<OutboxEvent> claimDue(@Param("maxAttempts") int maxAttempts, @Param("batchSize") int batchSize);
}
