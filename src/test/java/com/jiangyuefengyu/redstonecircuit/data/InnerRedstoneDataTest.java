package com.jiangyuefengyu.redstonecircuit.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pure-logic tests for the inner-redstone data layer.
 *
 * <p>These cover the parts that are easy to get subtly wrong and hard to notice in game:
 * save/load round-trips and the "take back the last thing placed" ordering.
 *
 * <p>Deliberately free of any {@code Items.*} / {@code Blocks.*} access: those require vanilla
 * registries to be bootstrapped, which is unreliable outside a running NeoForge game
 * ({@code Bootstrap.bootStrap()} blows up in {@code BlockBehaviour$Properties.<init>}).
 * The item mapping in {@code HostRules} is verified in-game instead.
 */
class InnerRedstoneDataTest {

    @Test
    @DisplayName("a node survives an NBT round-trip unchanged")
    void nodeRoundTrip() {
        BlockPos pos = new BlockPos(12, 64, -30);
        InnerRedstoneNode node = new InnerRedstoneNode();

        Slot dustNorth = node.put(Direction.NORTH, ComponentType.DUST);
        dustNorth.power = 11;
        dustNorth.wrenchLinks.add(Direction.EAST);

        Slot repeaterUp = node.put(Direction.UP, ComponentType.REPEATER);
        repeaterUp.delay = 3;
        repeaterUp.facing = Direction.WEST;
        repeaterUp.powered = true;

        Slot comparatorDown = node.put(Direction.DOWN, ComponentType.COMPARATOR);
        comparatorDown.mode = ComparatorMode.SUBTRACT;

        CompoundTag saved = node.save();
        // Serialising through the tag and back must not lose or rename anything.
        InnerRedstoneNode reloaded = InnerRedstoneNode.load(saved);

        assertEquals(3, reloaded.size(), "all three components should come back");

        Slot reloadedDust = reloaded.get(Direction.NORTH);
        assertNotNull(reloadedDust, "north dust should exist");
        assertEquals(ComponentType.DUST, reloadedDust.type);
        assertEquals(11, reloadedDust.power);
        assertTrue(reloadedDust.wrenchLinks.contains(Direction.EAST),
                "wrench-locked connections must persist");

        Slot reloadedRepeater = reloaded.get(Direction.UP);
        assertNotNull(reloadedRepeater, "up repeater should exist");
        assertEquals(3, reloadedRepeater.delay);
        assertEquals(Direction.WEST, reloadedRepeater.facing);
        assertTrue(reloadedRepeater.powered);

        Slot reloadedComparator = reloaded.get(Direction.DOWN);
        assertNotNull(reloadedComparator, "down comparator should exist");
        assertEquals(ComparatorMode.SUBTRACT, reloadedComparator.mode);
    }

    @Test
    @DisplayName("retrieval follows last-in-first-out order")
    void newestFaceFollowsPlacementOrder() {
        InnerRedstoneNode node = new InnerRedstoneNode();

        node.put(Direction.NORTH, ComponentType.DUST);
        node.put(Direction.SOUTH, ComponentType.DUST);
        node.put(Direction.EAST, ComponentType.DUST);

        assertEquals(Direction.EAST, node.newestFace(), "the last placed component comes out first");

        node.remove(Direction.EAST);
        assertEquals(Direction.SOUTH, node.newestFace());

        node.remove(Direction.SOUTH);
        assertEquals(Direction.NORTH, node.newestFace());

        node.remove(Direction.NORTH);
        assertNull(node.newestFace(), "an emptied node has nothing to hand back");
        assertTrue(node.isEmpty());
    }

    @Test
    @DisplayName("placement order survives a save/load cycle")
    void placementOrderIsPersisted() {
        InnerRedstoneNode node = new InnerRedstoneNode();
        node.put(Direction.NORTH, ComponentType.DUST);
        node.put(Direction.UP, ComponentType.LEVER);

        InnerRedstoneNode reloaded = InnerRedstoneNode.load(node.save());

        assertEquals(Direction.UP, reloaded.newestFace(),
                "order must be restored from NBT, not guessed from face order");
    }

    @Test
    @DisplayName("maxPower reports the strongest dust and ignores other components")
    void maxPowerIgnoresNonDust() {
        InnerRedstoneNode node = new InnerRedstoneNode();

        Slot low = node.put(Direction.NORTH, ComponentType.DUST);
        low.power = 4;
        Slot high = node.put(Direction.SOUTH, ComponentType.DUST);
        high.power = 13;
        Slot torch = node.put(Direction.UP, ComponentType.TORCH);
        torch.power = 15; // must not be counted: not dust

        assertEquals(13, node.maxPower());

        node.remove(Direction.SOUTH);
        assertEquals(4, node.maxPower());
    }

    @Test
    @DisplayName("unknown or corrupt NBT values fall back to safe defaults")
    void corruptDataFallsBackSafely() {
        CompoundTag tag = new CompoundTag();
        tag.putString("type", "NOT_A_REAL_TYPE");
        tag.putString("facing", "sideways");
        tag.putInt("delay", 99); // out of range
        tag.putString("mode", "???");
        tag.putIntArray("wrenchLinks", new int[] { 99, -1 });

        Slot slot = Slot.load(tag);

        assertEquals(ComponentType.DUST, slot.type, "unknown component type falls back to dust");
        assertEquals(Direction.NORTH, slot.facing, "unknown direction falls back to north");
        assertEquals(4, slot.delay, "repeater delay is clamped to 1-4");
        assertEquals(ComparatorMode.COMPARE, slot.mode);
        assertTrue(slot.wrenchLinks.isEmpty(), "invalid direction ids must be dropped");
    }
}
