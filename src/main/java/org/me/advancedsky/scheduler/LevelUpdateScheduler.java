package org.me.advancedsky.scheduler;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.island.LevelHandler;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Rescans island levels. Listeners report players arriving on and leaving islands; the first
 * player on an empty island schedules it, it is scanned right away (its block limit counts come
 * from the scan) and then every few minutes while it has players. The first scan to start after
 * the last player left is its last: the island is dropped once that scan finishes, holding the
 * level and counts it ended with.
 * <p>
 * All state is touched on the global region thread only; the public methods are safe from any
 * thread.
 */
public final class LevelUpdateScheduler {

    private static final long BASE_INTERVAL_MS = 5 * 60_000L;
    private static final long JITTER_MS = 60_000L;
    private static final int MAX_CONCURRENT = 5;
    // A scan whose chunk futures are never completed (a chunk task dropped at shutdown) would
    // otherwise pin its concurrency slot forever; the widest realistic scan is ~10s.
    private static final long SCAN_TIMEOUT_SECONDS = 120L;
    private static final long POLL_PERIOD_TICKS = 20L;
    private final AdvancedSky plugin;
    private final LevelHandler levelHandler;
    private final Map<UUID, Entry> entries = new HashMap<>();
    private final Map<UUID, Integer> playerCounts = new HashMap<>();
    // Leaves are numbered in the order they happen, so "someone left after this scan started"
    // compares positions instead of clock readings that can tie.
    private final Map<UUID, Long> lastLeaves = new HashMap<>();
    private long leaves;
    private final PriorityQueue<Entry> pq = new PriorityQueue<>(Comparator.comparingLong(e -> e.nextRunAtMs));
    private final AtomicInteger inFlight = new AtomicInteger(0);
    private final SplittableRandom rnd = new SplittableRandom();
    private ScheduledTask task;

    public LevelUpdateScheduler(AdvancedSky plugin, LevelHandler levelHandler) {
        this.plugin = plugin;
        this.levelHandler = levelHandler;
    }

    public void start() {
        if (task != null) {
            plugin.debug("LevelUpdateScheduler", "Scheduler is already running.");
            return;
        }

        task = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, scheduled -> tick(), POLL_PERIOD_TICKS, POLL_PERIOD_TICKS);
        plugin.debug("LevelUpdateScheduler", "Started level update scheduler with poll period of " + POLL_PERIOD_TICKS + " ticks.");
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        entries.clear();
        pq.clear();
        playerCounts.clear();
        lastLeaves.clear();

        plugin.debug("LevelUpdateScheduler", "Stopped and cleared all scheduled islands.");
    }

    /**
     * A player arrived on the island. The first one on an empty island schedules it.
     */
    public void playerEnter(UUID islandUuid) {
        plugin.getGlobalRegionExecutor().execute(() -> {
            if (playerCounts.merge(islandUuid, 1, Integer::sum) == 1) {
                register(islandUuid);
            }
        });
    }

    /**
     * A player left the island.
     */
    public void playerLeave(UUID islandUuid) {
        plugin.getGlobalRegionExecutor().execute(() -> {
            Integer count = playerCounts.get(islandUuid);
            if (count == null) {
                return;
            }

            if (count > 1) {
                playerCounts.put(islandUuid, count - 1);
            } else {
                playerCounts.remove(islandUuid);
            }
            lastLeaves.put(islandUuid, ++leaves);
        });
    }

    /**
     * Schedules the island for a scan at the next poll unless it is already scheduled.
     */
    private void register(UUID islandUuid) {
        if (entries.containsKey(islandUuid)) {
            return;
        }

        // Due at the next poll: block limits fail closed until this first scan has counted the
        // island, so it must not sit behind the jitter. MAX_CONCURRENT still bounds a burst.
        Entry e = new Entry(islandUuid, System.currentTimeMillis());
        entries.put(islandUuid, e);
        pq.add(e);

        plugin.debug("LevelUpdateScheduler", "Registered island " + islandUuid + " for a scan at the next poll");
    }

    private void tick() {
        long now = System.currentTimeMillis();

        while (inFlight.get() < MAX_CONCURRENT) {
            Entry due = pollNextDue(now);
            if (due == null) break;

            startOne(due);
        }
    }

    private Entry pollNextDue(long now) {
        while (!pq.isEmpty()) {
            Entry e = pq.peek();
            if (e.cancelled) {
                pq.poll();
                continue;
            }

            if (e.nextRunAtMs > now) {
                return null;
            }

            pq.poll();

            Entry current = entries.get(e.islandUuid);
            if (current == null || current != e) continue;

            return e;
        }
        return null;
    }

    private void startOne(Entry e) {
        long leavesAtStart = leaves;
        int cur = inFlight.incrementAndGet();
        plugin.debug("LevelUpdateScheduler", "Starting calculate for " + e.islandUuid + " (inFlight=" + cur + ")");

        CompletableFuture<Integer> fut;
        try {
            fut = levelHandler.calIslandLevel(e.islandUuid);
        } catch (Throwable t) {
            plugin.severe("LevelUpdateScheduler: Failed to start calculate for " + e.islandUuid, t);
            inFlight.decrementAndGet();
            reschedule(e, leavesAtStart, System.currentTimeMillis(), false);
            return;
        }

        fut.orTimeout(SCAN_TIMEOUT_SECONDS, TimeUnit.SECONDS).whenComplete((level, err) -> {
            plugin.getGlobalRegionExecutor().execute(() -> {
                int after = inFlight.decrementAndGet();

                if (err != null) {
                    plugin.severe("LevelUpdateScheduler: Calculate failed for " + e.islandUuid, err);
                } else {
                    plugin.debug("LevelUpdateScheduler", "Calculate done for " + e.islandUuid + " level=" + level + " (inFlight=" + after + ")");
                }

                reschedule(e, leavesAtStart, System.currentTimeMillis(), err == null);
            });
        });
    }

    /**
     * Only the entry that started the scan may reschedule it. An island nobody has been on since
     * the scan started is dropped: that scan saw its final state. A failed scan is retried
     * sooner, for as long as the island stays in use.
     */
    private void reschedule(Entry finished, long leavesAtStart, long now, boolean succeeded) {
        if (entries.get(finished.islandUuid) != finished) return;

        finished.cancelled = true;

        boolean occupiedSinceStart = playerCounts.containsKey(finished.islandUuid) || lastLeaves.getOrDefault(finished.islandUuid, 0L) > leavesAtStart;
        if (!occupiedSinceStart) {
            entries.remove(finished.islandUuid);
            lastLeaves.remove(finished.islandUuid);
            plugin.debug("LevelUpdateScheduler", "Island " + finished.islandUuid + " has had no players since its last scan, no further scans scheduled");
            return;
        }

        long delay = succeeded ? BASE_INTERVAL_MS : Math.max(5_000L, BASE_INTERVAL_MS / 10);
        Entry ne = new Entry(finished.islandUuid, now + delay + nextJitterMs());
        entries.put(finished.islandUuid, ne);
        pq.add(ne);
    }

    private long nextJitterMs() {
        if (JITTER_MS <= 0) {
            return 0L;
        }
        return (long) (rnd.nextDouble() * (double) JITTER_MS);
    }

    private static final class Entry {
        private final UUID islandUuid;
        private final long nextRunAtMs;
        private boolean cancelled;

        private Entry(UUID islandUuid, long nextRunAtMs) {
            this.islandUuid = islandUuid;
            this.nextRunAtMs = nextRunAtMs;
        }
    }
}
