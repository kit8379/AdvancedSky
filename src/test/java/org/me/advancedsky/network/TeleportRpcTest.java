package org.me.advancedsky.network;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.messaging.CrossServerMessageHandler;
import org.me.advancedsky.messaging.CrossServerMessenger;
import org.me.advancedsky.teleport.TeleportHandler;
import org.me.advancedsky.util.ComponentUtils;
import org.mockito.ArgumentCaptor;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TeleportRpcTest {
    private final UUID playerUuid = UUID.randomUUID();
    private final TeleportHandler teleports = mock(TeleportHandler.class);

    @Test
    void theDestinationReceivesTheFullStyledSuccessMessage() throws Exception {
        Component message = Component.text("Teleported to home ", NamedTextColor.GREEN)
                .append(Component.text("default", NamedTextColor.AQUA));
        when(teleports.prepareTeleport(playerUuid, "skyblock", "1,70,3,0,0", message))
                .thenReturn(CompletableFuture.completedFuture(false));

        JSONObject response = registeredHandler().handle(payload().put("successMessage", ComponentUtils.serialize(message))).join();

        verify(teleports).prepareTeleport(playerUuid, "skyblock", "1,70,3,0,0", message);
        assertFalse(response.getBoolean("teleported"));
    }

    @Test
    void anExplicitlyEmptySuccessMessagePreparesATeleportWithoutAMessage() throws Exception {
        when(teleports.prepareTeleport(playerUuid, "skyblock", "1,70,3,0,0", null))
                .thenReturn(CompletableFuture.completedFuture(true));

        JSONObject response = registeredHandler().handle(payload().put("successMessage", JSONObject.NULL)).join();

        verify(teleports).prepareTeleport(playerUuid, "skyblock", "1,70,3,0,0", null);
        assertTrue(response.getBoolean("teleported"));
    }

    @Test
    void aRequestMissingARequiredProtocolFieldFails() throws Exception {
        assertThrows(org.json.JSONException.class, () -> registeredHandler().handle(payload()));
        verifyNoInteractions(teleports);
    }

    private JSONObject payload() {
        return new JSONObject().put("playerUuid", playerUuid.toString())
                .put("teleportWorld", "skyblock").put("teleportLocation", "1,70,3,0,0");
    }

    private CrossServerMessageHandler registeredHandler() throws Exception {
        AdvancedSky plugin = mock(AdvancedSky.class);
        CrossServerMessenger messenger = mock(CrossServerMessenger.class);
        var register = AdvancedSky.class.getDeclaredMethod("registerCrossServerHandlers", CrossServerMessenger.class,
                IslandOperator.class, TeleportHandler.class);
        register.setAccessible(true);
        register.invoke(plugin, messenger, null, teleports);
        ArgumentCaptor<CrossServerMessageHandler> handler = ArgumentCaptor.forClass(CrossServerMessageHandler.class);
        verify(messenger).register(eq(IslandDistributor.ACTION_ISLAND_TELEPORT_PREPARE), handler.capture());
        return handler.getValue();
    }
}
