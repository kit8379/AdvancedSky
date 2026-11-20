package org.me.advancedsky.util;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TranslatedNamesTest {

    @Test
    void blocksAndBiomesBecomeTranslationTagsInEverySpelling() {
        assertEquals("<lang:block.minecraft.coal_ore>", TranslatedNames.block(Material.COAL_ORE));
        assertEquals("<lang:biome.minecraft.cherry_grove>", TranslatedNames.biome("CHERRY_GROVE"));
        assertEquals("<lang:biome.minecraft.cherry_grove>", TranslatedNames.biome("cherry_grove"));
        assertEquals("<lang:biome.minecraft.cherry_grove>", TranslatedNames.biome("minecraft:cherry_grove"));
    }
}
