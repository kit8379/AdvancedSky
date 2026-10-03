package org.me.advancedsky.world;

import org.bukkit.World;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * Paper/Folia 1.21.11 Moonrise storage barrier. Bukkit's isChunkLoaded only covers full chunks:
 * partially unloaded holders and pending region writes also have to finish before slot reuse.
 * An unsupported server fails closed: the purge keeps its reservation instead of reusing dirt.
 */
final class RegionStorageAccess {
    private RegionStorageAccess() {}

    static boolean hasHolders(World world, int baseX, int baseZ) {
        try {
            Object handle = world.getClass().getMethod("getHandle").invoke(world);
            Object scheduler = handle.getClass().getMethod("moonrise$getChunkTaskScheduler").invoke(handle);
            Object holders = scheduler.getClass().getField("chunkHolderManager").get(scheduler);
            Method getHolder = holders.getClass().getMethod("getChunkHolder", int.class, int.class);
            for (int x = baseX; x < baseX + 32; x++) {
                for (int z = baseZ; z < baseZ + 32; z++) {
                    if (getHolder.invoke(holders, x, z) != null) return true;
                }
            }
            return false;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot verify region storage is unloaded on this server", e);
        }
    }

    static void flushAndEvict(World world, int regionX, int regionZ) {
        try {
            Object handle = world.getClass().getMethod("getHandle").invoke(world);
            Class<?> io = Class.forName("ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO", true, handle.getClass().getClassLoader());
            // Run off-thread. Drain chunk, entity and POI writes before closing their cached files.
            Method flush = java.util.Arrays.stream(io.getMethods()).filter(method -> method.getName().equals("flush")
                    && method.getParameterCount() == 1 && method.getParameterTypes()[0].isInstance(handle)).findFirst().orElseThrow();
            flush.invoke(null, handle);
            if (hasHolders(world, regionX * 32, regionZ * 32)) throw new IllegalStateException("Region was loaded again during cleanup");
            Class<?> type = Class.forName(io.getName() + "$RegionFileType", true, handle.getClass().getClassLoader());
            Method controller = java.util.Arrays.stream(io.getMethods()).filter(method -> method.getName().equals("getControllerFor")
                    && method.getParameterCount() == 2).findFirst().orElseThrow();
            long key = (regionX & 0xffffffffL) | ((long) regionZ << 32);
            for (Object kind : type.getEnumConstants()) {
                Object data = controller.invoke(null, handle, kind);
                Object storage = data.getClass().getMethod("getCache").invoke(data);
                synchronized (storage) {
                    Map<?, ?> cache = (Map<?, ?>) storage.getClass().getField("regionCache").get(storage);
                    Object file = cache.get(key);
                    if (file != null) {
                        ((AutoCloseable) file).close();
                        cache.remove(key);
                    }
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Cannot flush and evict region storage; slot remains reserved", e);
        }
    }
}
