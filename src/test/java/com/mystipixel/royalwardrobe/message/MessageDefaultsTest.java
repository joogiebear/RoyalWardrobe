package com.mystipixel.royalwardrobe.message;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// Every key the code reads must ship in the bundled files, and the shipped text must be what the code printed before.
class MessageDefaultsTest {

    private static final Path SOURCES = Path.of("src", "main", "java");
    // messages.get("key") / messages().send(player, "key") / label("key"); the key is the first string literal
    private static final Pattern MESSAGE_CALL = Pattern.compile(
            "(?:\\bmessages(?:\\(\\))?\\s*\\.\\s*(?:get|format|lines|send|sendPlain)|\\blabel|\\bmessage)"
                    + "\\s*\\(\\s*(?:[\\w.()]+\\s*,\\s*)?(\"[^\"]*\"|\\w++(?!\\s+\\w))");
    // the outcome switch in WardrobeMenu.apply picks its key into a variable
    private static final Pattern KEY_ASSIGNMENT = Pattern.compile("\\bkey = \"([a-z.\\-]+)\"");
    private static final Pattern GUI_READ = Pattern.compile(
            "(?:\\bgui\\.getString(?:List)?|\\bitem)\\(\"([a-z.\\-]+)\"(?=[,)])");

    // the Java DEFAULT_MESSAGES map this refactor removed: what players saw before
    private static final Map<String, String> PREVIOUS_MESSAGES = Map.ofEntries(
            Map.entry("equipped", "&aEquipped that setup."),
            Map.entry("stored", "&aStored your current setup."),
            Map.entry("unequipped", "&aUnequipped your setup."),
            Map.entry("no-armor", "&cYou aren't wearing any armor to store."),
            Map.entry("already-active", "&cYou're already wearing a saved setup — unequip it first."),
            Map.entry("slot-locked", "&cThat wardrobe slot is locked. Unlock more with a rank or perk."),
            Map.entry("inventory-full", "&cMake room in your inventory first."),
            Map.entry("save-failed", "&cYour wardrobe could not be saved — tell an admin before changing more sets."),
            Map.entry("save-pending", "&cYour last wardrobe change isn't saved yet. The wardrobe stays locked until it is."),
            Map.entry("save-recovered", "&aYour wardrobe has been saved and can be used again."),
            Map.entry("renamed", "&aSetup renamed."),
            Map.entry("nothing-to-rename", "&cStore a setup there first, then name it."),
            Map.entry("no-such-slot", "&cNo wardrobe slot with that number."),
            Map.entry("already-wearing", "&eYou're already wearing that setup."),
            Map.entry("setup-empty", "&cThat setup is empty."),
            Map.entry("no-profile", "&cJoin or create a profile before using your wardrobe."),
            Map.entry("load-failed", "&cYour wardrobe couldn't be loaded right now. Try again shortly."),
            Map.entry("slot-corrupt", "&cThat wardrobe slot is damaged and can't be used — tell an admin."));

    @TempDir
    Path dataFolder;

    @Test
    void everyMessageKeyReadInCodeIsShipped() throws IOException {
        YamlConfiguration shipped = load("/messages.yml");
        List<String> problems = new ArrayList<>();
        int found = 0;
        for (Path file : sources()) {
            if (file.endsWith("MessageManager.java")) {
                continue;
            }
            String source = Files.readString(file);
            Matcher m = MESSAGE_CALL.matcher(source);
            while (m.find()) {
                String where = file.getFileName() + ":" + lineOf(source, m.start());
                String arg = m.group(1);
                if (!arg.startsWith("\"")) {
                    // only the two pass-through helpers may take a key from a variable
                    if (!arg.equals("key")) {
                        problems.add(where + ": message key must be a string literal, not " + arg);
                    }
                    continue;
                }
                found++;
                String key = arg.substring(1, arg.length() - 1);
                if (!shipped.contains(key)) {
                    problems.add(where + ": " + key + " is not in messages.yml");
                }
            }
            Matcher k = KEY_ASSIGNMENT.matcher(source);
            while (k.find()) {
                found++;
                if (!shipped.contains(k.group(1))) {
                    problems.add(file.getFileName() + ":" + lineOf(source, k.start()) + ": " + k.group(1)
                            + " is not in messages.yml");
                }
            }
        }
        assertTrue(found > 30, "found only " + found + " message reads; is the scan still matching?");
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    void everyMenuKeyReadInCodeIsShipped() throws IOException {
        YamlConfiguration shipped = load("/gui/wardrobe.yml");
        String source = Files.readString(SOURCES.resolve("com/mystipixel/royalwardrobe/gui/WardrobeMenu.java"));
        List<String> problems = new ArrayList<>();
        int found = 0;
        Matcher m = GUI_READ.matcher(source);
        while (m.find()) {
            found++;
            if (!shipped.contains(m.group(1))) {
                problems.add("line " + lineOf(source, m.start()) + ": " + m.group(1) + " is not in gui/wardrobe.yml");
            }
        }
        assertTrue(found >= 15, "found only " + found + " menu reads; is the scan still matching?");
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    void noTextIsLeftInJava() throws IOException {
        // a colour code inside a string literal is player-facing text that belongs in a config file
        Pattern colouredLiteral = Pattern.compile("\"[^\"\\n]*&[0-9a-fk-or][^\"\\n]*\"");
        List<String> problems = new ArrayList<>();
        for (Path file : sources()) {
            String source = Files.readString(file);
            Matcher m = colouredLiteral.matcher(source);
            while (m.find()) {
                String line = source.substring(source.lastIndexOf('\n', m.start()) + 1,
                        source.indexOf('\n', m.start())).strip();
                if (!line.startsWith("//") && !line.startsWith("*")) {
                    problems.add(file.getFileName() + ":" + lineOf(source, m.start()) + ": " + m.group());
                }
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    void shippedMessagesMatchWhatTheCodeSentBefore() throws IOException {
        YamlConfiguration shipped = load("/messages.yml");
        PREVIOUS_MESSAGES.forEach((key, text) -> assertEquals(text, shipped.getString(key), key));
        assertEquals(List.of("&fName this setup", "&7Leave it blank to cancel."), shipped.getStringList("input.rename"));
        assertEquals("&aDone", shipped.getString("input.confirm"));
        assertEquals("&cCancel", shipped.getString("input.cancel"));
    }

    @Test
    void commandAndListOutputRendersAsBefore() {
        MessageManager messages = manager();
        String label = "wardrobe";
        assertEquals("&cUsage: /" + label + " equip <number> &7— see /" + label + " list",
                messages.format("command.usage-equip", Map.of("label", label)));
        assertEquals("&cYou don't have permission to do that.", messages.get("command.reload-no-permission"));
        assertEquals("&aRoyalWardrobe config, messages, menu and scope reloaded."
                + " &7Storage settings apply on restart.", messages.get("command.reloaded"));
        assertEquals("&cOnly players can open the wardrobe.", messages.get("command.players-only"));
        assertEquals("&cYou don't have permission to use the wardrobe.", messages.get("command.no-permission"));

        assertEquals("&6&lWardrobe &7— /wardrobe equip <number>", messages.get("list.header"));
        assertEquals("&a(wearing)", messages.get("list.state.wearing"));
        assertEquals("&4(damaged — ask an admin)", messages.get("list.state.damaged"));
        assertEquals("&c(locked)", messages.get("list.state.locked"));
        assertEquals("&8(empty)", messages.get("list.state.empty"));
        String state = messages.format("list.state.pieces", Map.of("pieces", "Diamond Helmet, Iron Boots"));
        assertEquals("&7Diamond Helmet, Iron Boots", state);
        assertEquals("&e" + 3 + ". &f" + "Setup #3" + " " + state,
                messages.format("list.line", Map.of("number", "3", "name", "Setup #3", "state", state)));
    }

    @Test
    void placeholderValuesAreNotExpandedAgain() {
        MessageManager messages = manager();
        assertEquals("&e1. &f{state} &a(wearing)",
                messages.format("list.line", Map.of("number", "1", "name", "{state}", "state", "&a(wearing)")));
    }

    @Test
    void anOlderServerFileFallsBackToTheShippedText() throws IOException {
        Files.writeString(dataFolder.resolve("messages.yml"), "equipped: \"&bCustom\"\n");
        MessageManager messages = manager();
        assertEquals("&bCustom", messages.get("equipped"));
        assertEquals("&aSetup renamed.", messages.get("renamed"));
        assertEquals("&6Wardrobe &8» &r", messages.get("prefix"));
        assertEquals(List.of("&fName this setup", "&7Leave it blank to cancel."), messages.lines("input.rename"));
        assertEquals("equipped: \"&bCustom\"\n", Files.readString(dataFolder.resolve("messages.yml")),
                "the server's file is not rewritten");
    }

    @Test
    void bundledFilesHaveNoDuplicateKeys() throws IOException {
        for (String name : List.of("/messages.yml", "/gui/wardrobe.yml", "/config.yml")) {
            assertEquals(List.of(), duplicateKeys(resource(name)), name);
        }
    }

    private MessageManager manager() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(dataFolder.toFile());
        when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("test"));
        when(plugin.getResource(anyString())).thenAnswer(i -> getClass().getResourceAsStream("/" + i.getArgument(0)));
        return new MessageManager(plugin);
    }

    // YAML keeps only the last of two equal keys, silently dropping the first block
    static List<String> duplicateKeys(String yaml) {
        List<String> duplicates = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<String> path = new ArrayList<>();
        List<Integer> indents = new ArrayList<>();
        for (String line : yaml.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("-")) {
                continue;
            }
            int colon = trimmed.indexOf(':');
            if (colon < 0) {
                continue;
            }
            int indent = line.length() - line.stripLeading().length();
            while (!indents.isEmpty() && indents.get(indents.size() - 1) >= indent) {
                indents.remove(indents.size() - 1);
                path.remove(path.size() - 1);
            }
            path.add(trimmed.substring(0, colon));
            indents.add(indent);
            String key = String.join(".", path);
            if (!seen.add(key)) {
                duplicates.add(key);
            }
        }
        return duplicates;
    }

    private static List<Path> sources() throws IOException {
        try (Stream<Path> walk = Files.walk(SOURCES)) {
            return walk.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    private static int lineOf(String source, int index) {
        return (int) source.substring(0, index).chars().filter(c -> c == '\n').count() + 1;
    }

    static String resource(String name) throws IOException {
        try (InputStream in = MessageDefaultsTest.class.getResourceAsStream(name)) {
            assertNotNull(in, name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static YamlConfiguration load(String name) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(resource(name));
        } catch (InvalidConfigurationException e) {
            throw new IOException(e);
        }
        return yaml;
    }
}
