package com.mystipixel.royalwardrobe.storage;

import com.mystipixel.royalwardrobe.wardrobe.ArmorSet;
import com.mystipixel.royalwardrobe.wardrobe.ItemCodec;
import com.mystipixel.royalwardrobe.wardrobe.WardrobeData;
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
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Wardrobe persistence over HikariCP (SQLite or MySQL). One row per set:
 * {@code (owner, scope, idx) -> armor, first_worn, active}. The active set's items are on the player,
 * so its row stores empty armor with {@code active=1}.
 */
public final class WardrobeStorage {

    private enum Type { SQLITE, MYSQL }

    // bound on a stored index, so one bad row can't make a load allocate millions of slots
    private static final int MAX_SLOTS = 9 * 64;

    private final JavaPlugin plugin;
    private Type type;
    private HikariDataSource dataSource;

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

            this.dataSource = new HikariDataSource(hikari);
            this.writer = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "RoyalWardrobe-writer");
                t.setDaemon(false);              // must finish its queue before the JVM exits
                return t;
            });
            createTables();
            plugin.getLogger().info("RoyalWardrobe connected to " + type + " storage.");
            return true;
        } catch (Exception e) {
            plugin.getLogger().severe("RoyalWardrobe storage init failed: " + e.getMessage());
            return false;
        }
    }

    private void loadDriver(String driverClass) {
        try {
            Class.forName(driverClass, true, getClass().getClassLoader());
        } catch (ClassNotFoundException e) {
            plugin.getLogger().log(Level.WARNING, "JDBC driver not found: " + driverClass, e);
        }
    }

    private void createTables() throws SQLException {
        String armorType = type == Type.MYSQL ? "MEDIUMTEXT" : "TEXT";
        String ddl = "CREATE TABLE IF NOT EXISTS wardrobe_sets ("
                + "owner VARCHAR(36) NOT NULL, "
                + "scope VARCHAR(64) NOT NULL, "
                + "idx INT NOT NULL, "
                + "armor " + armorType + " NOT NULL, "
                + "first_worn BIGINT NOT NULL DEFAULT 0, "
                + "active INT NOT NULL DEFAULT 0, "
                + "PRIMARY KEY (owner, scope, idx))";
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate(ddl);
            // v1 tables had only the armor column
            addColumnIfMissing(c, "wardrobe_sets", "first_worn", "BIGINT NOT NULL DEFAULT 0");
            addColumnIfMissing(c, "wardrobe_sets", "active", "INT NOT NULL DEFAULT 0");
            addColumnIfMissing(c, "wardrobe_sets", "set_name", "VARCHAR(48)");
        }
    }

    private void addColumnIfMissing(Connection c, String table, String column, String definition) {
        try {
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
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Could not ensure column " + table + "." + column, e);
        }
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
        try (Connection c = dataSource.getConnection(); PreparedStatement st = c.prepareStatement(sql)) {
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
        writer.execute(write);
    }

    /** One slot's full state, as {@link #saveAll} writes it. */
    public record SlotWrite(int idx, ArmorSet set, long firstWorn, boolean active, String name) {

        // snapshots item stacks, so call it on the main thread
        public static SlotWrite of(WardrobeData data, int index) {
            return new SlotWrite(index, data.set(index).snapshot(), data.firstWorn(index),
                    data.activeIndex() == index, data.name(index));
        }
    }

    /**
     * Write several slots in one transaction, so an equip's two slots commit together or not at all.
     * Returns whether it committed; callers must not treat a failed write as success.
     */
    public boolean saveAll(UUID owner, String scope, java.util.List<SlotWrite> writes) {
        try (Connection c = dataSource.getConnection()) {
            boolean autoCommit = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                for (SlotWrite write : writes) {
                    write(c, owner, scope, write);
                }
                c.commit();
                return true;
            } catch (Exception e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(autoCommit);
            }
        } catch (Exception e) {
            // SEVERE, not WARNING: a lost wardrobe write means real gear is unaccounted for.
            plugin.getLogger().log(Level.SEVERE, "Failed to save wardrobe slots for " + owner + "/" + scope, e);
            return false;
        }
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
