package com.ems.common.security;

/**
 * The headers the gateway uses to forward an already-authenticated identity downstream.
 *
 * <p>Kept free of any Spring Security types so the gateway — which authenticates but does not
 * depend on {@code spring-security-core} — can share the same names as the services that consume
 * them through {@link GatewayAuthenticationFilter}.
 */
public final class GatewayHeaders {

    /** The authenticated user's id. Its presence is what makes a request authenticated. */
    public static final String USER_ID = "X-User-Id";

    /** The user's roles, comma separated, with or without the {@code ROLE_} prefix. */
    public static final String USER_ROLES = "X-User-Roles";

    private GatewayHeaders() {}
}
