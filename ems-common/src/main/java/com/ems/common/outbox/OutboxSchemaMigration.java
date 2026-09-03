package com.ems.common.outbox;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Statement;
import java.util.zip.CRC32;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * Creates {@code outbox_event} and {@code processed_event} from the shared script in
 * {@code db/common/outbox.sql}, so that every service gets the same two tables without
 * copying the DDL into its own migration folder.
 *
 * <p>Flyway takes a migration's version from its class name, and the version a service is
 * up to is the service's business — which is why this is abstract. A service applies the
 * outbox schema by naming the slot it goes in:
 *
 * <pre>{@code
 * package db.migration;
 *
 * public class V3__outbox extends OutboxSchemaMigration {}
 * }</pre>
 *
 * <p>The script deliberately lives outside {@code db/migration} and has no {@code V}
 * prefix, so Flyway's own scanner never picks it up and no service inherits a version
 * number chosen by ems-common.
 */
public abstract class OutboxSchemaMigration extends BaseJavaMigration {

    private static final String SCRIPT = "db/common/outbox.sql";

    @Override
    public void migrate(Context context) throws Exception {
        try (Statement statement = context.getConnection().createStatement()) {
            statement.execute(readScript());
        }
    }

    /**
     * Checksums the script rather than leaving it null, so that editing the shared DDL
     * after it has been applied fails validation in the service, exactly as editing an
     * applied {@code .sql} migration would.
     */
    @Override
    public Integer getChecksum() {
        CRC32 crc32 = new CRC32();
        crc32.update(readScript().getBytes(StandardCharsets.UTF_8));
        return (int) crc32.getValue();
    }

    private String readScript() {
        try (InputStream in = OutboxSchemaMigration.class.getClassLoader().getResourceAsStream(SCRIPT)) {
            if (in == null) {
                throw new IllegalStateException(SCRIPT + " is missing from the classpath; is ems-common on it?");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("Could not read " + SCRIPT, ex);
        }
    }
}
