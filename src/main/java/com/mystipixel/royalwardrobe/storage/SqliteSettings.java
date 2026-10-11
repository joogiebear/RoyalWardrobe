package com.mystipixel.royalwardrobe.storage;

import java.util.Properties;

// Passed as driver properties, not connectionInitSql: sqlite-jdbc runs only the first statement of a
// multi-statement init string. No foreign_keys: wardrobe_sets declares no foreign key yet.
final class SqliteSettings {

    // spent on the writer thread, not the server thread
    static final int BUSY_TIMEOUT_MS = 5000;

    // one connection: all writes already go through the single writer thread, so this avoids SQLITE_BUSY
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
