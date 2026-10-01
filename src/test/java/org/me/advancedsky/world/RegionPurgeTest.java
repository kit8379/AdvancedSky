package org.me.advancedsky.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class RegionPurgeTest {

    @TempDir
    Path folder;

    @Test
    void theNextStartupDeletesTheListedFilesAndTheList() throws IOException {
        Path data = folder.resolve("plugin");
        Path region = Files.createDirectories(folder.resolve("region")).resolve("r.4.4.mca");
        Path entities = Files.createDirectories(folder.resolve("entities")).resolve("r.4.4.mca");
        Files.writeString(region, "chunks");
        Files.writeString(entities, "entities");
        Path neighbour = Files.writeString(folder.resolve("region").resolve("r.8.0.mca"), "chunks");

        // A file never written, such as the point-of-interest file of an island without any, is fine.
        new RegionPurge(data).schedule(List.of(region, entities, folder.resolve("poi").resolve("r.4.4.mca")));
        RegionPurge.purge(data, mock(Logger.class));

        assertFalse(Files.exists(region));
        assertFalse(Files.exists(entities));
        assertTrue(Files.exists(neighbour));
        assertFalse(Files.exists(data.resolve("region-purge.txt")));
    }

    @Test
    void aFileThatCannotBeDeletedStaysListed() throws IOException {
        Path data = folder.resolve("plugin");
        Path deletable = Files.writeString(folder.resolve("r.4.4.mca"), "chunks");
        // A directory with something in it cannot be deleted like a file.
        Path stuck = Files.createDirectories(folder.resolve("r.0.4.mca"));
        Files.writeString(stuck.resolve("inside"), "x");

        new RegionPurge(data).schedule(List.of(deletable, stuck));
        RegionPurge.purge(data, mock(Logger.class));

        assertFalse(Files.exists(deletable));
        assertEquals(List.of(stuck.toString()), Files.readAllLines(data.resolve("region-purge.txt")));
    }
}
