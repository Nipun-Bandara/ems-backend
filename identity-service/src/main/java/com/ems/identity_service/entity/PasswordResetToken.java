package com.ems.identity_service.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import org.springframework.data.domain.Persistable;

/**
 * A single-use secret that lets whoever holds it set a new password without knowing the old
 * one.
 *
 * <p>Deliberately shaped like {@link VerificationToken} — token as the primary key, three
 * timestamps rather than a status column — because it answers the same questions and there is
 * nothing to gain from two different spellings of one idea.
 *
 * <p>What differs is the {@link #TTL}. A verification link may sit unread for a day without
 * costing anything; this one is a standing offer to take over the account, so it is worth an
 * hour and no more.
 */
@Entity
@Table(name = "password_reset_token")
@Getter
public class PasswordResetToken implements Persistable<UUID> {

    /**
     * How long a freshly issued token stays usable. Short on purpose: for the window this is
     * open, the mailbox is the account.
     */
    public static final Duration TTL = Duration.ofHours(1);

    @Id
    @Column(name = "token")
    private UUID token;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** Null until the token is spent. A token is redeemable exactly once. */
    @Column(name = "used_at")
    private Instant usedAt;

    /**
     * Spring Data decides insert-vs-merge from whether the id is null, and ours never is —
     * see {@link VerificationToken}, which carries an assigned key for the same reason.
     */
    @Transient
    private boolean isNew = true;

    protected PasswordResetToken() {
        // for JPA
    }

    /**
     * Mints a token for a user, valid for {@link #TTL} from now.
     *
     * <p>{@link UUID#randomUUID()} is specified to draw from a cryptographically strong
     * generator, which is what matters here: this value is a bearer credential for the
     * account, and a guessable one would hand the account to whoever guessed it.
     */
    public static PasswordResetToken issueFor(Long userId, Instant now) {
        PasswordResetToken issued = new PasswordResetToken();
        issued.token = UUID.randomUUID();
        issued.userId = userId;
        issued.createdAt = now;
        issued.expiresAt = now.plus(TTL);
        return issued;
    }

    public boolean isUsed() {
        return usedAt != null;
    }

    public boolean isExpired(Instant now) {
        return now.isAfter(expiresAt);
    }

    /** Spends the token. Callers check {@link #isUsed()} first; this does not re-check. */
    public void markUsed(Instant now) {
        this.usedAt = now;
    }

    @Override
    public UUID getId() {
        return token;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }
}
