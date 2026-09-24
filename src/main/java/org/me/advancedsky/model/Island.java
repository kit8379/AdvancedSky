package org.me.advancedsky.model;

import java.util.UUID;

/**
 * @param server the island server that hosts this island for its whole life
 * @param slot   network-wide unique position on the island grid (see IslandGrid); the same slot
 *               means the same coordinates on every server
 */
public record Island(UUID islandUuid, UUID ownerUuid, String server, int slot) {
}
