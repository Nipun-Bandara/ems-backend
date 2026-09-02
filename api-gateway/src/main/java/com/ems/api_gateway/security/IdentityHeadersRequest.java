package com.ems.api_gateway.security;

import com.ems.common.security.GatewayHeaders;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.http.HttpHeaders;

/**
 * The request as downstream services should see it: the bearer token replaced by the identity the
 * gateway resolved from it.
 *
 * <p>The identity headers are always overridden, never merged, so a client cannot smuggle in its
 * own {@code X-User-Id} — that guarantee is the whole basis on which services are allowed to trust
 * the header. Requests that were not authenticated (the public routes) get them stripped outright.
 */
class IdentityHeadersRequest extends HttpServletRequestWrapper {

    /** Header name (case-insensitively) to value, or absent to hide an inbound header. */
    private final Map<String, String> overrides = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

    /**
     * @param userId the authenticated user's id, or null to forward the request unauthenticated
     * @param roles the user's roles, comma separated, or null
     */
    IdentityHeadersRequest(HttpServletRequest request, String userId, String roles) {
        super(request);
        // The token has done its job at the edge. Services authenticate on the headers below, and
        // forwarding the token as well would invite them to go back to parsing it.
        overrides.put(HttpHeaders.AUTHORIZATION, null);
        overrides.put(GatewayHeaders.USER_ID, userId);
        overrides.put(GatewayHeaders.USER_ROLES, roles);
    }

    @Override
    public String getHeader(String name) {
        if (overrides.containsKey(name)) {
            return overrides.get(name);
        }
        return super.getHeader(name);
    }

    @Override
    public Enumeration<String> getHeaders(String name) {
        if (overrides.containsKey(name)) {
            String value = overrides.get(name);
            return Collections.enumeration(value == null ? List.of() : List.of(value));
        }
        return super.getHeaders(name);
    }

    @Override
    public Enumeration<String> getHeaderNames() {
        List<String> names = new ArrayList<>();
        for (Enumeration<String> inbound = super.getHeaderNames(); inbound.hasMoreElements(); ) {
            String name = inbound.nextElement();
            if (!overrides.containsKey(name)) {
                names.add(name);
            }
        }
        overrides.forEach((name, value) -> {
            if (value != null) {
                names.add(name);
            }
        });
        return Collections.enumeration(names);
    }
}
