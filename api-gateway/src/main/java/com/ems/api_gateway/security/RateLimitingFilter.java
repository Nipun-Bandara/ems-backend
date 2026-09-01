package com.ems.api_gateway.security;

import com.ems.common.error.ErrorResponseWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class RateLimitingFilter extends OncePerRequestFilter {

    private static final long TIME_WINDOW_MS = TimeUnit.MINUTES.toMillis(1);
    private static final int MAX_REQUESTS_PER_WINDOW = 50; // Max 50 requests per minute
    // Simple in-memory rate limiter using a map: IP -> array [timestamp,
    // requestCount]
    private final ConcurrentHashMap<String, long[]> requestCountsPerIp = new ConcurrentHashMap<>();

    private final ObjectMapper objectMapper;

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
            ErrorResponseWriter.write(
                    objectMapper,
                    request,
                    response,
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Too Many Requests",
                    "Rate limit exceeded");
            return;
        }

        filterChain.doFilter(request, response);
    }
}
