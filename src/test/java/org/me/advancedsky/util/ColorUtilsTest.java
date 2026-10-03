package org.me.advancedsky.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class ColorUtilsTest {

    @Test
    void readsLegacyColorsAndFormatting() {
        assertEquals(Component.text("Bold", NamedTextColor.RED).decorate(TextDecoration.BOLD),
                ColorUtils.colorize("&c&lBold"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"&#12ab34Hex", "&x&1&2&a&b&3&4Hex"})
    void readsLegacyHexColors(String message) {
        assertEquals(Component.text("Hex", TextColor.color(0x12ab34)), ColorUtils.colorize(message));
    }

    @Test
    void legacyResetEndsPreviousFormatting() {
        assertEquals("&lBold&rPlain", LegacyComponentSerializer.legacyAmpersand()
                .serialize(ColorUtils.colorize("&lBold&rPlain")));
    }

    @Test
    void keepsMiniMessageColorsAndFormatting() {
        assertEquals(Component.text("Mini", NamedTextColor.GREEN).decorate(TextDecoration.BOLD),
                ColorUtils.colorize("<green><bold>Mini</bold></green>"));
    }

    @Test
    void keepsOrdinaryAmpersandsAndEmptyMessages() {
        assertEquals(Component.text("Fish & chips"), ColorUtils.colorize("Fish & chips"));
        assertEquals(Component.empty(), ColorUtils.colorize(""));
    }

    @Test
    void missingMessagesStillFailClearly() {
        NullPointerException error = assertThrows(NullPointerException.class, () -> ColorUtils.colorize(null));
        assertEquals("message", error.getMessage());
    }
}
