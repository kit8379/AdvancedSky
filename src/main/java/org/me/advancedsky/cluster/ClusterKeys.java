package org.me.advancedsky.cluster;

/**
 * Central definition of every Redis key used by the cluster layer.
 * Keeps key schemas in one place instead of scattering string literals across stores.
 */
public final class ClusterKeys {

    private static final String ONLINE_PLAYERS = "online:players";
    private static final String ONLINE_PLAYER_SERVERS = "online:player_servers";

    private final String prefix;

    public ClusterKeys(String clusterId) {
        this.prefix = "advancedsky-" + clusterId + ":";
    }

    public String messagingInbox(String serverName) {
        return prefix + "messaging:inbox:" + serverName;
    }

    public String onlinePlayers() {
        return prefix + ONLINE_PLAYERS;
    }

    public String onlinePlayerServers() {
        return prefix + ONLINE_PLAYER_SERVERS;
    }
}
