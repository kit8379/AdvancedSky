package org.me.advancedsky.util;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public class EntityUtils {

    /**
     * Every player riding the entity, also those riding something that rides it, such as a mob
     * in a minecart.
     */
    public static List<Player> riders(Entity vehicle) {
        List<Player> riders = new ArrayList<>();
        for (Entity passenger : vehicle.getPassengers()) {
            if (passenger instanceof Player player) {
                riders.add(player);
            }
            riders.addAll(riders(passenger));
        }
        return riders;
    }
}
