package org.me.advancedsky.world;

import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Region files of deleted islands, deleted at the next startup before any world loads. An
 * island's area is exactly one region file, so a delete leaves its blocks where they are, on a
 * slot nobody may stand on and that is never handed out again, and the files go while nothing
 * can have them open or cached. Clearing the blocks in a running world costs ticks and stops
 * halfway when the server does; a file listed here is deleted at the next startup however this
 * run ends.
 */
public class RegionPurge {

    private static final String LIST_FILE = "region-purge.txt";

    private final Path listFile;
    private final Set<Path> listed = ConcurrentHashMap.newKeySet();

    public RegionPurge(Path dataDirectory) throws IOException {
        this.listFile = dataDirectory.resolve(LIST_FILE);
        // Files the last startup could not delete stay listed.
        if (Files.exists(listFile)) {
            for (String line : Files.readAllLines(listFile, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    listed.add(Path.of(line));
                }
            }
        }
    }

    /**
     * Lists the files for deletion at the next startup.
     */
    public synchronized void schedule(List<Path> files) throws IOException {
        Files.createDirectories(listFile.getParent());
        Files.write(listFile, files.stream().map(Path::toString).toList(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        listed.addAll(files);
    }

    /**
     * Whether the file is listed for deletion at the next startup.
     */
    public boolean isScheduled(Path file) {
        return listed.contains(file);
    }

    /**
     * Deletes every listed file. Runs while the server bootstraps, before any world loads. A file
     * that cannot be deleted stays listed for the next startup.
     */
    public static void purge(Path dataDirectory, Logger logger) {
        Path listFile = dataDirectory.resolve(LIST_FILE);
        if (!Files.exists(listFile)) {
            return;
        }

        try {
            List<String> kept = new ArrayList<>();
            int deleted = 0;
            for (String line : Files.readAllLines(listFile, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }

                try {
                    if (Files.deleteIfExists(Path.of(line))) {
                        deleted++;
                    }
                } catch (IOException e) {
                    logger.warn("Could not delete the region file of a deleted island, trying again at the next startup: " + line, e);
                    kept.add(line);
                }
            }

            if (kept.isEmpty()) {
                Files.delete(listFile);
            } else {
                Files.write(listFile, kept, StandardCharsets.UTF_8);
            }
            logger.info("Deleted " + deleted + " region file(s) of deleted islands");
        } catch (IOException e) {
            logger.error("Could not go through " + listFile + "; the region files of deleted islands stay until it can be", e);
        }
    }
}
