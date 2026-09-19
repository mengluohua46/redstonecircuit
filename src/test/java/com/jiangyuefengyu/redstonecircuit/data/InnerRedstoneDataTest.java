package com.jiangyuefengyu.redstonecircuit.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pure-logic tests for the inner-redstone data layer.
 *
 * <p>These cover the parts that are easy to get subtly wrong and hard to notice in game:
 * save/load round-trips, one-component-per-block semantics, and the wrench's per-direction
 * connection overrides.
 *
 * <p>Deliberately free of any {@code Items.*} / {@code Blocks.*} access: those require vanilla
 * registries to be bootstrapped, which is unreliable outside a running NeoForge game
 * ({@code Bootstrap.bootStrap()} blows up in {@code BlockBehaviour$Properties.<init>}).
 * The item mapping in {@code HostRules} is verified in-game instead.
 */
class InnerRedstoneDataTest {

    @Test
    @DisplayName("a component survives an NBT round-trip unchanged")
    void nodeRoundTrip() {
        InnerRedstoneNode node = InnerRedstoneNode.of(ComponentType.COMPARATOR);
        Slot slot = node.slot();
        assertNotNull(slot);

        slot.power = 9;
        slot.powered = true;
        slot.facing = Direction.WEST;
        slot.mode = ComparatorMode.SUBTRACT;
        slot.setConnection(Direction.UP, ConnectionState.ON);
        slot.setConnection(Direction.DOWN, ConnectionState.OFF);

        CompoundTag saved = node.save();
        InnerRedstoneNode reloaded = InnerRedstoneNode.load(saved);

        assertFalse(reloaded.isEmpty(), "the component should come back");
        Slot reloadedSlot = reloaded.slot();
        assertNotNull(reloadedSlot);
        assertEquals(ComponentType.COMPARATOR, reloadedSlot.type);
        assertEquals(9, reloadedSlot.power);
        assertTrue(reloadedSlot.powered);
        assertEquals(Direction.WEST, reloadedSlot.facing);
        assertEquals(ComparatorMode.SUBTRACT, reloadedSlot.mode);

        assertTrue(reloadedSlot.forcedOn.contains(Direction.UP), "forced-on direction must persist");
        assertTrue(reloadedSlot.forcedOff.contains(Direction.DOWN), "forced-off direction must persist");
    }

    @Test
    @DisplayName("an empty node round-trips as empty")
    void emptyNodeRoundTrip() {
        InnerRedstoneNode node = new InnerRedstoneNode();
        assertTrue(node.isEmpty());
        assertNull(node.slot());
        assertNull(node.type());

        InnerRedstoneNode reloaded = InnerRedstoneNode.load(node.save());
        assertTrue(reloaded.isEmpty(), "an empty node must not invent a component");
    }

    @Test
    @DisplayName("auto is the default and is overridden per direction")
    void connectionOverrides() {
        Slot slot = new Slot(ComponentType.DUST);

        // By default every direction is "auto", and isConnected reports the unlocked default.
        for (Direction direction : Direction.values()) {
            assertFalse(slot.isLocked(direction), direction + " should start unlocked");
        }
        assertFalse(slot.isRoutingLocked(), "nothing is locked yet");
        assertTrue(slot.isConnected(Direction.UP));

        slot.setConnection(Direction.UP, ConnectionState.OFF);
        assertTrue(slot.isLocked(Direction.UP));
        assertFalse(slot.isConnected(Direction.UP), "an explicit cut must win");
        assertTrue(slot.isConnected(Direction.DOWN),
                "and cutting one side must not freeze the others");

        slot.setConnection(Direction.UP, ConnectionState.ON);
        assertTrue(slot.isConnected(Direction.UP), "a locked side is open");
        assertFalse(slot.isConnected(Direction.DOWN),
                "locking one side makes the routing explicit, so the others are dead at once");
        assertTrue(slot.isRoutingLocked());

        // Setting back to AUTO clears the override rather than leaving a stale entry.
        slot.setConnection(Direction.UP, ConnectionState.AUTO);
        assertFalse(slot.isLocked(Direction.UP));
        assertTrue(slot.isConnected(Direction.UP));
        assertTrue(slot.forcedOn.isEmpty() && slot.forcedOff.isEmpty());
    }

    /**
     * The rule the wrench is built on: a locked component is connected to its locked neighbours and to
     * nothing else, and the sides that are dead are never written down - they are inferred, which is
     * what lets a second direction be locked with one more click.
     */
    @Test
    @DisplayName("locking a side cuts every side that was not locked, without storing anything")
    void lockingInfersTheOtherCuts() {
        Slot slot = new Slot(ComponentType.DUST);
        slot.setConnection(Direction.UP, ConnectionState.ON);

        assertTrue(slot.isConnected(Direction.UP));
        for (Direction direction : Direction.values()) {
            if (direction != Direction.UP) {
                assertFalse(slot.isConnected(direction),
                        direction + " was left out of the lock, so it is dead");
                assertFalse(slot.isExplicitlyCut(direction),
                        direction + " must not be stored as a cut");
                assertFalse(slot.isLocked(direction),
                        direction + " has no override, so the wrench still reads it as automatic"
                                + " and one click locks it");
            }
        }
    }

    @Test
    @DisplayName("clearConnections resets every override")
    void clearConnections() {
        Slot slot = new Slot(ComponentType.DUST);
        slot.setConnection(Direction.NORTH, ConnectionState.ON);
        slot.setConnection(Direction.SOUTH, ConnectionState.OFF);

        slot.clearConnections();

        for (Direction direction : Direction.values()) {
            assertFalse(slot.isLocked(direction), direction + " should be back to auto");
        }
    }

    @Test
    @DisplayName("unknown or corrupt NBT values fall back to safe defaults")
    void corruptDataFallsBackSafely() {
        CompoundTag tag = new CompoundTag();
        tag.putString("type", "NOT_A_REAL_TYPE");
        tag.putString("facing", "sideways");
        tag.putInt("delay", 99); // out of range
        tag.putString("mode", "???");
        tag.putIntArray("forcedOn", new int[] { 99, -1 });
        tag.putIntArray("forcedOff", new int[] { 42 });

        Slot slot = Slot.load(tag);

        assertEquals(ComponentType.DUST, slot.type, "unknown component type falls back to dust");
        assertEquals(Direction.NORTH, slot.facing, "unknown direction falls back to north");
        assertEquals(4, slot.delay, "repeater delay is clamped to 1-4");
        assertEquals(ComparatorMode.COMPARE, slot.mode);
        assertTrue(slot.forcedOn.isEmpty(), "invalid forced-on ids must be dropped");
        assertTrue(slot.forcedOff.isEmpty(), "invalid forced-off ids must be dropped");
    }

    @Test
    @DisplayName("power is reported for dust and zero for an empty node")
    void powerReporting() {
        InnerRedstoneNode node = new InnerRedstoneNode();
        assertEquals(0, node.power(), "an empty node reports no power");

        node = InnerRedstoneNode.of(ComponentType.DUST);
        node.slot().power = 13;
        assertEquals(13, node.power(), "dust reports its stored strength");

        // Non-dust components use `powered`, not `power`, so they report 0 until stage 3 wires up
        // their actual output.
        InnerRedstoneNode torch = InnerRedstoneNode.of(ComponentType.TORCH);
        assertEquals(0, torch.power());
    }

    @Test
    @DisplayName("slot copies are independent of the original")
    void copyIsIndependent() {
        Slot original = new Slot(ComponentType.REPEATER);
        original.delay = 4;
        original.setConnection(Direction.UP, ConnectionState.ON);

        Slot copy = original.copy();
        copy.delay = 1;
        copy.setConnection(Direction.UP, ConnectionState.OFF);

        assertEquals(4, original.delay, "mutating the copy must not touch the original");
        assertTrue(original.forcedOn.contains(Direction.UP));
        assertFalse(original.forcedOff.contains(Direction.UP));
    }
}
