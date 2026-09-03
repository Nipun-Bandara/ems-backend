-- Email verification: an account now records whether its address was proved
-- reachable, and the single-use tokens that prove it get a table.
--
-- Written to match what Hibernate expects from UserEntity and
-- VerificationToken, since ddl-auto: validate checks the entities against the
-- schema on every start. Instant maps to 'timestamp(6) with time zone', which
-- is why the columns here differ from users.created_at -- that one is a
-- LocalDateTime from V1 and stays as it is.

-- Nullable, and no default: null is the answer for every account that existed
-- before this migration as well as for every account registered after it, and
-- both mean the same thing. Backfilling the existing rows with now() would be
-- claiming an address was checked when nobody checked it.
ALTER TABLE users
    ADD COLUMN email_verified_at timestamp(6) with time zone;

CREATE TABLE verification_token (
    -- The token itself is the key: it is what arrives in the verify request.
    token uuid NOT NULL,
    user_id bigint NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    expires_at timestamp(6) with time zone NOT NULL,
    -- Null until spent. A token is redeemable exactly once.
    used_at timestamp(6) with time zone,
    PRIMARY KEY (token)
);

-- ON DELETE CASCADE because these rows are worth nothing without the account:
-- a token for a user who is gone can only ever be a failed lookup.
ALTER TABLE verification_token
    ADD CONSTRAINT fk_verification_token_user
    FOREIGN KEY (user_id) REFERENCES users (user_id) ON DELETE CASCADE;

-- Covers both queries that go by user rather than by token: retiring a user's
-- outstanding tokens on resend, and finding their newest one for the rate
-- limit. created_at descending so the latter is a lookup, not a sort.
CREATE INDEX idx_verification_token_user
    ON verification_token (user_id, created_at DESC);
