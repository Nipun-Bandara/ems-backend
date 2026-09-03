-- Password reset: single-use tokens that let someone who can read an
-- account's email choose a new password without knowing the old one, and a
-- watermark that retires the sessions a reset was meant to end.
--
-- Same conventions as V4: 'timestamp(6) with time zone' because these map to
-- Instant and ddl-auto: validate checks the entities against this schema on
-- every start.

-- The moment before which a token signed for this user is no longer accepted.
--
-- Refresh tokens here are stateless JWTs -- nothing records that one was
-- issued, so there is no row to delete when a password changes. Stamping a
-- watermark on the account instead lets the refresh endpoint reject every
-- token minted before the reset with one comparison and no extra table.
--
-- Nullable, and no default: null means "nothing has ever been revoked for this
-- account", which is the honest answer for every row that exists today.
ALTER TABLE users
    ADD COLUMN tokens_valid_from timestamp(6) with time zone;

CREATE TABLE password_reset_token (
    -- The token itself is the key, exactly as in verification_token: it is what
    -- arrives in the reset request, so the lookup is a primary key hit and the
    -- same value can never exist twice.
    token uuid NOT NULL,
    user_id bigint NOT NULL,
    -- Not in the same sense as expires_at, which is what makes a link stop
    -- working. This one backs the rate limit, which asks how many links were
    -- issued to an address in the last hour and cannot be derived from a TTL
    -- that may be retuned later.
    created_at timestamp(6) with time zone NOT NULL,
    expires_at timestamp(6) with time zone NOT NULL,
    -- Null until spent. A token is redeemable exactly once.
    used_at timestamp(6) with time zone,
    PRIMARY KEY (token)
);

-- ON DELETE CASCADE for the same reason as verification_token: a reset token
-- for an account that is gone can only ever be a failed lookup.
ALTER TABLE password_reset_token
    ADD CONSTRAINT fk_password_reset_token_user
    FOREIGN KEY (user_id) REFERENCES users (user_id) ON DELETE CASCADE;

-- Covers both queries that go by user rather than by token: counting the last
-- hour's issues for the rate limit, and retiring a user's outstanding tokens
-- when a newer one is minted. created_at descending so neither has to sort.
CREATE INDEX idx_password_reset_token_user
    ON password_reset_token (user_id, created_at DESC);
