package org.me.advancedsky.exceptions;

/** Island management must run on the server that hosts the target island. */
public class IslandNotHostedHereException extends RuntimeException {
    private final String server;

    public IslandNotHostedHereException(String server) {
        this.server = server;
    }

    public String getServer() {
        return server;
    }
}
