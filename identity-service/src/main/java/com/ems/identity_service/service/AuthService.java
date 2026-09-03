package com.ems.identity_service.service;

import com.ems.identity_service.dto.request.ForgotPasswordRequest;
import com.ems.identity_service.dto.request.LoginRequest;
import com.ems.identity_service.dto.request.RegisterRequest;
import com.ems.identity_service.dto.request.ResendVerificationRequest;
import com.ems.identity_service.dto.request.ResetPasswordRequest;
import com.ems.identity_service.dto.response.AuthResponse;
import com.ems.identity_service.dto.response.ForgotPasswordResponse;
import com.ems.identity_service.dto.response.PasswordResetResponse;
import com.ems.identity_service.dto.response.TokenValidationResponse;
import com.ems.identity_service.dto.response.VerificationResponse;

public interface AuthService {
    AuthResponse register(RegisterRequest request);

    AuthResponse login(LoginRequest request);

    AuthResponse getCurrentUser();

    TokenValidationResponse validateToken(String token);

    AuthResponse refreshToken(com.ems.identity_service.dto.request.RefreshTokenRequest request);

    /**
     * Redeems a verification token, marking it used and the account verified.
     *
     * @throws com.ems.identity_service.exception.InvalidVerificationTokenException if the
     *     token is unknown or expired
     */
    VerificationResponse verifyEmail(String token);

    /**
     * Issues a fresh verification token and republishes the registration event, retiring any
     * link sent before it.
     *
     * @throws com.ems.identity_service.exception.ResendTooSoonException if the address was
     *     mailed within the cooldown
     */
    void resendVerification(ResendVerificationRequest request);

    /**
     * Issues a password reset token and publishes {@code password.reset.requested}, retiring
     * any link sent before it.
     *
     * <p>Throws nothing a caller can tell apart. An address with no account, one that has hit
     * the hourly limit, and a successful request are all the same return — the difference is
     * only in whether mail goes out. Anything else would make this a way of asking which
     * addresses are registered.
     */
    ForgotPasswordResponse forgotPassword(ForgotPasswordRequest request);

    /**
     * Redeems a reset token: sets the new password, spends the token, and revokes the
     * account's outstanding refresh tokens.
     *
     * @throws com.ems.identity_service.exception.InvalidPasswordResetTokenException if the
     *     token is unknown, expired, or already spent
     */
    PasswordResetResponse resetPassword(ResetPasswordRequest request);
}
