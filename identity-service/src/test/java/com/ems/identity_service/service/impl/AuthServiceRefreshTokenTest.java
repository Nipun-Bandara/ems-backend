package com.ems.identity_service.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ems.common.outbox.OutboxPublisher;
import com.ems.identity_service.dto.request.RefreshTokenRequest;
import com.ems.identity_service.dto.response.AuthResponse;
import com.ems.identity_service.entity.UserEntity;
import com.ems.identity_service.exception.AccountBannedException;
import com.ems.identity_service.exception.InvalidTokenException;
import com.ems.identity_service.exception.TokenReuseException;
import com.ems.identity_service.security.RefreshTokenStore;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.security.SignatureException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Covers refresh token rotation and the two ways a session ends.
 *
 * <p>The properties that matter here are the ones a stateless refresh token could not give us.
 * Refreshing must hand back a token that replaces the old one rather than reissuing it, so a
 * leaked token has a bounded life. Presenting a token that was already spent must be treated as
 * evidence the token was copied, and take the whole family down rather than just refusing the one
 * request. And logout must remove the row, because the signature keeps verifying either way.
 *
 * <p>{@code JwtService} is mocked rather than exercised: what a token is signed with is
 * {@code JwtServiceTest}'s business, and every decision under test here is made from the claims
 * once they have been read.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthServiceRefreshTokenTest {

    private static final String FAMILY = "family-1";
    private static final String OLD_JTI = "jti-old";
    private static final Long USER_ID = 7L;

    @Mock
    private com.ems.identity_service.repository.UserRepository userRepository;

    @Mock
    private com.ems.identity_service.repository.RoleRepository roleRepository;

    @Mock
    private com.ems.identity_service.repository.UserRolesRepository userRolesRepository;

    @Mock
    private com.ems.identity_service.repository.VerificationTokenRepository verificationTokenRepository;

    @Mock
    private com.ems.identity_service.repository.PasswordResetTokenRepository passwordResetTokenRepository;

    @Mock
    private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    @Mock
    private com.ems.identity_service.security.JwtService jwtService;

    @Mock
    private RefreshTokenStore refreshTokenStore;

    @Mock
    private org.springframework.security.authentication.AuthenticationManager authenticationManager;

    @Mock
    private OutboxPublisher outboxPublisher;

    @InjectMocks
    private AuthServiceImpl authService;

    @BeforeEach
    void stubTokenIssuing() {
        when(jwtService.getRefreshTokenTtl()).thenReturn(Duration.ofDays(7));
        when(jwtService.generateToken(any(UserEntity.class))).thenReturn("new-access-token");
        when(jwtService.generateRefreshToken(any(UserEntity.class), anyString(), anyString()))
                .thenReturn("new-refresh-token");
        when(userRolesRepository.findByUser_UserId(anyLong())).thenReturn(List.of());
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    // --- sign-in -----------------------------------------------------------

    /**
     * Every refresh token has to be recorded at the moment it is minted, and a sign-in is one of
     * the two places that happens. A login that skipped this would hand out a token whose first
     * use looks exactly like a replay.
     */
    @Test
    void signingInRecordsTheRefreshTokenUnderAFreshFamily() {
        UserEntity user = user();
        when(userRepository.findByEmail("ada@ems.local")).thenReturn(Optional.of(user));

        authService.login(com.ems.identity_service.dto.request.LoginRequest.builder()
                .email("ada@ems.local")
                .password("secret")
                .build());

        verify(refreshTokenStore)
                .store(eq(USER_ID), anyString(), anyString(), any(Instant.class), eq(Duration.ofDays(7)));
    }

    // --- rotation ----------------------------------------------------------

    @Test
    void refreshingSpendsThePresentedTokenAndReturnsAReplacementInTheSameFamily() {
        givenAPresentedToken(user(), OLD_JTI, FAMILY);
        when(refreshTokenStore.consume(USER_ID, OLD_JTI)).thenReturn(true);

        AuthResponse response = authService.refreshToken(refresh("old-refresh-token"));

        assertThat(response.getToken()).isEqualTo("new-access-token");
        // Not the token that was presented. Returning it unchanged, as this endpoint used to,
        // means a token that leaks stays usable for its full seven days.
        assertThat(response.getRefreshToken()).isEqualTo("new-refresh-token");

        verify(refreshTokenStore).consume(USER_ID, OLD_JTI);

        ArgumentCaptor<String> newJti = ArgumentCaptor.forClass(String.class);
        verify(refreshTokenStore)
                .store(eq(USER_ID), newJti.capture(), eq(FAMILY), any(Instant.class), eq(Duration.ofDays(7)));
        assertThat(newJti.getValue()).isNotEqualTo(OLD_JTI);
    }

    /** The row has to exist before the token reaches the caller, or its first use reads as replay. */
    @Test
    void recordsTheReplacementBeforeHandingItOut() {
        givenAPresentedToken(user(), OLD_JTI, FAMILY);
        when(refreshTokenStore.consume(USER_ID, OLD_JTI)).thenReturn(true);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(refreshTokenStore, jwtService);
        authService.refreshToken(refresh("old-refresh-token"));

        order.verify(refreshTokenStore).store(anyLong(), anyString(), anyString(), any(), any());
        order.verify(jwtService).generateRefreshToken(any(UserEntity.class), anyString(), anyString());
    }

    // --- replay ------------------------------------------------------------

    @Test
    void replayingASpentTokenRevokesTheFamilyAndReportsTokenReuse() {
        givenAPresentedToken(user(), OLD_JTI, FAMILY);
        when(refreshTokenStore.consume(USER_ID, OLD_JTI)).thenReturn(false);

        assertThatThrownBy(() -> authService.refreshToken(refresh("replayed-token")))
                .isInstanceOf(TokenReuseException.class);

        verify(refreshTokenStore).revokeFamily(USER_ID, FAMILY);
        // No replacement: the point of the family sweep is that this line of tokens ends here.
        verify(refreshTokenStore, never()).store(anyLong(), anyString(), anyString(), any(), any());
    }

    /**
     * The ordering the implementation commits to. A stolen token presented against an account
     * that has since been banned still has to take its family down — refusing it with a 403
     * first would leave the copy usable if the ban were ever lifted.
     */
    @Test
    void replayIsActedOnEvenForABannedAccount() {
        UserEntity banned = user();
        banned.setIsBanned(true);
        givenAPresentedToken(banned, OLD_JTI, FAMILY);
        when(refreshTokenStore.consume(USER_ID, OLD_JTI)).thenReturn(false);

        assertThatThrownBy(() -> authService.refreshToken(refresh("replayed-token")))
                .isInstanceOf(TokenReuseException.class);

        verify(refreshTokenStore).revokeFamily(USER_ID, FAMILY);
    }

    /**
     * An expired token is a timeout, not a replay. Its row expired alongside it, so reaching the
     * store at all would find nothing there and revoke a family over an ordinary sign-in prompt.
     */
    @Test
    void anExpiredTokenIsRefusedWithoutRevokingAnything() {
        when(jwtService.extractUsername("expired-token")).thenThrow(new ExpiredJwtException(null, null, "expired"));

        assertThatThrownBy(() -> authService.refreshToken(refresh("expired-token")))
                .isInstanceOf(InvalidTokenException.class);

        verify(refreshTokenStore, never()).consume(anyLong(), anyString());
        verify(refreshTokenStore, never()).revokeFamily(anyLong(), anyString());
    }

    @Test
    void aForgedTokenIsRefusedWithoutRevokingAnything() {
        when(jwtService.extractUsername("forged-token")).thenThrow(new SignatureException("bad signature"));

        assertThatThrownBy(() -> authService.refreshToken(refresh("forged-token")))
                .isInstanceOf(InvalidTokenException.class);

        verify(refreshTokenStore, never()).consume(anyLong(), anyString());
    }

    /**
     * An access token carries no jti, so it names no row. Presenting one is the wrong token
     * rather than a spent one, and must not cost the caller their session.
     */
    @Test
    void aTokenWithoutAJtiIsRefusedWithoutRevokingAnything() {
        when(jwtService.extractUsername("access-token")).thenReturn("ada");
        when(jwtService.extractJti("access-token")).thenReturn(null);
        when(jwtService.extractFamilyId("access-token")).thenReturn(null);

        assertThatThrownBy(() -> authService.refreshToken(refresh("access-token")))
                .isInstanceOf(InvalidTokenException.class);

        verify(refreshTokenStore, never()).consume(anyLong(), anyString());
        verify(refreshTokenStore, never()).revokeFamily(anyLong(), anyString());
    }

    // --- the checks rotation did not replace -------------------------------

    @Test
    void aLiveTokenForABannedAccountIsStillRefused() {
        UserEntity banned = user();
        banned.setIsBanned(true);
        givenAPresentedToken(banned, OLD_JTI, FAMILY);
        when(refreshTokenStore.consume(USER_ID, OLD_JTI)).thenReturn(true);

        assertThatThrownBy(() -> authService.refreshToken(refresh("old-refresh-token")))
                .isInstanceOf(AccountBannedException.class);
    }

    /** The password reset watermark, still enforced for any row the reset's sweep left behind. */
    @Test
    void aTokenIssuedBeforeAPasswordResetIsRefused() {
        UserEntity user = user();
        user.setTokensValidFrom(Instant.now());
        givenAPresentedToken(user, OLD_JTI, FAMILY);
        when(refreshTokenStore.consume(USER_ID, OLD_JTI)).thenReturn(true);
        when(jwtService.extractIssuedAt("old-refresh-token"))
                .thenReturn(Instant.now().minusSeconds(60));

        assertThatThrownBy(() -> authService.refreshToken(refresh("old-refresh-token")))
                .isInstanceOf(InvalidTokenException.class);

        verify(refreshTokenStore, never()).store(anyLong(), anyString(), anyString(), any(), any());
    }

    // --- logout ------------------------------------------------------------

    @Test
    void logoutDeletesThePresentedTokensRow() {
        authenticatedAs(USER_ID);
        when(jwtService.extractJti("my-refresh-token")).thenReturn(OLD_JTI);

        authService.logout(refresh("my-refresh-token"));

        verify(refreshTokenStore).consume(USER_ID, OLD_JTI);
    }

    /**
     * The account comes from the access token, never from the body. Otherwise a leaked refresh
     * token would be enough to sign its owner out.
     */
    @Test
    void logoutOnlyEverDeletesUnderTheAuthenticatedCallersId() {
        authenticatedAs(99L);
        when(jwtService.extractJti("someone-elses-token")).thenReturn(OLD_JTI);

        authService.logout(refresh("someone-elses-token"));

        verify(refreshTokenStore).consume(99L, OLD_JTI);
        verify(refreshTokenStore, never()).consume(eq(USER_ID), anyString());
    }

    /** Signing out of a session that is already gone is what the caller wanted, not an error. */
    @Test
    void logoutIsIdempotent() {
        authenticatedAs(USER_ID);
        when(jwtService.extractJti("spent-token")).thenReturn(OLD_JTI);
        when(refreshTokenStore.consume(USER_ID, OLD_JTI)).thenReturn(false);

        authService.logout(refresh("spent-token"));

        verify(refreshTokenStore).consume(USER_ID, OLD_JTI);
    }

    @Test
    void logoutRefusesATokenItCannotRead() {
        authenticatedAs(USER_ID);
        when(jwtService.extractJti("forged-token")).thenThrow(new SignatureException("bad signature"));

        assertThatThrownBy(() -> authService.logout(refresh("forged-token"))).isInstanceOf(InvalidTokenException.class);

        verify(refreshTokenStore, never()).consume(anyLong(), anyString());
    }

    @Test
    void logoutAllRevokesEveryRowTheAccountHolds() {
        authenticatedAs(USER_ID);

        authService.logoutAll();

        verify(refreshTokenStore).revokeAll(USER_ID);
    }

    // --- helpers -----------------------------------------------------------

    private static RefreshTokenRequest refresh(String token) {
        return RefreshTokenRequest.builder().refreshToken(token).build();
    }

    private static UserEntity user() {
        return UserEntity.builder()
                .userId(USER_ID)
                .username("ada")
                .email("ada@ems.local")
                .password("hashed")
                .isBanned(false)
                .emailVerifiedAt(Instant.now().minusSeconds(3600))
                .build();
    }

    /** Points the mocked {@code JwtService} at a token carrying these claims for this account. */
    private void givenAPresentedToken(UserEntity user, String jti, String familyId) {
        when(jwtService.extractUsername(anyString())).thenReturn(user.getUsername());
        when(jwtService.extractJti(anyString())).thenReturn(jti);
        when(jwtService.extractFamilyId(anyString())).thenReturn(familyId);
        when(userRepository.findByUsername(user.getUsername())).thenReturn(Optional.of(user));
    }

    /** Mirrors GatewayAuthenticationFilter: the principal is the raw X-User-Id string. */
    private static void authenticatedAs(Long userId) {
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(String.valueOf(userId), null, List.of()));
    }
}
