package lol.rowe.blocker;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AllowedAppsTest {
    @Test
    public void aNarrowScreenGetsThreeColumnsThatFit() {
        AllowedApps.Grid grid = AllowedApps.grid(304, 84, 6);

        assertEquals(3, grid.columns);
        assertEquals(89, grid.cellWidth);
        assertTrue(grid.columns * (grid.cellWidth + 2 * 6) <= 304);
    }

    @Test
    public void aWiderScreenGetsFourColumnsThatFit() {
        AllowedApps.Grid grid = AllowedApps.grid(355, 84, 6);

        assertEquals(4, grid.columns);
        assertEquals(76, grid.cellWidth);
        assertTrue(grid.columns * (grid.cellWidth + 2 * 6) <= 355);
    }

    @Test
    public void twentyShortcutsNeverWidenTheGridPastTheScreen() {
        AllowedApps.Grid grid = AllowedApps.grid(304, 84, 6);

        int rows = (20 + grid.columns - 1) / grid.columns;
        assertEquals(7, rows);
        assertTrue(grid.columns * (grid.cellWidth + 2 * 6) <= 304);
    }

    @Test
    public void aTinyWidthStillLeavesUsableCells() {
        AllowedApps.Grid grid = AllowedApps.grid(10, 84, 6);

        assertEquals(3, grid.columns);
        assertTrue(grid.cellWidth >= 1);
    }
}
