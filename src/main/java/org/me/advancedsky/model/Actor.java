package org.me.advancedsky.model;

import org.json.JSONObject;

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

    String FIELD = "actor";

    static JSONObject toJson(Actor actor) {
        return switch (actor) {
            case Player player -> new JSONObject().put("type", "player").put("uuid", player.uuid().toString());
            case Bypass bypass -> new JSONObject().put("type", "bypass").put("source", bypass.source());
        };
    }

    static Actor fromJson(JSONObject payload) {
        JSONObject actor = payload.getJSONObject(FIELD);
        String type = actor.getString("type");
        return switch (type) {
            case "player" -> new Player(UUID.fromString(actor.getString("uuid")));
            case "bypass" -> new Bypass(actor.getString("source"));
            default -> throw new IllegalArgumentException("Unknown actor type in cross-server payload: " + type);
        };
    }

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
