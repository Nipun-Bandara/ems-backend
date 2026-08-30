package com.ems.api_gateway.security;

import com.ems.api_gateway.dto.response.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class RateLimitingFilter extends OncePerRequestFilter {

    private static final long TIME_WINDOW_MS = TimeUnit.MINUTES.toMillis(1);
    private static final int MAX_REQUESTS_PER_WINDOW = 50; // Max 50 requests per minute
    // Simple in-memory rate limiter using a map: IP -> array [timestamp,
    // requestCount]
    private final ConcurrentHashMap<String, long[]> requestCountsPerIp = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String clientIp = request.getRemoteAddr();
        long currentTime = System.currentTimeMillis();

        requestCountsPerIp.compute(clientIp, (ip, data) -> {
            if (data == null || currentTime - data[0] > TIME_WINDOW_MS) {
                // First request or window expired, reset
                return new long[] {currentTime, 1};
            } else {
                // Increment counter
                data[1]++;
                return data;
            }
        });

        long[] data = requestCountsPerIp.get(clientIp);

        if (data[1] > MAX_REQUESTS_PER_WINDOW) {
            writeError(response, HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests", "Rate limit exceeded");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private void writeError(HttpServletResponse response, HttpStatus status, String error, String message)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType("application/json");

        ErrorResponse errorResponse = new ErrorResponse();
        errorResponse.setStatus(status.value());
        errorResponse.setError(error);
        errorResponse.setMessage(message);

        String body = "{\"status\":" + errorResponse.getStatus()
                + ",\"error\":\"" + escapeJson(errorResponse.getError())
                + "\",\"message\":\"" + escapeJson(errorResponse.getMessage())
                + "\"}";
        response.getWriter().write(body);
    }

    private String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
