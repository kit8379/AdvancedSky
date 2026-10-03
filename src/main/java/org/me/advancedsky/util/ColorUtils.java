package org.me.advancedsky.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import java.util.Objects;

public final class ColorUtils {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    public static Component colorize(String message) {
        return MINI.deserialize(Objects.requireNonNull(message, "message"));
    }
}
