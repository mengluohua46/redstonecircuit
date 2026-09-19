package com.jiangyuefengyu.redstonecircuit.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;

import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.HostEntry;
import com.jiangyuefengyu.redstonecircuit.data.Slot;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
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
 * lands in (including negative coordinates), that a stale dimension is never drawn, that an update
 * replaces the contents of a position rather than adding a second copy of it, and that the renderer
 * only sees hosts near the camera.
 *
 * <p>Deliberately free of any client bootstrap - {@code HostIndex} has no {@code Minecraft} reference.
 */
class HostIndexTest {

    private static final ResourceKey<Level> OVERWORLD =
            ResourceKey.create(Registries.DIMENSION, ResourceLocation.withDefaultNamespace("overworld"));
    private static final ResourceKey<Level> NETHER =
            ResourceKey.create(Registries.DIMENSION, ResourceLocation.withDefaultNamespace("the_nether"));

    private final HostIndex index = new HostIndex();

    private static BlockPos pos(int x, int y, int z) {
        return new BlockPos(x, y, z);
    }

    private static Slot dust(int power) {
        Slot slot = new Slot(ComponentType.DUST);
        slot.power = power;
        return slot;
    }

    private static HostEntry entry(int x, int y, int z, int power) {
        return new HostEntry(pos(x, y, z), dust(power));
    }

    /** The positions the index currently holds in one section, sorted so order never matters. */
    private static List<BlockPos> positions(HostIndex index, BlockPos sectionOrigin) {
        return positions(index, OVERWORLD, sectionOrigin);
    }

    private static List<BlockPos> positions(HostIndex index, ResourceKey<Level> dimension,
                                            BlockPos sectionOrigin) {
        List<BlockPos> out = new java.util.ArrayList<>();
        for (HostEntry entry : index.hostsInSection(dimension, sectionOrigin)) {
            out.add(entry.pos());
        }
        out.sort((first, second) -> {
            if (first.getX() != second.getX()) {
                return Integer.compare(first.getX(), second.getX());
            }
            if (first.getY() != second.getY()) {
                return Integer.compare(first.getY(), second.getY());
            }
            return Integer.compare(first.getZ(), second.getZ());
        });
        return out;
    }

    @Test
    @DisplayName("a snapshot is bucketed by section")
    void snapshotBucketsBySection() {
        index.applySnapshot(OVERWORLD, List.of(
                entry(0, 0, 0, 15), entry(5, 5, 5, 3), entry(16, 0, 0, 0)));

        assertEquals(List.of(pos(0, 0, 0), pos(5, 5, 5)), positions(index, pos(0, 0, 0)),
                "positions in the same 16^3 section belong together");
        assertEquals(List.of(pos(16, 0, 0)), positions(index, pos(16, 0, 0)),
                "and the next section starts at 16");
        assertEquals(2, index.sectionCount());
        assertEquals(3, index.size());
    }

    @Test
    @DisplayName("the contents travel with the position")
    void contentsAreRemembered() {
        index.applySnapshot(OVERWORLD, List.of(entry(0, 0, 0, 11)));

        Slot slot = index.slotAt(OVERWORLD, pos(0, 0, 0));
        assertNotNull(slot, "the position is known");
        assertEquals(11, slot.power, "and so is what is inside it");
        assertNull(index.slotAt(OVERWORLD, pos(1, 0, 0)), "an unknown position has no contents");
    }

    @Test
    @DisplayName("updating a position replaces its contents instead of adding a second entry")
    void updateReplacesContents() {
        index.applySnapshot(OVERWORLD, List.of(entry(0, 0, 0, 15)));

        Slot flipped = new Slot(ComponentType.REPEATER);
        flipped.facing = Direction.EAST;
        flipped.power = 0;
        index.applyChange(OVERWORLD, pos(0, 0, 0), flipped);

        assertEquals(1, index.size(), "one block, one component");
        assertEquals(1, positions(index, pos(0, 0, 0)).size());
        Slot stored = index.slotAt(OVERWORLD, pos(0, 0, 0));
        assertNotNull(stored);
        assertEquals(ComponentType.REPEATER, stored.type, "and it is the new one");
        assertEquals(Direction.EAST, stored.facing);
        // The object is stored as sent, not copied: the payload decoder already produced a private
        // copy, and copying again per update would be waste on the hottest sync path in the mod.
        assertSame(flipped, stored);
    }

    @Test
    @DisplayName("a null slot removes the host")
    void nullSlotRemoves() {
        index.applySnapshot(OVERWORLD, List.of(entry(0, 0, 0, 15)));

        index.applyChange(OVERWORLD, pos(0, 0, 0), null);

        assertEquals(0, index.size());
        assertEquals(0, index.sectionCount(), "empty buckets must not be kept");
    }

    @Test
    @DisplayName("a batch update applies every entry")
    void batchUpdateAppliesAll() {
        index.applySnapshot(OVERWORLD, List.of(entry(0, 0, 0, 15), entry(1, 0, 0, 14)));

        index.applyChanges(OVERWORLD, List.of(
                new HostEntry(pos(0, 0, 0), dust(13)),
                HostEntry.removed(pos(1, 0, 0)),
                entry(2, 0, 0, 12)));

        assertEquals(2, index.size());
        assertEquals(13, index.slotAt(OVERWORLD, pos(0, 0, 0)).power);
        assertNull(index.slotAt(OVERWORLD, pos(1, 0, 0)));
        assertEquals(12, index.slotAt(OVERWORLD, pos(2, 0, 0)).power);
    }

    @Test
    @DisplayName("another dimension is ignored until its own snapshot arrives")
    void staleDimensionIsNeverDrawn() {
        index.applySnapshot(OVERWORLD, List.of(entry(0, 0, 0, 15)));

        index.applyChange(NETHER, pos(0, 0, 0), dust(15));

        assertEquals(List.of(), index.hostsInSection(NETHER, pos(0, 0, 0)),
                "the nether must not inherit the overworld's hosts");
        assertEquals(1, index.size());

        index.applySnapshot(NETHER, List.of(entry(4, 0, 0, 15)));
        assertEquals(List.of(), index.hostsInSection(OVERWORLD, pos(0, 0, 0)),
                "and switching dimension must drop the old set");
        assertEquals(List.of(pos(4, 0, 0)), positions(index, NETHER, pos(0, 0, 0)));
    }

    @Test
    @DisplayName("negative coordinates land in the section below zero, not at zero")
    void negativeCoordinatesBucketCorrectly() {
        index.applySnapshot(OVERWORLD, List.of(entry(-1, -1, -1, 15), entry(-16, 0, 0, 15)));

        assertEquals(List.of(pos(-1, -1, -1)), positions(index, pos(-1, -1, -1)),
                "-1 is in section -1 on every axis");
        assertEquals(List.of(pos(-16, 0, 0)), positions(index, pos(-16, 0, 0)),
                "-16 is the start of section -1 on x");
        assertEquals(List.of(), index.hostsInSection(OVERWORLD, pos(0, 0, 0)),
                "and section 0 must stay empty");
        assertEquals(2, index.sectionCount());
    }

    @Test
    @DisplayName("a change before the snapshot is kept rather than dropped")
    void changeBeforeSnapshotAdoptsDimension() {
        index.applyChange(OVERWORLD, pos(3, 3, 3), dust(15));

        assertEquals(List.of(pos(3, 3, 3)), positions(index, pos(0, 0, 0)));
    }

    @Test
    @DisplayName("clear drops the dimension as well as the positions")
    void clearForgetsEverything() {
        index.applySnapshot(OVERWORLD, List.of(entry(0, 0, 0, 15)));
        index.clear();

        assertEquals(0, index.size());
        assertEquals(List.of(), index.hostsInSection(OVERWORLD, pos(0, 0, 0)));
        index.applyChange(OVERWORLD, pos(0, 0, 0), null);
        assertEquals(0, index.size(), "an empty index has nothing to remove");
    }

    // ------------------------------------------------------------ near range --

    @Test
    @DisplayName("forEachNear only visits hosts within the section radius")
    void forEachNearFiltersBySection() {
        // Section 0 on every axis, and one 20 sections away - which is past any sane draw distance.
        index.applySnapshot(OVERWORLD, List.of(entry(1, 1, 1, 15), entry(320, 1, 1, 15)));

        List<BlockPos> near = new java.util.ArrayList<>();
        index.forEachNear(OVERWORLD, pos(0, 0, 0), 6, (p, slot) -> near.add(p));

        assertEquals(List.of(pos(1, 1, 1)), near, "only the close host is visited");
    }

    @Test
    @DisplayName("forEachNear reaches exactly the requested number of sections")
    void forEachNearHonoursTheRadius() {
        // x=16 is section 1; x=256 is section 16.
        index.applySnapshot(OVERWORLD, List.of(entry(16, 0, 0, 15), entry(256, 0, 0, 15)));

        List<BlockPos> one = new java.util.ArrayList<>();
        index.forEachNear(OVERWORLD, pos(0, 0, 0), 1, (p, slot) -> one.add(p));

        assertEquals(List.of(pos(16, 0, 0)), one, "radius 1 covers sections -1, 0 and 1");
    }

    @Test
    @DisplayName("forEachNear says nothing in a dimension it does not describe")
    void forEachNearIgnoresOtherDimensions() {
        index.applySnapshot(OVERWORLD, List.of(entry(0, 0, 0, 15)));

        List<BlockPos> other = new java.util.ArrayList<>();
        index.forEachNear(NETHER, pos(0, 0, 0), 6, (p, slot) -> other.add(p));

        assertEquals(List.of(), other);
    }
}
