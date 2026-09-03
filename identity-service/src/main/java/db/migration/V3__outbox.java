package db.migration;

import com.ems.common.outbox.OutboxSchemaMigration;

/**
 * Applies the shared outbox schema — {@code outbox_event} and {@code processed_event} — as
 * identity-service's third migration.
 *
 * <p>The DDL is not here: it is ems-common's {@code db/common/outbox.sql}, and this class
 * exists to say which version slot it occupies in this service. See
 * {@link OutboxSchemaMigration}.
 *
 * <p>Java rather than SQL because Flyway cannot include one script from another, and a
 * versioned {@code .sql} file in a shared module would force the same version number on
 * every service that used it.
 */
public class V3__outbox extends OutboxSchemaMigration {}
