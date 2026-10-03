package org.me.advancedsky.cluster;

import java.util.UUID;

/**
 * Central definition of every Redis key used by the cluster layer.
 * Keeps key schemas in one place instead of scattering string literals across stores.
 */
public final class ClusterKeys {

    private static final String ONLINE_PLAYERS = "online:players";
    private static final String ONLINE_PLAYER_SERVERS = "online:player_servers";
    private static final String INVITATION_PREFIX = "invitation:island:";

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

    public String serverLeases() {
        return prefix + "servers:leases";
    }

    public String serverRoles() {
        return prefix + "servers:roles";
    }

    public String serverSessions() {
        return prefix + "servers:sessions";
    }

    public String invitation(UUID inviteeUuid) {
        return prefix + INVITATION_PREFIX + inviteeUuid;
    }
}
