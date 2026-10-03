package org.me.advancedsky.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.Objects;
import java.util.regex.Pattern;

public final class ColorUtils {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder().character('&').hexColors().build();
    private static final Pattern LEGACY_CODE_PATTERN = Pattern.compile("(?i)&(?:[0-9A-FK-OR]|#[0-9A-F]{6})");

    public static Component colorize(String message) {
        Objects.requireNonNull(message, "message");
        if (LEGACY_CODE_PATTERN.matcher(message).find()) {
            return LEGACY.deserialize(message);
        }
        return MINI.deserialize(message);
    }
}
