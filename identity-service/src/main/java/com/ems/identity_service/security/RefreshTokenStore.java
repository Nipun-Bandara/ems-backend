package com.ems.identity_service.security;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * The outstanding refresh tokens, one Redis key per token: {@code rt:{userId}:{jti}} holding the
 * token's family and when it was issued, expiring with the token itself.
 *
 * <p>A refresh token is a signed JWT, so its signature alone says nothing about whether it is
 * still live. These rows are what makes it revocable: a token is honoured only while its row
 * exists, refreshing deletes the row it was presented with, and logout and a password reset
 * delete rows without waiting for anything to expire.
 *
 * <p>Deleting the row on every refresh is also what detects replay. A signature that verifies
 * against a row that is gone means the token was already spent — either the legitimate holder is
 * retrying, or a copy leaked — and neither case can be told from the other, so the whole
 * {@link #revokeFamily family} descended from that sign-in goes.
 *
 * <p>Redis being unreachable is not survivable here, unlike the gateway's rate limiter which fails
 * open. There is no safe default: honouring a token whose row cannot be read would make every
 * revocation optional, and refusing one would sign every user out. The exception propagates and
 * the request fails.
 */
@Component
@RequiredArgsConstructor
public class RefreshTokenStore {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenStore.class);

    private static final String KEY_PREFIX = "rt:";

    /** How many keys a SCAN pass asks for at a time. A hint to Redis, not a limit. */
    private static final int SCAN_BATCH = 100;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /** What a {@code rt:} key holds. The family is the part {@link #revokeFamily} indexes on. */
    public record StoredToken(String familyId, Instant issuedAt) {}

    /** Records a freshly minted refresh token, to be forgotten again after {@code ttl}. */
    public void store(Long userId, String jti, String familyId, Instant issuedAt, Duration ttl) {
        redisTemplate
                .opsForValue()
                .set(key(userId, jti), objectMapper.writeValueAsString(new StoredToken(familyId, issuedAt)), ttl);
    }

    /**
     * Spends a refresh token, reporting whether it was still outstanding.
     *
     * <p>The read and the delete are one DELETE rather than a GET followed by a DELETE, so that
     * two requests presenting the same token concurrently cannot both be told it was live: Redis
     * runs the command for one of them first, and only that one sees a key removed.
     *
     * @return true if the token was outstanding and is now spent; false if there was no such row,
     *     which for a token whose signature verified means it has already been redeemed
     */
    public boolean consume(Long userId, String jti) {
        return Boolean.TRUE.equals(redisTemplate.delete(key(userId, jti)));
    }

    /**
     * Retires every token descended from one sign-in. Called when a spent token is presented
     * again: the token that replaced it is in the same family, so whoever holds the copy loses
     * the session along with whoever holds the current token.
     */
    public void revokeFamily(Long userId, String familyId) {
        List<String> toDelete = new ArrayList<>();
        for (String key : keysFor(userId)) {
            String value = redisTemplate.opsForValue().get(key);
            if (value != null && familyId.equals(readFamilyId(key, value))) {
                toDelete.add(key);
            }
        }
        if (!toDelete.isEmpty()) {
            redisTemplate.delete(toDelete);
        }
        log.info("Revoked {} refresh tokens in family {} for user {}", toDelete.size(), familyId, userId);
    }

    /** Retires every refresh token the account holds, whatever family it belongs to. */
    public void revokeAll(Long userId) {
        List<String> keys = keysFor(userId);
        if (!keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
        log.info("Revoked all {} refresh tokens for user {}", keys.size(), userId);
    }

    /**
     * Every outstanding token's key for one account. SCAN rather than KEYS: the pattern is
     * narrow, but KEYS blocks the whole server while it walks the keyspace, and this runs on a
     * request path shared with the gateway's rate limiter.
     */
    private List<String> keysFor(Long userId) {
        ScanOptions options = ScanOptions.scanOptions()
                .match(KEY_PREFIX + userId + ":*")
                .count(SCAN_BATCH)
                .build();

        List<String> keys = new ArrayList<>();
        try (Cursor<String> cursor = redisTemplate.scan(options)) {
            while (cursor.hasNext()) {
                keys.add(cursor.next());
            }
        }
        return keys;
    }

    /**
     * A row we cannot parse is treated as belonging to no family, so a revocation walks past it
     * rather than failing. It expires on its own, and the token naming it is refused the moment
     * it is presented — {@code consume} only cares that the key exists.
     */
    private String readFamilyId(String key, String value) {
        try {
            return objectMapper.readValue(value, StoredToken.class).familyId();
        } catch (RuntimeException e) {
            log.warn("Ignoring unreadable refresh token record at {}: {}", key, e.getMessage());
            return null;
        }
    }

    private static String key(Long userId, String jti) {
        return KEY_PREFIX + userId + ":" + jti;
    }
}
