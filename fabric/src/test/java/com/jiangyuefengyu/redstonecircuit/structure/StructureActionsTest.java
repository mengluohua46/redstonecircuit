package com.jiangyuefengyu.redstonecircuit.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;

/**
 * A region holding a block that a carrier is carrying may not be captured.
 *
 * <p>A carried host block must be landed first or the capture refused, and this project refuses: landing would mean
 * branching inside the compatibility layer, whose existing behaviour must not move. The predicate is a parameter, so
 * this needs no Sable, no Create and no game.
 */
class StructureActionsTest {

    private static final PreviewRegion REGION = new PreviewRegion(new BlockPos(10, 64, -30), new Vec3i(3, 2, 1));

    @Test
    @DisplayName("a region with nothing carried in it reports nothing")
    void anOrdinaryRegionIsLeftAlone() {
        assertNull(StructureActions.firstCarried(REGION, cell -> false),
                "no carrier, no refusal - the common case must not pay for the rare one");
        assertNull(StructureActions.firstCarried(REGION, cell -> cell.getY() > 100),
                "and a carried block somewhere else is not this region's business");
    }

    @Test
    @DisplayName("the cell that is carried is the one the refusal names")
    void theCarriedCellIsReported() {
        BlockPos carried = new BlockPos(12, 65, -30);
        BlockPos reported = StructureActions.firstCarried(REGION, cell -> cell.equals(carried));
        assertNotNull(reported, "a region holding a carried block must be refused");
        assertEquals(carried, reported, "and the sentence must name that block, so a player can go and land it");
    }

    @Test
    @DisplayName("the scan covers the whole box, not just its first cell")
    void theWholeBoxIsScanned() {
        // The last cell on every axis at once: a scan that stopped early, or that used only x, would miss it.
        BlockPos last = REGION.max();
        assertEquals(last, StructureActions.firstCarried(REGION, cell -> cell.equals(last)),
                "the far corner of the region is part of the region");

        // And every cell is visited exactly once, over a box of 3x2x1.
        Set<BlockPos> visited = new java.util.HashSet<>();
        StructureActions.firstCarried(REGION, cell -> {
            visited.add(cell);
            return false;
        });
        assertEquals(REGION.volume(), visited.size(), "the scan must be the region's own volume: " + visited);
        assertTrue(visited.contains(REGION.min()) && visited.contains(REGION.max()));
    }
}
