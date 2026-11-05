package com.jiangyuefengyu.redstonecircuit.carrier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.Slot;
import com.jiangyuefengyu.redstonecircuit.logic.TriggerRule;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A 红石触发器 riding a structure steps on the edge of its input, exactly like the one standing in the world - and the
 * pass that steps it is the carrier's own ({@code CarrierNetworks#solve}), which is what was missing: the world pass
 * walks the world store, and a carried host is not in it.
 *
 * <p>What a pure fixture can pin is the mechanism: the reading is the <b>current</b> one taken through the carrier's own
 * space (an inner neighbour in the structure first, the world half second), the switch is the only thing that reaches
 * the clients, and the same reading never flips it twice. What it cannot pin is a real Sable sub-level or Create
 * contraption handing over a pose: the bridge here is a lambda that answers air for the world half.
 */
class CarrierTriggerPassTest {

    /** The structure the whole fixture lives in. */
    private static final CarrierId X =
            CarrierId.subLevel(UUID.fromString("00000000-0000-0000-0000-00000000c1a7"));

    private static final ResourceKey<Level> DIMENSION =
            ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse("redstonecircuit:pc1a7"));

    /**
     * The anchor of the fixture's pose, which is a pure translation - the geometry a Sable sub-level's plot has, so a
     * local neighbour is the local cell next door and nothing here has to model a rotation.
     */
    private static final BlockPos ANCHOR = pos(5, 64, 5);

    /** The one supply of the fixture: a carried dust holding fifteen. */
    private static final BlockPos SOURCE = pos(1, 64, 5);

    /** The trigger, facing west - so its 正面 reads {@link #SOURCE} and its 背面 answers into {@link #CONSUMER}. */
    private static final BlockPos TRIGGER = pos(2, 64, 5);

    /** The run the switch feeds: a dust whose answer is the switch, in the same pass. */
    private static final BlockPos CONSUMER = pos(3, 64, 5);

    /** The world store, as a map - the stand-in every carrier fixture uses. */
    private static final class FakeWorld implements CarriedHostIndex.SlotWorld {

        private final Map<Long, Slot> slots = new HashMap<>();

        @Override
        @Nullable
        public Slot get(BlockPos pos) {
            return slots.get(pos.asLong());
        }

        @Override
        public void set(BlockPos pos, @Nullable Slot slot) {
            if (slot == null) {
                slots.remove(pos.asLong());
            } else {
                slots.put(pos.asLong(), slot);
            }
        }
    }

    /** Counts the write-backs: the one observable this file has for "the clients were told". */
    private static final class Recorder implements CarrierStore.WriteBack {

        final List<CarrierId> writes = new ArrayList<>();

        @Override
        public void wrote(CarrierId carrier) {
            writes.add(carrier);
        }
    }

    private final CarriedHostIndex index = new CarriedHostIndex();
    private final FakeWorld world = new FakeWorld();
    private final Recorder recorder = new Recorder();

    private static BlockPos pos(int x, int y, int z) {
        return new BlockPos(x, y, z);
    }

    @BeforeEach
    void reset() {
        CarrierNetworks.clear();
        recorder.writes.clear();
    }

    // ------------------------------------------------------------------ fixture --

    /** Picks a component up into the structure, the way the assembly callback does. */
    private Slot carry(BlockPos local, ComponentType type) {
        Slot slot = new Slot(type);
        world.set(pos(local.getX() + 100, 64, local.getZ() + 100), slot);
        index.claim(X, local, pos(local.getX() + 100, 64, local.getZ() + 100), world);
        return slotOf(local);
    }

    /** A carried dust with a debug supply on it, holding the value an ongoing run would already hold. */
    private Slot carrySuppliedDust(BlockPos local, int supply) {
        Slot slot = carry(local, ComponentType.DUST);
        slot.injectedPower = supply;
        slot.power = supply;
        return slot;
    }

    /** A carried trigger reading {@code facing}. */
    private Slot carryTrigger(BlockPos local, Direction facing) {
        Slot slot = carry(local, ComponentType.TRIGGER);
        slot.facing = facing;
        return slot;
    }

    /** The live slot the index holds - re-read every time, because the record is what the pass mutates. */
    private Slot slotOf(BlockPos local) {
        CarriedHost host = index.at(X, local);
        assertNotNull(host, "the structure is carrying a host at " + local);
        assertNotNull(host.slot(), "and it holds a component");
        return host.slot();
    }

    /**
     * The bridge as production builds it: a pose that can be read, and a world half that answers air everywhere - a
     * carried host stands where the level sees nothing, which is why the fixture's only input has to come from the
     * structure's own blocks.
     */
    private static CarrierWorldBridge bridge() {
        CarrierPose pose = CarrierPose.at(new Vector3d(ANCHOR.getX(), ANCHOR.getY(), ANCHOR.getZ()));
        return new CarrierWorldBridge(ignored -> pose, (worldPos, worldDirection) -> 0);
    }

    /** One pass over the structure, exactly as the driver makes it. */
    private CarrierNetworks.TickSummary solve(long tick) {
        CarrierStore.Clock clock = new CarrierStore.Clock(tick, DIMENSION);
        CarrierStore.TickContext ctx = new CarrierStore.TickContext(clock, index, world, null, recorder,
                bridge());
        return CarrierNetworks.tick(ctx, clock);
    }

    // ------------------------------------------------------------------- 1. the step --

    @Test
    @DisplayName("a carried trigger steps on the rising edge, and the settle below sees the switch in the same pass")
    void theCarriedTriggerFlipsOnTheRisingEdge() {
        carrySuppliedDust(SOURCE, 15);
        carryTrigger(TRIGGER, Direction.WEST);
        carry(CONSUMER, ComponentType.DUST);

        solve(1L);

        assertTrue(slotOf(TRIGGER).powered,
                "its 正面 is live, so the switch is on after the first pass - the carried form of the block was the "
                        + "one with no pass at all");
        assertEquals(TriggerRule.ON, slotOf(TRIGGER).power, "and the answer is fifteen");
        assertTrue(slotOf(TRIGGER).triggerInput,
                "the reading it saw is remembered; a pass that forgot it would take the hold for a new edge next tick");
        assertEquals(TriggerRule.ON, slotOf(CONSUMER).power,
                "the dust behind it reads fifteen in the SAME pass, which is what placing the step before the settle "
                        + "buys: an edge decided after the settle would be a tick late");
        assertEquals(1, recorder.writes.size(), "and the flip reached the clients, once");
    }

    // -------------------------------------------------------- 2. the same reading twice --

    @Test
    @DisplayName("the same reading does not flip it a second time - not in a later pass, not twice in one tick")
    void theSameReadingDoesNotFlipTwice() {
        carrySuppliedDust(SOURCE, 15);
        carryTrigger(TRIGGER, Direction.WEST);

        solve(1L);
        assertTrue(slotOf(TRIGGER).powered,
                "precondition: the first pass did flip it, or 'it did not flip again' below would be vacuously true");
        boolean powered = slotOf(TRIGGER).powered;
        int power = slotOf(TRIGGER).power;
        int writes = recorder.writes.size();

        solve(2L);
        // And the same tick again, which the driver skips - the answer to "twice in one tick" has to be no either way.
        solve(2L);

        assertEquals(powered, slotOf(TRIGGER).powered,
                "holding the input live must not toggle the switch again: the edge memory already holds this reading");
        assertEquals(power, slotOf(TRIGGER).power, "so the answer is unchanged: " + power);
        assertEquals(writes, recorder.writes.size(), "and nothing new had to be sent");
    }

    // -------------------------------------------------------- 3. what reaches the clients --

    @Test
    @DisplayName("the input falling moves only the reading, and the next rising edge flips it back off")
    void theFallingEdgeOnlyMovesTheReading() {
        Slot source = carrySuppliedDust(SOURCE, 15);
        carryTrigger(TRIGGER, Direction.WEST);

        solve(1L);
        assertTrue(slotOf(TRIGGER).powered, "precondition: the first rising edge switched it on");

        source.injectedPower = -1;
        solve(2L);
        assertEquals(0, slotOf(SOURCE).power,
                "precondition: the supply really went away - the settle in this pass dropped the dust to zero, so the "
                        + "trigger's own step still saw fifteen, one pass behind its neighbour as an inner wire is");
        // That drop is a change of the dust itself and the clients are told about it, so the count restarts here: what
        // the assertions below are about is the trigger alone.
        int writes = recorder.writes.size();
        solve(3L);

        assertTrue(slotOf(TRIGGER).powered, "a falling edge does not flip the switch");
        assertEquals(TriggerRule.ON, slotOf(TRIGGER).power, "so the answer stays fifteen");
        assertFalse(slotOf(TRIGGER).triggerInput, "while the reading it remembers has followed the input down");
        assertEquals(writes, recorder.writes.size(),
                "and nothing was sent for the trigger: only the answer moving is worth telling the clients, exactly as "
                        + "in the world pass");

        Slot live = slotOf(SOURCE);
        live.injectedPower = 15;
        live.power = 15;
        solve(4L);

        assertFalse(slotOf(TRIGGER).powered, "the second rising edge flips the switch back off");
        assertEquals(TriggerRule.OFF, slotOf(TRIGGER).power, "and the answer is zero");
        assertEquals(writes + 1, recorder.writes.size(), "which is the one thing the clients are told");
    }

    // ------------------------------------------------------------ 4. the reading's source --

    @Test
    @DisplayName("the input is read now, not from the number the last settle recorded")
    void theInputIsTheCurrentReadingNotTheRecordedOne() {
        // The recorded reading says "live" while nothing is feeding the face any more: no neighbour inside the
        // structure, and the world cell is air. A pass that read the recorded number (Slot#lastInput, what
        // 信号延长器 reads) would take this for a rising edge and switch the trigger on.
        Slot trigger = carryTrigger(TRIGGER, Direction.WEST);
        trigger.lastInput = TriggerRule.ON;

        solve(1L);

        assertFalse(slotOf(TRIGGER).powered,
                "nothing is arriving on its 正面 now, so there is no edge - reading the recorded number instead of the "
                        + "current one would flip it here");
        assertFalse(slotOf(TRIGGER).triggerInput, "and the reading it remembers is the current one: nothing");
        assertEquals(0, slotOf(TRIGGER).lastInput,
                "while the settle below took the same face through the same rule and recorded the same zero - one "
                        + "reading, two callers, no second source");

        // And the same pass does flip it the moment something really is arriving: without this leg the two assertions
        // above would also hold for a trigger no pass ever looked at, which is the state this file is here to rule out.
        carrySuppliedDust(SOURCE, 15);
        solve(2L);
        assertTrue(slotOf(TRIGGER).powered,
                "a live 正面 does switch it, so the pass really is reading the face rather than never running");
    }
}
