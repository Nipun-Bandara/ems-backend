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
 * A single-use secret that proves whoever holds it can read the account's email.
 *
 * <p>The token <em>is</em> the primary key rather than a column beside a surrogate id: it is
 * what arrives in the verify request, so making it the key is what makes the lookup a primary
 * key hit and what stops the same value ever existing twice.
 *
 * <p>Three timestamps rather than a status column. {@code expiresAt} and {@code usedAt} are
 * independent — a token can be both — and a link that stopped working is a question someone
 * asks afterwards, so "when did this expire" and "when was this spent" are worth more than an
 * enum that says only that the token is no longer good.
 */
@Entity
@Table(name = "verification_token")
@Getter
public class VerificationToken implements Persistable<UUID> {

    /** How long a freshly issued token stays usable. */
    public static final Duration TTL = Duration.ofHours(24);

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
     * see {@link com.ems.common.outbox.OutboxEvent}, which carries an assigned key for the
     * same reason.
     */
    @Transient
    private boolean isNew = true;

    protected VerificationToken() {
        // for JPA
    }

    /**
     * Mints a token for a user, valid for {@link #TTL} from now.
     *
     * <p>{@link UUID#randomUUID()} is specified to draw from a cryptographically strong
     * generator, which is the property that matters here: this value is a bearer credential
     * for the account, and a guessable one would verify someone else's address.
     */
    public static VerificationToken issueFor(Long userId, Instant now) {
        VerificationToken issued = new VerificationToken();
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
