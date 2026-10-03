package org.me.advancedsky.world;

import net.kyori.adventure.nbt.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.me.advancedsky.world.SchematicFixtures.*;

class SchematicTemplateTest {
    @TempDir Path folder;

    @Test
    void theBundledUserSchematicDecodesAndFitsTheDefaultIsland() throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(SchematicTemplate.DEFAULT_PATH)) {
            assertNotNull(input);
            var decoded = SchematicTemplate.decode(BinaryTagIO.reader().read(input, BinaryTagIO.Compression.GZIP).getCompound("Schematic"), DATA_VERSION);
            assertEquals(16, decoded.width());
            assertEquals(23, decoded.height());
            assertEquals(17, decoded.length());
            assertEquals(-8, decoded.offsetX());
            assertEquals(-12, decoded.offsetY());
            assertEquals(-7, decoded.offsetZ());
            assertDoesNotThrow(() -> decoded.validateBounds(-64, 320, 100));
            assertEquals(2, decoded.structure().getList("blocks").stream()
                    .filter(tag -> ((CompoundBinaryTag) tag).contains("nbt")).count());
        }
    }

    @Test
    void readsTheV3RootAndPreservesOffsetsPropertiesAndChestItems() throws IOException {
        CompoundBinaryTag items = CompoundBinaryTag.builder().putByte("Slot", (byte) 0)
                .putString("id", "minecraft:ice").putInt("count", 2).build();
        CompoundBinaryTag payload = CompoundBinaryTag.builder().put("Items", ListBinaryTag.from(List.of(items)))
                .putString("CustomName", "{\"text\":\"Starter chest\"}").build();
        CompoundBinaryTag.Builder entity = CompoundBinaryTag.builder().putString("Id", "minecraft:chest").putIntArray("Pos", new int[]{1, 0, 0});
        CompoundBinaryTag chest = entity.put("Data", payload).build();
        CompoundBinaryTag source = schematic(2, 2, 2, new int[]{-1, -2, -1}, new byte[]{1, 2, 0, 1, 0, 0, 1, 0});
        source = source.put("Blocks", source.getCompound("Blocks").put("BlockEntities", ListBinaryTag.from(List.of(chest))));
        Path file = folder.resolve("starter.schem");
        write(file, source);
        var decoded = SchematicTemplate.decode(BinaryTagIO.reader().read(file, BinaryTagIO.Compression.GZIP).getCompound("Schematic"), DATA_VERSION);
        assertEquals(-1, decoded.offsetX());
        assertEquals(-2, decoded.offsetY());
        assertEquals(-1, decoded.offsetZ());
        assertDoesNotThrow(() -> decoded.validateBounds(-64, 320, 100));
        ListBinaryTag blocks = decoded.structure().getList("blocks");
        assertEquals(4, blocks.size(), "air is skipped");
        assertEquals(List.of(0, 1, 1), blocks.getCompound(3).getList("pos").stream().map(tag -> ((IntBinaryTag) tag).value()).toList());
        CompoundBinaryTag block = blocks.getCompound(1);
        CompoundBinaryTag state = decoded.structure().getList("palette").getCompound(block.getInt("state"));
        assertEquals("minecraft:chest", state.getString("Name"));
        assertEquals("north", state.getCompound("Properties").getString("facing"));
        CompoundBinaryTag nbt = block.getCompound("nbt");
        assertEquals("minecraft:chest", nbt.getString("id"));
        assertEquals(1, nbt.getInt("x"));
        assertEquals(payload.getList("Items"), nbt.getList("Items"));
        assertEquals(payload.getString("CustomName"), nbt.getString("CustomName"));
        assertFalse(nbt.contains("Pos"));
        assertFalse(nbt.contains("Id"));
    }

    @Test
    void acceptsSparsePaletteIndicesAndMultibyteVarints() throws IOException {
        CompoundBinaryTag source = schematic(1, 1, 1, new int[3], new byte[]{(byte) 0x80, 1});
        source = source.put("Blocks", source.getCompound("Blocks")
                .put("Palette", CompoundBinaryTag.builder().putInt("minecraft:stone", 128).build()));
        var decoded = SchematicTemplate.decode(source, DATA_VERSION);
        assertEquals(0, decoded.structure().getList("blocks").getCompound(0).getInt("state"));
        assertEquals("minecraft:stone", decoded.structure().getList("palette").getCompound(0).getString("Name"));
    }

    @Test
    void refusesDamagedBlockDataAndInvalidPalettes() {
        CompoundBinaryTag source = schematic(1, 1, 1, new int[3], new byte[]{1});
        for (byte[] data : List.of(new byte[0], new byte[]{(byte) 0x80}, new byte[]{3}, new byte[]{1, 1},
                new byte[]{(byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0x80})) {
            assertThrows(IOException.class, () -> SchematicTemplate.decode(source.put("Blocks", source.getCompound("Blocks").putByteArray("Data", data)), DATA_VERSION));
        }
        assertThrows(IOException.class, () -> SchematicTemplate.decode(source.put("Blocks", source.getCompound("Blocks").put("Palette", CompoundBinaryTag.builder()
                .putInt("minecraft:stone", 1).putInt("minecraft:dirt", 1).build())), DATA_VERSION));
    }

    @Test
    void refusesUnsupportedVersionsEmptySchematicsAndInvalidDimensions() {
        CompoundBinaryTag source = schematic(1, 1, 1, new int[3], new byte[]{1});
        assertThrows(IOException.class, () -> SchematicTemplate.decode(source.putInt("Version", 1), DATA_VERSION));
        assertThrows(IOException.class, () -> SchematicTemplate.decode(source.putInt("Version", 2), DATA_VERSION));
        assertThrows(IOException.class, () -> SchematicTemplate.decode(source.putInt("DataVersion", DATA_VERSION + 1), DATA_VERSION));
        assertThrows(IOException.class, () -> SchematicTemplate.decode(source.put("Blocks", source.getCompound("Blocks").putByteArray("Data", new byte[]{0})), DATA_VERSION));
        assertThrows(IOException.class, () -> SchematicTemplate.decode(source.remove("Offset"), DATA_VERSION));
        assertThrows(IOException.class, () -> SchematicTemplate.decode(source.putShort("Width", (short) 0), DATA_VERSION));
        assertThrows(IOException.class, () -> SchematicTemplate.decode(source.putIntArray("Offset", new int[]{0}), DATA_VERSION));
        assertThrows(IOException.class, () -> SchematicTemplate.decode(source.putShort("Width", (short) -1).putShort("Length", (short) -1), DATA_VERSION));
    }

    @Test
    void selectionAndOriginMustStayInsideTheIslandRegionAndWorldHeight() throws IOException {
        var decoded = SchematicTemplate.decode(schematic(2, 1, 1, new int[]{255, 0, 0}, new byte[]{1, 1}), DATA_VERSION);
        assertThrows(IOException.class, () -> decoded.validateBounds(-64, 320, 100));
        var edge = SchematicTemplate.decode(schematic(2, 1, 1, new int[]{254, 0, -256}, new byte[]{1, 1}), DATA_VERSION);
        assertDoesNotThrow(() -> edge.validateBounds(-64, 320, 319));
        assertThrows(IOException.class, () -> edge.validateBounds(-64, 320, 320));
        assertThrows(IOException.class, () -> edge.validateBounds(-64, 320, -65));
    }
}
