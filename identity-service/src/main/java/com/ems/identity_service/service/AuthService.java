package com.ems.identity_service.service;

import com.ems.identity_service.dto.request.LoginRequest;
import com.ems.identity_service.dto.request.RegisterRequest;
import com.ems.identity_service.dto.request.ResendVerificationRequest;
import com.ems.identity_service.dto.response.AuthResponse;
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
}
