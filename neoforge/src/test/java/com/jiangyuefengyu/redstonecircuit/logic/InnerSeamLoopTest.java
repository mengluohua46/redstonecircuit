package com.jiangyuefengyu.redstonecircuit.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.jiangyuefengyu.redstonecircuit.carrier.CarriedHost;
import com.jiangyuefengyu.redstonecircuit.carrier.CarriedHostIndex;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierId;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierNetworks;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierPose;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierProjection;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierSpace;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierStore;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierWorldBridge;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierWorldIo;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierWorldSignals;
import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.Slot;

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
 * The seam between two independently physicalised blocks one world cell apart: each reads the other's dust
 * at full strength in BOTH directions, so with no supply left the pair stands still at fifteen instead of
 * decaying.
 *
 * <p>A hop is paid by the <b>receiver</b>, so a seam read that behaves has to satisfy A = B - 1 and
 * B = A - 1 at the same time - a pair of equations with <b>no solution</b>, which is exactly why "no supply"
 * has to decay. A pair standing at fifteen means at least one direction is a plain copy with a gain of one,
 * and then every value from 0 to 15 is a fixed point.
 *
 * <p>Which half was free: a projection-sourced answer reaches the solve through two halves of one merged
 * read - the explicit projected-signal half, and the vanilla readSignal half, whose charge is gated on the
 * block one step back along the query being a redstone wire. The reader of a seam is a carried host, whose
 * world cell is air, so that gate said "not a wire" and the merge kept the uncharged copy. Both halves now
 * pay the same hop.
 *
 * <p>A {@code ServerLevel} cannot be built in a unit test, so the scene models the merged read with
 * production's own two functions, and a separate leg asserts that production really calls them from the
 * mixin's projection branch.
 *
 * <p>The same arithmetic is the one thing that <b>cannot</b> be fixed by a charge, and a superconducting wire
 * is where that shows: a hop is paid by the receiver, so two superconductors on either side of a seam read each
 * other at gain exactly one - and then the pair's two equations have every value as a solution, so a strength
 * nothing supplies any more holds itself up for ever (the self-lock). Sections 3 and 4 are
 * that case: the latch, the rule that removes it (a lossless seam is asked what the world pushes <em>into</em>
 * the neighbour, never what the neighbour holds), and the reason the dust path keeps its own rule.
 */
class InnerSeamLoopTest {

    /** The structure whose dust is solved first - the "block one" of the scene. */
    private static final CarrierId X =
            CarrierId.subLevel(UUID.fromString("00000000-0000-0000-0000-00000000a146"));
    /** The independently physicalised structure standing next door - the seam. */
    private static final CarrierId Y =
            CarrierId.subLevel(UUID.fromString("00000000-0000-0000-0000-00000000b146"));

    private static final ResourceKey<Level> DIMENSION =
            ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse("redstonecircuit:p146"));

    /**
     * The anchor of the fixture's pose, which is a pure translation - so a structure's local neighbour is its
     * world neighbour, the geometry a Sable sub-level's plot has, and the one that makes two blocks from two
     * different structures land on adjacent world cells.
     */
    private static final BlockPos ANCHOR = pos(5, 64, 5);

    /** X's only host: its local coordinate, which is also the cell it is projected onto. */
    private static final BlockPos X_HOST = pos(5, 64, 5);

    /** Y's host, one local cell south of X's - i.e. exactly the cell X's south side reads. */
    private static final BlockPos Y_HOST_ADJACENT = pos(5, 64, 6);

    /** The same host, four cells away: the blocks moved apart. */
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
     * Which carrier's pass is running - the reader every read in it belongs to, standing in for the reader
     * identity production looks up. It only decides the two things production decides per carrier:
     * whose own projection is refused, and who pays the seam's hop.
     */
    private CarrierId currentReader = X;

    /**
     * The receiver the fixture's world half names. A lambda cannot be asked who is calling, so the test tells
     * it; production looks the reader up in the carrier index at its own local coordinate.
     */
    private ComponentType namedReader = ComponentType.DUST;

    /** Every host the world half was handed, by local coordinate - "offered" is not the same as "refused". */
    private final List<Long> offered = new ArrayList<>();

    /** Every host the world half offered and the rule refused, by local coordinate. */
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
        runSupply = 0;
        carriedSupplyBesideRuns = false;
        worldSources.clear();
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
     * The merged world read, as production builds it for a carrier: the larger of the vanilla half and the
     * explicit projected-signal half, each charged by the very function production charges it with.
     *
     * <p>The {@code base} term is the block really standing at that position - a carried host's world cell
     * holds air, so an honest fixture passes 0 unless it is modelling a block there.
     */
    private CarrierWorldBridge bridgeFor(int base) {
        CarrierPose pose = poseAt(ANCHOR);
        CarrierWorldSignals signals = (worldPos, worldDirection) -> {
            CarrierId reader = currentReader;
            ComponentType named = namedReader;
            CarrierProjection.ProjectedHost found = hostAt(X, worldPos);
            if (found == null) {
                // The table holds every carrier's hosts, so when the reading carrier holds nothing at this
                // cell, another structure's host IS the seam.
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
            // Both halves are charged against the same emission, so the merged answer is the larger of two
            // charges rather than of one charge and one raw copy.
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
    @DisplayName("the seam pays the receiver's hop in BOTH frames and in BOTH directions")
    void theSeamPaysInBothFramesAndBothDirections() {
        CarrierProjection.ProjectedHost theirs = projected(Y, Y_HOST_ADJACENT, ComponentType.DUST, 15);
        CarrierProjection.ProjectedHost ours = projected(X, X_HOST, ComponentType.DUST, 15);

        // X reads Y and Y reads X: A = B - 1 together with B = A - 1 has no solution, which is why
        // "no supply" must decay.
        assertEquals(14, InnerRedstoneNetwork.seamHop(theirs, ComponentType.DUST, 15),
                "the explicit projected half: a dust that reads a projected dust pays one");
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

        // The naming path for a reader the world can only describe by its block state is unchanged, which is
        // what keeps a real vanilla wire beside a carried host on fourteen.
        assertEquals(14, BlockSignalSources.projectionChargedByReader(theirs, 15, null, true),
                "a vanilla redstone wire reading a carried host still pays its own hop");
        assertEquals(15, BlockSignalSources.projectionChargedByReader(theirs, 15, null, false),
                "while a reader the world cannot name at all leaves the read unchanged");

        assertEquals(15, BlockSignalSources.projectionChargedByReader(null, 15, ComponentType.DUST, false),
                "air has nothing to be charged for");
        assertEquals(0, BlockSignalSources.projectionChargedByReader(theirs, 0, ComponentType.DUST, false),
                "nothing stays nothing: a seam that answers zero is not turned into a negative");
    }

    @Test
    @DisplayName("the old rule alone is the free copy: a carried reader was answered raw")
    void theOldRuleAloneIsTheFreeCopyTheAuthorMeasured() {
        // The free copy, stated with the older rule alone: it reproduces the standing fifteen without
        // needing the new API, so the free copy and the charge can be read side by side.
        Slot dust = new Slot(ComponentType.DUST);
        dust.power = 15;

        assertEquals(15, BlockSignalSources.hopChargedByReader(dust, 15, false),
                "the older rule alone answers a reader that is not a vanilla redstone wire with the sender's "
                        + "own emission - and the reader of a seam is a carried host, whose world cell is air, "
                        + "so this is the uncharged copy that won Math.max in BOTH directions");
        assertEquals(14, BlockSignalSources.hopChargedByReader(dust, 15, true),
                "the same rule already charged a real wire, which is why only the seam was free");
        assertEquals(15, BlockSignalSources.hopChargedByReader(new Slot(ComponentType.SUPERCONDUCTOR), 15,
                        true),
                "a lossless SENDER makes the whole hop free, so the vanilla wire beside a carried "
                        + "superconductor reads fifteen, which is what the superconductor's "
                        + "own zero cost has to mean for the wire outside it");
    }

    // ---------------------------------------------------------------- 2. the scene --

    @Test
    @DisplayName("two physicalised blocks a cell apart are one hop apart - and drain when the supply goes")
    void theTwoBlocksAreOneHopApartAndDecayWithNoSupply() {
        // The scene, with EXACTLY ONE supply: a debug injection on X's dust, the only kind of input a fixture
        // may use (a hand-set `power` is drained by the structure's own pass). Y is fed by nothing at all:
        // whatever it holds it can only have read across the seam.
        carryPowered(X, X_HOST, ComponentType.DUST, 15);
        carry(Y, Y_HOST_ADJACENT, ComponentType.DUST);

        // TWO passes, so the answer does not depend on which structure happens to be solved first.
        solve(1L);
        solve(2L);

        assertTrue(offered.contains(Y_HOST_ADJACENT.asLong()),
                "precondition: X's read really was handed the other structure's host. Without this, 'one hop "
                        + "apart' and 'the fixture never offered anything' are the same observation - a trap "
                        + "a fixture that offers nothing would set. Offered: " + offered);
        assertTrue(offered.contains(X_HOST.asLong()),
                "and the other direction too: Y's read was handed X's host, so both directions are in play");
        assertEquals(15, powerOf(X, X_HOST), "the fed block holds its supply");
        assertEquals(14, powerOf(Y, Y_HOST_ADJACENT),
                "and the block across the seam is ONE step lower: with the free copy it reads 15 and both "
                        + "blocks hold fifteen, while a charged hop makes it 14");

        // The supply goes away: `-1` is Slot's "no injected supply at all" value, so only the other block
        // could hold a value - and the pair's two equations have no solution, so whatever it holds must come
        // down.
        index.at(X, X_HOST).slot().injectedPower = -1;
        assertFalse(index.at(X, X_HOST).slot().hasInjectedPower(), "the one supply is really gone");

        int before = powerOf(X, X_HOST) + powerOf(Y, Y_HOST_ADJACENT);
        solve(3L);
        int after = powerOf(X, X_HOST) + powerOf(Y, Y_HOST_ADJACENT);
        assertTrue(after < before,
                "with no supply the pair must FADE, not stand still: " + before + " -> " + after + ". The "
                        + "fixed point is the one the charge removes");

        for (long tick = 4L; tick <= 24L; tick++) {
            solve(tick);
        }
        assertEquals(0, powerOf(X, X_HOST), "the loop drains to zero on X's side");
        assertEquals(0, powerOf(Y, Y_HOST_ADJACENT), "and on Y's - the maximum falls by at least one per "
                + "pass, so fifteen cannot survive twenty of them");
        assertFalse(refused.contains(Y_HOST_ADJACENT.asLong()),
                "and the seam was CHARGED, not refused: Y is not the carrier whose solve is running, so its "
                        + "projection still answers (a refusal would give 0 at once, which is a different "
                        + "fact and would lose the seam's own ability to answer)");
    }

    @Test
    @DisplayName("the control: the same pair with no adjacency and no supply holds nothing")
    void theSamePairApartHoldsNothingWithoutASupply() {
        // The control: the stale fifteens are there and nothing feeds them, and the only difference from the
        // test above is that the two hosts are not neighbours - so there is nothing to read across, which is
        // what makes the adjacency in the test above the thing under test rather than the fixture's arithmetic.
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

    // ------------------------------------- 3. the seam that crosses into the world --

    /**
     * One lossless pair's two halves, settled the way production settles them.
     *
     * <p>{@code world} stands in an ordinary host block of the level and {@code carried} in one a machine is
     * physicalising; the two blocks are one world cell apart, and both wires charge nothing for a hop, so
     * neither side's read pays anything. {@code worldSupply} is what the world pushes into the world half
     * from anywhere but the seam (a lever), {@code carriedSupply} what the machine's own blocks push into the
     * carried half.
     *
     * <p>{@code readOwnSupply} is the whole of the fix's decision, and production makes it with
     * {@link PowerSolver#losslessSeam}: a <b>lossless</b> seam answers what the world pushes into the
     * neighbour, and every other seam answers the strength the neighbour holds. The control method below runs
     * the value read, so the latch and the rule can be read side by side instead of one being assumed.
     */
    private static final class Pair {

        private final Slot world = new Slot(ComponentType.SUPERCONDUCTOR);
        private final Slot carried = new Slot(ComponentType.SUPERCONDUCTOR);

        private int worldSupply;
        private int carriedSupply;

        /** Both wires at the strength a solved pair would be holding when the source went away. */
        Pair standing() {
            world.power = PowerSolver.MAX_POWER;
            carried.power = PowerSolver.MAX_POWER;
            return this;
        }

        /**
         * One tick, as two passes in each direction: each half restarts its wire from zero and settles it, the
         * machine's half first and then the world's, and then the other way round, so the answer cannot depend
         * on which half this tick happens to visit first.
         */
        void tick(boolean readOwnSupply) {
            for (int pass = 0; pass < 2; pass++) {
                // The machine's half. Its supply across the seam is either the world half's own supply (the
                // fix) or the strength it holds, charged by the reader's hop - which is nothing here.
                int fromWorld = readOwnSupply
                        ? acrossTheLosslessSeamOfAPair()
                        : BlockSignalSources.hopChargedByReader(world, PowerSolver.clamp(world.power), false);
                carried.power = Math.max(carriedSupply, fromWorld);

                // The world's half. It has no view inside a machine, so the carried strength is the only answer
                // it can take - which is sound exactly while that strength is what the machine's own solve
                // makes it, and that is what the fix makes of it.
                int fromCarried = InnerRedstoneNetwork.seamHop(
                        projected(Y, Y_HOST_ADJACENT, carried.type, carried.power),
                        ComponentType.SUPERCONDUCTOR, PowerSolver.clamp(carried.power));
                world.power = Math.max(worldSupply, fromCarried);
            }
        }

        /** The rule the fix adds, over the pair's own two types. */
        private int acrossTheLosslessSeamOfAPair() {
            return PowerSolver.losslessSeam(carried.type, world.type)
                    ? worldSupply
                    : BlockSignalSources.hopChargedByReader(world, PowerSolver.clamp(world.power), false);
        }
    }

    @Test
    @DisplayName("a lossless seam read as a strength is the latch: fifteen holds fifteen with no source")
    void aLosslessSeamReadAsAStrengthHoldsItselfUp() {
        // The report, as arithmetic. A hop is paid by the receiver, and neither superconductor charges
        // one, so each half reads the other's strength at gain exactly one: A = B and B = A, whose fixed point
        // is every value from 0 to 15. This is the PRE-FIX behaviour, kept as a fact so the assertion that the
        // fix turns green below has something to be red against.
        Pair pair = new Pair().standing();

        assertTrue(PowerSolver.losslessSeam(ComponentType.SUPERCONDUCTOR, ComponentType.SUPERCONDUCTOR),
                "precondition: two superconductors make the lossless seam this is about");
        assertEquals(15, BlockSignalSources.hopChargedByReader(pair.world, 15, false),
                "and the machine reads the world block's superconductor at full strength - it pays nothing, "
                        + "because a hop is the RECEIVER's cost and a superconductor charges none");
        assertEquals(15, InnerRedstoneNetwork.seamHop(
                        projected(Y, Y_HOST_ADJACENT, ComponentType.SUPERCONDUCTOR, 15),
                        ComponentType.SUPERCONDUCTOR, 15),
                "with the other direction charged the same way: gain one in BOTH frames, which is the whole of "
                        + "the latch");

        for (int tick = 0; tick < 10; tick++) {
            pair.tick(false);
        }
        assertEquals(15, pair.world.power,
                "nothing supplies this pair any more, and it still stands at fifteen: the source's removal "
                        + "cannot reach a value both halves keep restating to each other");
        assertEquals(15, pair.carried.power, "on both sides - this is the self-lock");
    }

    @Test
    @DisplayName("a lossless seam reads what the world pushes into the neighbour, so the pair drains")
    void aLosslessSeamReadsTheNeighboursOwnSupply() {
        // The fix, over the same pair: a lossless seam is not a supply of the neighbour's strength but a
        // question about the neighbour's OWN supply, read around it and with the reading machine's own hosts
        // refused. Supplied, both halves light; the moment the source goes, the pair has nothing to restate
        // and drains in one pass.
        Pair supplied = new Pair();
        supplied.worldSupply = PowerSolver.MAX_POWER;
        supplied.tick(true);
        assertEquals(15, supplied.world.power, "the lever lights the world half");
        assertEquals(15, supplied.carried.power,
                "and the machine's half across a lossless seam, at full strength - the seam still costs "
                        + "nothing, which is the one wire that must not be charged");

        supplied.worldSupply = 0;
        supplied.tick(true);
        assertEquals(0, supplied.world.power, "the source is gone and the world half follows it down");
        assertEquals(0, supplied.carried.power,
                "and so does the machine's half - this is the assertion that is RED before the fix, where the "
                        + "seam answers the world block's strength (fifteen) instead of its own supply (none)");

        // ...and it stays down: a value that only decays after N passes is the same bug one tick later.
        for (int tick = 0; tick < 10; tick++) {
            supplied.tick(true);
            assertEquals(0, supplied.world.power, "still nothing on the world side, pass " + tick);
            assertEquals(0, supplied.carried.power, "still nothing on the machine's side, pass " + tick);
        }

        // The asymmetry the fix rests on, asserted: a source INSIDE the machine still reaches the world,
        // because the world half is the side that has no view inside a machine and so must keep reading the
        // carried strength.
        Pair fromInsideTheMachine = new Pair();
        fromInsideTheMachine.carriedSupply = PowerSolver.MAX_POWER;
        fromInsideTheMachine.tick(true);
        assertEquals(15, fromInsideTheMachine.carried.power, "the machine's own lever lights its half");
        assertEquals(15, fromInsideTheMachine.world.power,
                "and the world half with no supply of its own - it reads the carried strength, which is the "
                        + "only answer it is able to take");
    }

    @Test
    @DisplayName("a seam that costs a hop needs none of this: the dust pair cannot hold itself up")
    void aSeamThatCostsAHopKeepsItsOwnRule() {
        // The other half of the gate: ordinary dust charges one in both frames, so the pair's two equations
        // are A = B - 1 and B = A - 1 - contradictory, and no value survives. That is why the dust path keeps
        // reading the neighbour's strength and nothing there changes.
        assertFalse(PowerSolver.losslessSeam(ComponentType.DUST, ComponentType.DUST),
                "two dusts are not a lossless seam: each pays one, so the pair's equations contradict");
        assertFalse(PowerSolver.losslessSeam(ComponentType.SUPERCONDUCTOR, ComponentType.DUST),
                "nor is a superconductor reading a dust - the seam's KIND is a fact about BOTH sides, and the "
                        + "dust's own read of the superconductor pays the step that makes the pair solvable");
        assertEquals(14, PowerSolver.afterHop(ComponentType.DUST, ComponentType.DUST, 15),
                "the dust pair pays one in this frame");
        assertEquals(14, PowerSolver.afterHop(ComponentType.DUST, ComponentType.SUPERCONDUCTOR, 15),
                "and one in the other, so neither direction can copy a value back to its source");
        assertEquals(15, PowerSolver.afterHop(ComponentType.SUPERCONDUCTOR, ComponentType.DUST, 15),
                "while a lossless READER of a dust is free in its own direction, because the receiver is what "
                        + "pays - which is why the latch needs both sides lossless, and why the dust path keeps "
                        + "reading the neighbour's strength: substituting a supply there would answer the "
                        + "dust's own source instead of the strength the wire beside it actually carries");
    }

    @Test
    @DisplayName("the bridge asks the lossless seam's question, and only there")
    void theBridgeAsksTheLosslessSeamsQuestion() throws Exception {
        String space = flat(
                "src/main/java/com/jiangyuefengyu/redstonecircuit/carrier/CarrierSpace.java");
        assertTrue(space.contains("bridge.signalIntoOwnFrame(queryPos, direction)"),
                "the side's world half is still the bridge's own read - one road into the world, not two");
        assertTrue(space.contains("return bridge.signalIntoOwnFrame(wireCell, direction);"),
                "and the pair's own-supply read goes through that same bridge one side at a time: a frame read "
                        + "that reached the world any other way - a bare CarrierWorldSignals, or a read naming "
                        + "no reader - would be the second road this pins against");

        String bridge = flat(
                "src/main/java/com/jiangyuefengyu/redstonecircuit/carrier/CarrierWorldIo.java");
        assertTrue(bridge.contains("InnerRedstoneNetwork.worldSignalIntoOwnFrame("),
                "and that read now asks the seam-aware question, so the bridge and the world-side solve "
                        + "cannot disagree about which seam is which");
        assertFalse(bridge.contains(
                        "InnerRedstoneNetwork.worldSignalAt(level, worldPos, worldDirection, reader)"),
                "and no longer takes a neighbour's own strength for what a lossless seam supplies");

        String network = flat(
                "src/main/java/com/jiangyuefengyu/redstonecircuit/logic/InnerRedstoneNetwork.java");
        assertTrue(network.contains(
                        "if (!seamIsLossless(reader, worldHostTypeAt(level, worldPos, owner))) {"),
                "the world half keeps the value read for every seam that pays a hop - and asks about the "
                        + "neighbour only once the reader's own type has qualified, so a dust or a repeater "
                        + "costs no lookup; that gate is now the one function both halves ask");
        assertTrue(network.contains("return worldSupplyAt(level, worldPos, owner);"),
                "and asks for the neighbour's own supply only on the lossless one");
        assertTrue(network.contains("worldSignalAt(level, neighbour, direction, owner)"),
                "the supply is read AROUND the neighbour with the reader's own hosts refused, which is what "
                        + "keeps the machine out of its own answer");
        assertTrue(network.contains("|| host.slot().type.isWire()"),
                "while a host of ours holding anything but a wire is read as the source it is: only a wire "
                        + "reads all six sides and can hand our own answer back");

        String solver = flat(
                "src/main/java/com/jiangyuefengyu/redstonecircuit/logic/PowerSolver.java");
        assertTrue(solver.contains("public static boolean losslessSeam("),
                "and the rule itself is one pure function of the two component types");
    }

    // ------------------------------------------------------------------ 4. wiring --

    @Test
    @DisplayName("the mixin charges its projection-sourced answer with the seam's own rule")
    void theMixinChargesTheProjectionHalfToo() throws Exception {
        // A wiring leg, asserted on the source text: it names the mixin's projection branch and the rule that
        // branch has to call.
        String mixin = flat(
                "src/main/java/com/jiangyuefengyu/redstonecircuit/mixin/BlockStateSignalMixin.java");

        assertTrue(mixin.contains("read.source() == BlockSignalSources.Source.WORLD_PROJECTION"),
                "the mixin must know when its answer came from the projection table: that is the one source "
                        + "whose reader is not a block state of the level");
        assertTrue(mixin.contains("BlockSignalSources.projectionChargedByReader(read.host(), answer,"),
                "and it must ask the seam's charge for that source instead of the block-state gate alone");
        assertTrue(mixin.contains("InnerRedstoneNetwork.seamReaderAt(serverLevel, pos, direction)"),
                "with the reader found as production finds it: the bridge names it inside a structure, and "
                        + "the asker is otherwise one step back along the query direction");

        // The block-state half is untouched, so the other two sources are unchanged.
        assertTrue(mixin.contains("BlockSignalSources.hopChargedByReader("),
                "every other source still goes through the block-state rule");
        assertTrue(mixin.contains("serverLevel.getBlockState(reader).is(Blocks.REDSTONE_WIRE)"),
                "the one block read, and the only name a vanilla reader has");
        assertTrue(mixin.contains("!strong && answer > 0 && read.slot() != null "
                        + "&& read.slot().type.isWire()"),
                "behind the same gate as before: weak query, non-zero answer, a component that is a wire");
        assertTrue(flat("src/main/java/com/jiangyuefengyu/redstonecircuit/carrier/CarrierStore.java")
                        .contains("frame, read.source(), answer);"),
                "and the judgement line still reports the value the world really receives");

        String sources = flat(
                "src/main/java/com/jiangyuefengyu/redstonecircuit/logic/BlockSignalSources.java");
        assertTrue(sources.contains("return InnerRedstoneNetwork.seamHop(host, reader, answer);"),
                "the rule is not a second arithmetic: it delegates to the seam hop, which delegates "
                        + "to PowerSolver#afterHop - the receiver pays, and only between two wires");
        assertTrue(sources.contains("return hopChargedByReader(host == null ? null : host.slot(), answer, "
                        + "readerIsRedstoneWire);"),
                "and when the world cannot name a reader, the block-state rule still answers - including the "
                        + "vanilla-wire case");

        String network = flat(
                "src/main/java/com/jiangyuefengyu/redstonecircuit/logic/InnerRedstoneNetwork.java");
        assertTrue(network.contains("return seamHop(host, seamReaderAt(level, worldPos, worldDirection),"
                        + " answer);"),
                "while the explicit projected half is one line unchanged - both halves of the "
                        + "merged read now charge the same hop");
    }

    // ---------------------------------- 5. one ring, two and three structures --

    /**
     * The third structure of the ring: with two, a latch can hide behind a pair; with three there is no pair
     * left to blame.
     */
    private static final CarrierId Z =
            CarrierId.subLevel(UUID.fromString("00000000-0000-0000-0000-00000000c146"));

    /**
     * The world cell a local coordinate of the fixture's structure projects onto.
     *
     * <p><b>Every world position in these scenes has to come through this method.</b> {@code poseAt(ANCHOR)}
     * is a translation, so {@code project} maps a local coordinate to a world one by a constant whole-block
     * offset - and the fixture's level is keyed by world coordinates while {@code carry} takes local ones.
     * Mixing the two frames is invisible in a scene whose only world cell is a host that a carried host
     * also stands on, which is why the earlier scenes never had to care; a level host standing beside a
     * carried one does, and getting it wrong connects nothing at all (the level half then answers zero and
     * every precondition here fails).
     */
    private static BlockPos worldOf(BlockPos local) {
        return CarrierProjection.project(poseAt(ANCHOR), local);
    }

    /** The world host block the whole ring is wired through - an ordinary host of the level. */
    private static final BlockPos RING_HOST = worldOf(pos(5, 64, 5));

    /**
     * The three carried hosts, one per structure, west, east and north of the world host: the classic "two
     * structures sharing one world host" of the report, plus a third so no pair can be blamed.
     */
    private static final BlockPos A_LOCAL = pos(4, 64, 5);
    private static final BlockPos B_LOCAL = pos(6, 64, 5);
    private static final BlockPos C_LOCAL = pos(5, 64, 4);

    /** The superconducting run the level laid against that host block - "W's ring". */
    private static final BlockPos RING_NEAR = worldOf(pos(5, 64, 6));
    private static final BlockPos RING_FAR = worldOf(pos(5, 64, 7));

    /** One world-side run of superconducting wire, as the level holds it: its cells and what is written there. */
    private static final class Ring {

        final List<BlockPos> cells;
        int power;

        Ring(BlockPos... cells) {
            this.cells = List.of(cells);
        }
    }

    /** Every run of the fixture's level, by the cells it covers. */
    private final Map<Long, Ring> rings = new HashMap<>();

    /**
     * What feeds the runs of these scenes from anywhere but a host block of the level: a lever, standing in for
     * the one real source a run needs. Zero - nothing at all - in every scene but the one that asks for it.
     */
    private int runSupply;

    /**
     * Whether the fixture's world query at a run cell answers what a <b>machine</b> standing there emits, the
     * way the level's own query does through the projection table ({@code BlockStateSignalMixin}).
     *
     * <p>Off by default, so a scene that has no machine beside its run keeps the plain "nothing feeds a run
     * from anywhere but the host block" reading the earlier scenes were built on.
     */
    private boolean carriedSupplyBesideRuns;

    /**
     * An <b>ordinary block of the level</b> that pushes a signal into a carried host - the lever a player switches
     * off - as a strength per world cell. Not a host of ours: nothing in the world store stands there, so this is
     * exactly the case the level seeds nothing from (see {@code InnerRedstoneNetwork#settle}, which visits only
     * positions the store holds a component at).
     */
    private final Map<Long, Integer> worldSources = new HashMap<>();

    private Ring layRing(BlockPos... cells) {
        Ring ring = new Ring(cells);
        for (BlockPos cell : cells) {
            rings.put(cell.asLong(), ring);
        }
        return ring;
    }

    /**
     * The fixture's level as the merged solve asks about it. The <b>tables</b> are the fixture's (which run
     * covers which cells, what the store holds, which structure projects where); every <b>rule</b> applied to
     * them is production's own, because {@code collectUnion} and {@code settleUnion} are.
     */
    private final class FixtureSeam implements SeamLookup {

        @Override
        public boolean runAt(BlockPos cell) {
            return rings.containsKey(cell.asLong());
        }

        @Override
        public List<BlockPos> runCells(BlockPos origin) {
            Ring ring = rings.get(origin.asLong());
            return ring == null ? List.of() : ring.cells;
        }

        @Override
        @Nullable
        public Slot slotAt(BlockPos pos) {
            return world.get(pos);
        }

        @Override
        public int supplyAt(BlockPos cell) {
            // The host block beside the run is the injected supply on RING_HOST in the earlier scenes; here the
            // one real source is {@link #runSupply}, and - when the scene asks for it - the world query
            // production makes at that cell, which a machine standing there answers.
            int best = runSupply;
            if (!carriedSupplyBesideRuns) {
                return PowerSolver.clamp(best);
            }
            for (Direction direction : Direction.values()) {
                CarrierProjection.ProjectedHost host = hostAnywhere(cell.relative(direction));
                if (host == null || (host.slot() != null && host.slot().type.isWire())) {
                    // Production's rule: a wire another structure carries beside the run is a NODE of
                    // the merged solve, never a supply for it - the same refusal the world store already gets
                    // for a wire inside a block of the level.
                    continue;
                }
                int emission = CarrierProjection.emittedToward(host, direction, false, false);
                // InnerRedstoneNetwork#readSignal, then the seam's own charge - which the mixin applies and
                // which the reader pays: a run is a superconductor and pays nothing.
                best = Math.max(best, InnerRedstoneNetwork.seamHop(host, ComponentType.SUPERCONDUCTOR, emission));
            }
            return PowerSolver.clamp(best);
        }

        @Override
        public void writeRun(List<BlockPos> cells, int strength) {
            for (BlockPos cell : cells) {
                Ring ring = rings.get(cell.asLong());
                if (ring != null) {
                    ring.power = strength;
                }
            }
        }

        @Override
        public List<BlockPos> componentAt(BlockPos seed) {
            return worldComponent(seed);
        }

        @Override
        @Nullable
        public CarrierProjection.ProjectedHost carriedAt(BlockPos worldPos) {
            return hostAnywhere(worldPos);
        }

        /**
         * The same structure as a frame the merged solve can reach inside: a {@link CarrierSpace} over the
         * fixture's index and store, with the world read pinned to this carrier - which is what production's
         * {@code CarrierStore#contextOf} wiring does with a level.
         */
        @Override
        @Nullable
        public SeamLookup.CarriedFrame frameOf(CarrierId carrier) {
            CarrierPose pose = poseAt(ANCHOR);
            CarrierWorldIo io = new CarrierWorldIo(pose, pinnedTo(carrier), carrier, null);
            return new SeamLookup.CarriedFrame(carrier, pose,
                    new CarrierSpace(carrier, index, world, null, io, false));
        }
    }

    /** Production's component walk over the fixture's store, with production's own connection rule. */
    private List<BlockPos> worldComponent(BlockPos seed) {
        List<BlockPos> out = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        queue.add(seed);
        seen.add(seed.asLong());
        while (!queue.isEmpty()) {
            BlockPos at = queue.poll();
            Slot slot = world.get(at);
            if (slot == null) {
                continue;
            }
            out.add(at);
            for (Direction direction : Direction.values()) {
                BlockPos neighbour = at.relative(direction);
                Slot other = world.get(neighbour);
                if (other == null || !talks(slot, other, direction)) {
                    continue;
                }
                if (seen.add(neighbour.asLong())) {
                    queue.add(neighbour);
                }
            }
        }
        return out;
    }

    /** {@code InnerRedstoneNetwork}'s "do these two talk across this side" rule, over stored components. */
    private static boolean talks(Slot self, Slot other, Direction direction) {
        return PowerSolver.readsFrom(self, direction)
                        && PowerSolver.emitsToward(other, direction.getOpposite())
                || PowerSolver.readsFrom(other, direction.getOpposite())
                        && PowerSolver.emitsToward(self, direction);
    }

    /** The host any structure of the fixture projects onto this world cell, or null. */
    @Nullable
    private CarrierProjection.ProjectedHost hostAnywhere(BlockPos worldPos) {
        CarrierProjection.ProjectedHost found = hostAt(X, worldPos);
        if (found == null) {
            found = hostAt(Y, worldPos);
        }
        if (found == null) {
            found = hostAt(Z, worldPos);
        }
        return found;
    }

    /** The one logic component a structure carries: each structure of these scenes carries exactly one. */
    @Nullable
    private Slot readerSlotOf(CarrierId carrier) {
        for (CarriedHost host : index.liveHostsOf(carrier)) {
            Slot slot = host.slot();
            if (slot != null && CarrierLightNetwork.isLogic(slot.type)) {
                return slot;
            }
        }
        return null;
    }

    /** What the level's own block at that cell answers: a host of ours, or a cell of a run. */
    private int blockAnswer(BlockPos worldPos, Direction worldDirection, @Nullable ComponentType reader) {
        Slot slot = world.get(worldPos);
        if (slot != null) {
            return PowerSolver.signalAnswer(slot, worldDirection.getOpposite(), false, true, false);
        }
        Ring ring = rings.get(worldPos.asLong());
        if (ring == null) {
            // A block of the level that is not one of ours answers its own strength, as a lever does; there is no
            // hop to charge, because a hop is only charged between two of our own wires.
            Integer source = worldSources.get(worldPos.asLong());
            return source == null ? 0 : source;
        }
        // A run is <b>not</b> vanilla dust: {@code InnerRedstoneNetwork#neighbourSignal} steps one only for a
        // {@code Blocks.REDSTONE_WIRE}, and a superconductor's own hop costs nothing - so the read hands the
        // run's strength over unchanged and a lossless reader copies it (a fact of the read, not of this fix).
        int stepped = InnerRedstoneNetwork.neighbourSignal(ring.power, false);
        return reader != null && reader.isWire()
                ? PowerSolver.afterHop(reader, ComponentType.SUPERCONDUCTOR, stepped)
                : stepped;
    }

    /** What another structure projects onto this world cell, charged by the seam's own hop. */
    private int projectionAnswer(BlockPos worldPos, Direction worldDirection, @Nullable ComponentType reader,
                                 @Nullable CarrierId asking) {
        CarrierProjection.ProjectedHost found = hostAnywhere(worldPos);
        if (!InnerRedstoneNetwork.projectionReadableBy(found, asking)) {
            return 0;
        }
        int emission = CarrierProjection.emittedToward(found, worldDirection, false, false);
        return InnerRedstoneNetwork.seamHop(found, reader, emission);
    }

    /** {@code worldSignalAt}: the larger of the level's own block and another structure's projection. */
    private int worldRead(BlockPos worldPos, Direction worldDirection, @Nullable ComponentType reader,
                          @Nullable CarrierId asking) {
        return PowerSolver.clamp(Math.max(blockAnswer(worldPos, worldDirection, reader),
                projectionAnswer(worldPos, worldDirection, reader, asking)));
    }

    /** {@code worldSupplyAt}: what the world pushes INTO that block, read around it rather than off it. */
    private int supplyAround(BlockPos worldPos, @Nullable ComponentType reader, @Nullable CarrierId asking) {
        int best = 0;
        for (Direction direction : Direction.values()) {
            BlockPos neighbour = worldPos.relative(direction);
            best = Math.max(best, worldRead(neighbour, direction, reader, asking));
            best = Math.max(best, ownSourceAt(neighbour, direction, reader, asking));
            if (best >= PowerSolver.MAX_POWER) {
                return PowerSolver.MAX_POWER;
            }
        }
        return best;
    }

    /** {@code ourSourceAt}: a host of the asking structure beside that block, as a source rather than a wire. */
    private int ownSourceAt(BlockPos worldPos, Direction worldDirection, @Nullable ComponentType reader,
                            @Nullable CarrierId asking) {
        if (asking == null) {
            return 0;
        }
        CarrierProjection.ProjectedHost found = hostAt(asking, worldPos);
        if (found == null || found.slot() == null || found.slot().type.isWire()) {
            return 0;
        }
        int emission = CarrierProjection.emittedToward(found, worldDirection, false, false);
        return InnerRedstoneNetwork.seamHop(found, reader, emission);
    }

    /**
     * The bridge the ring scenes read the world through: the seam's own rule, chosen the way production
     * chooses it ({@code worldSignalIntoOwnFrame}) - a lossless seam asks what the world pushes <em>into</em>
     * the neighbour, every other seam reads the strength the neighbour holds.
     */
    private CarrierWorldBridge ringBridge() {
        CarrierPose pose = poseAt(ANCHOR);
        return new CarrierWorldBridge(carrier -> {
            currentReader = carrier;
            return pose;
        }, ringSignals());
    }

    /**
     * The same reads without a bridge around them: the lambda a <b>joined frame</b> needs, because the world
     * side now solves a carried network through that structure's own environment, and that environment is
     * built here with the fixture's tables. The reader is pinned for the duration of the read, so a network
     * being solved as part of the merge is named as the reader rather than whichever carrier ran last.
     */
    private CarrierWorldSignals pinnedTo(CarrierId carrier) {
        return (worldPos, worldDirection) -> {
            CarrierId was = currentReader;
            currentReader = carrier;
            try {
                return ringSignals().externalSignal(worldPos, worldDirection);
            } finally {
                currentReader = was;
            }
        };
    }

    /** The seam rules the fixture's world reads apply - production's rules over the fixture's tables. */
    private CarrierWorldSignals ringSignals() {
        return (worldPos, worldDirection) -> {
            CarrierId asking = currentReader;
            Slot mine = readerSlotOf(asking);
            ComponentType reader = mine == null ? null : mine.type;
            if (InnerRedstoneNetwork.seamIsLossless(reader, neighbourTypeAt(worldPos, asking))) {
                return PowerSolver.clamp(supplyAround(worldPos, reader, asking));
            }
            return worldRead(worldPos, worldDirection, reader, asking);
        };
    }

    /**
     * The kind of the component standing at a world cell, as the read has to see it: the fixture's store first,
     * and - where the store can never answer, because a carried host is drawn over air - the projection of
     * another structure's host. The reader's own projected hosts are refused, as every other read refuses them.
     */
    @Nullable
    private ComponentType neighbourTypeAt(BlockPos worldPos, @Nullable CarrierId asking) {
        Slot here = world.get(worldPos);
        if (here != null) {
            return here.type;
        }

        CarrierProjection.ProjectedHost host = hostAnywhere(worldPos);
        return host == null || host.slot() == null || !InnerRedstoneNetwork.projectionReadableBy(host, asking)
                ? null
                : host.slot().type;
    }

    /** The world side of one solve, over the fixture: production's node rules, the fixture's tables. */
    private final class RingEnv implements UnionEnvironment {

        private Map<BlockPos, PowerEnvironment.Node> owned = Map.of();
        private BlockPos query = BlockPos.ZERO;
        private boolean queryingWire;

        @Override
        public void owning(Map<BlockPos, PowerEnvironment.Node> cells) {
            this.owned = cells;
        }

        @Override
        public void setQuery(BlockPos pos, @Nullable ComponentType reader) {
            this.query = pos;
            this.queryingWire = reader != null && reader.isWire();
        }

        @Override
        public void setQueryPosition(BlockPos pos) {
            this.query = pos;
            this.queryingWire = false;
        }

        @Override
        @Nullable
        public PowerEnvironment.Node nodeAt(int x, int y, int z) {
            BlockPos pos = new BlockPos(x, y, z);
            PowerEnvironment.Node run = owned.get(pos);
            return run == null ? SlotNode.of(world.get(pos)) : run;
        }

        @Override
        public int externalSignal() {
            int best = 0;
            for (Direction direction : Direction.values()) {
                best = Math.max(best, externalSignal(direction));
                if (best >= PowerSolver.MAX_POWER) {
                    return PowerSolver.MAX_POWER;
                }
            }
            return best;
        }

        @Override
        public int externalSignal(Direction direction) {
            BlockPos neighbour = query.relative(direction);
            PowerEnvironment.Node run = owned.get(neighbour);
            if (run != null) {
                // A run this solve owns is an output of it, so a wire takes it as a neighbouring node while
                // everything else reads it as a supply - production's own reading of the same case.
                return queryingWire ? 0 : run.power();
            }
            Slot mine = world.get(query);
            return worldRead(neighbour, direction, mine == null ? null : mine.type, null);
        }

        @Override
        public boolean isRedstoneConductor(int x, int y, int z) {
            return false;
        }
    }

    /** One tick of the fixture's world side, as {@code recompute} would do it for one seed. */
    private void worldTickFor(BlockPos seed) {
        FixtureSeam seam = new FixtureSeam();
        List<BlockPos> component = seam.componentAt(seed);
        if (component.isEmpty()) {
            return;
        }
        InnerRedstoneNetwork.Union union = InnerRedstoneNetwork.collectUnion(seam, component);
        RingEnv env = new RingEnv();
        env.owning(union.cells());
        InnerRedstoneNetwork.settleUnion(seam, env, union);
    }

    /**
     * One tick of the level's own solve for one world-side run - what
     * {@code SuperconductingWireBlock#updateComponent} does with it: a run laid against a <b>host block</b> is
     * handed to the merged solve and nothing is written here; any other run is solved on its own as "the best
     * supply anywhere in it".
     */
    private void runTickFor(List<BlockPos> run) {
        FixtureSeam seam = new FixtureSeam();
        List<BlockPos> hosting = new ArrayList<>();
        for (BlockPos cell : run) {
            for (Direction direction : Direction.values()) {
                BlockPos neighbour = cell.relative(direction);
                if (world.get(neighbour) != null && !hosting.contains(neighbour)) {
                    hosting.add(neighbour);
                }
            }
        }
        if (!hosting.isEmpty()) {
            for (BlockPos host : hosting) {
                worldTickFor(host);
            }
            return;
        }
        InnerRedstoneNetwork.Union union = InnerRedstoneNetwork.collectUnionForRun(seam, run);
        if (union.ports().isEmpty()) {
            // Nothing carried could join it, so the run is the network of its own it always was: the best
            // supply anywhere in it. Production's own branch, spelled out because the level is not here.
            int strength = 0;
            for (BlockPos cell : run) {
                strength = Math.max(strength, seam.supplyAt(cell));
            }
            seam.writeRun(run, PowerSolver.clamp(strength));
            return;
        }
        RingEnv env = new RingEnv();
        env.owning(union.cells());
        InnerRedstoneNetwork.settleUnion(seam, env, union);
    }

    /** {@link #ringTicks} for a scene whose world side is one run with no host block of its own. */
    private long runTicks(int n, List<BlockPos> run, long from) {
        long tick = from;
        for (int i = 0; i < n; i++) {
            runTickFor(run);
            CarrierStore.Clock clock = new CarrierStore.Clock(tick++, DIMENSION);
            CarrierStore.TickContext ctx = new CarrierStore.TickContext(clock, index, world, null, recorder,
                    ringBridge());
            CarrierNetworks.tick(ctx, clock);
        }
        return tick;
    }

    /**
     * {@code n} whole ticks from {@code from}: the level's own solve first, then one pass over the structures
     * - the order the merged solve is given in production. Returns the tick to carry on from, because two
     * carrier passes at one game time are one pass ({@code CarrierNetworks#tick} skips the second).
     */
    private long ringTicks(int n, BlockPos seed, long from) {
        long tick = from;
        for (int i = 0; i < n; i++) {
            worldTickFor(seed);
            CarrierStore.Clock clock = new CarrierStore.Clock(tick++, DIMENSION);
            CarrierStore.TickContext ctx = new CarrierStore.TickContext(clock, index, world, null, recorder,
                    ringBridge());
            CarrierNetworks.tick(ctx, clock);
        }
        return tick;
    }

    @Test
    @DisplayName("two structures wired through one world host drain together when the source goes")
    void twoStructuresOnOneWorldRingBothDrainWhenTheSourceGoes() {
        carry(X, A_LOCAL, ComponentType.SUPERCONDUCTOR);
        carry(Y, B_LOCAL, ComponentType.SUPERCONDUCTOR);
        Slot host = new Slot(ComponentType.SUPERCONDUCTOR);
        host.injectedPower = PowerSolver.MAX_POWER;
        world.set(RING_HOST, host);
        Ring ring = layRing(RING_NEAR, RING_FAR);

        long tick = ringTicks(3, RING_HOST, 1L);

        assertTrue(powerOf(X, A_LOCAL) > 0, "precondition: the supplied ring lights the first structure");
        assertTrue(powerOf(Y, B_LOCAL) > 0, "precondition: and the second, through the same world host");
        assertTrue(ring.power > 0, "precondition: and the world's own run beside it");

        host.injectedPower = -1;
        assertFalse(host.hasInjectedPower(), "the one supply is really gone");
        tick = ringTicks(12, RING_HOST, tick);

        // THE LATCH: each structure's read of a lossless seam asks what the world pushes into the neighbour,
        // and that answer is "around the host block", where the OTHER structure is standing. Neither of the
        // two is in the world's own solve, so each supplies the other and fifteen holds itself up.
        assertEquals(0, powerOf(X, A_LOCAL), "with no supply the first structure must go dark");
        assertEquals(0, powerOf(Y, B_LOCAL),
                "and so must the second: two structures on one world ring are one network, or they are a latch");
    }

    @Test
    @DisplayName("a third structure on the same ring drains too")
    void aThirdStructureOnTheSameRingDrainsToo() {
        carry(X, A_LOCAL, ComponentType.SUPERCONDUCTOR);
        carry(Y, B_LOCAL, ComponentType.SUPERCONDUCTOR);
        carry(Z, C_LOCAL, ComponentType.SUPERCONDUCTOR);
        Slot host = new Slot(ComponentType.SUPERCONDUCTOR);
        host.injectedPower = PowerSolver.MAX_POWER;
        world.set(RING_HOST, host);
        Ring ring = layRing(RING_NEAR, RING_FAR);

        long tick = ringTicks(3, RING_HOST, 1L);
        assertTrue(powerOf(X, A_LOCAL) > 0, "precondition: the supplied ring lights the first structure");
        assertTrue(powerOf(Y, B_LOCAL) > 0, "precondition: the second");
        assertTrue(powerOf(Z, C_LOCAL) > 0, "precondition: and the third");
        assertTrue(ring.power > 0, "precondition: and the world's own run");

        host.injectedPower = -1;
        tick = ringTicks(16, RING_HOST, tick);

        // A "whose own frame holds the root" rule cannot answer this one: the ground each structure stands on
        // is the ground the others stand on, so there is no owner whose own supply decides, and all three keep
        // restating each other. Only one solve over the three can reach zero.
        assertEquals(0, powerOf(X, A_LOCAL), "all three drain: the first");
        assertEquals(0, powerOf(Y, B_LOCAL), "the second");
        assertEquals(0, powerOf(Z, C_LOCAL), "and the third");
    }

    @Test
    @DisplayName("a structure's own torch still lights another structure through the world host")
    void aCarriedTorchStillLightsAnotherStructureThroughTheWorldHost() {
        // The guard for the whole family: the fix must not be a wider refusal. A source inside one structure
        // is not a wire of the ring - it is what the world host beside it is lit BY - and the other structure
        // has to see it. The torch is a real carried component, lit by its own rule, not an injected number.
        carry(X, A_LOCAL, ComponentType.TORCH).facing = Direction.UP;
        carry(Y, B_LOCAL, ComponentType.SUPERCONDUCTOR);
        world.set(RING_HOST, new Slot(ComponentType.SUPERCONDUCTOR));
        layRing(RING_NEAR, RING_FAR);

        long tick = ringTicks(4, RING_HOST, 1L);

        assertTrue(powerOf(X, A_LOCAL) > 0, "precondition: a torch is lit unless its own side is powered");
        assertEquals(PowerSolver.MAX_POWER, world.get(RING_HOST).power,
                "the world host beside it is lit by the torch, through the projection");
        assertEquals(PowerSolver.MAX_POWER, powerOf(Y, B_LOCAL),
                "and the wire of ANOTHER structure is lit by it - a lossless seam costs nothing, so the far "
                        + "side is at full strength, and refusing it would be the fix eating its own subject");
        tick = ringTicks(8, RING_HOST, tick);
        assertEquals(PowerSolver.MAX_POWER, powerOf(Y, B_LOCAL), "still lit, several ticks later");
    }

    @Test
    @DisplayName("a dust seam still pays the receiver's hop, and a dust never joins the union")
    void aDustSeamStillPaysOneHop() {
        // The guard for the merged solve. Joining a carried network is right for a wire that charges nothing
        // and wrong for every other wire: a dust pays one, which is exactly what makes a dust pair's equations
        // contradictory and its own latch impossible. So a dust must be refused the union and must keep the
        // numbers it has today. This scene puts a union around it, with a world host and a run to its south, so
        // the refusal is a refusal rather than a scene that never asked. The pair stands beside the world's
        // RUN rather than beside the host block: a dust beside a host block
        // reads it uncharged in one direction while the host reads the dust's projection charged in the other,
        // which is a pair whose arithmetic is nobody's subject here. Beside a run cell the read is one-way -
        // the run takes only wires it can see as nodes, and a carried dust is not one - so the numbers below
        // are today's numbers and only today's.
        BlockPos first = pos(6, 64, 6);
        BlockPos second = pos(7, 64, 6);
        Slot source = carryPowered(X, first, ComponentType.DUST, PowerSolver.MAX_POWER);
        carry(Y, second, ComponentType.DUST);
        world.set(RING_HOST, new Slot(ComponentType.SUPERCONDUCTOR));
        layRing(RING_NEAR, RING_FAR);

        long tick = ringTicks(3, RING_HOST, 1L);

        assertEquals(PowerSolver.MAX_POWER, powerOf(X, first), "precondition: the fed dust holds its supply");
        assertEquals(14, powerOf(Y, second),
                "and the dust beside it is ONE step lower. Fifteen here would mean the seam had stopped "
                        + "charging the receiver - a dust joined to the world's solve reads the far side through "
                        + "a node whose type charges nothing, and the step that keeps a dust pair solvable is gone");

        source.injectedPower = -1;
        assertFalse(source.hasInjectedPower(), "the one supply is really gone");
        tick = ringTicks(10, RING_HOST, tick);

        assertEquals(0, powerOf(X, first), "with no supply the dust goes dark, exactly as it does today");
        assertEquals(0, powerOf(Y, second), "and so does the dust across the seam");
    }

    /**
     * The scene of limitation (2): a <b>carried</b> superconductor beside the world's own run,
     * with no host block anywhere in it. Both sides are lossless, so each read of the other is a copy at gain
     * exactly one - and if the two are solved apart, every value from zero to fifteen is a fixed point of that
     * pair and a strength nothing supplies any more stands there for ever.
     *
     * <p>The run is the cell west of the machine's only cell, which is the geometry of "a wire laid against the
     * block my machine is drawn on": a host block's world cell holds air, so the run's own query at that cell is
     * answered by the projection table and nothing else.
     */
    @Test
    @DisplayName("a carried wire beside the world's own run drains with it when the source goes")
    void aCarriedWireBesideTheWorldsRunDrainsWithIt() {
        BlockPos carried = pos(5, 64, 5);
        carry(X, carried, ComponentType.SUPERCONDUCTOR);
        Ring ring = layRing(RING_NEAR, RING_FAR);
        carriedSupplyBesideRuns = true;
        runSupply = PowerSolver.MAX_POWER;

        long tick = runTicks(3, ring.cells, 1L);

        assertTrue(ring.power > 0, "precondition: the run is lit by the one source in the scene");
        assertTrue(powerOf(X, carried) > 0,
                "precondition: and the machine's own wire across the cell boundary is lit with it, so both "
                        + "directions of the seam are in play");

        runSupply = 0;
        tick = runTicks(12, ring.cells, tick);

        assertEquals(0, ring.power,
                "with no source left, the world's run must go dark - it is not a supply for the machine beside "
                        + "it and the machine is not a supply for it");
        assertEquals(0, powerOf(X, carried),
                "and the carried wire with it: two lossless wires solved one against the other keep every value "
                        + "as a fixed point, which is the self-lock this scene is about");

        tick = runTicks(6, ring.cells, tick);
        assertEquals(0, ring.power, "and it stays dark, several ticks later");
        assertEquals(0, powerOf(X, carried), "on both sides");
        assertTrue(tick > 0, "the tick counter advanced");
    }

    /** One tick of the structures' own passes, with the production-shaped bridge and nothing in the world. */
    private long carrierTicks(int n, long from) {
        long tick = from;
        for (int i = 0; i < n; i++) {
            // The level's own solve has nothing to visit in these scenes - not "is not run": every position it
            // looks at must hold a world-store component (InnerRedstoneNetwork#settle), and these scenes put
            // every component inside a structure, so the queue is empty and the loop below is the whole tick.
            worldTickFor(pos(0, 0, 0));
            CarrierStore.Clock clock = new CarrierStore.Clock(tick++, DIMENSION);
            CarrierStore.TickContext ctx = new CarrierStore.TickContext(clock, index, world, null, recorder,
                    ringBridge());
            CarrierNetworks.tick(ctx, clock);
        }
        return tick;
    }

    @Test
    @DisplayName("two TOUCHING carried superconductors drain when their only source goes")
    void twoTouchingCarriedSuperconductorsDrainWhenTheirSourceGoes() {
        // The scene, in the one geometry the family had not covered: two host blocks of two DIFFERENT
        // structures drawn on FACE-ADJACENT world cells, each holding one superconducting component, with nothing
        // of ours in the world store anywhere and no world run. There is therefore no seed for a merged solve at
        // all - so the two structures can only be solved one after the other, each reading the other across the
        // seam.
        BlockPos first = pos(5, 64, 5);
        BlockPos second = pos(5, 64, 4);
        carry(X, first, ComponentType.SUPERCONDUCTOR);
        carry(Y, second, ComponentType.SUPERCONDUCTOR);
        // The one source: a lever of the level beside X's block, and nothing anywhere beside Y's - so whatever Y
        // holds it can only have read across the seam.
        worldSources.put(worldOf(pos(6, 64, 5)).asLong(), PowerSolver.MAX_POWER);

        FixtureSeam seam = new FixtureSeam();
        assertEquals(worldOf(first).relative(Direction.NORTH), worldOf(second),
                "precondition: the two blocks are DRAWN on face-adjacent world cells - touching, not one cell "
                        + "apart - so each one's read of the other asks the very cell the other stands on");
        assertTrue(seam.componentAt(worldOf(first)).isEmpty(),
                "precondition: the LEVEL holds nothing at either block, so settle() has no position to seed a "
                        + "merged solve from - 'no union was assembled' is this fact, not an assumption");
        assertFalse(seam.runAt(worldOf(second)), "precondition: and no world run stands beside them either");

        long tick = carrierTicks(3, 1L);

        assertEquals(PowerSolver.MAX_POWER, powerOf(X, first),
                "precondition: the lever lights the block it stands beside");
        assertEquals(PowerSolver.MAX_POWER, powerOf(Y, second),
                "precondition: and the block TOUCHING it, at full strength - a seam between two components that "
                        + "both charge nothing must cost nothing, in both directions");

        // The player breaks the lever. Nothing feeds either block any more.
        worldSources.clear();

        tick = carrierTicks(12, tick);

        assertEquals(0, powerOf(X, first),
                "with its source gone the block that was fed must go dark: what it reads across the seam is the "
                        + "neighbour's own stored strength, and two lossless wires read each other at gain exactly "
                        + "one, so that answer is the latch itself");
        assertEquals(0, powerOf(Y, second), "and so must the block touching it");

        tick = carrierTicks(8, tick);
        assertEquals(0, powerOf(X, first), "and it stays dark, several ticks later");
        assertEquals(0, powerOf(Y, second), "on both sides");
    }

    @Test
    @DisplayName("the seam's KIND is asked about a host another structure carries, not only about the store")
    void theSeamKindQuestionSeesAHostAnotherStructureCarries() throws Exception {
        // The rule itself, as a pure function of the two types, so both halves of the read can call it instead of
        // restating it: a neighbour the world store cannot see is still the seam's other half.
        assertTrue(InnerRedstoneNetwork.seamIsLossless(ComponentType.SUPERCONDUCTOR, ComponentType.SUPERCONDUCTOR),
                "two superconductors are a lossless seam, whichever structure carries them");
        assertFalse(InnerRedstoneNetwork.seamIsLossless(ComponentType.DUST, ComponentType.SUPERCONDUCTOR),
                "a dust pays its hop, so the pair's equations contradict and no latch is possible - the dust path "
                        + "must keep the value read it has today");
        assertFalse(InnerRedstoneNetwork.seamIsLossless(ComponentType.SUPERCONDUCTOR, null),
                "and a neighbour whose kind cannot be established is NOT a lossless seam: answering 'no supply' "
                        + "there would darken a machine standing beside an ordinary block");

        String network = flat(
                "src/main/java/com/jiangyuefengyu/redstonecircuit/logic/InnerRedstoneNetwork.java");
        assertTrue(network.contains("seamIsLossless(reader, worldHostTypeAt(level, worldPos, owner))"),
                "production must ask the ONE kind question, with the neighbour resolved past the store");
        assertTrue(network.contains("CarrierStore.projectedHostAt(level, worldPos)"),
                "and resolving it must consult the projection table: a carried host's world cell holds air, so "
                        + "the store can never answer for the seam's other half - that omission is the defect");
        assertTrue(network.contains("projectionReadableBy(carried, owner)"),
                "with the reader's OWN projected hosts refused, as every other read refuses them");
    }

    // ----------------------------- 6. the two scenes of the self-lock --

    /**
     * The second scene, as geometry: an <b>ordinary block of the level</b> holding a superconductor,
     * the <b>physicalised block</b> holding one beside it, and the world's own superconducting wire on the far
     * side of that - three cells on one line.
     *
     * <p>The world store owns the first cell, the projection table the second, the level's run the third, so
     * the merged solve has to cross a <b>port</b> to reach the first. It did not: the walk took in the run and
     * the carried network beside it, and a world block standing one cell further on - reachable only across
     * the port - was never asked about, so it stayed outside the solve while its stored strength was read back
     * as an arrival. A hop that costs nothing in both directions makes every value a fixed point of that pair,
     * which is the self-lock, and why the answer depends on which cell is activated:
     * seeded from the ordinary block the walk owns all three, seeded from the wire it owns two.
     */
    @Test
    @DisplayName("three cells on one line: seeded from the world wire, the block behind the port must still join")
    void theRunSideSeedReachesTheWorldHostBehindThePort() {
        BlockPos west = worldOf(pos(4, 64, 5));
        BlockPos middle = pos(5, 64, 5);
        BlockPos east = worldOf(pos(6, 64, 5));
        world.set(west, new Slot(ComponentType.SUPERCONDUCTOR));
        carry(X, middle, ComponentType.SUPERCONDUCTOR);
        Ring ring = layRing(east);
        runSupply = PowerSolver.MAX_POWER;

        FixtureSeam seam = new FixtureSeam();
        assertEquals(List.of(west), InnerRedstoneNetwork.collectUnion(seam, seam.componentAt(west)).hosts(),
                "precondition: seeded from the ordinary block, the solve owns it");
        assertTrue(InnerRedstoneNetwork.collectUnionForRun(seam, ring.cells).hosts().contains(west),
                "and seeded from the world wire beside it the SAME solve has to own it too: the ordinary block "
                        + "is one cell past the physicalised one, so the only way to it is across the port - and a "
                        + "solve that stops at the port leaves that stored strength outside itself, from where it "
                        + "is read back as an arrival at gain exactly one");

        runTickFor(ring.cells);
        worldTickFor(west);
        runTicks(1, ring.cells, 2L);

        assertTrue(ring.power > 0, "precondition: the world wire is lit");
        assertTrue(world.get(west).power > 0, "precondition: and so is the ordinary block behind the port");
        assertTrue(powerOf(X, middle) > 0, "precondition: and the physicalised block between them");

        runSupply = 0;
        for (int i = 0; i < 12; i++) {
            // The seed that locks, and the only one that does locked: the world's own wire.
            runTickFor(ring.cells);
        }

        assertEquals(0, ring.power, "with the source gone the wire must go dark, whoever recomputes it");
        assertEquals(0, world.get(west).power, "and so must the ordinary block the port stands against");
        assertEquals(0, powerOf(X, middle), "and the physicalised block between them");

        for (int i = 0; i < 6; i++) {
            runTickFor(ring.cells);
        }
        assertEquals(0, ring.power, "and it stays dark, several ticks later");
        assertEquals(0, world.get(west).power, "on every one of the three cells");
        assertEquals(0, powerOf(X, middle), "not one value left standing");
    }

    /**
     * The first scene: a <b>physicalised block</b> holding a superconductor, and the world's own wire
     * on the cell <b>above</b> it.
     *
     * <p>What feeds a superconductor buried in a block is a source in the same block, so the block's component
     * holds that dust as well - and a component that is not wholly a lossless wire may not be joined by halves
     * ({@code collectUnion}'s fourth refusal). The port is therefore refused, the pair is solved twice, and the
     * two sides are left reading each other: the wire's supply was the <em>projection</em> of the
     * superconductor (a raw copy, charging nothing), while the superconductor read the wire's stored strength
     * the same way. Gain exactly one in both directions, so fifteen stands there with the source off.
     *
     * <p>The rule the read already applies to a block of the level - "a wire inside that block is a node, never
     * a supply for the wire beside it" - was simply not applied to a block a structure is carrying, because the
     * lookup that answers it is the world store and a carried host is drawn over air.
     */
    @Test
    @DisplayName("the wire ABOVE a physicalised block drains even when the block's own component cannot join")
    void theWireAboveAPhysicalisedBlockDrainsWhenItsPortCannotJoin() {
        BlockPos cell = pos(5, 64, 5);
        carry(X, cell, ComponentType.SUPERCONDUCTOR);
        // What feeds a superconductor buried in a block is a source in that same block, so the block's own
        // component holds that dust too - and a component that is not wholly a lossless wire may not be joined
        // by halves (collectUnion's fourth refusal). The port is therefore refused, which is the scene.
        carry(X, pos(6, 64, 5), ComponentType.DUST);
        Ring above = layRing(worldOf(pos(5, 65, 5)));
        // What the level's own query answers at a carried cell: the projection of the host riding it.
        carriedSupplyBesideRuns = true;
        runSupply = PowerSolver.MAX_POWER;

        FixtureSeam seam = new FixtureSeam();
        assertEquals(worldOf(cell).above(), above.cells.get(0),
                "precondition: the world's wire is the cell above the physicalised block");
        assertTrue(InnerRedstoneNetwork.collectUnionForRun(seam, above.cells).ports().isEmpty(),
                "precondition: the port is REFUSED - the block's component holds the dust beside its "
                        + "superconductor, so the frame is mixed and may not be joined by halves");

        runTicks(3, above.cells, 1L);
        assertTrue(above.power > 0, "precondition: the wire above the block is lit by the source beside it");
        assertTrue(powerOf(X, cell) > 0,
                "precondition: and the superconductor inside the block is lit across the seam by that wire, so "
                        + "the two really are reading each other");

        runSupply = 0;
        for (int i = 0; i < 12; i++) {
            runTicks(1, above.cells, 10L + i);
        }

        assertEquals(0, above.power,
                "with no source the wire must go dark: a wire another structure carries is a NODE of the merged "
                        + "solve, and read as a supply instead it is a copy at gain exactly one - a pair whose "
                        + "every value is a fixed point");
        assertEquals(0, powerOf(X, cell), "and the superconductor inside the block with it");

        for (int i = 0; i < 6; i++) {
            runTicks(1, above.cells, 40L + i);
        }
        assertEquals(0, above.power, "and it stays dark, several ticks later");
        assertEquals(0, powerOf(X, cell), "on both sides of the seam");
    }

    /** One file's text, whitespace-flattened, so a wiring assertion is not a formatting assertion. */
    private static String flat(String relative) throws Exception {
        return java.nio.file.Files.readString(java.nio.file.Path.of(relative))
                .replace("\r\n", "\n").replaceAll("\\s+", " ");
    }
}
