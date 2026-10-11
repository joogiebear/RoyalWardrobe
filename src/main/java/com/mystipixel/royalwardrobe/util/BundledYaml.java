package com.mystipixel.royalwardrobe.util;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;

/**
 * Reads a file from the plugin folder with the jar's copy as in-memory defaults, so a key missing
 * from an older server file shows the shipped value. The server's file is never rewritten.
 */
public final class BundledYaml {

    private BundledYaml() {
    }

    public static YamlConfiguration load(JavaPlugin plugin, String path) {
        YamlConfiguration loaded = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), path));
        try (InputStream in = plugin.getResource(path)) {
            if (in != null) {
                loaded.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Could not read the bundled " + path
                    + "; keys missing from the server's copy will be blank.", e);
        }
        return loaded;
    }
}
