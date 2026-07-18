package com.mystipixel.royalwardrobe.wardrobe;

import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;

/**
 * Serializes an {@link ArmorSet}'s four pieces to a Base64 string and back, using Bukkit's object
 * stream so slot positions, empty slots and full item NBT (eco stats, enchants) round-trip exactly —
 * the same mechanism RoyalSkyblock uses for profile inventories. Base64 text stores identically under
 * SQLite and MySQL, so there is no dialect-specific BLOB handling.
 */
public final class ItemCodec {

    private ItemCodec() {
    }

    public static String encode(ItemStack[] pieces) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             BukkitObjectOutputStream out = new BukkitObjectOutputStream(bytes)) {
            out.writeInt(ArmorSet.SIZE);
            for (int i = 0; i < ArmorSet.SIZE; i++) {
                out.writeObject(pieces != null && i < pieces.length ? pieces[i] : null);
            }
            out.flush();
            return Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialize armor set", e);
        }
    }

    public static ItemStack[] decode(String base64) {
        ItemStack[] pieces = new ItemStack[ArmorSet.SIZE];
        if (base64 == null || base64.isBlank()) {
            return pieces;
        }
        byte[] data = Base64.getDecoder().decode(base64);
        try (BukkitObjectInputStream in = new BukkitObjectInputStream(new ByteArrayInputStream(data))) {
            int count = in.readInt();
            for (int i = 0; i < count; i++) {
                ItemStack piece = (ItemStack) in.readObject();
                if (i < ArmorSet.SIZE) {
                    pieces[i] = piece;
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Could not deserialize armor set", e);
        }
        return pieces;
    }
}
