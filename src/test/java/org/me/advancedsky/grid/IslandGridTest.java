package org.me.advancedsky.grid;

import org.bukkit.Location;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class IslandGridTest {

    private final IslandGrid grid = new IslandGrid(100);

    @Test
    void spiralIndexInvertsSpiralCell() {
        for (int slot = 0; slot < 100_000; slot++) {
            int[] cell = IslandGrid.spiralCell(slot);
            assertEquals(slot, IslandGrid.spiralIndex(cell[0], cell[1]), "slot " + slot);
        }

        int[] last = IslandGrid.spiralCell(IslandGrid.MAX_SLOT);
        assertEquals(IslandGrid.MAX_SLOT, IslandGrid.spiralIndex(last[0], last[1]));
    }

    @Test
    void slotAtFindsEveryBlockOfTheArea() {
        for (int slot : new int[]{0, 1, 8, 9, 24, 12345}) {
            Location center = grid.center(null, slot);
            int cx = center.getBlockX();
            int cz = center.getBlockZ();

            assertEquals(slot, grid.slotAt(cx, cz));
            assertEquals(slot, grid.slotAt(cx - IslandGrid.AREA_RADIUS, cz - IslandGrid.AREA_RADIUS));
            assertEquals(slot, grid.slotAt(cx + IslandGrid.AREA_RADIUS - 0.5, cz + IslandGrid.AREA_RADIUS - 0.5));
            assertTrue(grid.isInArea(slot, cx + 0.5, cz + 0.5));
        }
    }

    @Test
    void slotAtIsEmptyInTheGapAndBeyondTheLastRing() {
        Location center = grid.center(null, 0);
        assertEquals(-1, grid.slotAt(center.getBlockX() + IslandGrid.AREA_RADIUS, center.getBlockZ()));
        assertEquals(-1, grid.slotAt(center.getBlockX() - IslandGrid.AREA_RADIUS - 1, center.getBlockZ()));
        assertEquals(-1, grid.slotAt(1000, 0));

        double beyond = (IslandGrid.MAX_RING + 1) * (double) IslandGrid.SPACING;
        assertEquals(-1, grid.slotAt(beyond, 0));
    }

    @Test
    void islandBoundaryMatchesNewSkyLayoutAroundTheCentre() {
        int slot = 7;
        Location center = grid.center(null, slot);
        int cx = center.getBlockX();
        int cz = center.getBlockZ();

        // Size 75: -37 .. 37 around the centre, as NewSky's border around an island world origin.
        assertTrue(grid.isInsideIsland(slot, 75, cx - 37, cz + 37));
        assertFalse(grid.isInsideIsland(slot, 75, cx - 38, cz));
        assertFalse(grid.isInsideIsland(slot, 75, cx, cz + 38));

        // Even size 100: -50 .. 49.
        assertTrue(grid.isInsideIsland(slot, 100, cx - 50, cz + 49));
        assertFalse(grid.isInsideIsland(slot, 100, cx + 50, cz));
    }
}
