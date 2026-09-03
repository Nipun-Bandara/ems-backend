package com.ems.identity_service.dto.response;

/**
 * Answer to a reset that succeeded.
 *
 * <p>Echoes the address so the sign-in page the user is sent to can prefill it and say which
 * account was changed. Safe to return here in a way it would not be from
 * {@link ForgotPasswordResponse}: reaching this response at all required a valid token, and
 * whoever holds one has already proved they can read that mailbox.
 *
 * <p>No tokens. A reset ends every session the account had rather than starting a new one, so
 * the caller is sent to sign in with the password they just chose.
 */
public record PasswordResetResponse(String email) {}
