package com.mystipixel.royalwardrobe.message;

import com.mystipixel.royalwardrobe.util.BundledYaml;
import com.mystipixel.royalwardrobe.util.Text;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Player-facing text from {@code messages.yml}. The jar's copy backs every missing key, so an older
 * server file still shows the shipped wording. Reloadable via {@code /wardrobe reload}.
 */
public final class MessageManager {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z_]+)}");

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
        this.messages = BundledYaml.load(plugin, "messages.yml");
    }

    // copies a reworded legacy 'messages:' section from config.yml into a fresh messages.yml so it isn't reverted
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
                    + " messages.yml; the defaults will be used until you edit messages.yml.", e);
        }
    }

    /** The raw text for {@code key}, or "" when neither the server file nor the jar has it. */
    public String get(String key) {
        String raw = messages.getString(key);
        return raw == null ? "" : raw;
    }

    /** {@link #get} with {@code {name}} placeholders filled in one pass, so a value is never expanded again. */
    public String format(String key, Map<String, String> placeholders) {
        return fill(get(key), placeholders);
    }

    public List<String> lines(String key) {
        return messages.getStringList(key);
    }

    /** Send with the prefix. Blank text is skipped, so a message can be silenced by emptying it. */
    public void send(CommandSender target, String key) {
        String raw = get(key);
        if (!raw.isBlank()) {
            target.sendMessage(Text.of(get("prefix") + raw));
        }
    }

    /** Send without the prefix (command output and lists), with {@code {name}} placeholders. */
    public void sendPlain(CommandSender target, String key, Map<String, String> placeholders) {
        String raw = format(key, placeholders);
        if (!raw.isBlank()) {
            target.sendMessage(Text.of(raw));
        }
    }

    static String fill(String raw, Map<String, String> placeholders) {
        if (placeholders.isEmpty()) {
            return raw;
        }
        Matcher m = PLACEHOLDER.matcher(raw);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = placeholders.get(m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(value != null ? value : m.group()));
        }
        m.appendTail(out);
        return out.toString();
    }
}
