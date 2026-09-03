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
import com.ems.identity_service.dto.request.ForgotPasswordRequest;
import com.ems.identity_service.dto.request.ResetPasswordRequest;
import com.ems.identity_service.dto.response.ForgotPasswordResponse;
import com.ems.identity_service.dto.response.PasswordResetResponse;
import com.ems.identity_service.entity.PasswordResetToken;
import com.ems.identity_service.entity.UserEntity;
import com.ems.identity_service.event.PasswordChangedPayload;
import com.ems.identity_service.event.PasswordResetRequestedPayload;
import com.ems.identity_service.exception.AuthErrorCode;
import com.ems.identity_service.exception.InvalidPasswordResetTokenException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Covers the password reset pair. Two properties carry most of the weight here.
 *
 * <p>The first is that forgot-password must look identical from the outside whatever it did
 * internally — an account, no account, or an address over its limit are one response — because
 * any difference turns a public endpoint into a way of asking who is registered.
 *
 * <p>The second is that reset-password spends its token, so the second attempt with the same
 * link must fail rather than silently succeed against a password that was never applied.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthServicePasswordResetTest {

    /**
     * Fixtures are placed relative to the real clock rather than a fixed instant, because
     * {@link AuthServiceImpl} calls {@code Instant.now()} directly and the reset TTL is only
     * an hour. A hard-coded timestamp would make whether a token counts as expired depend on
     * what time of day the suite runs.
     */
    private static Instant agoBy(java.time.Duration amount) {
        return Instant.now().minus(amount);
    }

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
    private com.ems.identity_service.security.RefreshTokenStore refreshTokenStore;

    @Mock
    private org.springframework.security.authentication.AuthenticationManager authenticationManager;

    @Mock
    private OutboxPublisher outboxPublisher;

    @InjectMocks
    private AuthServiceImpl authService;

    // --- forgot-password ---------------------------------------------------

    @Test
    void issuesATokenAndPublishesForAKnownAddress() {
        UserEntity user = user();
        when(userRepository.findByEmail("ada@ems.local")).thenReturn(Optional.of(user));
        when(passwordResetTokenRepository.countByUserIdAndCreatedAtAfter(anyLong(), any()))
                .thenReturn(0L);
        when(passwordResetTokenRepository.save(any(PasswordResetToken.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ForgotPasswordResponse response = authService.forgotPassword(forgot("ada@ems.local"));

        assertThat(response.message()).isNotBlank();
        verify(passwordResetTokenRepository).save(any(PasswordResetToken.class));
        verify(outboxPublisher).publish(anyString(), anyString(), eq(PasswordResetRequestedPayload.TYPE), any());
    }

    /** The whole point of the endpoint's shape: nothing sent, and nothing that says so. */
    @Test
    void answersAnUnknownAddressIdenticallyAndSendsNothing() {
        when(userRepository.findByEmail("nobody@ems.local")).thenReturn(Optional.empty());

        ForgotPasswordResponse response = authService.forgotPassword(forgot("nobody@ems.local"));

        assertThat(response.message())
                .isEqualTo(ForgotPasswordResponse.generic().message());
        verify(passwordResetTokenRepository, never()).save(any());
        verify(outboxPublisher, never()).publish(anyString(), anyString(), anyString(), any());
    }

    /**
     * Over the limit is suppressed rather than refused. A 429 would be reachable only for an
     * address that has an account, so the status alone would answer the question the identical
     * response exists to refuse.
     */
    @Test
    void suppressesAFourthRequestInTheHourWithoutRevealingTheLimitWasHit() {
        UserEntity user = user();
        when(userRepository.findByEmail("ada@ems.local")).thenReturn(Optional.of(user));
        when(passwordResetTokenRepository.countByUserIdAndCreatedAtAfter(anyLong(), any()))
                .thenReturn(3L);

        ForgotPasswordResponse response = authService.forgotPassword(forgot("ada@ems.local"));

        assertThat(response.message())
                .isEqualTo(ForgotPasswordResponse.generic().message());
        verify(passwordResetTokenRepository, never()).save(any());
        verify(outboxPublisher, never()).publish(anyString(), anyString(), anyString(), any());
    }

    @Test
    void retiresOutstandingLinksBeforeMintingANewOne() {
        UserEntity user = user();
        when(userRepository.findByEmail("ada@ems.local")).thenReturn(Optional.of(user));
        when(passwordResetTokenRepository.countByUserIdAndCreatedAtAfter(anyLong(), any()))
                .thenReturn(1L);
        when(passwordResetTokenRepository.save(any(PasswordResetToken.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        authService.forgotPassword(forgot("ada@ems.local"));

        verify(passwordResetTokenRepository).invalidateOutstanding(eq(7L), any());
    }

    // --- reset-password ----------------------------------------------------

    @Test
    void setsTheNewPasswordSpendsTheTokenAndRevokesRefreshTokens() {
        UserEntity user = user();
        PasswordResetToken token = given(user, agoBy(java.time.Duration.ofMinutes(1)), false);
        when(passwordEncoder.encode("new-secret")).thenReturn("hashed-new-secret");

        PasswordResetResponse response =
                authService.resetPassword(reset(token.getToken().toString(), "new-secret"));

        assertThat(response.email()).isEqualTo("ada@ems.local");
        assertThat(user.getPassword()).isEqualTo("hashed-new-secret");
        assertThat(token.isUsed()).isTrue();
        // The watermark is what actually revokes the sessions -- without it the reset would
        // change the password and leave every existing refresh token working.
        assertThat(user.getTokensValidFrom()).isNotNull();
        // And the rows go with it, so the sessions end now rather than merely stopping being
        // extendable. Item 14's requirement, and the reason a reset is not just a password write.
        verify(refreshTokenStore).revokeAll(7L);
        verify(outboxPublisher).publish(anyString(), anyString(), eq(PasswordChangedPayload.TYPE), any());
    }

    /**
     * The case the acceptance criteria names. Unlike a spent verification token, this is a
     * failure: the second caller's password was never applied, so telling them it worked would
     * lock them out of an account they believe they just changed.
     */
    @Test
    void rejectsAnAlreadyUsedToken() {
        UserEntity user = user();
        PasswordResetToken token = given(user, agoBy(java.time.Duration.ofMinutes(5)), true);

        assertThatThrownBy(
                        () -> authService.resetPassword(reset(token.getToken().toString(), "new-secret")))
                .isInstanceOf(InvalidPasswordResetTokenException.class)
                .extracting(ex -> ((InvalidPasswordResetTokenException) ex).getCode())
                .isEqualTo(AuthErrorCode.PASSWORD_RESET_TOKEN_USED);

        assertThat(user.getPassword()).isEqualTo("hashed");
        verify(outboxPublisher, never()).publish(anyString(), anyString(), anyString(), any());
    }

    @Test
    void rejectsAnExpiredTokenAsExpiredSoThePageCanOfferAnother() {
        UserEntity user = user();
        PasswordResetToken token = given(user, agoBy(PasswordResetToken.TTL.plusMinutes(10)), false);

        assertThatThrownBy(
                        () -> authService.resetPassword(reset(token.getToken().toString(), "new-secret")))
                .isInstanceOf(InvalidPasswordResetTokenException.class)
                .extracting(ex -> ((InvalidPasswordResetTokenException) ex).getCode())
                .isEqualTo(AuthErrorCode.PASSWORD_RESET_TOKEN_EXPIRED);

        assertThat(user.getPassword()).isEqualTo("hashed");
    }

    @Test
    void rejectsAnUnknownTokenAsUnknown() {
        UUID unknown = UUID.randomUUID();
        when(passwordResetTokenRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.resetPassword(reset(unknown.toString(), "new-secret")))
                .isInstanceOf(InvalidPasswordResetTokenException.class)
                .extracting(ex -> ((InvalidPasswordResetTokenException) ex).getCode())
                .isEqualTo(AuthErrorCode.PASSWORD_RESET_TOKEN_INVALID);
    }

    /** Not a UUID at all, so it never named a row. Same answer as one we do not hold. */
    @Test
    void rejectsAMalformedTokenAsUnknown() {
        assertThatThrownBy(() -> authService.resetPassword(reset("not-a-uuid", "new-secret")))
                .isInstanceOf(InvalidPasswordResetTokenException.class)
                .extracting(ex -> ((InvalidPasswordResetTokenException) ex).getCode())
                .isEqualTo(AuthErrorCode.PASSWORD_RESET_TOKEN_INVALID);
    }

    // --- helpers -----------------------------------------------------------

    private static ForgotPasswordRequest forgot(String email) {
        return ForgotPasswordRequest.builder().email(email).build();
    }

    private static ResetPasswordRequest reset(String token, String newPassword) {
        return ResetPasswordRequest.builder()
                .token(token)
                .newPassword(newPassword)
                .build();
    }

    private UserEntity user() {
        return UserEntity.builder()
                .userId(7L)
                .username("ada")
                .email("ada@ems.local")
                .password("hashed")
                .build();
    }

    /** Registers a reset token against the mocked repositories and returns it. */
    private PasswordResetToken given(UserEntity user, Instant issuedAt, boolean used) {
        PasswordResetToken token = PasswordResetToken.issueFor(user.getUserId(), issuedAt);
        if (used) {
            token.markUsed(issuedAt.plusSeconds(1));
        }
        when(passwordResetTokenRepository.findById(token.getToken())).thenReturn(Optional.of(token));
        when(userRepository.findById(user.getUserId())).thenReturn(Optional.of(user));
        return token;
    }
}
