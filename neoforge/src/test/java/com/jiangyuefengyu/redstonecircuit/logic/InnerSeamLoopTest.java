package com.jiangyuefengyu.redstonecircuit.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.jiangyuefengyu.redstonecircuit.carrier.CarriedHost;
import com.jiangyuefengyu.redstonecircuit.carrier.CarriedHostIndex;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierId;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierNetworks;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierPose;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierProjection;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierStore;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierWorldBridge;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierWorldSignals;
import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.Slot;

import net.minecraft.core.BlockPos;
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
 * <b>P1.46: two independently physicalised blocks one world cell apart read each other's dust at full
 * strength in BOTH directions, so with no supply left the pair stands still at fifteen and only comes down
 * when the blocks are moved apart.</b>
 *
 * <h2>The author's report, and the arithmetic it makes</h2>
 * <em>"Two separately physicalised blocks, each with redstone dust inside, standing next to each other in
 * the world: with an external source both reach 15; take the source away and both are STILL 15; move the
 * blocks apart and they go to zero."</em>
 *
 * <p>A hop in this mod is paid by the <b>receiver</b> ({@code PowerSolver#afterHop}), so a seam read that behaves
 * must satisfy {@code A = B - 1} and {@code B = A - 1} at the same time - a pair of equations with <b>no
 * solution</b>, which is exactly why "no supply" has to decay. Standing at 15 instead means at least one
 * direction is a <b>plain copy</b>, i.e. a loop with a gain of exactly one: then every value from 0 to 15 is
 * a fixed point, and the pair holds whatever it happened to be carrying.
 *
 * <h2>Which half was the free one</h2>
 * A projection-sourced answer reaches the solve through <b>two</b> halves of one merged read
 * ({@code InnerRedstoneNetwork#worldSignalAt} = {@code max(readSignal, projectedSignalAt)}):
 *
 * <pre>
 *   projectedSignalAt -> CarrierProjection#emittedToward -> seamHop          (P1.45: charged)
 *   readSignal        -> SignalGetter#getSignal -> BlockStateSignalMixin
 *                        -> BlockSignalSources#select (source = WORLD_PROJECTION)
 *                        -> hopChargedByReader(.., readerIsRedstoneWire)      (P1.44: gated on BLOCK STATE)
 * </pre>
 *
 * <p>The second half is answered from the <b>same projection table</b> (a {@code ServerLevel} is a
 * {@code SignalGetter}), and P1.44's charge only fires when the block one step back along the query
 * direction <em>is</em> {@code minecraft:redstone_wire}. The reader of a seam is a carried host, whose world
 * cell is air - so that gate said "not a wire", the charge was skipped, and {@code max} kept the uncharged
 * copy over P1.45's charged one. Both directions were free, hence 15 standing still. This round gives that
 * half the same rule ({@code BlockSignalSources#projectionChargedByReader}, which is
 * {@code InnerRedstoneNetwork#seamHop} whenever the reader is one of ours).
 *
 * <h2>What this file pins, and in which colour</h2>
 * <table>
 *   <tr><th>test</th><th>on the P1.45 tree</th><th>after P1.46</th></tr>
 *   <tr><td>{@code theSeamPaysInBothFramesAndBothDirections}</td><td>compile-red (the rule does not
 *       exist) - and the same statements against P1.45's own {@code seamHop} are green, which is the point:
 *       only the vanilla half was free</td><td>green</td></tr>
 *   <tr><td>{@code theOldRuleAloneIsTheFreeCopyTheAuthorMeasured}</td><td>green (it asserts P1.44's
 *       number: the free fifteen the loop was built on)</td><td>green - the defect, reproduced in the same
 *       file as the rule that removes it</td></tr>
 *   <tr><td>{@code theTwoBlocksAreOneHopApartAndDecayWithNoSupply}</td><td>compile-red; with the API
 *       available and the old rule wired in it reads <b>15, 15</b> and holds it for ever</td>
 *       <td>green: 15 and 14, then a drain to zero within the bound</td></tr>
 *   <tr><td>{@code theSamePairApartHoldsNothingWithoutASupply}</td><td>green (no adjacency, nothing to
 *       read - the control)</td><td>green</td></tr>
 *   <tr><td>{@code theMixinChargesTheProjectionHalfToo}</td><td><b>runtime-red</b>: the strings do not
 *       exist on the P1.45 tree, so it fails without needing the new API at all</td><td>green</td></tr>
 * </table>
 *
 * <h2>Honest boundary</h2>
 * A {@code ServerLevel} cannot be built in a unit test, so the half under test (the mixin's projection
 * branch) cannot be <em>run</em> here. The scene below therefore models the merged read with production's
 * own two functions - {@code projectionChargedByReader} for the vanilla half and {@code seamHop} for the
 * explicit one - and the wiring test asserts that production really calls the first of them from the mixin's
 * {@code WORLD_PROJECTION} branch. Both legs are needed: without the wiring leg the scene would only be
 * asserting the fixture, and without the scene leg the wiring leg would only be asserting a string. The
 * field judgement stays the author's, as it does for every round in this family.
 */
class InnerSeamLoopTest {

    /** The structure whose dust is solved first - and the one the author's "block one" stands for. */
    private static final CarrierId X =
            CarrierId.subLevel(UUID.fromString("00000000-0000-0000-0000-00000000a146"));
    /** The independently physicalised structure standing next door - the seam. */
    private static final CarrierId Y =
            CarrierId.subLevel(UUID.fromString("00000000-0000-0000-0000-00000000b146"));

    private static final ResourceKey<Level> DIMENSION =
            ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse("redstonecircuit:p146"));

    /**
     * The anchor of the fixture's pose, which is a pure translation - so a structure's local neighbour is
     * its world neighbour, the geometry a Sable sub-level's plot has and the one that makes two blocks from
     * two different structures land on adjacent world cells ({@code InnerProjectionSupplyTest} uses it for
     * the same reason).
     */
    private static final BlockPos ANCHOR = pos(5, 64, 5);

    /** X's only host: its local coordinate, which is also the cell it is projected onto. */
    private static final BlockPos X_HOST = pos(5, 64, 5);

    /** Y's host, one local cell south of X's - i.e. exactly the cell X's south side reads. */
    private static final BlockPos Y_HOST_ADJACENT = pos(5, 64, 6);

    /** The same host, four cells away: the author's "moved the blocks apart". */
    private static final BlockPos Y_HOST_APART = pos(5, 64, 9);

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

    /** Counts the write-backs, so "the record was updated" and "the clients were told" are one fact. */
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

    /**
     * <b>Which carrier's pass is running - the reader every read in it belongs to.</b>
     *
     * <p>Production builds one bridge per carrier per pass, and asks that carrier's pose immediately before
     * it does so ({@code CarrierNetworks#worldIoOf}), and a {@code CarrierWorldSignals} lambda is never
     * asked who is calling. This fixture therefore takes the reader from the last pose it was asked for -
     * which is that same carrier, because no other structure's pose is asked until this pass is over. It is
     * the fixture's equivalent of {@code CarrierWorldIo#readerTypeOf}, and it is only used to decide the two
     * things production decides per carrier: whose own projection is refused (P1.41) and who pays the seam's
     * hop (P1.45/P1.46).
     */
    private CarrierId currentReader = X;

    /**
     * <b>The receiver the fixture's world half names, standing in for {@code CarrierWorldIo#readerTypeOf}.</b>
     *
     * <p>A lambda cannot be asked who is calling, so - exactly as {@code InnerProjectionSupplyTest} does -
     * the test tells it. Production looks the reader up in the carrier index at its own local coordinate.
     */
    private ComponentType namedReader = ComponentType.DUST;

    /** Every host the world half was handed, by local coordinate - "offered" is not the same as "refused". */
    private final List<Long> offered = new ArrayList<>();

    /** Every host the world half offered and P1.41's rule refused, by local coordinate. */
    private final List<Long> refused = new ArrayList<>();

    private static BlockPos pos(int x, int y, int z) {
        return new BlockPos(x, y, z);
    }

    @BeforeEach
    void reset() {
        CarrierNetworks.clear();
        recorder.writes.clear();
        offered.clear();
        refused.clear();
        currentReader = X;
        namedReader = ComponentType.DUST;
    }

    // ------------------------------------------------------------------ fixture --

    /** Picks a component up into a structure, the way the assembly callback does. */
    private Slot carry(CarrierId carrier, BlockPos local, ComponentType type) {
        Slot slot = new Slot(type);
        world.set(pos(local.getX() + 100, 64, local.getZ() + 100), slot);
        index.claim(carrier, local, pos(local.getX() + 100, 64, local.getZ() + 100), world);
        return index.at(carrier, local).slot();
    }

    /** Picks a component up with a debug supply planted on it - the one input a fixture needs. */
    private Slot carryPowered(CarrierId carrier, BlockPos local, ComponentType type, int injected) {
        Slot slot = carry(carrier, local, type);
        slot.injectedPower = injected;
        return slot;
    }

    private static CarrierPose poseAt(BlockPos local) {
        return CarrierPose.at(new Vector3d(local.getX(), local.getY(), local.getZ()));
    }

    /** The host of {@code carrier} whose local coordinate projects onto {@code worldPos}, or null. */
    @Nullable
    private CarrierProjection.ProjectedHost hostAt(CarrierId carrier, BlockPos worldPos) {
        CarrierPose pose = poseAt(ANCHOR);
        for (CarriedHost host : index.liveHostsOf(carrier)) {
            if (CarrierProjection.project(pose, host.localPos()).equals(worldPos)) {
                Slot slot = host.slot();
                return new CarrierProjection.ProjectedHost(carrier, host.localPos(), worldPos, pose, slot,
                        slot == null ? 0 : slot.power);
            }
        }
        return null;
    }

    /**
     * <b>The merged world read, as production builds it for a carrier.</b>
     *
     * <pre>
     *   worldSignalAt(level, pos, dir, reader)
     *     = max(readSignal(...), projectedSignalAt(..., reader))
     * </pre>
     *
     * <p>The {@code base} term is the vanilla half's other source: the block really standing at that
     * position. A carried host's world cell holds air, so an honest fixture passes 0 unless it is
     * deliberately modelling a block there.
     *
     * <p>The projection is offered by the very predicate production refuses the reader's own host with,
     * and each half is charged by the very function production charges it with - the explicit half by
     * {@link InnerRedstoneNetwork#seamHop} (P1.45), the vanilla half by
     * {@link BlockSignalSources#projectionChargedByReader} (P1.46). That is the whole point of the fixture:
     * the two functions are the two halves, and the merge is the {@code max} production performs.
     */
    private CarrierWorldBridge bridgeFor(int base) {
        CarrierPose pose = poseAt(ANCHOR);
        CarrierWorldSignals signals = (worldPos, worldDirection) -> {
            CarrierId reader = currentReader;
            ComponentType named = namedReader;
            CarrierProjection.ProjectedHost found = hostAt(X, worldPos);
            if (found == null) {
                // P1.45: the table holds every carrier's hosts, so when the reading carrier holds nothing
                // at this cell, another structure's host IS the seam P1.38 opened.
                found = hostAt(Y, worldPos);
            }
            if (found != null) {
                offered.add(found.localPos().asLong());
            }
            if (found == null || !InnerRedstoneNetwork.projectionReadableBy(found, reader)) {
                if (found != null) {
                    // The refused host's LOCAL coordinate is recorded, so a test can prove that a
                    // projection really was offered and really was refused instead of never being handed
                    // over at all. The coordinate, not the host: the live slot's power mutates.
                    refused.add(found.localPos().asLong());
                }
                return PowerSolver.clamp(base);
            }
            int emission = CarrierProjection.emittedToward(found, worldDirection, false, false);
            // The half P1.45 charged, and the half it did not - both against the same emission, so the
            // merged answer is the larger of two charges rather than of one charge and one raw copy.
            int projectedHalf = InnerRedstoneNetwork.seamHop(found, named, emission);
            int vanillaHalf = BlockSignalSources.projectionChargedByReader(found, emission, named, false);
            return PowerSolver.clamp(Math.max(base, Math.max(vanillaHalf, projectedHalf)));
        };
        return new CarrierWorldBridge(carrier -> {
            currentReader = carrier;
            return pose;
        }, signals);
    }

    /** One solve pass over the whole index, with the bridge open and no world block anywhere. */
    private CarrierNetworks.TickSummary solve(long tick) {
        return solve(tick, 0);
    }

    /** One solve pass with an explicit base signal for the vanilla half of every world read. */
    private CarrierNetworks.TickSummary solve(long tick, int base) {
        CarrierStore.Clock clock = new CarrierStore.Clock(tick, DIMENSION);
        CarrierStore.TickContext ctx = new CarrierStore.TickContext(clock, index, world, null, recorder,
                bridgeFor(base));
        return CarrierNetworks.tick(ctx, clock);
    }

    private int powerOf(CarrierId carrier, BlockPos local) {
        CarriedHost host = index.at(carrier, local);
        assertNotNull(host, "the structure is carrying a host at " + local);
        assertNotNull(host.slot(), "and it holds a component");
        return host.slot().power;
    }

    /** A projected host, built directly - for the pure-rule tests, which need no index. */
    private static CarrierProjection.ProjectedHost projected(CarrierId carrier, BlockPos local,
                                                             ComponentType type, int power) {
        Slot slot = new Slot(type);
        slot.power = power;
        return new CarrierProjection.ProjectedHost(carrier, local,
                pos(local.getX(), 64, local.getZ()), poseAt(ANCHOR), slot, power);
    }

    // ------------------------------------------------- 1. the rule, in both directions --

    @Test
    @DisplayName("P1.46: the seam pays the receiver's hop in BOTH frames and in BOTH directions")
    void theSeamPaysInBothFramesAndBothDirections() {
        CarrierProjection.ProjectedHost theirs = projected(Y, Y_HOST_ADJACENT, ComponentType.DUST, 15);
        CarrierProjection.ProjectedHost ours = projected(X, X_HOST, ComponentType.DUST, 15);

        // X reads Y, and Y reads X. The two equations the author's scene needs are A = B - 1 and
        // B = A - 1, and no supply can satisfy both - which is why "no supply" must decay.
        assertEquals(14, InnerRedstoneNetwork.seamHop(theirs, ComponentType.DUST, 15),
                "the explicit projected half is P1.45's rule: a dust that reads a projected dust pays one");
        assertEquals(14, BlockSignalSources.projectionChargedByReader(theirs, 15, ComponentType.DUST, false),
                "and the vanilla half - the one BlockStateSignalMixin answers from the same table - pays it "
                        + "too now, instead of handing the neighbour over at full strength");
        assertEquals(14, InnerRedstoneNetwork.seamHop(ours, ComponentType.DUST, 15),
                "the other direction, in the same frame");
        assertEquals(14, BlockSignalSources.projectionChargedByReader(ours, 15, ComponentType.DUST, false),
                "and the other direction in the mixin's frame: BOTH directions are charged, so the pair's "
                        + "only fixed point is zero");

        // The charge is the RECEIVER's hop cost, not a flat one per seam read.
        assertEquals(15, BlockSignalSources.projectionChargedByReader(theirs, 15,
                ComponentType.SUPERCONDUCTOR, false),
                "a superconductor receives for nothing - charging a flat one per seam read would break the "
                        + "one wire whose purpose is not to be charged");
        assertEquals(15, BlockSignalSources.projectionChargedByReader(theirs, 15, ComponentType.REPEATER,
                false),
                "and a repeater is not a wire, so it still reads the neighbour at full strength, as vanilla "
                        + "says about a repeater beside a wire");

        // P1.44's own naming path - the reader the world can only describe by its block state - is
        // unchanged, which is what keeps a real vanilla wire beside a carried host on fourteen.
        assertEquals(14, BlockSignalSources.projectionChargedByReader(theirs, 15, null, true),
                "a vanilla redstone wire reading a carried host still pays its own hop (P1.44's edge A)");
        assertEquals(15, BlockSignalSources.projectionChargedByReader(theirs, 15, null, false),
                "while a reader the world cannot name at all leaves the read as it was before this round");

        assertEquals(15, BlockSignalSources.projectionChargedByReader(null, 15, ComponentType.DUST, false),
                "air has nothing to be charged for");
        assertEquals(0, BlockSignalSources.projectionChargedByReader(theirs, 0, ComponentType.DUST, false),
                "nothing stays nothing: a seam that answers zero is not turned into a negative");
    }

    @Test
    @DisplayName("the old rule alone is the free copy the author measured: a carried reader was answered raw")
    void theOldRuleAloneIsTheFreeCopyTheAuthorMeasured() {
        // The defect, stated with P1.44's own function - this leg is green on both trees, and that is the
        // point: it reproduces the author's fifteen without needing any of this round's API, so the reader
        // can see the free copy and the charge side by side.
        Slot dust = new Slot(ComponentType.DUST);
        dust.power = 15;

        assertEquals(15, BlockSignalSources.hopChargedByReader(dust, 15, false),
                "P1.44 alone answers a reader that is not a vanilla redstone wire with the sender's own "
                        + "emission - and the reader of a seam is a carried host, whose world cell is air, "
                        + "so this is the uncharged copy that won Math.max in BOTH directions");
        assertEquals(14, BlockSignalSources.hopChargedByReader(dust, 15, true),
                "the same rule already charged a real wire, which is why only the seam was free");
        assertEquals(15, BlockSignalSources.hopChargedByReader(new Slot(ComponentType.SUPERCONDUCTOR), 15,
                        true),
                "P1.53: a lossless SENDER makes the whole hop free, so the vanilla wire beside a carried "
                        + "superconductor reads fifteen - the author's report, and what the superconductor's "
                        + "own zero cost has to mean for the wire outside it");
    }

    // ------------------------------------------------------- 2. the author's scene --

    @Test
    @DisplayName("P1.46: two physicalised blocks a cell apart are one hop apart - and drain when the supply goes")
    void theTwoBlocksAreOneHopApartAndDecayWithNoSupply() {
        // The scene, with EXACTLY ONE supply: a debug injection on X's dust - which is the only kind of
        // input a fixture may use (`injectedPower`; a hand-set `power` is drained by the structure's own
        // pass, see `CarrierLightNetwork#relax`). Y is fed by nothing at all: whatever it holds it can only
        // have read across the seam.
        carryPowered(X, X_HOST, ComponentType.DUST, 15);
        carry(Y, Y_HOST_ADJACENT, ComponentType.DUST);

        // TWO passes, so the answer does not depend on which structure happens to be solved first.
        solve(1L);
        solve(2L);

        assertTrue(offered.contains(Y_HOST_ADJACENT.asLong()),
                "precondition: X's read really was handed the other structure's host. Without this, 'one hop "
                        + "apart' and 'the fixture never offered anything' are the same observation - the trap "
                        + "P1.45 recorded. Offered: " + offered);
        assertTrue(offered.contains(X_HOST.asLong()),
                "and the other direction too: Y's read was handed X's host, so both directions are in play");
        assertEquals(15, powerOf(X, X_HOST), "the fed block holds its supply");
        assertEquals(14, powerOf(Y, Y_HOST_ADJACENT),
                "and the block across the seam is ONE step lower. This is the assertion the author's report "
                        + "turns into a number: with the free copy it read 15 and both blocks held fifteen - "
                        + "on the old rule this fails with 15 where 14 is required");

        // THE SUPPLY GOES AWAY. `-1` is Slot's "no injected supply at all" value, so nothing feeds either
        // block and the only thing that could hold a value is the other block - and A = B - 1 together with
        // B = A - 1 has no solution. Whatever the pair holds now must therefore come down.
        index.at(X, X_HOST).slot().injectedPower = -1;
        assertFalse(index.at(X, X_HOST).slot().hasInjectedPower(), "the one supply is really gone");

        int before = powerOf(X, X_HOST) + powerOf(Y, Y_HOST_ADJACENT);
        solve(3L);
        int after = powerOf(X, X_HOST) + powerOf(Y, Y_HOST_ADJACENT);
        assertTrue(after < before,
                "with no supply the pair must FADE, not stand still: " + before + " -> " + after + ". The "
                        + "fixed point the author measured is the one this round removes");

        for (long tick = 4L; tick <= 24L; tick++) {
            solve(tick);
        }
        assertEquals(0, powerOf(X, X_HOST), "the loop drains to zero on X's side");
        assertEquals(0, powerOf(Y, Y_HOST_ADJACENT), "and on Y's - the maximum falls by at least one per "
                + "pass, so fifteen cannot survive twenty of them");
        assertFalse(refused.contains(Y_HOST_ADJACENT.asLong()),
                "and the seam was CHARGED, not refused: Y is not the carrier whose solve is running, so its "
                        + "projection still answers (a refusal would give 0 at once, which is a different "
                        + "fact and would lose P1.38's ability)");
    }

    @Test
    @DisplayName("the control: the same pair with no adjacency and no supply holds nothing")
    void theSamePairApartHoldsNothingWithoutASupply() {
        // The author's second observation as a control: the stale fifteens are there and nothing feeds them,
        // and the only difference from the test above is that the two hosts are not neighbours - so there is
        // nothing to read across. This one is green before and after the round, which is what makes the
        // adjacency in the test above the thing under test rather than the fixture's arithmetic.
        carry(X, X_HOST, ComponentType.DUST).power = 15;
        carry(Y, Y_HOST_APART, ComponentType.DUST).power = 15;

        solve(1L);
        for (long tick = 2L; tick <= 6L; tick++) {
            solve(tick);
        }

        assertTrue(offered.isEmpty(),
                "precondition: neither read was handed a host at all - the two blocks are four cells apart, "
                        + "so no side of either asks the other's cell. Offered: " + offered);
        assertEquals(0, powerOf(X, X_HOST), "no neighbour and no supply: nothing holds the stale fifteen");
        assertEquals(0, powerOf(Y, Y_HOST_APART), "on either side");
    }

    // ------------------------------------------------------------------ 3. wiring --

    @Test
    @DisplayName("the mixin charges its projection-sourced answer with the seam's own rule")
    void theMixinChargesTheProjectionHalfToo() throws Exception {
        // This is the red light that does not need the new API: on the P1.45 tree none of these strings
        // exists, so the test fails at RUN time - and what it names is the missing half.
        String mixin = flat(
                "src/main/java/com/jiangyuefengyu/redstonecircuit/mixin/BlockStateSignalMixin.java");

        assertTrue(mixin.contains("read.source() == BlockSignalSources.Source.WORLD_PROJECTION"),
                "the mixin must know when its answer came from the projection table: that is the one source "
                        + "whose reader is not a block state of the level");
        assertTrue(mixin.contains("BlockSignalSources.projectionChargedByReader(read.host(), answer,"),
                "and it must ask the seam's charge for that source instead of the block-state gate alone");
        assertTrue(mixin.contains("InnerRedstoneNetwork.seamReaderAt(serverLevel, pos, direction)"),
                "with the reader found the way P1.45 finds it - the bridge names it inside a structure, and "
                        + "the asker is otherwise one step back along the query direction");

        // And P1.44's half is still exactly where it was, so the other two sources are untouched.
        assertTrue(mixin.contains("BlockSignalSources.hopChargedByReader("),
                "every other source still goes through the block-state rule");
        assertTrue(mixin.contains("serverLevel.getBlockState(reader).is(Blocks.REDSTONE_WIRE)"),
                "which is P1.44's one block read, still the only name a vanilla reader has");
        assertTrue(mixin.contains("!strong && answer > 0 && read.slot() != null "
                        + "&& read.slot().type.isWire()"),
                "behind the same gate as before: weak query, non-zero answer, a component that is a wire");
        assertTrue(flat("src/main/java/com/jiangyuefengyu/redstonecircuit/carrier/CarrierStore.java")
                        .contains("frame, read.source(), answer);"),
                "and the judgement line still reports the value the world really receives");

        String sources = flat(
                "src/main/java/com/jiangyuefengyu/redstonecircuit/logic/BlockSignalSources.java");
        assertTrue(sources.contains("return InnerRedstoneNetwork.seamHop(host, reader, answer);"),
                "the new rule is not a second arithmetic: it delegates to P1.45's seam hop, which delegates "
                        + "to PowerSolver#afterHop - the receiver pays, and only between two wires");
        assertTrue(sources.contains("return hopChargedByReader(host == null ? null : host.slot(), answer, "
                        + "readerIsRedstoneWire);"),
                "and when the world cannot name a reader, P1.44's own rule still answers - including the "
                        + "vanilla-wire case, so that edge is not lost");

        String network = flat(
                "src/main/java/com/jiangyuefengyu/redstonecircuit/logic/InnerRedstoneNetwork.java");
        assertTrue(network.contains("return seamHop(host, seamReaderAt(level, worldPos, worldDirection),"
                        + " answer);"),
                "while the explicit projected half P1.45 charged is one line unchanged - both halves of the "
                        + "merged read now charge the same hop");
    }

    /** One file's text, whitespace-flattened, so a wiring assertion is not a formatting assertion. */
    private static String flat(String relative) throws Exception {
        return java.nio.file.Files.readString(java.nio.file.Path.of(relative))
                .replace("\r\n", "\n").replaceAll("\\s+", " ");
    }
}
