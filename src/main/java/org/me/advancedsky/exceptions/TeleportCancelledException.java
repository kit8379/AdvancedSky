package org.me.advancedsky.exceptions;

/**
 * A teleport that a listener cancelled, such as the island arrival rule, which tells the
 * player why itself.
 */
public class TeleportCancelledException extends RuntimeException {

    public TeleportCancelledException() {
        super();
    }
}
