package com.ems.identity_service.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Covers the key layout and the family sweep, against a mocked Redis but a real mapper — what a
 * row is written as and read back as is exactly the part worth exercising, since a value the
 * sweep cannot parse silently spares the token it names.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RefreshTokenStoreTest {

    private static final Long USER_ID = 7L;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    private RefreshTokenStore store() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        return new RefreshTokenStore(redisTemplate, objectMapper);
    }

    @Test
    void storesATokenUnderItsUserAndJtiWithTheTokensOwnTtl() {
        Instant issuedAt = Instant.parse("2026-09-03T10:15:30Z");

        store().store(USER_ID, "jti-1", "family-1", issuedAt, Duration.ofDays(7));

        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eqKey("rt:7:jti-1"), value.capture(), eqTtl(Duration.ofDays(7)));

        // Read back with the same mapper the sweep uses: a value that does not round trip would
        // leave revokeFamily walking past rows it is supposed to delete.
        RefreshTokenStore.StoredToken stored =
                objectMapper.readValue(value.getValue(), RefreshTokenStore.StoredToken.class);
        assertThat(stored.familyId()).isEqualTo("family-1");
        assertThat(stored.issuedAt()).isEqualTo(issuedAt);
    }

    /** One DELETE, and its result is the answer: a key that was there is now spent. */
    @Test
    void consumeReportsWhetherTheRowWasStillThere() {
        when(redisTemplate.delete("rt:7:jti-1")).thenReturn(true);
        when(redisTemplate.delete("rt:7:jti-gone")).thenReturn(false);

        assertThat(store().consume(USER_ID, "jti-1")).isTrue();
        assertThat(store().consume(USER_ID, "jti-gone")).isFalse();
    }

    @Test
    void revokeFamilyDeletesOnlyTheRowsInThatFamily() {
        givenRows(
                row("rt:7:a", "family-1", Instant.now()),
                row("rt:7:b", "family-2", Instant.now()),
                row("rt:7:c", "family-1", Instant.now()));

        store().revokeFamily(USER_ID, "family-1");

        assertThat(deletedKeys()).containsExactlyInAnyOrder("rt:7:a", "rt:7:c");
    }

    /**
     * A row whose value cannot be read is left alone rather than taking the sweep down with it.
     * It expires on its own, and the token naming it is refused the moment it is presented.
     */
    @Test
    void revokeFamilySkipsAnUnreadableRow() {
        givenRows(row("rt:7:a", "family-1", Instant.now()), new Row("rt:7:broken", "not json"));

        store().revokeFamily(USER_ID, "family-1");

        assertThat(deletedKeys()).containsExactly("rt:7:a");
    }

    @Test
    void revokeFamilyDeletesNothingWhenNoRowMatches() {
        givenRows(row("rt:7:a", "family-2", Instant.now()));

        store().revokeFamily(USER_ID, "family-1");

        verify(redisTemplate, never()).delete(anyCollection());
    }

    @Test
    void revokeAllDeletesEveryRowForTheAccountWhateverItsFamily() {
        givenRows(row("rt:7:a", "family-1", Instant.now()), row("rt:7:b", "family-2", Instant.now()));

        store().revokeAll(USER_ID);

        assertThat(deletedKeys()).containsExactlyInAnyOrder("rt:7:a", "rt:7:b");
    }

    @Test
    void revokeAllDeletesNothingForAnAccountWithNoSessions() {
        givenRows();

        store().revokeAll(USER_ID);

        verify(redisTemplate, never()).delete(anyCollection());
    }

    /** The scan is scoped to one account, so another user's rows are never even looked at. */
    @Test
    void scansOnlyTheCallersOwnKeyspace() {
        givenRows();

        store().revokeAll(USER_ID);

        ArgumentCaptor<ScanOptions> options = ArgumentCaptor.forClass(ScanOptions.class);
        verify(redisTemplate).scan(options.capture());
        assertThat(options.getValue().toOptionString()).contains("rt:7:*");
    }

    // --- helpers -----------------------------------------------------------

    private record Row(String key, String value) {}

    private Row row(String key, String familyId, Instant issuedAt) {
        return new Row(key, objectMapper.writeValueAsString(new RefreshTokenStore.StoredToken(familyId, issuedAt)));
    }

    /** Points the mocked template at these rows, as both a SCAN result and individual GETs. */
    private void givenRows(Row... rows) {
        when(redisTemplate.scan(any(ScanOptions.class)))
                .thenAnswer(invocation ->
                        cursorOver(List.of(rows).stream().map(Row::key).toList()));
        for (Row row : rows) {
            when(valueOperations.get(row.key())).thenReturn(row.value());
        }
    }

    @SuppressWarnings("unchecked")
    private Collection<String> deletedKeys() {
        ArgumentCaptor<Collection<String>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(redisTemplate).delete(captor.capture());
        return captor.getValue();
    }

    /**
     * A read-only {@link Cursor} over a fixed list; only the iteration half is ever used. The
     * deprecated {@code getCursorId} still has to be implemented for the anonymous class to be
     * concrete, hence the suppression.
     */
    @SuppressWarnings("deprecation")
    private static Cursor<String> cursorOver(List<String> keys) {
        java.util.Iterator<String> delegate = keys.iterator();
        return new Cursor<>() {
            private boolean closed;

            @Override
            public boolean hasNext() {
                return delegate.hasNext();
            }

            @Override
            public String next() {
                return delegate.next();
            }

            @Override
            public void close() {
                closed = true;
            }

            @Override
            public boolean isClosed() {
                return closed;
            }

            @Override
            public CursorId getId() {
                return CursorId.of(0L);
            }

            @Override
            public long getCursorId() {
                return 0;
            }

            @Override
            public long getPosition() {
                return 0;
            }
        };
    }

    private static String eqKey(String key) {
        return org.mockito.ArgumentMatchers.eq(key);
    }

    private static Duration eqTtl(Duration ttl) {
        return org.mockito.ArgumentMatchers.eq(ttl);
    }
}
