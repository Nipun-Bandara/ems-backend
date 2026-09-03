package com.ems.identity_service.repository;

import com.ems.identity_service.entity.PasswordResetToken;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, UUID> {

    /**
     * How many reset links a user has been sent since a given moment. Backs the hourly limit.
     *
     * <p>Counts issues rather than surviving tokens, so retiring the earlier ones on each
     * request does not reset the budget — the limit is about how much mail an address can be
     * made to receive, and a retired token was still delivered.
     */
    long countByUserIdAndCreatedAtAfter(Long userId, Instant since);

    /**
     * Retires every unspent token a user holds by stamping it used, so that requesting a new
     * link invalidates the ones mailed before it.
     *
     * <p>Marked used rather than deleted, as in
     * {@link VerificationTokenRepository#invalidateOutstanding}: the row survives to be
     * recognised, so a click on a superseded link is answered as a spent token rather than as
     * one we never issued.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update PasswordResetToken t set t.usedAt = :now where t.userId = :userId and t.usedAt is null")
    int invalidateOutstanding(@Param("userId") Long userId, @Param("now") Instant now);
}
