package db.migration;

import com.ems.common.outbox.OutboxSchemaMigration;

/**
 * Applies the shared outbox schema — {@code outbox_event} and {@code processed_event} — as
 * notification-service's third migration.
 *
 * <p>This service publishes nothing, so {@code outbox_event} stays empty; it is created
 * anyway because ems-common's outbox auto-configuration is on whenever a service has a
 * database, and Hibernate validates its entities against the schema either way. The table
 * this service actually depends on is {@code processed_event}, which is what makes
 * {@code @IdempotentConsumer} work.
 *
 * <p>The DDL is not here: it is ems-common's {@code db/common/outbox.sql}, and this class
 * exists to say which version slot it occupies in this service. See
 * {@link OutboxSchemaMigration}.
 */
public class V3__outbox extends OutboxSchemaMigration {}
