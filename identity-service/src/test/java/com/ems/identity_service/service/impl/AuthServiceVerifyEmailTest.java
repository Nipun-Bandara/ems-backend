package com.ems.identity_service.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ems.common.outbox.OutboxPublisher;
import com.ems.identity_service.dto.response.VerificationResponse;
import com.ems.identity_service.entity.UserEntity;
import com.ems.identity_service.entity.VerificationToken;
import com.ems.identity_service.event.UserVerifiedPayload;
import com.ems.identity_service.exception.AuthErrorCode;
import com.ems.identity_service.exception.InvalidVerificationTokenException;
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
 * Covers redeeming a verification token, where the interesting part is what a <em>spent</em>
 * token means. It has two causes that look identical in the token row — it was redeemed, or a
 * resend retired it — and only the account says which. Getting that wrong tells the holder of
 * a stale link they are verified and then refuses them at sign-in.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthServiceVerifyEmailTest {

    private static final Instant NOW = Instant.parse("2026-09-03T10:15:30Z");

    @Mock
    private com.ems.identity_service.repository.UserRepository userRepository;

    @Mock
    private com.ems.identity_service.repository.RoleRepository roleRepository;

    @Mock
    private com.ems.identity_service.repository.UserRolesRepository userRolesRepository;

    @Mock
    private com.ems.identity_service.repository.VerificationTokenRepository verificationTokenRepository;

    @Mock
    private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    @Mock
    private com.ems.identity_service.security.JwtService jwtService;

    @Mock
    private org.springframework.security.authentication.AuthenticationManager authenticationManager;

    @Mock
    private OutboxPublisher outboxPublisher;

    @InjectMocks
    private AuthServiceImpl authService;

    @Test
    void verifiesTheAccountAndPublishesOnAFreshToken() {
        UserEntity user = user(null);
        VerificationToken token = given(user, NOW.minusSeconds(60), false);

        VerificationResponse response = authService.verifyEmail(token.getToken().toString());

        assertThat(response.alreadyVerified()).isFalse();
        assertThat(response.email()).isEqualTo("ada@ems.local");
        assertThat(user.getEmailVerifiedAt()).isNotNull();
        assertThat(token.isUsed()).isTrue();
        verify(outboxPublisher).publish(anyString(), anyString(), eq(UserVerifiedPayload.TYPE), any());
    }

    /** A second click is the same answer, and emits nothing: no state changed this time. */
    @Test
    void reportsAlreadyVerifiedWhenTheTokenWasRedeemedAndTheAccountIsVerified() {
        UserEntity user = user(NOW.minusSeconds(120));
        VerificationToken token = given(user, NOW.minusSeconds(300), true);

        VerificationResponse response = authService.verifyEmail(token.getToken().toString());

        assertThat(response.alreadyVerified()).isTrue();
        verify(outboxPublisher, never()).publish(anyString(), anyString(), anyString(), any());
    }

    /**
     * The case that matters: spent because a resend retired it, on an account still
     * unverified. Answering "already verified" here would be a lie the user only discovers
     * when sign-in refuses them.
     */
    @Test
    void rejectsATokenRetiredByAResendWhileTheAccountIsStillUnverified() {
        UserEntity user = user(null);
        VerificationToken token = given(user, NOW.minusSeconds(300), true);

        assertThatThrownBy(() -> authService.verifyEmail(token.getToken().toString()))
                .isInstanceOf(InvalidVerificationTokenException.class)
                .extracting(ex -> ((InvalidVerificationTokenException) ex).getCode())
                .isEqualTo(AuthErrorCode.VERIFICATION_TOKEN_INVALID);

        assertThat(user.getEmailVerifiedAt()).isNull();
    }

    @Test
    void rejectsAnExpiredTokenAsExpiredSoThePageCanOfferAResend() {
        UserEntity user = user(null);
        VerificationToken token = given(user, NOW.minus(VerificationToken.TTL).minusSeconds(3600), false);

        assertThatThrownBy(() -> authService.verifyEmail(token.getToken().toString()))
                .isInstanceOf(InvalidVerificationTokenException.class)
                .extracting(ex -> ((InvalidVerificationTokenException) ex).getCode())
                .isEqualTo(AuthErrorCode.VERIFICATION_TOKEN_EXPIRED);

        assertThat(user.getEmailVerifiedAt()).isNull();
    }

    /** Not a UUID at all, so it never named a row. Same answer as one we do not hold. */
    @Test
    void rejectsAMalformedTokenAsUnknown() {
        assertThatThrownBy(() -> authService.verifyEmail("not-a-uuid"))
                .isInstanceOf(InvalidVerificationTokenException.class)
                .extracting(ex -> ((InvalidVerificationTokenException) ex).getCode())
                .isEqualTo(AuthErrorCode.VERIFICATION_TOKEN_INVALID);
    }

    @Test
    void rejectsAnUnknownTokenAsUnknown() {
        UUID unknown = UUID.randomUUID();
        when(verificationTokenRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.verifyEmail(unknown.toString()))
                .isInstanceOf(InvalidVerificationTokenException.class)
                .extracting(ex -> ((InvalidVerificationTokenException) ex).getCode())
                .isEqualTo(AuthErrorCode.VERIFICATION_TOKEN_INVALID);
    }

    private UserEntity user(Instant emailVerifiedAt) {
        UserEntity user = UserEntity.builder()
                .userId(7L)
                .username("ada")
                .email("ada@ems.local")
                .password("hashed")
                .build();
        user.setEmailVerifiedAt(emailVerifiedAt);
        return user;
    }

    /** Registers a token against the mocked repositories and returns it. */
    private VerificationToken given(UserEntity user, Instant issuedAt, boolean used) {
        VerificationToken token = VerificationToken.issueFor(user.getUserId(), issuedAt);
        if (used) {
            token.markUsed(issuedAt.plusSeconds(1));
        }
        when(verificationTokenRepository.findById(token.getToken())).thenReturn(Optional.of(token));
        when(userRepository.findById(user.getUserId())).thenReturn(Optional.of(user));
        return token;
    }
}
