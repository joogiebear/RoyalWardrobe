package com.mystipixel.royalwardrobe.storage;

import java.util.Properties;

/**
 * Connection settings for the SQLite store, handed to the driver as connection properties so it
 * applies every one of them to every connection it opens.
 *
 * <p>They used to be a single {@code connectionInitSql} string of two {@code PRAGMA} statements.
 * HikariCP runs that string through {@code Statement#execute}, which sqlite-jdbc prepares only the
 * first statement of — so journal mode became WAL and {@code busy_timeout} silently stayed at the
 * driver's 3000 ms. Nothing ever read the pragmas back, which is why it went unnoticed;
 * {@code SqliteSettingsTest} now does.
 *
 * <p>This deliberately does not set {@code foreign_keys}: {@code wardrobe_sets} declares no foreign
 * key, so there is nothing for it to enforce. Add it here together with the constraint, not before.
 */
final class SqliteSettings {

    /**
     * Milliseconds a connection waits for another's write lock before giving up. Wardrobe saves run on
     * a dedicated writer thread, so this is the budget that thread gets rather than the server thread's.
     */
    static final int BUSY_TIMEOUT_MS = 5000;

    /**
     * Connections in the pool. SQLite takes one writer at a time and all writes are already funnelled
     * through the single {@code RoyalWardrobe-writer} thread, so one connection matches that and avoids
     * SQLITE_BUSY outright.
     */
    static final int POOL_SIZE = 1;

    private SqliteSettings() {
    }

    static Properties properties() {
        Properties props = new Properties();
        props.setProperty("journal_mode", "WAL");
        props.setProperty("busy_timeout", String.valueOf(BUSY_TIMEOUT_MS));
        return props;
    }
}
