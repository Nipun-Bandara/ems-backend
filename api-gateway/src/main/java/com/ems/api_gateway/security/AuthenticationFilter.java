package com.ems.api_gateway.security;

import com.ems.common.error.ErrorResponseWriter;
import com.nimbusds.jwt.JWTClaimsSet;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * The single place a token is verified. Downstream services see only the identity it carried, as
 * {@code X-User-Id} and {@code X-User-Roles}.
 *
 * <p>Every request is wrapped, including the public ones, because the wrapper is also what strips
 * client-supplied identity headers.
 */
@Component
@Order(2)
@RequiredArgsConstructor
public class AuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AuthenticationFilter.class);

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String USER_ID_CLAIM = "userId";
    private static final String ROLES_CLAIM = "roles";

    /**
     * Endpoints reachable without a valid access token. Matched as Ant patterns, not prefixes: a
     * refresh carries an access token that has already expired, so it has to be exempt, while
     * {@code /api/auth/loginfoo} must not inherit the exemption of {@code /api/auth/login}.
     */
    private static final List<String> PUBLIC_PATHS = List.of(
            "/api/auth/login",
            "/api/auth/register",
            "/api/auth/refresh",
            "/api/auth/validate",
            // Email verification. A link opened from a mail client carries no Authorization
            // header, and an account that cannot sign in yet has no token to present for the
            // resend either -- these two are exactly the pair an unverified user can reach.
            "/api/auth/verify",
            "/api/auth/resend-verification",
            // Password reset. A user who has forgotten their password has no token to
            // present, and the reset link is opened from a mail client that has none either.
            "/api/auth/forgot-password",
            "/api/auth/reset-password",
            "/.well-known/**",
            "/actuator/health/**");

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    private final JwtTokenValidator jwtTokenValidator;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        if (isPublic(request)) {
            filterChain.doFilter(new IdentityHeadersRequest(request, null, null), response);
            return;
        }

        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            unauthorized(request, response, "Unauthorized: Missing Token");
            return;
        }

        JWTClaimsSet claims;
        try {
            claims = jwtTokenValidator.validate(authHeader.substring(BEARER_PREFIX.length()));
        } catch (Exception e) {
            log.debug("Rejecting {} {}: {}", request.getMethod(), request.getRequestURI(), e.getMessage());
            unauthorized(request, response, "Unauthorized: Invalid Token");
            return;
        }

        String userId = readUserId(claims);
        if (userId == null) {
            log.debug(
                    "Rejecting {} {}: token has no usable userId claim", request.getMethod(), request.getRequestURI());
            unauthorized(request, response, "Unauthorized: Invalid Token");
            return;
        }

        filterChain.doFilter(new IdentityHeadersRequest(request, userId, readRoles(claims)), response);
    }

    private static boolean isPublic(HttpServletRequest request) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI();
        return PUBLIC_PATHS.stream().anyMatch(pattern -> PATH_MATCHER.match(pattern, path));
    }

    /** The claim is a number when identity-service writes it, but read it leniently. */
    private static String readUserId(JWTClaimsSet claims) {
        Object userId = claims.getClaim(USER_ID_CLAIM);
        if (userId == null) {
            return null;
        }
        String value = String.valueOf(userId).trim();
        return value.isEmpty() ? null : value;
    }

    private static String readRoles(JWTClaimsSet claims) {
        List<String> roles;
        try {
            roles = claims.getStringListClaim(ROLES_CLAIM);
        } catch (java.text.ParseException e) {
            // A token with a malformed roles claim still identifies its user; it just grants
            // nothing, which every downstream authorization rule already handles.
            return null;
        }
        return roles == null || roles.isEmpty() ? null : String.join(",", roles);
    }

    private void unauthorized(HttpServletRequest request, HttpServletResponse response, String message)
            throws IOException {
        ErrorResponseWriter.write(objectMapper, request, response, HttpStatus.UNAUTHORIZED, "Unauthorized", message);
    }
}
