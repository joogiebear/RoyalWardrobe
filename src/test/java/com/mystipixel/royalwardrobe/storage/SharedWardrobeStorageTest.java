package com.mystipixel.royalwardrobe.storage;

import com.mystipixel.royalwardrobe.wardrobe.ArmorSet;
import com.mystipixel.royalwardrobe.wardrobe.WardrobeData;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Two storages on one database stand in for two servers. The SQLite cases share one file; the MySQL cases
 * run when RW_TEST_MYSQL_PORT is set (RW_TEST_MYSQL_USER and RW_TEST_MYSQL_PASSWORD default to root with
 * no password; the user needs CREATE DATABASE).
 */
class SharedWardrobeStorageTest {

    private static final String SCOPE = "default";

    @TempDir
    Path dir;

    private final List<WardrobeStorage> opened = new ArrayList<>();

    @AfterEach
    void close() {
        opened.forEach(WardrobeStorage::shutdown);
    }

    @Test
    void aStaleCopyIsNotSavedOverANewerOne() {
        WardrobeStorage a = sqlite("a");
        WardrobeStorage b = sqlite("b");
        UUID owner = UUID.randomUUID();

        assertNotNull(a.load(owner, SCOPE, 3));
        assertNotNull(b.load(owner, SCOPE, 3));
        assertEquals(WardrobeStorage.SaveResult.SAVED, a.saveAll(owner, SCOPE, named(0, "from a")));
        assertEquals(WardrobeStorage.SaveResult.CONFLICT, b.saveAll(owner, SCOPE, named(0, "from b")));
        assertEquals("from a", a.load(owner, SCOPE, 3).name(0));

        // reloading picks up the newer copy, after which this server may save again
        assertEquals("from a", b.load(owner, SCOPE, 3).name(0));
        assertEquals(WardrobeStorage.SaveResult.SAVED, b.saveAll(owner, SCOPE, named(0, "from b")));
        assertEquals("from b", a.load(owner, SCOPE, 3).name(0));
    }

    @Test
    void consecutiveSavesFromTheServerHoldingTheWardrobeAllLand() {
        WardrobeStorage a = sqlite("a");
        UUID owner = UUID.randomUUID();
        a.load(owner, SCOPE, 3);
        for (int i = 0; i < 5; i++) {
            assertEquals(WardrobeStorage.SaveResult.SAVED, a.saveAll(owner, SCOPE, named(1, "set " + i)));
        }
        assertEquals("set 4", a.load(owner, SCOPE, 3).name(1));
    }

    @Test
    void aWardrobeNeverLoadedHereIsNotSaved() {
        WardrobeStorage a = sqlite("a");
        UUID owner = UUID.randomUUID();
        assertEquals(WardrobeStorage.SaveResult.CONFLICT, a.saveAll(owner, SCOPE, named(0, "blind")));
    }

    @Test
    void aReleasedPlayerMustBeLoadedAgainBeforeSaving() {
        WardrobeStorage a = sqlite("a");
        UUID owner = UUID.randomUUID();
        a.load(owner, SCOPE, 3);
        a.release(owner);
        assertEquals(WardrobeStorage.SaveResult.CONFLICT, a.saveAll(owner, SCOPE, named(0, "after quit")));
    }

    @Test
    void sqliteNeverWaitsForAnotherServer() {
        WardrobeStorage a = sqlite("a");
        UUID owner = UUID.randomUUID();
        a.load(owner, SCOPE, 3);
        assertEquals(WardrobeStorage.Claim.READY, sqlite("b").awaitHandoff(owner));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "RW_TEST_MYSQL_PORT", matches = "[0-9]+")
    void onMySqlTheNextServerWaitsUntilTheLastOneSaved() throws Exception {
        String database = freshDatabase();
        WardrobeStorage a = mysql("a", database);
        WardrobeStorage b = mysql("b", database);
        UUID owner = UUID.randomUUID();

        assertEquals(WardrobeStorage.Claim.READY, a.awaitHandoff(owner));
        a.load(owner, SCOPE, 3);
        assertEquals(WardrobeStorage.SaveResult.SAVED, a.saveAll(owner, SCOPE, named(2, "on a")));
        assertEquals(WardrobeStorage.Claim.BUSY, b.awaitHandoff(owner));

        a.release(owner);
        assertEquals(WardrobeStorage.Claim.READY, b.awaitHandoff(owner));
        assertEquals("on a", b.load(owner, SCOPE, 3).name(2));
        assertEquals(WardrobeStorage.SaveResult.SAVED, b.saveAll(owner, SCOPE, named(2, "on b")));
        // the server the player left can't write its old copy back
        assertEquals(WardrobeStorage.SaveResult.CONFLICT, a.saveAll(owner, SCOPE, named(2, "stale")));
        assertEquals(WardrobeStorage.Claim.BUSY, a.awaitHandoff(owner));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "RW_TEST_MYSQL_PORT", matches = "[0-9]+")
    void onMySqlAStaleCopyIsNotSavedOverANewerOne() throws Exception {
        String database = freshDatabase();
        WardrobeStorage a = mysql("a", database);
        WardrobeStorage b = mysql("b", database);
        UUID owner = UUID.randomUUID();

        a.load(owner, SCOPE, 3);
        b.load(owner, SCOPE, 3);
        assertEquals(WardrobeStorage.SaveResult.SAVED, b.saveAll(owner, SCOPE, named(0, "from b")));
        assertEquals(WardrobeStorage.SaveResult.CONFLICT, a.saveAll(owner, SCOPE, named(0, "from a")));
        assertEquals("from b", b.load(owner, SCOPE, 3).name(0));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "RW_TEST_MYSQL_PORT", matches = "[0-9]+")
    void onMySqlServersStartingTogetherMigrateOnce() throws Exception {
        String database = freshDatabase();
        List<Thread> threads = new ArrayList<>();
        List<WardrobeStorage> started = java.util.Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < 4; i++) {
            String id = "s" + i;
            threads.add(new Thread(() -> started.add(mysql(id, database))));
        }
        threads.forEach(Thread::start);
        for (Thread t : threads) {
            t.join();
        }
        assertEquals(4, started.size());
        try (Connection c = DriverManager.getConnection(mysqlUrl(database), user(), password());
             Statement st = c.createStatement();
             var rs = st.executeQuery("SELECT version FROM wardrobe_schema_version")) {
            assertTrue(rs.next());
            assertEquals(WardrobeStorage.SCHEMA_VERSION, rs.getInt(1));
        }
    }

    private static List<WardrobeStorage.SlotWrite> named(int idx, String name) {
        return List.of(new WardrobeStorage.SlotWrite(idx, ArmorSet.empty(), 0, false, name));
    }

    private WardrobeStorage sqlite(String serverId) {
        YamlConfiguration config = new YamlConfiguration();
        config.set("storage.type", "SQLITE");
        config.set("storage.sqlite-file", dir.resolve("wardrobe.db").toString());
        config.set("storage.server-id", serverId);
        return open(serverId, config);
    }

    private WardrobeStorage mysql(String serverId, String database) {
        YamlConfiguration config = new YamlConfiguration();
        config.set("storage.type", "MYSQL");
        config.set("storage.server-id", serverId);
        config.set("storage.mysql.host", "127.0.0.1");
        config.set("storage.mysql.port", Integer.parseInt(System.getenv("RW_TEST_MYSQL_PORT")));
        config.set("storage.mysql.database", database);
        config.set("storage.mysql.username", user());
        config.set("storage.mysql.password", password());
        config.set("storage.mysql.pool-size", 4);
        return open(serverId, config);
    }

    private WardrobeStorage open(String serverId, YamlConfiguration config) {
        try {
            Path folder = Files.createDirectories(dir.resolve(serverId));
            JavaPlugin plugin = mock(JavaPlugin.class);
            when(plugin.getConfig()).thenReturn(config);
            when(plugin.getDataFolder()).thenReturn(folder.toFile());
            when(plugin.getLogger()).thenReturn(Logger.getLogger("wardrobe-test-" + serverId));
            WardrobeStorage storage = new WardrobeStorage(plugin);
            assertTrue(storage.connect(), "storage " + serverId + " connects");
            opened.add(storage);
            return storage;
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static String freshDatabase() throws Exception {
        String name = "sdb_rwtest_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try (Connection c = DriverManager.getConnection(mysqlUrl(""), user(), password());
             Statement st = c.createStatement()) {
            st.executeUpdate("CREATE DATABASE " + name);
        }
        return name;
    }

    private static String mysqlUrl(String database) {
        return "jdbc:mysql://127.0.0.1:" + System.getenv("RW_TEST_MYSQL_PORT") + "/" + database
                + "?useSSL=false&allowPublicKeyRetrieval=true";
    }

    private static String user() {
        return System.getenv().getOrDefault("RW_TEST_MYSQL_USER", "root");
    }

    private static String password() {
        return System.getenv().getOrDefault("RW_TEST_MYSQL_PASSWORD", "");
    }
}
