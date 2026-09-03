package com.ems.identity_service.controller;

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
import com.ems.identity_service.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .contentType(MediaType.APPLICATION_JSON)
                .body(authService.register(request));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(authService.login(request));
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refreshToken(
            @Valid @RequestBody com.ems.identity_service.dto.request.RefreshTokenRequest request) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(authService.refreshToken(request));
    }

    @GetMapping("/me")
    public ResponseEntity<AuthResponse> getCurrentUser() {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(authService.getCurrentUser());
    }

    /**
     * Redeems a verification link. A GET because it is what a mail client opens in a browser,
     * and the request is idempotent: spending an already-spent token is a 200 saying so, not
     * an error. See {@link VerificationResponse}.
     */
    @GetMapping("/verify")
    public ResponseEntity<VerificationResponse> verifyEmail(@RequestParam("token") String token) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(authService.verifyEmail(token));
    }

    /**
     * Sends a fresh verification link, retiring the ones before it.
     *
     * <p>204 rather than a body, and the same 204 whether or not anything was sent: an
     * address with no account and one that is already verified are answered exactly as a
     * successful resend is, so that this public endpoint cannot be used to ask which
     * addresses are registered.
     */
    @PostMapping("/resend-verification")
    public ResponseEntity<Void> resendVerification(@Valid @RequestBody ResendVerificationRequest request) {
        authService.resendVerification(request);
        return ResponseEntity.noContent().build();
    }

    /**
     * Starts a password reset.
     *
     * <p>Always 200, always the same body. Whether the address has an account, and whether it
     * has already had its three links this hour, are both invisible here — see
     * {@link ForgotPasswordResponse}.
     */
    @PostMapping("/forgot-password")
    public ResponseEntity<ForgotPasswordResponse> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(authService.forgotPassword(request));
    }

    /**
     * Completes a password reset.
     *
     * <p>A POST, not the GET that {@code /verify} is: this one is not idempotent and must not
     * be something a mail client can trigger by prefetching the link. The link in the email
     * goes to a page, and the page makes this request once the user has typed a new password.
     */
    @PostMapping("/reset-password")
    public ResponseEntity<PasswordResetResponse> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(authService.resetPassword(request));
    }

    @GetMapping("/validate")
    public ResponseEntity<TokenValidationResponse> validateToken(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @RequestParam(value = "token", required = false) String token) {
        String tokenToValidate = authorizationHeader != null ? authorizationHeader : token;

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(authService.validateToken(tokenToValidate));
    }
}
