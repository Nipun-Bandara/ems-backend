package com.ems.identity_service.service;

import com.ems.identity_service.dto.response.AuthResponse;
import com.ems.identity_service.dto.response.TokenValidationResponse;
import com.ems.identity_service.dto.request.LoginRequest;
import com.ems.identity_service.dto.request.RegisterRequest;

public interface AuthService {
    AuthResponse register(RegisterRequest request);

    AuthResponse login(LoginRequest request);

    AuthResponse getCurrentUser();

    TokenValidationResponse validateToken(String token);

    AuthResponse refreshToken(com.ems.identity_service.dto.request.RefreshTokenRequest request);
}
