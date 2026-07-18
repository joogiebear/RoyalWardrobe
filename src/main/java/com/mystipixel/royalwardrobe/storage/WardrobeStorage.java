package com.mystipixel.royalwardrobe.storage;

import com.mystipixel.royalwardrobe.wardrobe.ArmorSet;
import com.mystipixel.royalwardrobe.wardrobe.ItemCodec;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Persistence for wardrobe sets over one HikariCP data source, dual-dialect (SQLite default / MySQL for
 * a network) exactly like the rest of the Royal suite. The JDBC driver + pool arrive via Paper's library
 * loader (plugin.yml {@code libraries}); nothing is shaded. Each row is one set: {@code (owner, scope,
 * idx) -> armor} where armor is the Base64 of four ItemStacks. All methods are blocking JDBC — callers
 * run them off the main thread.
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
                + "PRIMARY KEY (owner, scope, idx))";
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate(ddl);
        }
    }

    /** Load a player's sets for a scope into a fixed-capacity array (empty slots stay {@link ArmorSet#empty}). */
    public ArmorSet[] load(UUID owner, String scope, int capacity) {
        ArmorSet[] sets = new ArmorSet[capacity];
        for (int i = 0; i < capacity; i++) {
            sets[i] = ArmorSet.empty();
        }
        String sql = "SELECT idx, armor FROM wardrobe_sets WHERE owner = ? AND scope = ?";
        try (Connection c = dataSource.getConnection(); PreparedStatement st = c.prepareStatement(sql)) {
            st.setString(1, owner.toString());
            st.setString(2, scope);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    int idx = rs.getInt("idx");
                    if (idx >= 0 && idx < capacity) {
                        sets[idx] = new ArmorSet(ItemCodec.decode(rs.getString("armor")));
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to load wardrobe for " + owner + "/" + scope, e);
        }
        return sets;
    }

    /** Persist one slot: upsert when the set has pieces, delete the row when it's empty. */
    public void saveSet(UUID owner, String scope, int idx, ArmorSet set) {
        if (set == null || set.isEmpty()) {
            deleteSet(owner, scope, idx);
            return;
        }
        String sql = type == Type.MYSQL
                ? "INSERT INTO wardrobe_sets (owner, scope, idx, armor) VALUES (?,?,?,?) "
                + "ON DUPLICATE KEY UPDATE armor=VALUES(armor)"
                : "INSERT INTO wardrobe_sets (owner, scope, idx, armor) VALUES (?,?,?,?) "
                + "ON CONFLICT(owner, scope, idx) DO UPDATE SET armor=excluded.armor";
        try (Connection c = dataSource.getConnection(); PreparedStatement st = c.prepareStatement(sql)) {
            st.setString(1, owner.toString());
            st.setString(2, scope);
            st.setInt(3, idx);
            st.setString(4, ItemCodec.encode(set.pieces()));
            st.executeUpdate();
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to save wardrobe slot " + idx + " for " + owner, e);
        }
    }

    private void deleteSet(UUID owner, String scope, int idx) {
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
