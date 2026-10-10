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

    // ------------------------------------------------------------------ 3. wiring --

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

    /** One file's text, whitespace-flattened, so a wiring assertion is not a formatting assertion. */
    private static String flat(String relative) throws Exception {
        return java.nio.file.Files.readString(java.nio.file.Path.of(relative))
                .replace("\r\n", "\n").replaceAll("\\s+", " ");
    }
}
