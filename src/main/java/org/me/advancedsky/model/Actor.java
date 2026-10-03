package org.me.advancedsky.model;

import java.util.UUID;

/**
 * Who is performing an island write.
 * <p>
 * Every island mutation takes one of these, so island-level authorization cannot be forgotten:
 * only {@link Player} is subject to role checks, and skipping them requires deliberately naming
 * {@link Bypass}. The role a given operation demands is fixed by the operation itself and is
 * never supplied by the caller, so a caller cannot weaken its own check.
 */
public sealed interface Actor {

    /** A player acting through their own island commands. Island role rules apply. */
    record Player(UUID uuid) implements Actor {
    }

    /**
     * A caller deliberately exempt from island role rules: an operator, the console, or an
     * internal task. The source string identifies it in logs, nothing more.
     */
    record Bypass(String source) implements Actor {
    }
}
