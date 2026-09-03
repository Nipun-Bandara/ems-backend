package com.ems.identity_service.controller;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.util.List;
import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.Test;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** Verifies the @PreAuthorize expression guarding GET /api/users/{userId}. */
class UserAccessExpressionTest {

    private static final String EXPRESSION = "hasRole('SYSTEM_ADMIN') or hasRole('DEPARTMENT_HEAD')"
            + " or #userId.toString() == authentication.principal";

    /** Mirrors GatewayAuthenticationFilter: principal is the raw X-User-Id string. */
    private Authentication auth(String userId, String... roles) {
        return new UsernamePasswordAuthenticationToken(
                userId,
                null,
                List.of(roles).stream().map(SimpleGrantedAuthority::new).toList());
    }

    private boolean evaluate(String expression, Authentication authentication, Long pathUserId) throws Exception {
        DefaultMethodSecurityExpressionHandler handler = new DefaultMethodSecurityExpressionHandler();
        Method method = UserController.class.getMethod("getUserById", Long.class);
        MethodInvocation invocation = new StubInvocation(method, new Object[] {pathUserId});
        EvaluationContext ctx = handler.createEvaluationContext(() -> authentication, invocation);
        Expression parsed = handler.getExpressionParser().parseExpression(expression);
        return Boolean.TRUE.equals(parsed.getValue(ctx, Boolean.class));
    }

    @Test
    void employeeReadingOwnRecordIsAllowed() throws Exception {
        assertTrue(evaluate(EXPRESSION, auth("42", "ROLE_EMPLOYEE"), 42L));
    }

    @Test
    void employeeReadingAnotherUserIsDenied() throws Exception {
        assertFalse(evaluate(EXPRESSION, auth("42", "ROLE_EMPLOYEE"), 99L));
    }

    @Test
    void adminAndDepartmentHeadCanReadAnyUser() throws Exception {
        assertTrue(evaluate(EXPRESSION, auth("7", "ROLE_SYSTEM_ADMIN"), 99L));
        assertTrue(evaluate(EXPRESSION, auth("7", "ROLE_DEPARTMENT_HEAD"), 99L));
    }

    /** The form in the ticket: a Long path variable never equals a String principal in SpEL. */
    @Test
    void rawLongComparisonWouldAlwaysBeFalse() throws Exception {
        assertFalse(evaluate("#userId == authentication.principal", auth("42", "ROLE_EMPLOYEE"), 42L));
    }

    private record StubInvocation(Method getMethod, Object[] getArguments) implements MethodInvocation {
        @Override
        public Object getThis() {
            return null;
        }

        @Override
        public java.lang.reflect.AccessibleObject getStaticPart() {
            return getMethod;
        }

        @Override
        public Object proceed() {
            throw new UnsupportedOperationException();
        }
    }
}
