package org.me.advancedsky.util;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;

import java.util.Locale;

/**
 * Vanilla names as MiniMessage {@code <lang:...>} tags: each client shows the name in its own
 * language, where a raw ID such as {@code COAL_ORE} or {@code birch_forest} would show English.
 */
public final class TranslatedNames {

    private TranslatedNames() {
    }

    public static String block(Material material) {
        NamespacedKey key = material.getKey();
        return "<lang:block." + key.getNamespace() + "." + key.getKey() + ">";
    }

    /** A biome as the config and players spell it: {@code PLAINS}, {@code plains} or {@code minecraft:plains}. */
    public static String biome(String biome) {
        String id = biome.toLowerCase(Locale.ROOT);
        int colon = id.indexOf(':');
        String namespace = colon < 0 ? "minecraft" : id.substring(0, colon);
        return "<lang:biome." + namespace + "." + id.substring(colon + 1) + ">";
    }
}
