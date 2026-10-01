package org.me.advancedsky.cluster;

import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.redis.RedisHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Island creates sent to a server and not finished yet. The database counts an island only once
 * its host has inserted it, so creates started at the same moment would all pick the server that
 * had the fewest islands when they looked; counting these too spreads them.
 */
public class CreateReservations extends ClusterState {

    /**
     * Longer than any create waits for its host, so a reservation whose requester went down
     * before releasing it stops counting on its own.
     */
    private static final long RESERVATION_TTL_MILLIS = 60_000L;

    /**
     * KEYS: one sorted set of reservations per island server. ARGV: now, expired-before, the
     * create's island, the island count of each server in KEYS order, the TTL. Picks the server
     * with the fewest islands plus reservations, the first one on a tie, and reserves there, in
     * one step so concurrent picks see each other. Returns the index of the server picked.
     */
    private static final String RESERVE_SCRIPT = """
            local best, bestLoad
            for i, key in ipairs(KEYS) do
                redis.call('ZREMRANGEBYSCORE', key, '-inf', ARGV[2])
                local load = tonumber(ARGV[3 + i]) + redis.call('ZCARD', key)
                if best == nil or load < bestLoad then
                    best, bestLoad = i, load
                end
            end
            redis.call('ZADD', KEYS[best], ARGV[1], ARGV[3])
            redis.call('PEXPIRE', KEYS[best], ARGV[4 + #KEYS])
            return best - 1
            """;

    public CreateReservations(AdvancedSky plugin, RedisHandler redisHandler) {
        super(plugin, redisHandler);
    }

    /**
     * Reserves the create on the listed server with the fewest islands, counting the creates
     * reserved there, and returns that server.
     */
    public String reserve(List<String> servers, Map<String, Integer> islandCounts, UUID islandUuid) {
        long now = System.currentTimeMillis();
        List<String> keyList = servers.stream().map(keys::createReservations).toList();
        List<String> args = new ArrayList<>();
        args.add(Long.toString(now));
        args.add(Long.toString(now - RESERVATION_TTL_MILLIS));
        args.add(islandUuid.toString());
        for (String server : servers) {
            args.add(Integer.toString(islandCounts.getOrDefault(server, 0)));
        }
        args.add(Long.toString(RESERVATION_TTL_MILLIS));

        long index = execute(jedis -> (Long) jedis.eval(RESERVE_SCRIPT, keyList, args), "Failed to reserve island create: " + islandUuid);
        return servers.get((int) index);
    }

    public void release(String server, UUID islandUuid) {
        run(jedis -> jedis.zrem(keys.createReservations(server), islandUuid.toString()), "Failed to release island create: " + islandUuid);
    }
}
