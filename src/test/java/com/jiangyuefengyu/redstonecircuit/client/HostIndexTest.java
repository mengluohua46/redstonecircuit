package com.jiangyuefengyu.redstonecircuit.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests the client's host bookkeeping.
 *
 * <p>This is the class that decides what a client believes is inside a block, so the rules worth
 * pinning down are the ones that would look like a rendering bug in game: which section a position
 * lands in (including negative coordinates), that a stale dimension is never drawn, and that exactly
 * the affected sections are queued for a rebuild.
 *
 * <p>Deliberately free of any client bootstrap - {@code HostIndex} has no {@code Minecraft}
 * reference, and the rebuild callback is a plain consumer.
 */
class HostIndexTest {

    private static final ResourceKey<Level> OVERWORLD =
            ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                    ResourceLocation.withDefaultNamespace("overworld"));
    private static final ResourceKey<Level> NETHER =
            ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                    ResourceLocation.withDefaultNamespace("the_nether"));

    /** Records the section keys the index asks to be rebuilt. */
    private final List<Long> rebuilt = new ArrayList<>();
    private final HostIndex index = new HostIndex(rebuilt::add);

    private static BlockPos pos(int x, int y, int z) {
        return new BlockPos(x, y, z);
    }

    @Test
    @DisplayName("a snapshot is bucketed by section and reports every section once")
    void snapshotBucketsBySection() {
        index.applySnapshot(OVERWORLD, List.of(pos(0, 0, 0), pos(5, 5, 5), pos(16, 0, 0)));

        assertEquals(List.of(pos(0, 0, 0), pos(5, 5, 5)),
                index.hostsInSection(OVERWORLD, pos(0, 0, 0)),
                "positions in the same 16^3 section belong together");
        assertEquals(List.of(pos(16, 0, 0)),
                index.hostsInSection(OVERWORLD, pos(16, 0, 0)),
                "and the next section starts at 16");
        assertEquals(2, index.sectionCount());
        assertEquals(3, index.size());

        assertEquals(java.util.Set.of(SectionPos.asLong(0, 0, 0), SectionPos.asLong(1, 0, 0)),
                new java.util.HashSet<>(rebuilt),
                "each touched section must be rebuilt exactly once");
    }

    @Test
    @DisplayName("a single change only rebuilds its own section")
    void changeRebuildsOnlyItsOwnSection() {
        index.applySnapshot(OVERWORLD, List.of(pos(0, 0, 0), pos(16, 0, 0)));
        rebuilt.clear();

        index.applyChange(OVERWORLD, pos(2, 2, 2), true);

        assertEquals(List.of(SectionPos.asLong(0, 0, 0)), rebuilt,
                "adding a block must not queue the whole level for a rebuild");
        assertEquals(3, index.size());
        assertTrue(index.hostsInSection(OVERWORLD, pos(0, 0, 0)).contains(pos(2, 2, 2)));
    }

    @Test
    @DisplayName("removing the last host of a section forgets the section")
    void removingEmptiesTheBucket() {
        index.applySnapshot(OVERWORLD, List.of(pos(0, 0, 0)));
        assertEquals(1, index.sectionCount());

        index.applyChange(OVERWORLD, pos(0, 0, 0), false);

        assertEquals(0, index.size());
        assertEquals(0, index.sectionCount(), "empty buckets must not be kept");
        assertEquals(List.of(), index.hostsInSection(OVERWORLD, pos(0, 0, 0)));
    }

    @Test
    @DisplayName("removing something that was never there changes nothing")
    void removingUnknownPositionIsIgnored() {
        index.applySnapshot(OVERWORLD, List.of(pos(0, 0, 0)));
        rebuilt.clear();

        index.applyChange(OVERWORLD, pos(7, 7, 7), false);

        assertEquals(List.of(), rebuilt, "nothing changed, so nothing needs re-rendering");
        assertEquals(1, index.size());
    }

    @Test
    @DisplayName("the same position twice is still one host")
    void duplicateAddsAreIgnored() {
        index.applySnapshot(OVERWORLD, List.of(pos(0, 0, 0), pos(0, 0, 0)));
        index.applyChange(OVERWORLD, pos(0, 0, 0), true);

        assertEquals(1, index.size(), "a duplicated position must not be drawn twice");
        assertEquals(List.of(pos(0, 0, 0)), index.hostsInSection(OVERWORLD, pos(0, 0, 0)));
    }

    @Test
    @DisplayName("another dimension is ignored until its own snapshot arrives")
    void staleDimensionIsNeverDrawn() {
        index.applySnapshot(OVERWORLD, List.of(pos(0, 0, 0)));
        rebuilt.clear();

        index.applyChange(NETHER, pos(0, 0, 0), true);

        assertEquals(List.of(), index.hostsInSection(NETHER, pos(0, 0, 0)),
                "the nether must not inherit the overworld's hosts");
        assertEquals(List.of(), rebuilt);
        assertEquals(1, index.size());

        index.applySnapshot(NETHER, List.of(pos(4, 0, 0)));
        assertEquals(List.of(), index.hostsInSection(OVERWORLD, pos(0, 0, 0)),
                "and switching dimension must drop the old set");
        assertEquals(List.of(pos(4, 0, 0)), index.hostsInSection(NETHER, pos(0, 0, 0)));
    }

    @Test
    @DisplayName("negative coordinates land in the section below zero, not at zero")
    void negativeCoordinatesBucketCorrectly() {
        index.applySnapshot(OVERWORLD, List.of(pos(-1, -1, -1), pos(-16, 0, 0)));

        assertEquals(List.of(pos(-1, -1, -1)), index.hostsInSection(OVERWORLD, pos(-1, -1, -1)),
                "-1 is in section -1 on every axis");
        assertEquals(List.of(pos(-16, 0, 0)), index.hostsInSection(OVERWORLD, pos(-16, 0, 0)),
                "-16 is the start of section -1 on x");
        assertEquals(List.of(), index.hostsInSection(OVERWORLD, pos(0, 0, 0)),
                "and section 0 must stay empty");
        assertEquals(2, index.sectionCount());
    }

    @Test
    @DisplayName("a change before the snapshot is kept rather than dropped")
    void changeBeforeSnapshotAdoptsDimension() {
        index.applyChange(OVERWORLD, pos(3, 3, 3), true);

        assertEquals(List.of(pos(3, 3, 3)), index.hostsInSection(OVERWORLD, pos(0, 0, 0)));
        assertEquals(List.of(SectionPos.asLong(0, 0, 0)), rebuilt);
    }

    @Test
    @DisplayName("clear drops the dimension as well as the positions")
    void clearForgetsEverything() {
        index.applySnapshot(OVERWORLD, List.of(pos(0, 0, 0)));
        index.clear();

        assertEquals(0, index.size());
        assertEquals(List.of(), index.hostsInSection(OVERWORLD, pos(0, 0, 0)));
        rebuilt.clear();
        index.applyChange(OVERWORLD, pos(0, 0, 0), false);
        assertEquals(List.of(), rebuilt, "an empty index has nothing to rebuild");
    }
}
