package com.ems.common.outbox;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, ProcessedEventId> {

    /**
     * Claims an event for a consumer, returning {@code 1} if this call is the one that
     * claimed it and {@code 0} if it had already been handled.
     *
     * <p>{@code ON CONFLICT DO NOTHING} rather than an insert whose duplicate-key violation
     * is caught: in Postgres a constraint violation aborts the whole transaction, so
     * catching it would leave the handler — and the listener's own transaction — with
     * nothing usable to continue in. The conflict is the expected case here, not an error,
     * and this is the form that says so.
     *
     * <p>The insert is deliberately not routed through the persistence context: it has to
     * reach the database now, because it is the lock that stops a concurrent redelivery
     * from being handled twice.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
                    INSERT INTO processed_event (event_id, consumer, processed_at)
                    VALUES (:eventId, :consumer, now())
                    ON CONFLICT (event_id, consumer) DO NOTHING
                    """, nativeQuery = true)
    int claim(@Param("eventId") UUID eventId, @Param("consumer") String consumer);
}
