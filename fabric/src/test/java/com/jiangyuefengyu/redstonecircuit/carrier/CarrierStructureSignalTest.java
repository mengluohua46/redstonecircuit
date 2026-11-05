package com.jiangyuefengyu.redstonecircuit.carrier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.Slot;
import com.jiangyuefengyu.redstonecircuit.logic.InnerRedstoneNetwork;
import com.jiangyuefengyu.redstonecircuit.logic.PowerSolver;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.SignalGetter;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The signal sources the structure itself is carrying count as an input to the solve inside it.
 *
 * <p>A carried lever is a real block of the structure and works, but a solve that only asks the <em>world</em> what is
 * on each side of a host block projects the neighbour's local coordinate through the pose and reads the air the machine
 * is passing over, so a lever one block away inside the machine is never asked. Pinned here: the lever reaches the
 * solve; the world half and the structure half are combined as the larger, and {@code carrierFreezeWorldIo} closes only
 * the world half; an unreadable structure costs its own half, warns once and is not asked again in the same pass; and
 * {@code carrierStructureSignals} off restores the withdrawn behaviour.
 */
class CarrierStructureSignalTest {

    private static final CarrierId CARRIER =
            CarrierId.contraption(UUID.fromString("00000000-0000-0000-0000-0000000019aa"));
    private static final ResourceKey<Level> DIMENSION =
            ResourceKey.create(Registries.DIMENSION,
                    ResourceLocation.parse("redstonecircuit:p19test"));

    /** The world store, as a map - the same stand-in the index's own tests use. */
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

    /**
     * The structure's own blocks: a signal per local coordinate, and a record of what was asked. It returns {@code null}
     * from {@code getBlockState} because a unit test has no block registry ({@code Blocks.AIR.defaultBlockState()}
     * throws before it returns), so a fake state is impossible; the shared rule treats a {@code null} state as "no state
     * to inspect" and continues with the signal getter's answer.
     */
    private static final class FakeStructure implements SignalGetter {

        private final Map<Long, Integer> signals = new HashMap<>();
        final List<BlockPos> asked = new ArrayList<>();
        final List<Direction> directions = new ArrayList<>();

        FakeStructure signal(BlockPos local, int strength) {
            signals.put(local.asLong(), strength);
            return this;
        }

        @Override
        public int getSignal(BlockPos pos, Direction direction) {
            asked.add(pos);
            directions.add(direction);
            Integer value = signals.get(pos.asLong());
            return value == null ? 0 : value;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return null;
        }

        @Override
        @Nullable
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return Fluids.EMPTY.defaultFluidState();
        }

        @Override
        public int getHeight() {
            return 384;
        }

        @Override
        public int getMinBuildHeight() {
            return -64;
        }
    }

    /** A structure view that cannot be read at all - the case that must degrade, not throw. */
    private static final class UnreadableStructure implements BlockGetter {

        @Override
        public BlockState getBlockState(BlockPos pos) {
            throw new UnsupportedOperationException("structure read at " + pos);
        }

        @Override
        @Nullable
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return Fluids.EMPTY.defaultFluidState();
        }

        @Override
        public int getHeight() {
            return 384;
        }

        @Override
        public int getMinBuildHeight() {
            return -64;
        }
    }

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

    /** Picks a component up into the structure, the way the assembly callback does. */
    private Slot carry(BlockPos local, BlockPos origin, ComponentType type) {
        Slot slot = new Slot(type);
        world.set(origin, slot);
        index.claim(CARRIER, local, origin, world);
        return index.at(CARRIER, local).slot();
    }

    private CarrierStore.Clock clock(long tick) {
        return new CarrierStore.Clock(tick, DIMENSION);
    }

    /**
     * The solve's "already solved this dimension at this clock value" guard is process-wide, so a test that reused a
     * tick would silently assert nothing.
     */
    @org.junit.jupiter.api.BeforeEach
    void reset() {
        CarrierNetworks.clear();
        recorder.writes.clear();
    }

    /** The whole driver, with a structure view and no world bridge at all. */
    private CarrierNetworks.TickSummary solveWithStructure(long tick, CarrierAccess access) {
        CarrierStore.Clock clock = clock(tick);
        CarrierStore.TickContext ctx = new CarrierStore.TickContext(clock, index, world, access,
                recorder, null);
        return CarrierNetworks.tick(ctx, clock);
    }

    // ------------------------------------------------- the lever reaches the inside --

    @Test
    @DisplayName("a lever the structure is carrying feeds the redstone inside it")
    void theStructuresOwnLeverFeedsTheInside() {
        CarrierNetworks.clear();
        recorder.writes.clear();
        Slot wire = carry(pos(1, 0, 0), pos(10, 64, 10), ComponentType.DUST);
        FakeStructure structure = new FakeStructure()
                // The machine's own lever, one block south of the host (+Z is south), emitting full
                // strength towards it.
                .signal(pos(1, 0, 1), 15);

        CarrierNetworks.TickSummary summary = solveWithStructure(1L, id -> structure);

        assertEquals(15, wire.power,
                "the inside solve read the machine's OWN lever");
        assertEquals(15, index.at(CARRIER, pos(1, 0, 0)).slot().power,
                "and the answer is written back to the record the client and the renderer read");
        assertEquals(1, summary.solvedChanges(), "which is a real change");
        assertEquals(List.of(CARRIER), recorder.writes, "told to the clients once, as the carrier sync already does");
    }

    @Test
    @DisplayName("the input is the larger of the world's answer and the structure's own answer")
    void bothHalvesAreCombinedAsTheLarger() {
        carry(pos(1, 0, 2), pos(10, 64, 10), ComponentType.DUST);
        FakeStructure structure = new FakeStructure().signal(pos(2, 0, 2), 15);
        // The world, through the bridge, offers seven on the opposite side only.
        CarrierWorldIo io = new CarrierWorldIo(CarrierPose.at(new Vector3d(10.0, 64.0, 10.0)),
                (worldPos, worldDirection) -> worldDirection == Direction.WEST ? 7 : 0);

        CarrierSpace space = new CarrierSpace(CARRIER, index, world, id -> structure, io, true);
        space.setQueryPosition(pos(1, 0, 2));

        assertEquals(15, space.externalSignal(Direction.EAST),
                "the machine's own lever wins on its side");
        assertEquals(7, space.externalSignal(Direction.WEST),
                "and the world still feeds the other side - neither half replaces the other");
        assertEquals(15, space.externalSignal(), "the omnidirectional answer is the larger of the two");
    }

    @Test
    @DisplayName("a closed world bridge does not close the structure's own input")
    void aFrozenWorldDoesNotFreezeTheMachinesOwnLever() {
        carry(pos(1, 0, 0), pos(10, 64, 10), ComponentType.DUST);
        FakeStructure structure = new FakeStructure().signal(pos(1, 0, 1), 11);

        // No io at all: this is carrierFreezeWorldIo = true, the world boundary.
        CarrierNetworks.TickSummary summary = solveWithStructure(1L, id -> structure);

        assertEquals(11, index.at(CARRIER, pos(1, 0, 0)).slot().power,
                "a lever the machine carries travels with it, so the world boundary is not its business");
        assertEquals(1, summary.solvedChanges());
    }

    // --------------------------------------------- one rule, and the local neighbour --

    @Test
    @DisplayName("the structure half asks the local neighbour, exactly as the world half asks its own")
    void theStructureHalfAsksTheLocalNeighbour() {
        carry(pos(1, 0, 2), pos(10, 64, 10), ComponentType.DUST);
        FakeStructure structure = new FakeStructure().signal(pos(2, 0, 2), 6);
        CarrierSpace space = new CarrierSpace(CARRIER, index, world, id -> structure, null, true);
        space.setQueryPosition(pos(1, 0, 2));

        assertEquals(6, space.externalSignal(Direction.EAST));
        assertEquals(List.of(pos(2, 0, 2)), structure.asked,
                "the machine's own neighbour coordinate - not a world position, and not the host itself");
        assertEquals(List.of(Direction.EAST), structure.directions,
                "vanilla's convention, unchanged: the query points from the block asking towards the "
                        + "neighbour, so the same rule serves both halves");

        // The arithmetic half of the shared rule, which needs no block registry to pin: a vanilla wire
        // hands on one less, everything else is passed through and clamped.
        assertEquals(14, InnerRedstoneNetwork.neighbourSignal(15, true),
                "a neighbouring vanilla wire is charged one hop, exactly as vanilla charges two wires");
        assertEquals(15, InnerRedstoneNetwork.neighbourSignal(15, false),
                "anything else is passed on unchanged");
        assertEquals(0, InnerRedstoneNetwork.neighbourSignal(0, true), "and nothing stays nothing");
        assertEquals(0, InnerRedstoneNetwork.neighbourSignal(-4, false), "clamped, not negative");
        assertEquals(15, InnerRedstoneNetwork.neighbourSignal(99, false), "and clamped at fifteen");
    }

    // -------------------------------------------------------- degrading, once and safely --

    @Test
    @DisplayName("an unreadable structure drops only its own half, and warns once however often it fails")
    void anUnreadableStructureDropsOnlyItsHalf() {
        carry(pos(1, 0, 2), pos(10, 64, 10), ComponentType.DUST);
        int warningsBefore = CarrierSpace.structureReadWarnings();

        for (int attempt = 0; attempt < 5; attempt++) {
            CarrierSpace space = new CarrierSpace(CARRIER, index, world,
                    id -> new UnreadableStructure(),
                    new CarrierWorldIo(CarrierPose.at(new Vector3d(10.0, 64.0, 10.0)),
                            (worldPos, worldDirection) -> worldDirection == Direction.EAST ? 9 : 0),
                    true);
            space.setQueryPosition(pos(1, 0, 2));

            assertEquals(9, space.externalSignal(Direction.EAST),
                    "the world half is untouched by the structure's failure");
            assertEquals(0, space.externalSignal(Direction.WEST),
                    "and a side the world says nothing about is still zero - no exception, no guess");
        }

        assertTrue(CarrierSpace.structureReadWarnings() - warningsBefore <= 1,
                "five unreadable structures, at most one warning: the line is a signal, not a flood");
    }

    @Test
    @DisplayName("a structure view that throws on resolution degrades the same way")
    void aStructureViewThatCannotBeResolvedDegrades() {
        carry(pos(1, 0, 2), pos(10, 64, 10), ComponentType.DUST);
        CarrierSpace space = new CarrierSpace(CARRIER, index, world, id -> {
            throw new IllegalStateException("the structure is gone");
        }, new CarrierWorldIo(CarrierPose.at(new Vector3d(10.0, 64.0, 10.0)),
                (worldPos, worldDirection) -> worldDirection == Direction.SOUTH ? 4 : 0), true);
        space.setQueryPosition(pos(1, 0, 2));

        assertEquals(4, space.externalSignal(Direction.SOUTH),
                "resolving the view is where the failure is, and it costs the structure half only");
        assertEquals(0, space.externalSignal(Direction.NORTH),
                "and the world half still answers on every side after the failure - an ordinary zero");
        assertTrue(space.isBridged(), "the bridge itself is not what failed");
    }

    @Test
    @DisplayName("a machine with no view at all answers the world half, and is not a failure")
    void noStructureViewIsNotAFailure() {
        carry(pos(1, 0, 2), pos(10, 64, 10), ComponentType.DUST);
        int warningsBefore = CarrierSpace.structureReadWarnings();
        CarrierSpace space = new CarrierSpace(CARRIER, index, world, null, null, true);
        space.setQueryPosition(pos(1, 0, 2));

        assertEquals(0, space.externalSignal(), "nothing to read, nothing to say");
        assertEquals(warningsBefore, CarrierSpace.structureReadWarnings(),
                "a jar without the compat half is the ordinary case, not a fault to warn about");
    }

    // -------------------------------------------------------------- the escape hatch --

    @Test
    @DisplayName("with the switch off the machine's own lever reaches nothing")
    void theSwitchOffRestoresTheWithdrawnBehaviour() {
        carry(pos(1, 0, 0), pos(10, 64, 10), ComponentType.DUST);
        FakeStructure structure = new FakeStructure().signal(pos(1, 0, 1), 15);

        CarrierSpace off = new CarrierSpace(CARRIER, index, world, id -> structure, null, false);
        off.setQueryPosition(pos(1, 0, 0));
        assertEquals(0, off.externalSignal(Direction.SOUTH),
                "carrierStructureSignals=false must answer exactly zero");
        assertEquals(0, off.externalSignal(), "on every side, and in the omnidirectional form");

        CarrierSpace on = new CarrierSpace(CARRIER, index, world, id -> structure, null, true);
        on.setQueryPosition(pos(1, 0, 0));
        assertEquals(15, on.externalSignal(Direction.SOUTH),
                "and the same space with the switch on reads it - the switch is the whole difference");
    }

    /**
     * <b>The read the rule asks for: the structure's own lossless wire
     * block is the other half of one machine, not an external supply to it.</b>
     *
     * <p>One component lives inside a block the structure carries; the cell above that block holds a
     * superconducting wire <em>block</em> - a block state of the structure's own grid, which the carrier index
     * never records (only a host that holds a component is a host). Refusing it costs
     * the machine its own surface: a wire the machine placed on top of itself could no longer feed what it was
     * laid on. What is wrong is not the pair but the <em>copy</em>: read as its stock strength the wire hands over a
     * value no source supplies any more, at gain exactly one because a superconductor pays nothing for a hop.
     *
     * <p>So the read asks the other question - what the wire block's <b>own sides</b> push into it, with <b>this
     * host's cell</b> left out ({@code supplyAround(wireCell, queryPos, ...)}). A value is
     * {@code max(own supply, neighbours)}, so leaving out the reader's own term can only lower the answer: a real
     * source on the wire's far side still arrives at full strength, and a pair that supplies nothing but itself
     * comes down to zero.
     *
     * <p>A unit test has no block registry ({@code Blocks.AIR.defaultBlockState()} throws before it returns - see
     * {@code FakeStructure}'s note, which is why it answers {@code null}), so the wire's own state cannot be built
     * here and this scene cannot be settled numerically without a level. What is pinned is that the cut is gone,
     * that what replaces it is the same pure rule the run side asks, and which cell that rule leaves out.
     */
    @Test
    @DisplayName("the structure half reads the wire block's own supply with this host's cell left out")
    void theStructureHalfAsksTheWireBlocksOwnSupply() throws Exception {
        String space = flatten("src/main/java/com/jiangyuefengyu/redstonecircuit/carrier/CarrierSpace.java");
        assertFalse(space.contains("the neighbour is a lossless superconducting wire of the structure's own blocks"),
                "the cut is gone - a lossless wire of the machine's own blocks is read again, because a "
                        + "machine on its own surface is exactly that pair");
        assertTrue(space.contains("InnerRedstoneNetwork.structureHoldsLosslessWire(blocks, neighbour) && losslessPartnerHere()"),
                "what replaces the cut is the pair question, and it is asked of the SAME pure function the run "
                        + "side asks - one rule, so the two directions cannot drift apart");
        assertTrue(space.contains("InnerRedstoneNetwork.losslessPairInOneStructure(self == null ? null : self.type, ComponentType.SUPERCONDUCTOR, carrier, carrier)"),
                "this host's own component kind meets the wire block's: only two lossless halves are a pair, so a "
                        + "torch or a repeater inside this host keeps the plain read and the inside still lights");
        assertTrue(space.contains("InnerRedstoneNetwork.supplyAround(wireCell, queryPos, (side, direction) -> {"),
                "and the read is around the wire block with THIS host's coordinate excluded - queryPos is the "
                        + "reader, and it is the only cell left out");
        assertTrue(space.indexOf("structureHoldsLosslessWire(blocks, neighbour) && losslessPartnerHere()")
                        < space.indexOf("InnerRedstoneNetwork.readSignal(blocks, neighbour, direction)"),
                "asked BEFORE the stock read: a supply already taken cannot be taken back, and the lossless copy "
                        + "is what would win the max");
        assertTrue(space.contains("the neighbour is one of this carrier's own hosts"),
                "the older guard for a host of the index is still there and still first: a slot's value arrives "
                        + "through the inner relationship, which charges the hop");
    }

    @Test
    @DisplayName("only the lossless wire is a pair: dust of the machine's own blocks still feeds it")
    void thePairIsTheLosslessWiresAlone() throws Exception {
        String network = flatten("src/main/java/com/jiangyuefengyu/redstonecircuit/logic/InnerRedstoneNetwork.java");
        assertTrue(network.contains("state != null && SuperconductingWireBlock.isSuperconductor(state)"),
                "the rule asks for OUR superconducting wire specifically - not for 'a wire block is there' and "
                        + "not for a vanilla wire: ordinary dust pays a hop, so a dust pair's equations are "
                        + "contradictory and it comes down by itself - pairing it would change a working "
                        + "machine's answer for no reason at all");
        assertFalse(network.contains("Blocks.REDSTONE_WIRE) && state.getValue"),
                "nothing here reaches for the vanilla wire block's POWER");

        // The arithmetic the rule rests on, restated where the rule lives: only a lossless pair would copy.
        assertEquals(15, PowerSolver.afterHop(ComponentType.SUPERCONDUCTOR, ComponentType.SUPERCONDUCTOR, 15),
                "two superconductors copy each other unchanged - gain exactly one, which is why the dead value "
                        + "stood there for ever");
        assertEquals(14, PowerSolver.afterHop(ComponentType.DUST, ComponentType.SUPERCONDUCTOR, 15),
                "a dust receiving pays its own hop, so a dust pair must come down by itself");
        assertEquals(15, PowerSolver.afterHop(ComponentType.SUPERCONDUCTOR, ComponentType.DUST, 15),
                "while a lossless receiver of a dust is free - the receiver is what pays");
    }

    /** One file's text, whitespace-flattened, so a wiring assertion is not a formatting assertion. */
    private static String flatten(String relative) throws Exception {
        return java.nio.file.Files.readString(java.nio.file.Path.of(relative))
                .replace("\r\n", "\n").replaceAll("\\s+", " ");
    }
}

