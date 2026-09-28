package org.me.advancedsky.exceptions;

/**
 * The island grid has no slot left, see {@code IslandGrid.MAX_SLOT}.
 */
public class IslandGridFullException extends RuntimeException {

    public IslandGridFullException() {
        super();
    }
}
