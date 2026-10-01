package org.me.advancedsky.listener;

import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.junit.jupiter.api.Test;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.api.AdvancedSkyAPI;
import org.me.advancedsky.cache.IslandCache;
import org.me.advancedsky.config.ConfigHandler;
import org.me.advancedsky.model.Island;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.List;
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
    private final EntityScheduler scheduler = mock(EntityScheduler.class);
    private final UUID playerUuid = UUID.randomUUID();
    private final Location deathLocation = new Location(world, 2304, 40, 256);
    private final IslandRespawnListener listener = new IslandRespawnListener(plugin, config, cache);

    IslandRespawnListenerTest() {
        when(plugin.getApi()).thenReturn(api);
        when(api.lobby(any())).thenReturn(CompletableFuture.completedFuture(null));
        when(config.getIslandWorldName()).thenReturn("skyblock");
        when(world.getName()).thenReturn("skyblock");
        when(player.getUniqueId()).thenReturn(playerUuid);
        when(player.getLocation()).thenReturn(deathLocation);
        when(player.getScheduler()).thenReturn(scheduler);
    }

    @Test
    void aMemberIsTakenHomeOnceAliveAgain() {
        island(UUID.randomUUID(), Set.of(playerUuid), Set.of(), Map.of(playerUuid, "2304.5,101.0,256.5,0.0,0.0"), null);
        ArgumentCaptor<Consumer<ScheduledTask>> check = captor();

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getWorld("skyblock")).thenReturn(world);
            die();
        }
        verify(scheduler).runAtFixedRate(eq(plugin), check.capture(), isNull(), eq(1L), eq(1L));

        ScheduledTask task = mock(ScheduledTask.class);
        when(player.isDead()).thenReturn(true);
        check.getValue().accept(task);
        verify(player, never()).teleportAsync(any(Location.class));

        when(player.isDead()).thenReturn(false);
        check.getValue().accept(task);
        verify(task).cancel();
        verify(player).teleportAsync(new Location(world, 2304.5, 101.0, 256.5, 0, 0));
        verify(api, never()).lobby(any());
    }

    @Test
    void aBannedPlayerGoesToTheLobby() {
        island(UUID.randomUUID(), Set.of(), Set.of(playerUuid), Map.of(), "2304.5,101.0,256.5,0.0,0.0");

        die();

        verify(api).lobby(playerUuid);
        verifyNoInteractions(scheduler);
    }

    @Test
    void aDeathInTheGapGoesToTheLobby() {
        die();

        verify(api).lobby(playerUuid);
        verifyNoInteractions(scheduler);
    }

    private void island(UUID owner, Set<UUID> members, Set<UUID> bans, Map<UUID, String> homes, String warp) {
        Island island = new Island(UUID.randomUUID(), 1, false, false, owner, members, Set.of(), bans, homes, warp, 1, 1);
        when(cache.getIslandUuidAt(deathLocation)).thenReturn(island.getIslandUuid());
        when(cache.getAt(deathLocation)).thenReturn(island);
    }

    private void die() {
        listener.onDeath(new PlayerDeathEvent(player, mock(DamageSource.class), List.of(), 0, Component.empty(), false));
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Consumer<ScheduledTask>> captor() {
        return ArgumentCaptor.forClass(Consumer.class);
    }
}
