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
import java.util.logging.Level;

/**
 * Persistence for wardrobe sets over one HikariCP data source, dual-dialect (SQLite / MySQL) like the
 * rest of the suite. One row per set: {@code (owner, scope, idx) -> armor, first_worn, active}. The
 * <em>active</em> set's items live on the player, so its row stores empty armor with {@code active=1}
 * and only its {@code first_worn} date — which is what keeps everything dupe-safe.
 */
public final class WardrobeStorage {

    private enum Type { SQLITE, MYSQL }

    private final JavaPlugin plugin;
    private Type type;
    private HikariDataSource dataSource;

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
                hikari.setMaximumPoolSize(1);
                hikari.setConnectionInitSql("PRAGMA journal_mode=WAL; PRAGMA busy_timeout=5000;");
            }

            this.dataSource = new HikariDataSource(hikari);
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
            // Migrate a pre-existing v1 table (armor only) by adding the columns if they're missing.
            addColumnIfMissing(c, "wardrobe_sets", "first_worn", "BIGINT NOT NULL DEFAULT 0");
            addColumnIfMissing(c, "wardrobe_sets", "active", "INT NOT NULL DEFAULT 0");
        }
    }

    private void addColumnIfMissing(Connection c, String table, String column, String definition) {
        try {
            DatabaseMetaData meta = c.getMetaData();
            try (ResultSet rs = meta.getColumns(c.getCatalog(), null, table, column)) {
                if (rs.next()) {
                    return; // already present
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

    public WardrobeData load(UUID owner, String scope, int capacity) {
        ArmorSet[] sets = new ArmorSet[capacity];
        long[] firstWorn = new long[capacity];
        for (int i = 0; i < capacity; i++) {
            sets[i] = ArmorSet.empty();
        }
        int activeIndex = -1;
        String sql = "SELECT idx, armor, first_worn, active FROM wardrobe_sets WHERE owner = ? AND scope = ?";
        try (Connection c = dataSource.getConnection(); PreparedStatement st = c.prepareStatement(sql)) {
            st.setString(1, owner.toString());
            st.setString(2, scope);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    int idx = rs.getInt("idx");
                    if (idx < 0 || idx >= capacity) {
                        continue;
                    }
                    firstWorn[idx] = rs.getLong("first_worn");
                    if (rs.getInt("active") == 1) {
                        activeIndex = idx;            // items are on the player, not in storage
                    } else {
                        sets[idx] = new ArmorSet(ItemCodec.decode(rs.getString("armor")));
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to load wardrobe for " + owner + "/" + scope, e);
        }
        return new WardrobeData(sets, firstWorn, activeIndex);
    }

    /** Upsert one slot. An empty, never-worn, inactive slot is deleted instead. */
    public void save(UUID owner, String scope, int idx, ArmorSet set, long firstWorn, boolean active) {
        boolean empty = set == null || set.isEmpty();
        if (!active && empty && firstWorn <= 0) {
            delete(owner, scope, idx);
            return;
        }
        String armor = active || empty ? "" : ItemCodec.encode(set.pieces());
        String sql = type == Type.MYSQL
                ? "INSERT INTO wardrobe_sets (owner, scope, idx, armor, first_worn, active) VALUES (?,?,?,?,?,?) "
                + "ON DUPLICATE KEY UPDATE armor=VALUES(armor), first_worn=VALUES(first_worn), active=VALUES(active)"
                : "INSERT INTO wardrobe_sets (owner, scope, idx, armor, first_worn, active) VALUES (?,?,?,?,?,?) "
                + "ON CONFLICT(owner, scope, idx) DO UPDATE SET armor=excluded.armor, "
                + "first_worn=excluded.first_worn, active=excluded.active";
        try (Connection c = dataSource.getConnection(); PreparedStatement st = c.prepareStatement(sql)) {
            st.setString(1, owner.toString());
            st.setString(2, scope);
            st.setInt(3, idx);
            st.setString(4, armor);
            st.setLong(5, Math.max(0, firstWorn));
            st.setInt(6, active ? 1 : 0);
            st.executeUpdate();
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to save wardrobe slot " + idx + " for " + owner, e);
        }
    }

    private void delete(UUID owner, String scope, int idx) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement(
                     "DELETE FROM wardrobe_sets WHERE owner = ? AND scope = ? AND idx = ?")) {
            st.setString(1, owner.toString());
            st.setString(2, scope);
            st.setInt(3, idx);
            st.executeUpdate();
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to delete wardrobe slot " + idx + " for " + owner, e);
        }
    }

    public void shutdown() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }
}
