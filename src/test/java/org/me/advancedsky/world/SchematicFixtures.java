package org.me.advancedsky.world;

import net.kyori.adventure.nbt.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class SchematicFixtures {
    static final int DATA_VERSION = 4671;

    static CompoundBinaryTag schematic(int version, int width, int height, int length, int[] offset, byte[] data) {
        CompoundBinaryTag palette = CompoundBinaryTag.builder().putInt("minecraft:air", 0).putInt("minecraft:stone", 1)
                .putInt("minecraft:chest[facing=north,type=single,waterlogged=false]", 2).build();
        CompoundBinaryTag.Builder result = CompoundBinaryTag.builder().putInt("Version", version).putInt("DataVersion", DATA_VERSION)
                .putShort("Width", (short) width).putShort("Height", (short) height).putShort("Length", (short) length)
                .putIntArray("Offset", offset);
        CompoundBinaryTag container = CompoundBinaryTag.builder().put("Palette", palette)
                .putByteArray(version == 3 ? "Data" : "BlockData", data).build();
        return version == 3 ? result.put("Blocks", container).build() : result.put(container).build();
    }

    static void write(Path file, CompoundBinaryTag schematic) throws IOException {
        Files.createDirectories(file.getParent());
        // WorldEdit v2 writes a named root; v3 writes a Schematic compound inside the root.
        if (schematic.getInt("Version") == 3) {
            BinaryTagIO.writer().write(CompoundBinaryTag.builder().put("Schematic", schematic).build(), file, BinaryTagIO.Compression.GZIP);
        } else {
            BinaryTagIO.writer().writeNamed(java.util.Map.entry("Schematic", schematic), file, BinaryTagIO.Compression.GZIP);
        }
    }
}
