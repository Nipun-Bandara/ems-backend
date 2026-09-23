-- The two tables required by ems-common's outbox and idempotent consumer.
-- Domain tables begin in a later migration when the organization domain lands.

CREATE TABLE outbox_event (
    id uuid NOT NULL,
    aggregate_type varchar(255) NOT NULL,
    aggregate_id varchar(255) NOT NULL,
    type varchar(255) NOT NULL,
    payload jsonb NOT NULL,
    correlation_id varchar(255),
    created_at timestamp(6) with time zone NOT NULL,
    sent_at timestamp(6) with time zone,
    attempts integer NOT NULL DEFAULT 0,
    next_attempt_at timestamp(6) with time zone NOT NULL DEFAULT now(),
    PRIMARY KEY (id)
);

CREATE INDEX idx_outbox_event_pending
    ON outbox_event (next_attempt_at, created_at)
    WHERE sent_at IS NULL;

CREATE TABLE processed_event (
    event_id uuid NOT NULL,
    consumer varchar(255) NOT NULL,
    processed_at timestamp(6) with time zone NOT NULL,
    PRIMARY KEY (event_id, consumer)
);
