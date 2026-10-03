package org.me.advancedsky.world;

import net.kyori.adventure.nbt.*;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.structure.Structure;
import org.me.advancedsky.grid.IslandGrid;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads Sponge v2/v3 schematics without a template world or a WorldEdit dependency.
 * Converts to vanilla structure NBT so Paper can upgrade Minecraft data and expose unplaced
 * block-state snapshots, including tile entities. These snapshots are copied per island.
 */
final class SchematicTemplate {
    static final String DEFAULT_PATH = "schematics/starter.schem";
    private static final int MAX_VOLUME = 4_194_304;
    private static final long MAX_NBT_BYTES = 64L * 1024 * 1024;
    private final List<BlockState> blocks;
    private final int offsetX;
    private final int offsetY;
    private final int offsetZ;

    private SchematicTemplate(List<BlockState> blocks, Decoded decoded) {
        this.blocks = List.copyOf(blocks);
        this.offsetX = decoded.offsetX();
        this.offsetY = decoded.offsetY();
        this.offsetZ = decoded.offsetZ();
    }

    @SuppressWarnings("deprecation")
    static SchematicTemplate load(Path dataFolder, String configuredPath, World world, int islandHeight) {
        Path root = dataFolder.toAbsolutePath().normalize();
        if (configuredPath == null || configuredPath.isBlank()) {
            throw new IllegalStateException("Set island.template.schematic to a .schem file relative to " + root);
        }
        Path relative = Path.of(configuredPath);
        Path file = root.resolve(relative).normalize();
        if (relative.isAbsolute() || !file.startsWith(root) || !configuredPath.toLowerCase(Locale.ROOT).endsWith(".schem")) {
            throw new IllegalStateException("island.template.schematic must be a relative .schem path inside " + root);
        }
        try {
            Files.createDirectories(file.getParent());
            if (!Files.isRegularFile(file)) {
                throw new IOException("File is missing; export the starter island as a Sponge .schem and put it here");
            }
            CompoundBinaryTag nbt = BinaryTagIO.reader(MAX_NBT_BYTES).read(file, BinaryTagIO.Compression.GZIP);
            Decoded decoded = decode(nbt, Bukkit.getUnsafe().getDataVersion());
            decoded.validateBounds(world.getMinHeight(), world.getMaxHeight(), islandHeight);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            BinaryTagIO.writer().write(decoded.structure(), bytes, BinaryTagIO.Compression.GZIP);
            Structure structure;
            try (ByteArrayInputStream input = new ByteArrayInputStream(bytes.toByteArray())) {
                structure = Bukkit.getStructureManager().loadStructure(input);
            }
            List<BlockState> states = structure.getPalettes().getFirst().getBlocks();
            if (states.isEmpty() || states.size() != decoded.structure().getList("blocks").size()
                    || states.stream().anyMatch(state -> isAir(state.getType()))) {
                throw new IOException("Schematic contains no usable blocks or blocks unsupported by this Minecraft version");
            }
            return new SchematicTemplate(states, decoded);
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Could not load island schematic '" + file + "': " + e.getMessage(), e);
        }
    }

    List<BlockState> blocks() { return blocks; }
    int offsetX() { return offsetX; }
    int offsetY() { return offsetY; }
    int offsetZ() { return offsetZ; }

    /** Package-visible for format tests; no server or world is needed for decoding. */
    static Decoded decode(CompoundBinaryTag root, int currentDataVersion) throws IOException {
        CompoundBinaryTag schematic = root.contains("Schematic", BinaryTagTypes.COMPOUND) ? root.getCompound("Schematic") : root;
        int version = requiredInt(schematic, "Version");
        if (version != 2 && version != 3) throw new IOException("Only Sponge .schem versions 2 and 3 are supported (found " + version + ")");
        int dataVersion = requiredInt(schematic, "DataVersion");
        if (dataVersion <= 0 || dataVersion > currentDataVersion) throw new IOException("Schematic Minecraft DataVersion " + dataVersion + " is unsupported by this server (" + currentDataVersion + ")");
        int width = dimension(schematic, "Width");
        int height = dimension(schematic, "Height");
        int length = dimension(schematic, "Length");
        long volume = (long) width * height * length;
        if (volume > MAX_VOLUME) throw new IOException("Schematic selection exceeds " + MAX_VOLUME + " blocks; use a smaller selection");
        int[] offset = pasteOffset(schematic, version);
        CompoundBinaryTag container = version == 3 ? requiredCompound(schematic, "Blocks") : schematic;
        CompoundBinaryTag palette = requiredCompound(container, "Palette");
        if (palette.isEmpty()) throw new IOException("Schematic palette is empty");

        Map<Integer, Integer> indices = new HashMap<>();
        Map<Integer, Boolean> air = new HashMap<>();
        ListBinaryTag.Builder<CompoundBinaryTag> vanillaPalette = ListBinaryTag.builder(BinaryTagTypes.COMPOUND);
        for (String state : palette.keySet()) {
            int id = requiredInt(palette, state);
            if (id < 0 || indices.putIfAbsent(id, indices.size()) != null) throw new IOException("Invalid or duplicate palette index " + id);
            CompoundBinaryTag blockData = blockData(state);
            vanillaPalette.add(blockData);
            String name = blockData.getString("Name");
            air.put(id, name.equals("minecraft:air") || name.equals("minecraft:cave_air") || name.equals("minecraft:void_air"));
        }

        Map<Integer, CompoundBinaryTag> blockEntities = new HashMap<>();
        if (container.contains("BlockEntities")) {
            if (!(container.get("BlockEntities") instanceof ListBinaryTag list)
                    || (!list.isEmpty() && list.elementType() != BinaryTagTypes.COMPOUND)) throw new IOException("Invalid BlockEntities list");
            for (BinaryTag tag : list) {
                CompoundBinaryTag entity = (CompoundBinaryTag) tag;
                int[] pos = vector(entity, "Pos");
                if (pos[0] < 0 || pos[0] >= width || pos[1] < 0 || pos[1] >= height || pos[2] < 0 || pos[2] >= length) throw new IOException("Block entity is outside the schematic selection");
                String id = entity.getString("Id");
                if (id.isBlank()) throw new IOException("Block entity is missing its Id");
                if (version == 3 && entity.contains("Data") && !entity.contains("Data", BinaryTagTypes.COMPOUND)) throw new IOException("Invalid block entity Data compound");
                CompoundBinaryTag payload = version == 3 ? entity.getCompound("Data") : entity;
                CompoundBinaryTag vanilla = CompoundBinaryTag.builder().put(payload).remove("Id").remove("Pos")
                        .putString("id", id).putInt("x", pos[0]).putInt("y", pos[1]).putInt("z", pos[2]).build();
                if (blockEntities.put(pos[0] + pos[2] * width + pos[1] * width * length, vanilla) != null) throw new IOException("Duplicate block entity position");
            }
        }

        String dataKey = version == 3 ? "Data" : "BlockData";
        if (!container.contains(dataKey, BinaryTagTypes.BYTE_ARRAY)) throw new IOException("Schematic is missing " + dataKey);
        byte[] data = container.getByteArray(dataKey);
        int cursor = 0;
        ListBinaryTag.Builder<CompoundBinaryTag> blocks = ListBinaryTag.builder(BinaryTagTypes.COMPOUND);
        for (int index = 0; index < volume; index++) {
            int id = 0;
            int shift = 0;
            int value;
            do {
                if (cursor >= data.length) throw new IOException("Truncated schematic block data");
                value = data[cursor++] & 0xff;
                if (shift == 28 && (value & 0xf8) != 0) throw new IOException("Invalid schematic palette varint");
                id |= (value & 0x7f) << shift;
                shift += 7;
            } while ((value & 0x80) != 0);
            Integer state = indices.get(id);
            if (state == null) throw new IOException("Block refers to unknown palette index " + id);
            CompoundBinaryTag entity = blockEntities.remove(index);
            if (air.get(id)) {
                if (entity != null) throw new IOException("Block entity is attached to air");
                continue;
            }
            int x = index % width;
            int z = (index / width) % length;
            int y = index / (width * length);
            CompoundBinaryTag.Builder block = CompoundBinaryTag.builder().put("pos", ints(x, y, z)).putInt("state", state);
            if (entity != null) block.put("nbt", entity);
            blocks.add(block.build());
        }
        if (cursor != data.length) throw new IOException("Schematic block data contains extra entries");
        CompoundBinaryTag structure = CompoundBinaryTag.builder().putInt("DataVersion", dataVersion)
                .put("size", ints(width, height, length)).put("palette", vanillaPalette.build())
                .put("blocks", blocks.build()).put("entities", ListBinaryTag.empty()).build();
        if (structure.getList("blocks").isEmpty()) throw new IOException("Schematic contains only air");
        return new Decoded(structure, offset[0], offset[1], offset[2], width, height, length);
    }

    private static int requiredInt(CompoundBinaryTag tag, String key) throws IOException {
        if (!(tag.get(key) instanceof IntBinaryTag number)) throw new IOException("Missing or invalid integer '" + key + "'");
        return number.value();
    }

    private static int dimension(CompoundBinaryTag tag, String key) throws IOException {
        if (!(tag.get(key) instanceof ShortBinaryTag number) || (number.value() & 0xffff) == 0) throw new IOException("Missing or invalid dimension '" + key + "'");
        return number.value() & 0xffff;
    }

    private static int[] vector(CompoundBinaryTag tag, String key) throws IOException {
        if (!tag.contains(key, BinaryTagTypes.INT_ARRAY) || tag.getIntArray(key).length != 3) throw new IOException("'" + key + "' must contain exactly three integers");
        return tag.getIntArray(key);
    }

    private static int[] pasteOffset(CompoundBinaryTag schematic, int version) throws IOException {
        int[] offset = schematic.contains("Offset") ? vector(schematic, "Offset") : new int[3];
        if (version == 2) {
            // WorldEdit v2 stores the selection's original WORLD coordinates in Offset.
            // Its minimum-point-to-copy-origin vector lives in Metadata instead. V3 fixed this.
            CompoundBinaryTag metadata = schematic.getCompound("Metadata");
            CompoundBinaryTag worldEdit = metadata.getCompound("WorldEdit");
            if (worldEdit.contains("Offset")) return vector(worldEdit, "Offset");
            if (metadata.contains("WEOffsetX") || metadata.contains("WEOffsetY") || metadata.contains("WEOffsetZ")) {
                return new int[]{requiredInt(metadata, "WEOffsetX"), requiredInt(metadata, "WEOffsetY"), requiredInt(metadata, "WEOffsetZ")};
            }
        }
        return offset;
    }

    private static CompoundBinaryTag requiredCompound(CompoundBinaryTag tag, String key) throws IOException {
        if (!tag.contains(key, BinaryTagTypes.COMPOUND)) throw new IOException("Missing or invalid compound '" + key + "'");
        return tag.getCompound(key);
    }

    private static ListBinaryTag ints(int x, int y, int z) {
        return ListBinaryTag.from(List.of(IntBinaryTag.intBinaryTag(x), IntBinaryTag.intBinaryTag(y), IntBinaryTag.intBinaryTag(z)));
    }

    private static CompoundBinaryTag blockData(String state) throws IOException {
        int bracket = state.indexOf('[');
        String name = bracket < 0 ? state : state.substring(0, bracket);
        if (!name.contains(":")) name = "minecraft:" + name;
        if (!name.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) throw new IOException("Invalid block name '" + name + "'");
        CompoundBinaryTag.Builder result = CompoundBinaryTag.builder().putString("Name", name);
        if (bracket >= 0) {
            if (!state.endsWith("]")) throw new IOException("Invalid block state '" + state + "'");
            CompoundBinaryTag.Builder properties = CompoundBinaryTag.builder();
            for (String property : state.substring(bracket + 1, state.length() - 1).split(",")) {
                String[] pair = property.split("=", -1);
                if (pair.length != 2 || pair[0].isBlank() || pair[1].isBlank()) throw new IOException("Invalid block property '" + property + "'");
                properties.putString(pair[0], pair[1]);
            }
            result.put("Properties", properties.build());
        }
        return result.build();
    }

    private static boolean isAir(Material type) {
        return type == Material.AIR || type == Material.CAVE_AIR || type == Material.VOID_AIR;
    }

    record Decoded(CompoundBinaryTag structure, int offsetX, int offsetY, int offsetZ, int width, int height, int length) {
        void validateBounds(int minHeight, int maxHeight, int islandHeight) throws IOException {
            int radius = IslandGrid.AREA_RADIUS;
            if (offsetX < -radius || (long) offsetX + width > radius || offsetZ < -radius || (long) offsetZ + length > radius) {
                throw new IOException("Schematic selection and copy origin must fit inside the island's 512 x 512 area");
            }
            long bottom = (long) islandHeight + offsetY;
            if (bottom < minHeight || bottom + height > maxHeight) throw new IOException("Schematic is outside the island world's build height at island.height=" + islandHeight);
        }
    }
}
