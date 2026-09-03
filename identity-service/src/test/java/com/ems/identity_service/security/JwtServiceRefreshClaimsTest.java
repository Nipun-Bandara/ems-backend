package com.ems.identity_service.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.ems.identity_service.entity.UserEntity;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Exercises the real signer over the two claims rotation depends on.
 *
 * <p>Worth doing against real tokens rather than a mock because both assertions are about what
 * JJWT actually writes, and the rest of the feature reads a great deal into their absence: a
 * refresh token whose {@code jti} did not survive the round trip would be refused as unusable,
 * and an access token that somehow carried one could be presented to {@code /refresh}.
 */
class JwtServiceRefreshClaimsTest {

    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        // No key paths: the development profile answers with an ephemeral keypair, which is all
        // a signature that only has to verify inside this test needs.
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("development");

        jwtService = new JwtService(new RsaKeyProvider("", "", environment));
        ReflectionTestUtils.setField(
                jwtService, "jwtExpiration", Duration.ofMinutes(15).toMillis());
        ReflectionTestUtils.setField(
                jwtService, "refreshExpiration", Duration.ofDays(7).toMillis());
    }

    @Test
    void aRefreshTokenCarriesItsJtiAndFamily() {
        String token = jwtService.generateRefreshToken(user(), "jti-1", "family-1");

        assertThat(jwtService.extractJti(token)).isEqualTo("jti-1");
        assertThat(jwtService.extractFamilyId(token)).isEqualTo("family-1");
        assertThat(jwtService.extractUsername(token)).isEqualTo("ada");
    }

    /**
     * An access token names no row in the store, and {@code refreshToken} relies on that to tell
     * "wrong token presented" apart from "token already spent" — the latter costs the caller
     * their whole family.
     */
    @Test
    void anAccessTokenCarriesNeither() {
        String token = jwtService.generateToken(user());

        assertThat(jwtService.extractJti(token)).isNull();
        assertThat(jwtService.extractFamilyId(token)).isNull();
    }

    @Test
    void theRefreshTtlIsTheConfiguredOne() {
        assertThat(jwtService.getRefreshTokenTtl()).isEqualTo(Duration.ofDays(7));
    }

    private static UserEntity user() {
        return UserEntity.builder()
                .userId(7L)
                .username("ada")
                .email("ada@ems.local")
                .build();
    }
}
