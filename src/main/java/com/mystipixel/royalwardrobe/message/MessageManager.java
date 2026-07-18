package com.mystipixel.royalwardrobe.message;

import com.mystipixel.royalwardrobe.util.Text;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.logging.Level;

/**
 * Loads player-facing text from {@code messages.yml}. Every key has an inline fallback, so the file is
 * optional and a missing key is never an empty message. Reloadable via {@code /wardrobe reload}.
 */
public final class MessageManager {

    private final JavaPlugin plugin;
    private FileConfiguration messages;

    public MessageManager(JavaPlugin plugin) {
        this.plugin = plugin;
        File file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists()) {
            plugin.saveResource("messages.yml", false);
            migrateFromConfig(file);
        }
        reload();
    }

    public void reload() {
        this.messages = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "messages.yml"));
    }

    /**
     * Carry a server's existing wording across when messages move out of {@code config.yml}.
     *
     * <p>These used to live under {@code messages:} in the main config. Writing a fresh messages.yml over
     * the top would silently revert anyone who had reworded them, so on the first run any keys still in
     * config.yml are copied into the new file and the old section is left in place, ignored and harmless.
     */
    private void migrateFromConfig(File file) {
        ConfigurationSection old = plugin.getConfig().getConfigurationSection("messages");
        if (old == null) {
            return;
        }
        try {
            FileConfiguration fresh = YamlConfiguration.loadConfiguration(file);
            int moved = 0;
            for (String key : old.getKeys(false)) {
                String value = old.getString(key);
                if (value != null) {
                    fresh.set(key, value);
                    moved++;
                }
            }
            if (moved > 0) {
                fresh.save(file);
                plugin.getLogger().info("Moved " + moved + " message(s) from config.yml into messages.yml."
                        + " The old 'messages:' section in config.yml is no longer read and can be deleted.");
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Could not migrate messages from config.yml into"
                    + " messages.yml — the defaults will be used until you edit messages.yml.", e);
        }
    }

    public String prefix() {
        return messages.getString("prefix", "");
    }

    /** The raw message for {@code key}, or {@code fallback} when it isn't in the file. */
    public String get(String key, String fallback) {
        return messages.getString(key, fallback);
    }

    /** Send a prefixed message. Blank text is skipped, so a message can be silenced by emptying it. */
    public void send(CommandSender target, String key, String fallback) {
        String raw = get(key, fallback);
        if (raw == null || raw.isBlank()) {
            return;
        }
        target.sendMessage(Text.of(prefix() + raw));
    }

    /** As {@link #send}, with {placeholder} substitution. */
    public void send(CommandSender target, String key, String fallback, Map<String, String> placeholders) {
        String raw = get(key, fallback);
        if (raw == null || raw.isBlank()) {
            return;
        }
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            raw = raw.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        target.sendMessage(Text.of(prefix() + raw));
    }
}
