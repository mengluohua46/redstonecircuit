package com.jiangyuefengyu.redstonecircuit.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;

/**
 * The allocator's reserve path, and the things a plot table has to survive.
 *
 * <p>Both exist for one reason: a restart. Nothing in memory survives it, so the cells that are already
 * taken have to be learned from the save before anything new is handed out - and if that learning is
 * wrong, two structures end up in one cell, which reads in game as "my machine ate someone else's machine".
 */
class PlotAllocatorReserveTest {

    @Test
    @DisplayName("a reserved cell is never handed out, and the grid grows past it")
    void reservedCellsAreNotHandedOut() {
        PlotAllocator allocator = new PlotAllocator();
        assertTrue(allocator.reserve(0), "reserving an unused cell must work");
        assertTrue(allocator.reserve(3));
        assertTrue(!allocator.reserve(3), "reserving the same cell twice is not a state change");

        int first = allocator.allocate();
        int second = allocator.allocate();
        assertEquals(1, first, "the next cell after a reserved one, not the reserved one itself");
        assertEquals(2, second);
        assertTrue(allocator.isLive(0) && allocator.isLive(3), "and the reserved ones stay live");
        assertEquals(4, allocator.liveCount());
    }

    @Test
    @DisplayName("releasing a reserved cell makes it available again")
    void releasedReservationsComeBack() {
        PlotAllocator allocator = new PlotAllocator();
        allocator.reserve(2);
        assertTrue(allocator.free(2), "a reserved cell is live, so it can be given back");
        assertEquals(2, allocator.allocate(), "and the next claim gets it");
        assertTrue(!allocator.isLive(5), "while cells nobody touched stay free");
    }

    @Test
    @DisplayName("reserving a cell far out does not hand out the ones before it")
    void reservingFarOutLeavesTheGapForLater() {
        PlotAllocator allocator = new PlotAllocator();
        allocator.reserve(7);
        assertEquals(0, allocator.allocate(), "the gap before a reservation is still ordinary free space");
        assertEquals(1, allocator.allocate());
        assertTrue(allocator.isLive(7));
        assertTrue(!allocator.isLive(2));
    }
}
