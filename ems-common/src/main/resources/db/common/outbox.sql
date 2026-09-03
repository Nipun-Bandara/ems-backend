-- The two tables the outbox pattern needs, shared by every EMS service.
--
-- Not a Flyway migration itself: it carries no version, because the version it
-- lands on differs per service. A service applies it by declaring a migration
-- that extends com.ems.common.outbox.OutboxSchemaMigration, which runs this
-- file -- see identity-service's db/migration/V3__outbox.java.
--
-- Editing this file changes the checksum of every migration that has already
-- run it, which Flyway will refuse. Add a second script and a second migration
-- instead.

-- Domain events waiting to reach the broker. Written in the same transaction as
-- the change that caused them, drained afterwards by OutboxPoller.
CREATE TABLE outbox_event (
    -- The event id, minted by OutboxPublisher and carried on the envelope. It is
    -- what a consumer deduplicates on, so it is not a surrogate key.
    id uuid NOT NULL,
    aggregate_type varchar(255) NOT NULL,
    aggregate_id varchar(255) NOT NULL,
    -- Also the routing key the event is published under.
    type varchar(255) NOT NULL,
    payload jsonb NOT NULL,
    correlation_id varchar(255),
    created_at timestamp(6) with time zone NOT NULL,
    -- Null until the broker has accepted it. The poller only claims nulls.
    sent_at timestamp(6) with time zone,
    attempts integer NOT NULL DEFAULT 0,
    -- Backoff state. In the row rather than in the poller so that it survives a
    -- restart and is shared by every instance.
    next_attempt_at timestamp(6) with time zone NOT NULL DEFAULT now(),
    PRIMARY KEY (id)
);

-- Covers the poller's claim query. Partial, so it holds only the backlog: a sent
-- row leaves the index, which keeps it small no matter how much history the
-- table accumulates.
CREATE INDEX idx_outbox_event_pending
    ON outbox_event (next_attempt_at, created_at)
    WHERE sent_at IS NULL;

-- Events a consumer has already handled. Written by IdempotentConsumerAspect in
-- the handler's own transaction, which is what makes a redelivery a no-op.
CREATE TABLE processed_event (
    event_id uuid NOT NULL,
    -- Part of the key: one service may have several handlers for the same event,
    -- and each of them needs to run once.
    consumer varchar(255) NOT NULL,
    processed_at timestamp(6) with time zone NOT NULL,
    PRIMARY KEY (event_id, consumer)
);
