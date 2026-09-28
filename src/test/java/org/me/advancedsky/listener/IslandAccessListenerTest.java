package org.me.advancedsky.listener;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.api.AdvancedSkyAPI;
import org.me.advancedsky.cache.IslandCache;
import org.me.advancedsky.config.ConfigHandler;
import org.me.advancedsky.model.Island;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

class IslandAccessListenerTest {
    private final AdvancedSky plugin = mock(AdvancedSky.class);
    private final AdvancedSkyAPI api = mock(AdvancedSkyAPI.class);
    private final ConfigHandler config = mock(ConfigHandler.class);
    private final IslandCache cache = mock(IslandCache.class);
    private final World world = mock(World.class);
    private final Player player = mock(Player.class);
    private final UUID playerUuid = UUID.randomUUID();
    private final Location location = new Location(world, 256, 101, 256);

    @BeforeEach
    void setUp() {
        when(plugin.getApi()).thenReturn(api);
        when(api.lobby(any())).thenReturn(CompletableFuture.completedFuture(null));
        when(config.getIslandWorldName()).thenReturn("skyblock");
        when(world.getName()).thenReturn("skyblock");
        when(player.getUniqueId()).thenReturn(playerUuid);
        when(player.getLocation()).thenReturn(location);
    }

    @Test
    void aFirstJoinOnAFreeSlotIsNotSentToTheLobby() {
        // World spawn falls on slot 0, which no island ever gets.
        join();

        verify(api, never()).lobby(any());
    }

    @Test
    void anIslandWhoseCacheEntryIsUnavailableFailsClosed() {
        when(cache.getIslandUuidAt(location)).thenReturn(UUID.randomUUID());

        join();

        verify(api).lobby(playerUuid);
    }

    @Test
    void bannedPlayersAreSentToTheLobby() {
        Island island = new Island(UUID.randomUUID(), 1, false, false, UUID.randomUUID(), Set.of(), Set.of(), Set.of(playerUuid), Map.of(), null, 1, 1);
        when(cache.getIslandUuidAt(location)).thenReturn(island.getIslandUuid());
        when(cache.getAt(location)).thenReturn(island);

        join();

        verify(api).lobby(playerUuid);
    }

    @Test
    void membersStay() {
        Island island = new Island(UUID.randomUUID(), 1, true, false, UUID.randomUUID(), Set.of(playerUuid), Set.of(), Set.of(), Map.of(), null, 1, 1);
        when(cache.getIslandUuidAt(location)).thenReturn(island.getIslandUuid());
        when(cache.getAt(location)).thenReturn(island);

        join();

        verify(api, never()).lobby(any());
    }

    private void join() {
        new IslandAccessListener(plugin, config, cache).onPlayerJoin(new PlayerJoinEvent(player, net.kyori.adventure.text.Component.empty()));
    }
}
