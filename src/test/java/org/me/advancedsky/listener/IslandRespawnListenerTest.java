package org.me.advancedsky.listener;

import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.junit.jupiter.api.Test;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.api.AdvancedSkyAPI;
import org.me.advancedsky.cache.IslandCache;
import org.me.advancedsky.config.ConfigHandler;
import org.me.advancedsky.model.Island;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.mockito.Mockito.*;

class IslandRespawnListenerTest {
    private final AdvancedSky plugin = mock(AdvancedSky.class);
    private final AdvancedSkyAPI api = mock(AdvancedSkyAPI.class);
    private final ConfigHandler config = mock(ConfigHandler.class);
    private final IslandCache cache = mock(IslandCache.class);
    private final World world = mock(World.class);
    private final Player player = mock(Player.class);
    private final UUID playerUuid = UUID.randomUUID();
    // Died in the gap; the bed they respawn at is on an island.
    private final Location gap = new Location(world, 1024, 70, 1024);
    private final Location bed = new Location(world, 256, 70, 256);
    private final IslandRespawnListener listener = new IslandRespawnListener(plugin, config, cache);

    IslandRespawnListenerTest() {
        when(plugin.getApi()).thenReturn(api);
        when(api.lobby(any())).thenReturn(CompletableFuture.completedFuture(null));
        when(config.getIslandWorldName()).thenReturn("skyblock");
        when(world.getName()).thenReturn("skyblock");
        when(player.getUniqueId()).thenReturn(playerUuid);
        when(player.getLocation()).thenReturn(gap);
        // The lobby trip waits a tick after the respawn; run it at once here.
        EntityScheduler scheduler = mock(EntityScheduler.class);
        when(player.getScheduler()).thenReturn(scheduler);
        when(scheduler.runDelayed(any(), any(), any(), anyLong())).thenAnswer(call -> {
            call.<Consumer<ScheduledTask>>getArgument(1).accept(mock(ScheduledTask.class));
            return mock(ScheduledTask.class);
        });
    }

    @Test
    void aGapDeathRespawningOnAnIslandThatBannedThePlayerGoesToTheLobby() {
        island(Set.of(playerUuid));

        respawn();

        verify(api).lobby(playerUuid);
    }

    @Test
    void aGapDeathRespawningOnAnIslandThePlayerMayBeOnStaysThere() {
        island(Set.of());

        respawn();

        verify(api, never()).lobby(any());
    }

    private void island(Set<UUID> bans) {
        Island island = new Island(UUID.randomUUID(), 1, false, false, UUID.randomUUID(), Set.of(), Set.of(), bans, Map.of(), null, 1, 1);
        when(cache.getIslandUuidAt(bed)).thenReturn(island.getIslandUuid());
        when(cache.getAt(bed)).thenReturn(island);
    }

    private void respawn() {
        listener.onRespawn(new PlayerRespawnEvent(player, bed, true, false, false, PlayerRespawnEvent.RespawnReason.DEATH));
    }
}
