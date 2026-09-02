package com.ems.api_gateway.security;

import com.ems.common.error.ErrorResponseWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Fixed-window rate limiter, 50 requests per client IP per minute.
 *
 * <p>The counter lives in Redis rather than in this process, so every gateway instance draws from
 * one budget instead of each granting the full allowance on its own.
 *
 * <p>Runs ahead of {@link AuthenticationFilter} so that unauthenticated floods are shed before any
 * signature verification work is done.
 */
@Component
@Order(1)
@RequiredArgsConstructor
public class RateLimitingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitingFilter.class);

    private static final String KEY_PREFIX = "rl:";
    private static final Duration WINDOW = Duration.ofSeconds(60);
    private static final long MAX_REQUESTS_PER_WINDOW = 50;

    private static final String FORWARDED_FOR_HEADER = "X-Forwarded-For";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String key = KEY_PREFIX + clientIp(request) + ":" + Instant.now().getEpochSecond() / 60;

        long count;
        try {
            Long incremented = redisTemplate.opsForValue().increment(key);
            count = incremented == null ? 0 : incremented;
            if (count == 1) {
                // Only on the first hit of the window: re-expiring on every request would slide
                // the window forward instead of keeping it fixed.
                redisTemplate.expire(key, WINDOW);
            }
        } catch (DataAccessException e) {
            // Redis being down must not take the gateway down with it. Fail open and let the
            // request through rather than turning an infrastructure outage into a full outage.
            log.warn("Rate limiting skipped, Redis unavailable: {}", e.getMessage());
            filterChain.doFilter(request, response);
            return;
        }

        if (count > MAX_REQUESTS_PER_WINDOW) {
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

    /**
     * The left-most {@code X-Forwarded-For} entry is the original client as seen by the first
     * proxy. It is only trustworthy because the load balancer in front of the gateway overwrites
     * the header; exposing the gateway directly would let a caller pick its own bucket.
     */
    private static String clientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader(FORWARDED_FOR_HEADER);
        if (forwardedFor != null) {
            String first = forwardedFor.split(",", 2)[0].trim();
            if (!first.isEmpty()) {
                return first;
            }
        }
        return request.getRemoteAddr();
    }
}
