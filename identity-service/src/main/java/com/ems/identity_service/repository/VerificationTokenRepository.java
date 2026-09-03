package com.ems.identity_service.repository;

import com.ems.identity_service.entity.VerificationToken;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VerificationTokenRepository extends JpaRepository<VerificationToken, UUID> {

    /**
     * The newest token issued to a user, spent or not. Backs the resend rate limit, which
     * asks how long ago this service last mailed the address.
     */
    Optional<VerificationToken> findFirstByUserIdOrderByCreatedAtDesc(Long userId);

    /**
     * Retires every unspent token a user holds by stamping it used, so that a resend
     * invalidates the links in the mails before it.
     *
     * <p>Marked used rather than deleted so the row survives to be recognised: a click on a
     * superseded link lands on a token that is present and spent, which the verify endpoint
     * can tell apart from a genuine redemption by looking at whether the account is actually
     * verified. Deleting instead would make an old link indistinguishable from a forgery.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update VerificationToken t set t.usedAt = :now where t.userId = :userId and t.usedAt is null")
    int invalidateOutstanding(@Param("userId") Long userId, @Param("now") Instant now);
}
