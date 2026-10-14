package com.mystipixel.royalwardrobe.storage;

import com.mystipixel.royalwardrobe.wardrobe.ArmorSet;
import com.mystipixel.royalwardrobe.wardrobe.ItemCodec;
import com.mystipixel.royalwardrobe.wardrobe.WardrobeData;
import com.mystipixel.royalcommon.storage.PlayerHandoff;
import com.mystipixel.royalcommon.storage.SchemaVersion;
import com.mystipixel.royalcommon.storage.ServerId;
import com.mystipixel.royalcommon.storage.TxRunner;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Wardrobe persistence over HikariCP (SQLite or MySQL). One row per set:
 * {@code (owner, scope, idx) -> armor, first_worn, active}. The active set's items are on the player,
 * so its row stores empty armor with {@code active=1}.
 *
 * <p>Servers sharing a MySQL database: each wardrobe has a version in {@code wardrobe_versions} that
 * every save raises, and a save only commits when the version is the one this server last loaded or
 * wrote. A player's wardrobe is loaded only after the server they came from has written everything
 * ({@link PlayerHandoff}).
 */
public final class WardrobeStorage {

    private enum Type { SQLITE, MYSQL }

    /** How a save ended. CONFLICT: another server changed this wardrobe since it was loaded here; nothing was written. */
    public enum SaveResult { SAVED, CONFLICT, FAILED }

    /** Whether a player's wardrobe may be loaded here. BUSY: the server they came from is still saving it. */
    public enum Claim { READY, BUSY, FAILED }

    static final int SCHEMA_VERSION = 1;

    // bound on a stored index, so one bad row can't make a load allocate millions of slots
    private static final int MAX_SLOTS = 9 * 64;
    private static final Duration HANDOFF_WAIT = Duration.ofSeconds(5);
    private static final Duration HANDOFF_STALE = Duration.ofSeconds(60);

    private record Key(UUID owner, String scope) {
    }

    private final JavaPlugin plugin;
    private Type type;
    private HikariDataSource dataSource;
    private TxRunner db;
    private PlayerHandoff handoff;
    // writer thread only: the version this server last loaded or wrote for each wardrobe
    private final Map<Key, Long> known = new HashMap<>();
    // writer thread only: wardrobes with a failed write not yet landed, so the table is behind the player.
    // Cleared by settled(), never by a later save of other slots.
    private final java.util.Set<Key> unsaved = new java.util.HashSet<>();
    // writer thread only: players who left but whose markSaved failed; retried by tick()
    private final java.util.Set<UUID> releasing = new java.util.HashSet<>();

    // one thread, so saves for the same slot commit in order; shutdown() drains it before closing the pool
    private ExecutorService writer;

    public WardrobeStorage(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean connect() {
        try {
            ConfigurationSection storage = plugin.getConfig().getConfigurationSection("storage");
            if (storage == null) {
                storage = plugin.getConfig().createSection("storage");
            }
            this.type = "MYSQL".equals(storage.getString("type", "SQLITE").toUpperCase(Locale.ROOT))
                    ? Type.MYSQL : Type.SQLITE;

            HikariConfig hikari = new HikariConfig();
            hikari.setPoolName("RoyalWardrobe");

            if (type == Type.MYSQL) {
                ConfigurationSection my = storage.getConfigurationSection("mysql");
                if (my == null) {
                    my = storage.createSection("mysql");
                }
                loadDriver("com.mysql.cj.jdbc.Driver");
                hikari.setJdbcUrl("jdbc:mysql://" + my.getString("host", "localhost") + ":"
                        + my.getInt("port", 3306) + "/" + my.getString("database", "royalwardrobe")
                        + "?" + my.getString("properties", "useSSL=false"));
                hikari.setDriverClassName("com.mysql.cj.jdbc.Driver");
                hikari.setUsername(my.getString("username", "root"));
                hikari.setPassword(my.getString("password", ""));
                hikari.setMaximumPoolSize(Math.max(1, my.getInt("pool-size", 10)));
            } else {
                File dataFolder = plugin.getDataFolder();
                if (!dataFolder.exists() && !dataFolder.mkdirs()) {
                    plugin.getLogger().severe("Could not create data folder: " + dataFolder.getAbsolutePath());
                    return false;
                }
                File dbFile = new File(dataFolder, storage.getString("sqlite-file", "wardrobe.db"));
                loadDriver("org.sqlite.JDBC");
                hikari.setJdbcUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());
                hikari.setDriverClassName("org.sqlite.JDBC");
                hikari.setMaximumPoolSize(SqliteSettings.POOL_SIZE);
                hikari.setDataSourceProperties(SqliteSettings.properties());
            }

            String serverId = ServerId.resolve(storage.getString("server-id", ""), plugin.getDataFolder().toPath());
            this.dataSource = new HikariDataSource(hikari);
            this.writer = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "RoyalWardrobe-writer");
                t.setDaemon(false);              // must finish its queue before the JVM exits
                return t;
            });
            this.db = new TxRunner(dataSource);
            migrate();
            this.handoff = new PlayerHandoff(db, "wardrobe", serverId, HANDOFF_STALE);
            handoff.createTable();
            plugin.getLogger().info("RoyalWardrobe connected to " + type + " storage"
                    + (type == Type.MYSQL ? " as server " + serverId + "." : "."));
            return true;
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "RoyalWardrobe storage init failed: " + e.getMessage(), e);
            if (dataSource != null) {
                dataSource.close();
            }
            return false;
        }
    }

    // servers sharing a MySQL database that start together take turns here; the second finds nothing to do
    private void migrate() throws SQLException {
        int before = new SchemaVersion(db, "wardrobe").ensure(SCHEMA_VERSION, (c, dialect, from, to) -> {
            if (from < 1) {
                createTables(c);
            }
        });
        if (before < SCHEMA_VERSION) {
            plugin.getLogger().info("Database schema migrated from version " + before + " to " + SCHEMA_VERSION + ".");
        }
    }

    private void loadDriver(String driverClass) {
        try {
            Class.forName(driverClass, true, getClass().getClassLoader());
        } catch (ClassNotFoundException e) {
            plugin.getLogger().log(Level.WARNING, "JDBC driver not found: " + driverClass, e);
        }
    }

    private void createTables(Connection c) throws SQLException {
        String armorType = type == Type.MYSQL ? "MEDIUMTEXT" : "TEXT";
        String ddl = "CREATE TABLE IF NOT EXISTS wardrobe_sets ("
                + "owner VARCHAR(36) NOT NULL, "
                + "scope VARCHAR(64) NOT NULL, "
                + "idx INT NOT NULL, "
                + "armor " + armorType + " NOT NULL, "
                + "first_worn BIGINT NOT NULL DEFAULT 0, "
                + "active INT NOT NULL DEFAULT 0, "
                + "PRIMARY KEY (owner, scope, idx))";
        try (Statement st = c.createStatement()) {
            st.executeUpdate(ddl);
            // v1 tables had only the armor column
            addColumnIfMissing(c, "wardrobe_sets", "first_worn", "BIGINT NOT NULL DEFAULT 0");
            addColumnIfMissing(c, "wardrobe_sets", "active", "INT NOT NULL DEFAULT 0");
            addColumnIfMissing(c, "wardrobe_sets", "set_name", "VARCHAR(48)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS wardrobe_versions ("
                    + "owner VARCHAR(36) NOT NULL, "
                    + "scope VARCHAR(64) NOT NULL, "
                    + "version BIGINT NOT NULL DEFAULT 0, "
                    + "PRIMARY KEY (owner, scope))");
        }
    }

    // a failure stops startup: a wardrobe saved without its columns would lose set names or the active set
    private void addColumnIfMissing(Connection c, String table, String column, String definition) throws SQLException {
        DatabaseMetaData meta = c.getMetaData();
        try (ResultSet rs = meta.getColumns(c.getCatalog(), null, table, column)) {
            if (rs.next()) {
                return;
            }
        }
        try (Statement st = c.createStatement()) {
            st.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
            plugin.getLogger().info("Added missing column " + table + "." + column + ".");
        }
    }

    /**
     * Waits until the server the player came from has saved their wardrobe. Blocks for up to a few
     * seconds, so call it off the main thread and off the writer thread; {@link #load} then records
     * that this server has the player.
     */
    public Claim awaitHandoff(UUID owner) {
        try {
            PlayerHandoff.Result result = handoff.awaitSaved(owner, HANDOFF_WAIT);
            if (!result.ready()) {
                plugin.getLogger().warning("Wardrobe of " + owner + " is still open on server " + result.holder()
                        + "; not loading it here yet.");
                return Claim.BUSY;
            }
            return Claim.READY;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not check where the wardrobe of " + owner + " was last used", e);
            return Claim.FAILED;
        }
    }

    /**
     * Records that every write for this player has committed, so another server may load their wardrobe.
     * Call on the writer thread, after their last queued write.
     */
    public void release(UUID owner) {
        if (unsaved.stream().anyMatch(key -> key.owner().equals(owner))) {
            return;                              // released once the failed write lands or is dropped
        }
        known.keySet().removeIf(key -> key.owner().equals(owner));
        releasing.add(owner);
        markSaved(owner);
    }

    private void markSaved(UUID owner) {
        try {
            handoff.markSaved(owner);
            releasing.remove(owner);
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Could not mark the wardrobe of " + owner + " as saved;"
                    + " retrying every " + heartbeatInterval().toSeconds() + "s", e);
        }
    }

    /** A wardrobe's failed write has landed or been dropped; its owner may be released again. Writer thread. */
    public void settled(UUID owner, String scope) {
        unsaved.remove(new Key(owner, scope));
    }

    /**
     * Retries releases that failed, then keeps this server's claims on its players alive. Call on the
     * writer thread every {@link #heartbeatInterval()}.
     */
    public void tick() {
        if (dataSource == null || dataSource.isClosed()) {
            return;
        }
        for (UUID owner : java.util.List.copyOf(releasing)) {
            markSaved(owner);
        }
        try {
            handoff.heartbeat();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Could not refresh wardrobe claims", e);
        }
    }

    public static Duration heartbeatInterval() {
        return HANDOFF_STALE.dividedBy(4);
    }

    private record Row(int idx, String armor, long firstWorn, boolean active, String name) {
    }

    /**
     * Load a player's wardrobe, or {@code null} if it could not be read. Never return an empty wardrobe
     * on failure: storing into a "free" slot would overwrite the real set. A row that fails to decode is
     * flagged corrupt instead. The result can exceed {@code capacity} if the menu shrank; those slots
     * show locked so their gear can still be taken out.
     */
    public WardrobeData load(UUID owner, String scope, int capacity) {
        java.util.List<Row> rows = new java.util.ArrayList<>();
        String sql = "SELECT idx, armor, first_worn, active, set_name FROM wardrobe_sets WHERE owner = ? AND scope = ?";
        try {
            // before the read: a quit on this server queued ahead of this load has released the player by now
            handoff.markActive(owner);
            try (Connection c = dataSource.getConnection()) {
                // version first: a write landing between the two reads leaves it behind, so the next save
                // is refused rather than written over rows this server never saw
                long version = readVersion(c, owner, scope, "");
                try (PreparedStatement st = c.prepareStatement(sql)) {
                    st.setString(1, owner.toString());
                    st.setString(2, scope);
                    try (ResultSet rs = st.executeQuery()) {
                        while (rs.next()) {
                            int idx = rs.getInt("idx");
                            if (idx >= 0 && idx < MAX_SLOTS) {
                                rows.add(new Row(idx, rs.getString("armor"), rs.getLong("first_worn"),
                                        rs.getInt("active") == 1, rs.getString("set_name")));
                            }
                        }
                    }
                }
                known.put(new Key(owner, scope), version);
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load wardrobe for " + owner + "/" + scope, e);
            return null;
        }

        int size = capacity;
        for (Row row : rows) {
            size = Math.max(size, row.idx() + 1);
        }
        ArmorSet[] sets = new ArmorSet[size];
        long[] firstWorn = new long[size];
        String[] names = new String[size];
        boolean[] corrupt = new boolean[size];
        for (int i = 0; i < size; i++) {
            sets[i] = ArmorSet.empty();
        }
        int activeIndex = -1;
        for (Row row : rows) {
            int idx = row.idx();
            firstWorn[idx] = row.firstWorn();
            names[idx] = row.name();
            if (row.active()) {
                activeIndex = idx;                    // items are on the player, not in storage
                continue;
            }
            try {
                sets[idx] = new ArmorSet(ItemCodec.decode(row.armor()));
            } catch (RuntimeException badRow) {
                corrupt[idx] = true;
                plugin.getLogger().log(Level.SEVERE, "Wardrobe slot " + idx + " for " + owner + "/" + scope
                        + " could not be decoded; it is locked and left untouched in the database.", badRow);
            }
        }
        return new WardrobeData(sets, firstWorn, names, corrupt, activeIndex);
    }

    /** Queue a write on the single writer thread. Ordering per slot is guaranteed; drained on shutdown. */
    public void submit(Runnable write) {
        if (writer == null || writer.isShutdown()) {
            write.run();                         // fall back to the caller rather than drop the write
            return;
        }
        try {
            writer.execute(write);
        } catch (java.util.concurrent.RejectedExecutionException shutDown) {
            write.run();                         // shut down between the check and here
        }
    }

    /** One slot's full state, as {@link #saveAll} writes it. */
    public record SlotWrite(int idx, ArmorSet set, long firstWorn, boolean active, String name) {

        // snapshots item stacks, so call it on the main thread
        public static SlotWrite of(WardrobeData data, int index) {
            return new SlotWrite(index, data.set(index).snapshot(), data.firstWorn(index),
                    data.activeIndex() == index, data.name(index));
        }
    }

    private static long readVersion(Connection c, UUID owner, String scope, String lockSuffix) throws SQLException {
        try (PreparedStatement st = c.prepareStatement(
                "SELECT version FROM wardrobe_versions WHERE owner = ? AND scope = ?" + lockSuffix)) {
            st.setString(1, owner.toString());
            st.setString(2, scope);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    /**
     * Write several slots in one transaction, so an equip's two slots commit together or not at all.
     * Nothing is written when another server changed the wardrobe since this one loaded it. Call on the
     * writer thread; callers must not treat anything but SAVED as success.
     */
    public SaveResult saveAll(UUID owner, String scope, java.util.List<SlotWrite> writes) {
        Key key = new Key(owner, scope);
        Long expected = known.get(key);
        if (expected == null) {
            plugin.getLogger().severe("Wardrobe " + owner + "/" + scope + " was saved without being loaded on this"
                    + " server; the write was refused: " + describe(writes));
            return SaveResult.CONFLICT;
        }
        try {
            long saved = db.inTransaction(tx -> {
                Connection c = tx.connection();
                try (PreparedStatement st = c.prepareStatement(tx.dialect().insertIgnore()
                        + " INTO wardrobe_versions (owner, scope, version) VALUES (?, ?, 0)")) {
                    st.setString(1, owner.toString());
                    st.setString(2, scope);
                    st.executeUpdate();
                }
                long current = readVersion(c, owner, scope, tx.dialect().forUpdate());
                if (current != expected) {
                    return -1L;
                }
                for (SlotWrite write : writes) {
                    write(c, owner, scope, write);
                }
                try (PreparedStatement st = c.prepareStatement(
                        "UPDATE wardrobe_versions SET version = ? WHERE owner = ? AND scope = ?")) {
                    st.setLong(1, current + 1);
                    st.setString(2, owner.toString());
                    st.setString(3, scope);
                    st.executeUpdate();
                }
                return current + 1;
            });
            if (saved < 0) {
                // SEVERE with the refused contents, so an admin can put the gear back by hand
                plugin.getLogger().severe("Wardrobe " + owner + "/" + scope + " was changed on another server since"
                        + " it was loaded here; these slots were not saved over it: " + describe(writes));
                return SaveResult.CONFLICT;
            }
            known.put(key, saved);
            return SaveResult.SAVED;
        } catch (Exception e) {
            // SEVERE, not WARNING: a lost wardrobe write means real gear is unaccounted for.
            plugin.getLogger().log(Level.SEVERE, "Failed to save wardrobe slots for " + owner + "/" + scope, e);
            unsaved.add(key);
            return SaveResult.FAILED;
        }
    }

    private static String describe(java.util.List<SlotWrite> writes) {
        StringBuilder out = new StringBuilder();
        for (SlotWrite w : writes) {
            boolean empty = w.set() == null || w.set().isEmpty();
            out.append("[slot ").append(w.idx() + 1)
                    .append(w.active() ? " worn" : "")
                    .append(empty ? " empty" : " armor=" + ItemCodec.encode(w.set().pieces()))
                    .append(']');
        }
        return out.toString();
    }

    // an empty, never-worn, inactive, unnamed slot is deleted; a named empty slot keeps its row
    private void write(Connection c, UUID owner, String scope, SlotWrite w) throws SQLException {
        boolean empty = w.set() == null || w.set().isEmpty();
        if (!w.active() && empty && w.firstWorn() <= 0 && (w.name() == null || w.name().isBlank())) {
            try (PreparedStatement st = c.prepareStatement(
                    "DELETE FROM wardrobe_sets WHERE owner = ? AND scope = ? AND idx = ?")) {
                st.setString(1, owner.toString());
                st.setString(2, scope);
                st.setInt(3, w.idx());
                st.executeUpdate();
            }
            return;
        }
        String armor = w.active() || empty ? "" : ItemCodec.encode(w.set().pieces());
        String sql = type == Type.MYSQL
                ? "INSERT INTO wardrobe_sets (owner, scope, idx, armor, first_worn, active, set_name) VALUES (?,?,?,?,?,?,?) "
                + "ON DUPLICATE KEY UPDATE armor=VALUES(armor), first_worn=VALUES(first_worn), "
                + "active=VALUES(active), set_name=VALUES(set_name)"
                : "INSERT INTO wardrobe_sets (owner, scope, idx, armor, first_worn, active, set_name) VALUES (?,?,?,?,?,?,?) "
                + "ON CONFLICT(owner, scope, idx) DO UPDATE SET armor=excluded.armor, "
                + "first_worn=excluded.first_worn, active=excluded.active, set_name=excluded.set_name";
        try (PreparedStatement st = c.prepareStatement(sql)) {
            st.setString(1, owner.toString());
            st.setString(2, scope);
            st.setInt(3, w.idx());
            st.setString(4, armor);
            st.setLong(5, Math.max(0, w.firstWorn()));
            st.setInt(6, w.active() ? 1 : 0);
            st.setString(7, w.name() == null || w.name().isBlank() ? null : w.name());
            st.executeUpdate();
        }
    }

    /** Drain queued writes BEFORE closing the pool, so a shutdown never discards a committed action. */
    public void shutdown() {
        if (writer != null) {
            writer.shutdown();
            try {
                if (!writer.awaitTermination(10, TimeUnit.SECONDS)) {
                    plugin.getLogger().warning("Wardrobe writes still pending after 10s, forcing shutdown.");
                    writer.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }
}
