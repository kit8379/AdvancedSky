package org.me.advancedsky.grid;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.BlockVector;

/**
 * Maps an island slot to a position in the island world. Slots are unique across the whole
 * network and spiral outwards from the origin, so an island has the same coordinates on every
 * server and islands stay clustered however many there are.
 * <p>
 * The spacing, centre offset and ring limit are deliberately not configurable: every island
 * position is derived from them, so changing any of them would move or invalidate existing islands.
 */
public final class IslandGrid {

    /**
     * Distance in blocks between neighbouring island centres: 8 Folia region sections of 256
     * blocks. With Folia's default regioniser (1 section of empty halo, merge radius 1 section)
     * two neighbouring islands stay in separate regions as long as each island's loaded area
     * (island radius plus view distance) stays within 512 blocks of its centre, i.e. islands up
     * to roughly 700 blocks wide at view distance 8.
     */
    public static final int SPACING = 2048;

    /**
     * Shift of every island centre off the grid line. Region files are 512x512 blocks with edges
     * on multiples of 512, so this puts each centre in the middle of a file: an island of radius
     * up to 256 lives in exactly one region file, whose index is a multiple of 4 on both axes.
     * Every other region file is pure gap and can be deleted offline at any time.
     */
    public static final int CENTER_OFFSET = 256;

    /**
     * Half-width in blocks of the square area that belongs to an island: exactly the 512x512
     * region file around its centre. Everything an island owns, and everything a delete clears,
     * lies within it.
     */
    public static final int AREA_RADIUS = 256;

    /**
     * Outermost ring that may hold islands. Keeps every island inside 2^24 blocks of the origin,
     * well clear of the world border and of the first documented far-coordinate effects in Java
     * Edition. Rings 0 to MAX_RING hold about 268 million islands.
     */
    public static final int MAX_RING = 8191;

    /**
     * Highest slot that may be allocated (slots are 0-based).
     */
    public static final int MAX_SLOT = (2 * MAX_RING + 1) * (2 * MAX_RING + 1) - 1;

    private final int height;

    public IslandGrid(int height) {
        this.height = height;
    }

    /**
     * Centre block of the island in the given slot.
     */
    public Location center(World world, int slot) {
        BlockVector center = centerOf(slot);
        return new Location(world, center.getBlockX(), center.getBlockY(), center.getBlockZ());
    }

    /**
     * Coordinates of the centre block of the island in the given slot, the same in the island
     * world of every server.
     */
    public BlockVector centerOf(int slot) {
        int[] cell = spiralCell(slot);
        return new BlockVector(cell[0] * SPACING + CENTER_OFFSET, height, cell[1] * SPACING + CENTER_OFFSET);
    }

    /**
     * Whether the block column at x, z lies in the area of the island in the given slot, the
     * same area a delete clears.
     */
    public boolean isInArea(int slot, double x, double z) {
        return slotAt(x, z) == slot;
    }

    /**
     * Slot whose island area holds the block column at x, z, or -1 in the gap between areas and
     * beyond the last ring. Says nothing about whether an island occupies the slot.
     */
    public int slotAt(double x, double z) {
        int blockX = (int) Math.floor(x);
        int blockZ = (int) Math.floor(z);

        // Each area starts on its cell's grid line: [cell * SPACING, cell * SPACING + 2 * AREA_RADIUS).
        if (Math.floorMod(blockX, SPACING) >= 2 * AREA_RADIUS || Math.floorMod(blockZ, SPACING) >= 2 * AREA_RADIUS) {
            return -1;
        }

        int cellX = Math.floorDiv(blockX, SPACING);
        int cellZ = Math.floorDiv(blockZ, SPACING);
        if (Math.max(Math.abs(cellX), Math.abs(cellZ)) > MAX_RING) {
            return -1;
        }

        return spiralIndex(cellX, cellZ);
    }

    /**
     * Whether the block column lies inside the island boundary of the given size: from -size/2 to
     * size - size/2 - 1 blocks of the island centre, the layout NewSky's island border has around
     * its world's origin.
     */
    public boolean isInsideIsland(int slot, int size, int blockX, int blockZ) {
        int[] cell = spiralCell(slot);
        int x = blockX - (cell[0] * SPACING + CENTER_OFFSET);
        int z = blockZ - (cell[1] * SPACING + CENTER_OFFSET);
        int half = size / 2;
        int max = size - half - 1;
        return x >= -half && x <= max && z >= -half && z <= max;
    }

    /**
     * Index of cell {x, z} on the square spiral, the inverse of {@link #spiralCell(int)}.
     */
    static int spiralIndex(int x, int z) {
        int ring = Math.max(Math.abs(x), Math.abs(z));
        if (ring == 0) {
            return 0;
        }

        int ringStart = (2 * ring - 1) * (2 * ring - 1);
        int sideLength = 2 * ring;

        if (x == ring && z > -ring) {
            return ringStart + (z + ring - 1);                     // right edge, top to bottom
        }
        if (z == ring) {
            return ringStart + sideLength + (ring - 1 - x);        // bottom edge, right to left
        }
        if (x == -ring) {
            return ringStart + 2 * sideLength + (ring - 1 - z);    // left edge, bottom to top
        }
        return ringStart + 3 * sideLength + (x + ring - 1);        // top edge, left to right
    }

    /**
     * Cell {x, z} for a 0-based index on a square spiral: 0 is the origin, 1..8 the first ring,
     * 9..24 the second ring, and so on. Each ring is walked clockwise starting just below its
     * top-right corner.
     */
    static int[] spiralCell(int index) {
        if (index == 0) {
            return new int[]{0, 0};
        }

        int ring = (int) Math.ceil((Math.sqrt(index + 1) - 1) / 2.0);
        int ringStart = (2 * ring - 1) * (2 * ring - 1);
        int sideLength = 2 * ring;
        int offset = index - ringStart;
        int side = offset / sideLength;
        int step = offset % sideLength;

        return switch (side) {
            case 0 -> new int[]{ring, -ring + 1 + step};   // right edge, top to bottom
            case 1 -> new int[]{ring - 1 - step, ring};    // bottom edge, right to left
            case 2 -> new int[]{-ring, ring - 1 - step};   // left edge, bottom to top
            default -> new int[]{-ring + 1 + step, -ring}; // top edge, left to right
        };
    }
}
