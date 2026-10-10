package com.jiangyuefengyu.redstonecircuit.carrier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.Slot;
import com.jiangyuefengyu.redstonecircuit.logic.PowerSolver;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A structure that turns on the spot re-publishes its hosts: which way a carried block emits is a question
 * about the structure's orientation, and a pass that compared only positions would see nothing change
 * while the world was never woken.
 *
 * <p>The dead face of a torch is the component, not a defect: a torch does not signal into the block it
 * hangs on.
 */
class CarrierTurnedSignalTest {

    private static final CarrierId CONTRAPTION =
            CarrierId.contraption(UUID.fromString("00000000-0000-0000-0000-00000000c0ff"));
    private static final ResourceKey<Level> DIMENSION =
            ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse("redstonecircuit:test"));

    /**
     * The structure's pivot, which is also the centre of its single host block: the projected world
     * position therefore cannot move however far it turns.
     */
    private static final Vector3d PIVOT = new Vector3d(10.0, 64.0, 10.0);
    private static final BlockPos LOCAL = new BlockPos(0, 0, 0);
    private static final BlockPos WORLD = new BlockPos(10, 64, 10);

    /** Where the host block came from, i.e. the world coordinate the index was told about. */
    private static final BlockPos ORIGIN = new BlockPos(100, 64, 100);

    /** The world store, as a map - the same stand-in the other carrier tests use. */
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

    /** Counts the write-backs, so "the value was re-published" is one fact. */
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

    // ------------------------------------------------------------------ poses --

    /** The bearing at 0 degrees: nothing is turned. */
    private static CarrierPose flat() {
        return CarrierPose.at(new Vector3d(PIVOT));
    }

    /** The same bearing, turned {@code degrees} about its own axis. */
    private static CarrierPose turned(double degrees) {
        return CarrierPose.of(new Vector3d(PIVOT),
                new Quaterniond().rotateY(Math.toRadians(degrees)));
    }

    // ----------------------------------------------------------------- pieces --

    /** A redstone torch hanging on its north face, lit, the way a freshly placed one is. */
    private static Slot torch() {
        Slot slot = new Slot(ComponentType.TORCH);
        slot.facing = Direction.NORTH;
        slot.power = ComponentType.TORCH.initialPower();
        return slot;
    }

    private CarrierProjection.ProjectedHost projected(CarrierId carrier, BlockPos local,
                                                      CarrierPose pose, Slot slot) {
        return new CarrierProjection.ProjectedHost(carrier, local,
                CarrierProjection.project(pose, local), pose, slot, slot.power);
    }

    private static List<BlockPos> changes(CarrierProjection projection) {
        List<BlockPos> out = new ArrayList<>();
        projection.forEachChange(DIMENSION, out::add);
        return out;
    }

    /** Picks the torch up into the structure, the way the assembly callback does. */
    private Slot carryTorch() {
        Slot slot = torch();
        world.set(ORIGIN, slot);
        index.claim(CONTRAPTION, LOCAL, ORIGIN, world);
        return index.at(CONTRAPTION, LOCAL).slot();
    }

    /** One pass of the tick driver over this level, with the world bridge open on the given pose. */
    private CarrierNetworks.TickSummary solve(long gameTime, CarrierPose pose,
                                              CarrierWorldSignals signals) {
        CarrierStore.TickContext context = new CarrierStore.TickContext(
                new CarrierStore.Clock(gameTime, DIMENSION), index, world, null, recorder,
                new CarrierWorldBridge(carrier -> pose, signals));
        return CarrierNetworks.tick(context, new CarrierStore.Clock(gameTime, DIMENSION));
    }

    // ------------------------------------------------- the turned projection --

    /** The same host block, in the same world position, with the same power, at four different angles. */
    @Test
    @DisplayName("a structure that turns on the spot re-publishes its host, because the world must "
            + "re-read which way it emits")
    void aTurnInPlaceIsRepublished() {
        CarrierProjection projection = new CarrierProjection();
        Slot torch = torch();

        projection.begin(DIMENSION, true);
        projection.put(DIMENSION, projected(CONTRAPTION, LOCAL, flat(), torch));
        assertEquals(List.of(WORLD), changes(projection), "the host appeared");

        // The same pose again: a parked machine says nothing.
        projection.begin(DIMENSION, true);
        projection.put(DIMENSION, projected(CONTRAPTION, LOCAL, flat(), torch));
        assertTrue(changes(projection).isEmpty(), "nothing changed, so the world is not woken");

        // A right angle: the position does not move and the value does not move, but the emitted
        // direction does.
        projection.begin(DIMENSION, true);
        projection.put(DIMENSION, projected(CONTRAPTION, LOCAL, turned(90.0), torch));
        assertEquals(List.of(WORLD), changes(projection),
                "the structure turned, so the block's emitted direction turned with it");

        // A half turn: same story, and it is the case where the dead face has swapped ends.
        projection.begin(DIMENSION, true);
        projection.put(DIMENSION, projected(CONTRAPTION, LOCAL, turned(180.0), torch));
        assertEquals(List.of(WORLD), changes(projection), "still turning, still worth a notification");

        // ...and back to where it started: without it the world keeps whatever it last latched onto.
        projection.begin(DIMENSION, true);
        projection.put(DIMENSION, projected(CONTRAPTION, LOCAL, turned(90.0), torch));
        assertEquals(List.of(WORLD), changes(projection), "turning back from the half turn");
        projection.begin(DIMENSION, true);
        projection.put(DIMENSION, projected(CONTRAPTION, LOCAL, flat(), torch));
        assertEquals(List.of(WORLD), changes(projection),
                "turning back is a change too - the signal has to be able to come back");

        // The projected position is the same all the way round.
        assertEquals(CarrierProjection.project(flat(), LOCAL),
                CarrierProjection.project(turned(90.0), LOCAL),
                "the block's world position does not move while the bearing turns on the spot");
        assertEquals(WORLD, CarrierProjection.project(turned(180.0), LOCAL));

        // And the diagnostics count it, so the next log can prove the path was taken.
        assertTrue(projection.describe(DIMENSION).contains("turnRepublish=4"),
                "four turns were re-published: " + projection.describe(DIMENSION));
    }

    /**
     * The turn report itself: one line per structure per tick, naming both orientations - the two hosts
     * sit on the axis a half turn about {@code Y} leaves alone, so only the pose differs between the two
     * passes.
     */
    @Test
    @DisplayName("the turn report names the two orientations, once per structure and not once per host")
    void theTurnReportNamesTheTwoOrientations() {
        CarrierProjection projection = new CarrierProjection();
        BlockPos above = new BlockPos(0, 1, 0);
        Slot torch = torch();
        Slot other = torch();

        projection.begin(DIMENSION, true);
        projection.put(DIMENSION, projected(CONTRAPTION, LOCAL, flat(), torch));
        projection.put(DIMENSION, projected(CONTRAPTION, above, flat(), other));
        changes(projection);

        projection.begin(DIMENSION, true);
        projection.put(DIMENSION, projected(CONTRAPTION, LOCAL, turned(180.0), torch));
        projection.put(DIMENSION, projected(CONTRAPTION, above, turned(180.0), other));

        List<CarrierProjection.TurnedHost> turns = new ArrayList<>();
        List<BlockPos> positions = new ArrayList<>();
        projection.forEachChange(DIMENSION, positions::add, turns::add);

        assertEquals(1, turns.size(),
                "two hosts of one structure turned: one report, not one per host");
        assertEquals(CONTRAPTION, turns.get(0).carrier());
        assertEquals(180.0, turns.get(0).deltaDegrees(), 1.0E-6, "0 -> 180 is a half turn");
        assertTrue(turns.get(0).describe().contains("rot 0.000deg -> 180.000deg"),
                "the line names both orientations: " + turns.get(0).describe());
        assertEquals(2, positions.size(), "both hosts are re-published, since both of them turned");
        assertTrue(positions.contains(WORLD) && positions.contains(new BlockPos(10, 65, 10)),
                "and neither of them moved while the structure turned: " + positions);
    }

    // -------------------------------------------------- the latched consumer --

    /**
     * What the world reads from the host: the query direction is flipped once into the emission
     * direction, turned into the structure's own frame through the pose, and answered by the component.
     */
    private static int readsFromTheBlockNorth(Slot slot, CarrierPose pose) {
        Direction emittedWorld = Direction.SOUTH.getOpposite();
        return PowerSolver.signalAnswer(slot, CarrierDirections.localDirection(pose, emittedWorld),
                false, false, false);
    }

    /**
     * The lamp is a number that only changes when the world tells it to re-read, as a real redstone lamp
     * is - which is why a missing notification shows up as a signal that never comes back.
     */
    @Test
    @DisplayName("the block beside a machine that turned its dead face past it sees the signal come back")
    void theSignalComesBackWhenTheStructureTurnsBack() {
        CarrierProjection projection = new CarrierProjection();
        Slot torch = torch();

        // The block north of the host starts out lit; at a half turn the dead face points south.
        int[] lamp = {readsFromTheBlockNorth(torch, turned(180.0))};
        assertEquals(15, lamp[0], "a torch lights everything except its attachment face");

        projection.begin(DIMENSION, true);
        projection.put(DIMENSION, projected(CONTRAPTION, LOCAL, turned(180.0), torch));
        changes(projection);

        // The dead face swings onto the lamp: it goes dark, which is the component working as designed.
        projection.begin(DIMENSION, true);
        projection.put(DIMENSION, projected(CONTRAPTION, LOCAL, turned(0.0), torch));
        List<BlockPos> told = changes(projection);
        assertEquals(List.of(WORLD), told, "the world is told that the machine has turned");
        if (told.contains(WORLD)) {
            lamp[0] = readsFromTheBlockNorth(torch, turned(0.0));
        }
        assertEquals(0, lamp[0],
                "a torch does not signal into the block it hangs on; the dead face stays dead");

        // Half a turn back: same position and same power, and only the angle differs.
        projection.begin(DIMENSION, true);
        projection.put(DIMENSION, projected(CONTRAPTION, LOCAL, turned(180.0), torch));
        told = changes(projection);
        assertEquals(List.of(WORLD), told,
                "turning back is a change: the world has to be woken, or nothing will re-read the block");
        if (told.contains(WORLD)) {
            lamp[0] = readsFromTheBlockNorth(torch, turned(180.0));
        }
        assertEquals(15, lamp[0], "so the signal comes back instead of staying dark for ever");
    }

    // ------------------------------------- the solve, driven through two poses --

    /**
     * A signal source standing one block north of the host, of the kind the world really has: it drives
     * the machine only while the machine's own north face is the world's north face. At a right angle
     * the machine's north face is against a different world block, and the source no longer reaches it.
     */
    private static final CarrierWorldSignals NORTH_SOURCE = (worldPos, worldDirection) ->
            worldPos.equals(WORLD.north()) && worldDirection == Direction.NORTH ? 15 : 0;

    /**
     * Driven at 0°, at 90° and back at 0°: the torch follows the world it is really against, and it comes
     * back when the structure turns back.
     */
    @Test
    @DisplayName("the in-carrier solve follows the pose, and a turn back restores the value it had")
    void theSolveFollowsThePoseAndTheValueComesBack() {
        CarrierNetworks.clear();
        recorder.writes.clear();
        Slot torch = carryTorch();

        // At 0 degrees the source is against the torch's input face, so the torch is put out after its
        // delay.
        solve(1L, flat(), NORTH_SOURCE);
        assertEquals(15, torch.power, "a torch does not follow its input in the same tick");
        assertEquals(1, CarrierNetworks.reportOf(CONTRAPTION).pendingFlips(), "its flip is waiting");

        solve(3L, flat(), NORTH_SOURCE);
        assertEquals(0, torch.power, "at 0 degrees the world drives the torch's input, so it goes out");
        assertEquals(List.of(CONTRAPTION), recorder.writes,
                "and the new value was re-published once");

        // A right angle, with the world where it was: the source no longer reaches the input face.
        CarrierNetworks.TickSummary atRightAngle = solve(5L, turned(90.0), NORTH_SOURCE);
        assertEquals(1, atRightAngle.carriers(), "the structure is still solved after a turn with no move");
        assertEquals(1, CarrierNetworks.reportOf(CONTRAPTION).pendingFlips(),
                "the turn made the torch stale again, so a new flip is waiting");

        solve(7L, turned(90.0), NORTH_SOURCE);
        assertEquals(15, torch.power, "turned away from the source, the torch is lit");
        assertEquals(List.of(CONTRAPTION, CONTRAPTION), recorder.writes,
                "and the change was re-published - this is the value coming back");

        // ...and back to where it started: off again, which is the value it had at 0 degrees.
        solve(9L, flat(), NORTH_SOURCE);
        solve(11L, flat(), NORTH_SOURCE);
        assertEquals(0, torch.power, "turning back restores what 0 degrees held, it does not latch at 15");
        assertEquals(3, recorder.writes.size(), "re-published again");
        assertEquals(6, CarrierNetworks.reportOf(CONTRAPTION).solves(),
                "the solve ran on every pass, turn or no turn: it is not gated on the pose");

        // Nothing about the position changed at any point.
        assertEquals(CarrierProjection.project(flat(), LOCAL),
                CarrierProjection.project(turned(90.0), LOCAL),
                "the whole sequence happened without the host moving a single block");
    }

    /**
     * The bridge's other direction: there is no per-carrier cache of the world read - a bridge is built
     * from a pose, so the same local side is read again at the world position that pose names.
     */
    @Test
    @DisplayName("the structure reads the world through the pose it has now, and turning back reads it again")
    void theWorldIsReadThroughTheCurrentPose() {
        carryTorch();
        Map<BlockPos, Integer> powered = new HashMap<>();
        powered.put(WORLD.north(), 15);

        assertEquals(new Read(15, new Query(new BlockPos(10, 64, 9), Direction.NORTH)),
                readInput(flat(), powered),
                "at 0 degrees the machine's north face is against the powered block");
        assertEquals(new Read(0, new Query(new BlockPos(9, 64, 10), Direction.WEST)),
                readInput(turned(90.0), powered),
                "at a right angle that face is somewhere else entirely, and asks a different question");
        assertEquals(new Read(15, new Query(new BlockPos(10, 64, 9), Direction.NORTH)),
                readInput(flat(), powered),
                "and turning back reads the same block again: nothing about the read is cached per carrier");

        // All three reads happened without the host block moving.
        assertEquals(CarrierProjection.project(flat(), LOCAL),
                CarrierProjection.project(turned(90.0), LOCAL));
    }

    /** One world read: what the component answered, and where the question was asked. */
    private record Read(int value, Query query) {
    }

    /** One world query as the bridge makes it: a world position and a world direction. */
    private record Query(BlockPos worldPos, Direction direction) {
    }

    /** The supply reaching the torch's input face, i.e. what the solve reads for a driven component. */
    private Read readInput(CarrierPose pose, Map<BlockPos, Integer> powered) {
        List<Query> asked = new ArrayList<>();
        CarrierSpace space = new CarrierSpace(CONTRAPTION, index, world, null,
                new CarrierWorldIo(pose, (worldPos, worldDirection) -> {
                    asked.add(new Query(worldPos, worldDirection));
                    return powered.getOrDefault(worldPos, 0);
                }));
        space.setQueryPosition(LOCAL);
        int value = space.externalSignal(Direction.NORTH);
        assertEquals(1, asked.size(), "one local side, one world read");
        return new Read(value, asked.get(0));
    }
}
