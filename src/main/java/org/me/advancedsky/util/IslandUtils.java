package org.me.advancedsky.util;

public final class IslandUtils {

    private IslandUtils() {
    }

    /**
     * Checks whether a home or warp name is legal. Names are limited to lowercase
     * alphanumerics, underscores and hyphens so that they stay safe as command arguments.
     *
     * @param name the already-normalized (lowercase) name
     * @return true if the name is legal, false otherwise
     */
    public static boolean isLegalPointName(String name) {
        if (name == null || name.isEmpty() || name.length() > 32) {
            return false;
        }

        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!(c >= 'a' && c <= 'z') && !(c >= '0' && c <= '9') && c != '_' && c != '-') {
                return false;
            }
        }

        return true;
    }
}
