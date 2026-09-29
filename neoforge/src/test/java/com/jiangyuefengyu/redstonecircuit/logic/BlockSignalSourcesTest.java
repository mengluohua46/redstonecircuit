package com.jiangyuefengyu.redstonecircuit.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import com.jiangyuefengyu.redstonecircuit.carrier.CarrierId;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierProjection;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierPose;
import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.Slot;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import org.joml.Vector3d;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>P1.38: which source answers a signal query while the mod's own solver is reading the world.</b>
 *
 * <h2>The defect these tests were written from</h2>
 * The author's own judge line, verbatim, from the log of a world with two blocks that were each
 * physicalised and then left alone, one of them activated with a lever beside it:
 *
 * <pre>
 *   carrierBridge=on (carrierFreezeWorldIo=false)  projected=2 host(s) worldQueries=0 (thisTick=0)
 *   structureFrames=2 structureQueries=460 via=structure-frame structureDeclines=0
 *   via=world-projection lastRead=structure_frame
 * </pre>
 *
 * <p>{@code worldQueries=0} is the whole of it. That counter is incremented in exactly one place - the
 * mixin, after it has found a host in the projection table - so zero means the projection source was
 * <b>never reached</b>, not that it answered zero. It never was reached because the mixin's first
 * statement returned zero whenever {@code InnerRedstoneNetwork#queryingWorld} had marked the level, and
 * <b>every</b> carrier-side read runs inside that window: the structure solve reads the world through
 * {@code CarrierWorldIo#signalInto -> CarrierWorldSignals -> Level#getSignal}. Two physicalised blocks
 * therefore could not read each other at all, whatever the projection table held.
 *
 * <h2>What is pinned here</h2>
 * The rule itself ({@link BlockSignalSources#select}), which is a pure function of what the three
 * sources hold and one boolean - so the two facts the defect consists of are offline assertions:
 * <b>the two carrier sources must answer while the world store is suppressed</b>, and <b>the world
 * store must not</b>. The mixin is asserted to have no suppression short-circuit of its own any more,
 * and the recording path is asserted to be the one that names the asker.
 */
class BlockSignalSourcesTest {

    /** One physicalised block: the author's build style, one sub-level per block. */
    private static final CarrierId STRUCTURE_A =
            CarrierId.subLevel(UUID.fromString("00000000-0000-0000-0000-00000000a2e1"));
    /** The block standing next to it, physicalised on its own: the second sub-level. */
    private static final CarrierId STRUCTURE_B =
            CarrierId.subLevel(UUID.fromString("00000000-0000-0000-0000-00000000b2e1"));

    private static final ResourceKey<Level> DIMENSION = ResourceKey.create(
            net.minecraft.core.registries.Registries.DIMENSION,
            ResourceLocation.parse("redstonecircuit:block_signal_sources_test"));

    /** Where A stands in the world; B stands one block to its east. This is the seam. */
    private static final BlockPos A_WORLD = new BlockPos(-35, -60, -32);
    private static final BlockPos B_WORLD = new BlockPos(-34, -60, -32);

    /** A's own coordinate inside A: the plot cell nonsense the author's log is full of. */
    private static final BlockPos A_LOCAL = new BlockPos(20481032, 128, 20483080);
    /** B's own coordinate inside B: one plot cell away, and never equal to a world position. */
    private static final BlockPos B_LOCAL = new BlockPos(20481032, 128, 20481032);

    private static Slot dust(int power) {
        Slot slot = new Slot(ComponentType.DUST);
        slot.power = power;
        return slot;
    }

    private static CarrierProjection.ProjectedHost host(CarrierId carrier, BlockPos local, BlockPos world,
                                                        Slot slot) {
        return new CarrierProjection.ProjectedHost(carrier, local, world,
                CarrierPose.at(new Vector3d(world.getX() + 0.5, world.getY() + 0.5, world.getZ() + 0.5)),
                slot, slot == null ? 0 : slot.power);
    }

    @BeforeEach
    void theFixtureIsTheAuthorsLayout() {
        assertEquals(1, A_WORLD.distManhattan(B_WORLD), "the two blocks stand face to face");
        assertEquals(2048, Math.abs(A_LOCAL.getZ() - B_LOCAL.getZ()),
                "their own cells differ by one plot cell, not by one block");
        assertFalse(A_LOCAL.equals(A_WORLD) || B_LOCAL.equals(B_WORLD),
                "a sub-level's own coordinate is never its world position");
    }

    // ---------------------------------------- 1. the defect: suppressed means dead --

    @Test
    @DisplayName("a projected host answers even while the world store is suppressed - the seam read")
    void aProjectedHostAnswersWhileSuppressed() {
        // B's own solve asks about the world position one block east of B's block. A stands exactly
        // there, projected, holding full power. That is the read that has to carry the signal.
        CarrierProjection.ProjectedHost aProjected = host(STRUCTURE_A, A_LOCAL, A_WORLD, dust(15));

        BlockSignalSources.Read read = BlockSignalSources.select(null, aProjected, null, false,
                true, Direction.WEST, false, false);

        assertEquals(BlockSignalSources.Source.WORLD_PROJECTION, read.source(),
                "the projection table is the source that answers a position A stands at");
        assertNotNull(read.slot(), "and it names A's component");
        assertEquals(15, read.answer(),
                "A's dust at full power: this is the value the seam was never given, because the "
                        + "suppression window used to answer zero to every query without looking");
        assertTrue(read.fromCarrier(), "and it is a carrier - not this solve's own output");
        assertTrue(read.projectionHit(), "the line can say the projection had a hit");
    }

    @Test
    @DisplayName("the world store still answers nothing while suppressed - its own output must not return")
    void theWorldStoreStaysQuietWhileSuppressed() {
        Slot own = dust(15);

        BlockSignalSources.Read suppressed = BlockSignalSources.select(own, null, null, false, true,
                Direction.EAST, false, false);
        assertEquals(BlockSignalSources.Source.NO_SIGNAL, suppressed.source(),
                "a stored component must not read its own output back: the fixed point would never settle");
        assertEquals(0, suppressed.answer());

        // The same query with the window open is answered exactly as it always was: suppression hides
        // the store, and nothing else about the branch changed.
        BlockSignalSources.Read open = BlockSignalSources.select(own, null, null, false, false,
                Direction.EAST, false, false);
        assertEquals(BlockSignalSources.Source.WORLD_STORE, open.source());
        assertEquals(15, open.answer(), "an ordinary host block in an ordinary level is untouched");
    }

    @Test
    @DisplayName("the structure frame answers its own host while suppressed - P1.36 is not given up")
    void theStructureFrameAnswersWhileSuppressed() {
        // The machine's own piston asks about the host block beside it, in the structure's own frame.
        CarrierProjection.ProjectedHost own = host(STRUCTURE_B, B_LOCAL, B_WORLD, dust(9));

        BlockSignalSources.Read read = BlockSignalSources.select(null, null, own, false, true,
                Direction.NORTH, false, false);

        assertEquals(BlockSignalSources.Source.STRUCTURE_FRAME, read.source(),
                "the inside-the-machine read keeps working inside the same window");
        assertEquals(9, read.answer(), "and it answers in the structure's own frame, without rotation");
    }

    @Test
    @DisplayName("a suppressed world with nothing at that position is still a zero, and says so")
    void anEmptyPositionIsStillASilentZero() {
        BlockSignalSources.Read read = BlockSignalSources.select(null, null, null, false, true,
                Direction.EAST, false, false);

        assertEquals(BlockSignalSources.Source.NO_SIGNAL, read.source());
        assertEquals(0, read.answer());
        assertFalse(read.fromCarrier(), "the line must not claim a carrier read that never happened");
        assertFalse(read.projectionHit(), "and it must not claim a projection hit either");
    }

    // ------------------------------------------------ 2. the P1.37 guard, kept --

    @Test
    @DisplayName("a coordinate refused as another structure's falls through to the world projection")
    void aDeclinedStructureFrameLeavesTheWorldProjectionToAnswer() {
        // B's own solve asks about its own local neighbour. That number is a plot cell of A's, so the
        // structure index holds A's host there and P1.37's ownership guard refuses it (the caller's
        // lookup answers null and reports the decline). The world projection is then the only source
        // left - and it is the one that can answer "what stands where".
        CarrierProjection.ProjectedHost aProjected = host(STRUCTURE_A, A_LOCAL, A_WORLD, dust(15));

        BlockSignalSources.Read read = BlockSignalSources.select(null, aProjected, null, true, true,
                Direction.WEST, false, false);

        assertEquals(BlockSignalSources.Source.WORLD_PROJECTION, read.source(),
                "A's block projected where it stands, in the world frame");
        assertEquals(15, read.answer(), "the P1.35 answer, restored by the guard");
        assertTrue(read.structureFrameDeclined(),
                "and the line can name the refusal, which is what structureDeclines= counts");
    }

    @Test
    @DisplayName("a refused structure frame with nothing projected is a silent zero that still says why")
    void aDeclinedStructureFrameWithNoProjectionReportsTheRefusal() {
        BlockSignalSources.Read read = BlockSignalSources.select(null, null, null, true, true,
                Direction.EAST, false, false);

        assertEquals(BlockSignalSources.Source.NO_SIGNAL, read.source());
        assertEquals(0, read.answer());
        assertTrue(read.structureFrameDeclined(),
                "declined is a fact about the coordinate, not about whether an answer followed - the "
                        + "author's judgement line has to be able to see a refusal that changed nothing");
    }

    @Test
    @DisplayName("an empty host in either carrier table answers zero rather than a stale value")
    void anEmptyCarrierHostAnswersZero() {
        CarrierProjection.ProjectedHost emptyProjected = host(STRUCTURE_A, A_LOCAL, A_WORLD, null);
        CarrierProjection.ProjectedHost emptyStructure = host(STRUCTURE_B, B_LOCAL, B_WORLD, null);

        assertEquals(0, BlockSignalSources.select(null, emptyProjected, null, false, true,
                Direction.EAST, false, false).answer(), "a projected host holding nothing");
        assertEquals(0, BlockSignalSources.select(null, null, emptyStructure, false, true,
                Direction.EAST, false, false).answer(), "a structure host holding nothing");
        assertEquals(BlockSignalSources.Source.NO_SIGNAL,
                BlockSignalSources.select(null, emptyProjected, null, false, true, Direction.EAST, false,
                        false).source());
    }

    // ------------------------------------------ 3. order, directions and strength --

    @Test
    @DisplayName("the structure frame is asked first, the projection second, the world store last")
    void theOrderIsStructureFrameThenProjectionThenWorldStore() {
        Slot worldSlot = dust(3);
        CarrierProjection.ProjectedHost projected = host(STRUCTURE_A, A_LOCAL, A_WORLD, dust(7));
        CarrierProjection.ProjectedHost inStructure = host(STRUCTURE_B, B_LOCAL, B_WORLD, dust(11));

        assertEquals(BlockSignalSources.Source.STRUCTURE_FRAME,
                BlockSignalSources.select(worldSlot, projected, inStructure, false, false, Direction.EAST,
                        false, false).source());
        assertEquals(BlockSignalSources.Source.WORLD_PROJECTION,
                BlockSignalSources.select(worldSlot, projected, null, false, false, Direction.EAST, false,
                        false).source());
        assertEquals(BlockSignalSources.Source.WORLD_STORE,
                BlockSignalSources.select(worldSlot, null, null, false, false, Direction.EAST, false,
                        false).source());
        assertEquals(BlockSignalSources.Source.NO_SIGNAL,
                BlockSignalSources.select(null, null, null, false, false, Direction.EAST, false, false)
                        .source());
    }

    @Test
    @DisplayName("a null direction is a silent zero, whatever the tables hold")
    void aNullDirectionAnswersNothing() {
        CarrierProjection.ProjectedHost projected = host(STRUCTURE_A, A_LOCAL, A_WORLD, dust(15));

        BlockSignalSources.Read read = BlockSignalSources.select(dust(15), projected, projected, true,
                false, null, false, false);

        assertEquals(BlockSignalSources.Source.NO_SIGNAL, read.source());
        assertEquals(0, read.answer());
        assertTrue(read.projectionHit(), "the hit is still reported, so the line can name it");
    }

    @Test
    @DisplayName("strong power still asks the neighbour question for the world store")
    void strongPowerStillConsultsTheNeighbourQuestionForTheWorldStore() {
        Slot lever = new Slot(ComponentType.LEVER);
        lever.power = 15;

        assertEquals(0, BlockSignalSources.select(lever, null, null, false, false, Direction.EAST, true,
                        true).answer(),
                "a lever has no strong power at all");
        assertEquals(15, BlockSignalSources.select(lever, null, null, false, false, Direction.EAST, false,
                        false).answer(),
                "while it does emit weak power everywhere - the weak branch is untouched");
    }

    // ------------------------------------------------------------- 4. wiring --

    @Test
    @DisplayName("the mixin has no suppression short-circuit of its own: the rule is the one that decides")
    void theMixinDoesNotShortCircuitOnSuppression() throws Exception {
        String mixin = flat(
                "src/main/java/com/jiangyuefengyu/redstonecircuit/mixin/BlockStateSignalMixin.java");

        assertFalse(mixin.contains("isSignalSuppressed"),
                "the old first statement returned zero to every query for the whole suppression window, "
                        + "which is what made the seam permanently dark - it must not come back");
        assertTrue(mixin.contains("BlockSignalSources.suppressWorldStore()"),
                "the world store is hidden through the one rule instead");
        assertTrue(mixin.contains("BlockSignalSources.select("),
                "and which answer wins is asked of the rule, so the rule is testable offline");
        assertTrue(mixin.contains("worldStoreSuppressed || store.isEmpty() ? null : store.slotAt(pos)"),
                "the store lookup itself is skipped while suppressed, which is the cost saving of the "
                        + "same decision");
    }

    @Test
    @DisplayName("the rule says which sources it hides, and the mixin keeps the projection first")
    void theRuleAndThePriorityAreWiredAsDocumented() throws Exception {
        String sources = flat(
                "src/main/java/com/jiangyuefengyu/redstonecircuit/logic/BlockSignalSources.java");
        assertTrue(sources.contains("Slot slot = worldStoreSuppressed ? null : worldSlot;"),
                "suppression hides the world store and nothing else");
        assertTrue(sources.contains("InnerRedstoneNetwork.isAnySignalSuppressed()"),
                "read through the one flag queryingWorld sets");

        String mixin = flat(
                "src/main/java/com/jiangyuefengyu/redstonecircuit/mixin/BlockStateSignalMixin.java");
        int projection = mixin.indexOf("projected = CarrierStore.projectedHostAt(serverLevel, pos)");
        int structure = mixin.indexOf("inStructure = CarrierStore.structureHostAt(serverLevel, pos)");
        assertTrue(projection > 0 && structure > projection,
                "the world position is asked before the structure's own coordinate, as it has been "
                        + "since P1.36");

        assertTrue(sources.contains("SignalReadTrace.suppressionBlocked()"),
                "a hidden window is counted, so the next log can say how often it happened");
    }

    /** One file's text, whitespace-flattened, so a wiring assertion is not a formatting assertion. */
    private static String flat(String relative) throws Exception {
        return java.nio.file.Files.readString(java.nio.file.Path.of(relative))
                .replace("\r\n", "\n").replaceAll("\\s+", " ");
    }
}
