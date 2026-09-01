package com.ems.api_gateway.security;

import com.ems.common.error.ErrorResponseWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class AuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final ObjectMapper objectMapper;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        String method = request.getMethod();
        return "OPTIONS".equalsIgnoreCase(method)
                || path.startsWith("/api/auth/login")
                || path.startsWith("/api/auth/register");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String authHeader = request.getHeader("Authorization");

        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            try {
                jwtUtil.validateToken(token);
            } catch (Exception e) {
                ErrorResponseWriter.write(
                        objectMapper,
                        request,
                        response,
                        HttpStatus.UNAUTHORIZED,
                        "Unauthorized",
                        "Unauthorized: Invalid Token");
                return;
            }
        } else {
            ErrorResponseWriter.write(
                    objectMapper,
                    request,
                    response,
                    HttpStatus.UNAUTHORIZED,
                    "Unauthorized",
                    "Unauthorized: Missing Token");
            return;
        }

        filterChain.doFilter(request, response);
    }
}
