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

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.carrier.CarriedHost;
import com.jiangyuefengyu.redstonecircuit.carrier.CarriedHostIndex;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierId;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierNetworks;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierPose;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierProjection;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierStore;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierWorldBridge;
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
 * Inside a physicalised structure a run of carried dust fades one step per block, the world half can still supply
 * a cell, and another structure's projection across the seam is still read - charged, because a projected
 * neighbour is read where both frames meet and the receiver pays its hop cost.
 *
 * <p>The reader's own projected host is not an external supply: it already arrives through the inner neighbour
 * relationship, which charges the hop, and that raw emission must not answer as well. Every fixture here has
 * exactly one supply source, since "the world is feeding this cell" and "the run must fade" are mutually exclusive
 * claims, and a {@code ServerLevel} cannot be built in a unit test, so the end-to-end read is pinned by the pure
 * rule plus the source-wiring assertions at the bottom.
 */
class InnerProjectionSupplyTest {

    /** The structure whose own dust run is being solved - the reader. */
    private static final CarrierId X =
            CarrierId.subLevel(UUID.fromString("00000000-0000-0000-0000-00000000a141"));
    /** An independently physicalised structure standing next door - the seam. */
    private static final CarrierId Y =
            CarrierId.subLevel(UUID.fromString("00000000-0000-0000-0000-00000000b141"));

    private static final ResourceKey<Level> DIMENSION =
            ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse("redstonecircuit:p141"));

    /**
     * The own coordinate of the first host of a fixture.
     *
     * <p>The pose is a pure translation from a host's local coordinate to itself - the geometry a Sable sub-level's
     * plot has, where a local neighbour maps onto the world neighbour - so "the neighbour one block east inside the
     * structure" and "the neighbour one block east in the world" are the same cell, which is what makes a leaking
     * projection readable in the first place.
     */
    private static final BlockPos ANCHOR = pos(5, 64, 5);

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
     * The <b>local</b> coordinates of the hosts this fixture's world half was offered and refused, i.e. the reads
     * the reader's-own-host rule answered with air.
     */
    private final List<Long> refused = new ArrayList<>();

    private static BlockPos pos(int x, int y, int z) {
        return new BlockPos(x, y, z);
    }

    @BeforeEach
    void reset() {
        CarrierNetworks.clear();
        recorder.writes.clear();
        refused.clear();
        seamOffers.clear();
        seamReader = ComponentType.DUST;
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
     * The world half of the bridge, as production builds it: the vanilla half is {@code base} - the signal a real
     * world block would offer at that position, a carried host standing on air, so an honest fixture passes 0 unless
     * it deliberately models a block there - and the projected half is the host a carrier carries at that position
     * and what it emits, filtered and charged by the very rules production applies.
     */
    /**
     * The receiver the running solve is reading with. The world half of this fixture is a lambda, and a lambda is
     * never asked who is calling, so the fixture has to be told: the charge the seam applies is the receiver's hop
     * cost, and the tests below set this to the type they are solving with. Production looks the reader up in the
     * carrier index at its own local coordinate.
     */
    private ComponentType seamReader = ComponentType.DUST;

    /**
     * Every host the world half handed to a read, by local coordinate - the counterpart of {@link #refused}, which
     * says "offered and refused". Without it, a read that never reached the seam and a read that reached it and got
     * zero look identical from the outside.
     */
    private final List<Long> seamOffers = new ArrayList<>();

    private CarrierWorldBridge bridgeFor(int base) {
        CarrierPose pose = poseAt(ANCHOR);
        ComponentType reader = seamReader;
        return new CarrierWorldBridge(
                ignored -> pose,
                (worldPos, worldDirection) -> {
                    CarrierProjection.ProjectedHost found = hostAt(X, worldPos);
                    if (found == null) {
                        // The table holds every carrier's hosts, so when the reading carrier holds nothing at
                        // this cell, another structure's host is the seam.
                        found = hostAt(Y, worldPos);
                    }
                    if (found != null) {
                        // OFFERED is not the same fact as REFUSED: this records that the world half really did
                        // hand a host over, so a later zero can be read.
                        seamOffers.add(found.localPos().asLong());
                    }
                    if (!InnerRedstoneNetwork.projectionReadableBy(found, X)) {
                        // Air where the reader's own host stands, exactly as the owner-aware read answers zero for
                        // it. The refused host's LOCAL coordinate is recorded, so a test can prove that a
                        // projection really was offered and really was refused; the coordinate, not the host,
                        // because the live slot's power mutates and a record of it would not compare equal later.
                        if (found != null) {
                            refused.add(found.localPos().asLong());
                        }
                        return PowerSolver.clamp(base);
                    }
                    int projected = CarrierProjection.emittedToward(found, worldDirection, false, false);
                    // And the hop the reader pays, through the SAME rule production applies: the seam answers,
                    // it is not refused, and it is not free.
                    projected = InnerRedstoneNetwork.seamHop(found, reader, projected);
                    return PowerSolver.clamp(Math.max(base, projected));
                });
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
    private static CarrierProjection.ProjectedHost projected(CarrierId carrier, BlockPos local, int power) {
        return projected(carrier, local, ComponentType.DUST, power);
    }

    /** The same, with the component the projected host holds named outright - for the hop rule. */
    private static CarrierProjection.ProjectedHost projected(CarrierId carrier, BlockPos local,
                                                             ComponentType type, int power) {
        Slot slot = new Slot(type);
        slot.power = power;
        return new CarrierProjection.ProjectedHost(carrier, local,
                pos(local.getX(), 64, local.getZ()), poseAt(ANCHOR), slot, power);
    }

    // ------------------------------------------- 1. the rule: whose projection may answer --

    @Test
    @DisplayName("the reader's own projected host is not an external supply")
    void theReadersOwnProjectionIsNotAnExternalSupply() {
        CarrierProjection.ProjectedHost own = projected(X, pos(6, 64, 5), 15);

        assertFalse(InnerRedstoneNetwork.projectionReadableBy(own, X),
                "a host of the carrier that is reading is a node of its own solve: it arrives through the "
                        + "inner-neighbour relationship, which charges the hop, so the projection's raw "
                        + "emission must not answer as well - that is how the run stopped fading");
        assertFalse(CarrierProjection.readableBy(own, X),
                "and the decision itself lives with ProjectedHost, so the two cannot disagree");
    }

    @Test
    @DisplayName("another carrier's projected host is still read - the seam with another structure, kept")
    void anotherCarriersProjectionIsStillRead() {
        CarrierProjection.ProjectedHost theirs = projected(Y, pos(6, 64, 5), 15);

        assertTrue(InnerRedstoneNetwork.projectionReadableBy(theirs, X),
                "two independently physicalised blocks see each other through this and nothing else: the "
                        + "level sees air where a carried block stands, so refusing it would leave two "
                        + "separate structures unable to reach each other");
        assertTrue(CarrierProjection.readableBy(theirs, X), "same answer from the owner of the rule");
        assertTrue(InnerRedstoneNetwork.projectionReadableBy(theirs, null),
                "and the world-side solve names no reader, so it is never filtered");
    }

    @Test
    @DisplayName("air is read as air, and only a named reader filters anything")
    void theFilterIsNarrowOnPurpose() {
        assertFalse(InnerRedstoneNetwork.projectionReadableBy(null, X),
                "no host projected there is nothing to read, whoever is asking");
        assertFalse(InnerRedstoneNetwork.projectionReadableBy(null, null),
                "and still nothing for the unfiltered reader");
        assertTrue(InnerRedstoneNetwork.projectionReadableBy(projected(X, pos(6, 64, 5), 15), null),
                "with no reader named the reader's own host is read like any other, which is what the "
                        + "world-side solve asks for");
    }

    // --------------------------------------------------- 2. the leak, in the solve --

    @Test
    @DisplayName("a run of carried dust fades one per block")
    void aRunOfCarriedDustFadesOnePerBlock() {
        // The feed is the chain head's own injected supply, and there is no world block anywhere: one source,
        // so "it fades" is the only thing the fixture can mean.
        BlockPos head = pos(1, 64, 5);
        carryPowered(X, head, ComponentType.DUST, 15);
        for (int step = 1; step <= 14; step++) {
            carry(X, head.offset(step, 0, 0), ComponentType.DUST);
        }

        solve(1L);

        for (int step = 0; step <= 14; step++) {
            assertEquals(15 - step, powerOf(X, head.offset(step, 0, 0)),
                    "the dust " + step + " hop(s) from the source holds " + (15 - step)
                            + "; inside a structure a wire hop costs one exactly as on the ground");
        }
    }

    @Test
    @DisplayName("two neighbouring host blocks with dust inside them are one hop apart")
    void acrossTwoHostBlocksTheHopIsStillCharged() {
        // Each block holds its own component and the blocks are inside ONE structure. The two wires downstream of
        // the head are given the value an ongoing run would already hold, which is the ordinary steady state of a
        // live machine - and it is what makes the leak visible in a SINGLE pass rather than only after a tick or
        // two, since the projection of an inner neighbour only carries power once that neighbour has some.
        carryPowered(X, pos(5, 64, 5), ComponentType.DUST, 15);
        carry(X, pos(6, 64, 5), ComponentType.DUST).power = 15;
        carry(X, pos(7, 64, 5), ComponentType.DUST).power = 15;

        CarrierNetworks.TickSummary summary = solve(1L);

        assertEquals(15, powerOf(X, pos(5, 64, 5)), "the fed host");
        assertEquals(14, powerOf(X, pos(6, 64, 5)), "the host next door - one hop");
        assertEquals(13, powerOf(X, pos(7, 64, 5)), "and the one after it - two");
        assertEquals(3, summary.solvedChanges(),
                "all three hosts are one wire run and all three changed in this pass");
        assertTrue(refused.contains(pos(6, 64, 5).asLong()),
                "and the world half really was offered the next host's projection and refused it: "
                        + "without that, 'the run faded' could equally mean the fixture never handed the "
                        + "projection over at all - the refusal is the thing under test, not the absence "
                        + "of a read. Refused hosts, by local coordinate: " + refused);
    }

    @Test
    @DisplayName("a settled run stays faded on the next pass - it does not jump back to fifteen")
    void aSettledRunStaysFaded() {
        // The same run, given a previous tick to settle in: after the first pass every wire that is not the head
        // already holds its faded value, so the projection of an inner neighbour is a STALE full-strength reading -
        // exactly the kind that must not win the maximum.
        BlockPos head = pos(1, 64, 5);
        carryPowered(X, head, ComponentType.DUST, 15);
        for (int step = 1; step <= 4; step++) {
            carry(X, head.offset(step, 0, 0), ComponentType.DUST);
        }

        solve(1L);
        for (int step = 0; step <= 4; step++) {
            assertEquals(15 - step, powerOf(X, head.offset(step, 0, 0)),
                    "the run settled faded on the first pass: hop " + step + " holds " + (15 - step));
        }

        CarrierNetworks.TickSummary second = solve(2L);

        for (int step = 0; step <= 4; step++) {
            assertEquals(15 - step, powerOf(X, head.offset(step, 0, 0)),
                    "and hop " + step + " is STILL one step below the one before it: a stale uncharged copy "
                            + "of an inner neighbour must not be able to push a settled wire back up");
        }
        assertEquals(0, second.solvedChanges(),
                "nothing changed on the second pass: the run is already at its fixpoint, which is the "
                        + "honest answer for a settled circuit");
    }

    @Test
    @DisplayName("a superconducting run inside a structure still does not fade")
    void aSuperconductingRunInsideAStructureDoesNotFade() {
        BlockPos head = pos(1, 64, 5);
        carryPowered(X, head, ComponentType.SUPERCONDUCTOR, 15);
        for (int step = 1; step <= 8; step++) {
            carry(X, head.offset(step, 0, 0), ComponentType.SUPERCONDUCTOR);
        }

        solve(1L);

        assertEquals(0, ComponentType.SUPERCONDUCTOR.hopCost(), "the type really is the zero-cost wire");
        assertEquals(15, powerOf(X, head), "the head takes its supply at full strength");
        for (int step = 1; step <= 8; step++) {
            assertEquals(15, powerOf(X, head.offset(step, 0, 0)),
                    "hop " + step + " of a superconductor costs nothing - the rule must not start charging "
                            + "the one wire whose whole purpose is not to be charged");
        }
    }

    @Test
    @DisplayName("dust -> superconductor -> dust: nothing through the superconductor, one out of it")
    void aMixedChainChargesOnEntryAndExitOnlyWhereTheTypeSaysSo() {
        // The charge is the RECEIVER's, so the pair of interesting hops is dust -> superconductor (nothing: the
        // superconductor receives) and superconductor -> dust (one: the dust receives). One supply, at the head.
        carryPowered(X, pos(1, 64, 5), ComponentType.DUST, 15);
        carry(X, pos(2, 64, 5), ComponentType.SUPERCONDUCTOR);
        carry(X, pos(3, 64, 5), ComponentType.DUST);
        carry(X, pos(4, 64, 5), ComponentType.SUPERCONDUCTOR);
        carry(X, pos(5, 64, 5), ComponentType.DUST);

        solve(1L);

        assertEquals(15, powerOf(X, pos(1, 64, 5)), "the fed dust holds its supply");
        assertEquals(15, powerOf(X, pos(2, 64, 5)), "into the superconductor: nothing charged");
        assertEquals(14, powerOf(X, pos(3, 64, 5)), "out of it into dust: one charged");
        assertEquals(14, powerOf(X, pos(4, 64, 5)), "into the next superconductor: nothing charged");
        assertEquals(13, powerOf(X, pos(5, 64, 5)), "and one more for the last dust");
    }

    // ------------------------------- 3. the two things the rule must not break, and one it must allow --

    @Test
    @DisplayName("an external supply still tops a cell up to fifteen - that is the semantics, not a bug")
    void anExternalSupplyStillTopsACellUp() {
        // A real world block offering 15 is a LEGAL external supply, and an external supply does not charge a hop
        // by definition. So a cell the world feeds holds 15 even while the run beside it fades - the world half was
        // not switched off, only the uncharged copy of an INNER neighbour was - and here the two facts are stated
        // where they do not overlap.
        BlockPos head = pos(1, 64, 5);
        carryPowered(X, head, ComponentType.DUST, 15);
        carry(X, head.offset(1, 0, 0), ComponentType.DUST);
        carry(X, head.offset(2, 0, 0), ComponentType.DUST);
        // A second, isolated wire: the world is the only thing it can draw from.
        carry(X, head.offset(4, 0, 0), ComponentType.DUST);

        CarrierNetworks.TickSummary summary = solve(7L, 15);

        assertEquals(15, powerOf(X, head.offset(4, 0, 0)),
                "a wire with no neighbour at all takes the world's fifteen: an external supply tops a "
                        + "cell up, and that is correct");
        assertEquals(15, powerOf(X, head), "and the fed head is unaffected by it");
        assertEquals(15, powerOf(X, head.offset(1, 0, 0)),
                "and the cell next to it is topped up by the SAME world supply: a world half that "
                        + "answers fifteen on every side is a full-strength supply to every cell, which is "
                        + "exactly why a fixture answering fifteen everywhere cannot also demand a fade - "
                        + "the fading run is asserted in the tests whose world half is silent");
        assertEquals(15, powerOf(X, head.offset(2, 0, 0)),
                "same for the rest of the run: nothing here is being routed through the inner "
                        + "relationship, the world is feeding each of them directly");
        assertEquals(4, summary.solvedChanges(),
                "four wires changed: the head, the two of the run and the one the world feeds - including "
                        + "the head, which is a wire and holds 0 before the pass even though its injected "
                        + "supply is already 15");
    }

    @Test
    @DisplayName("another carrier's projection at the coordinate this solve reads is still read - the seam")
    void anotherCarriersProjectionIsTheSeam() {
        // The seam read at the same time as the reader's own neighbour is charged: X carries a two-host run fed at
        // (5,64,5), and Y's own coordinate (5,64,6) projects onto the cell X's second host reads towards. The level
        // sees air there, so the projection is the only route across, and both halves are asserted at once - the
        // other structure's full-strength dust must not win the maximum, and it must not be refused either.
        carryPowered(X, pos(5, 64, 5), ComponentType.DUST, 15);
        carry(X, pos(6, 64, 5), ComponentType.DUST);
        Slot theirs = carry(Y, pos(5, 64, 6), ComponentType.DUST);
        theirs.power = 15;

        solve(1L);

        assertEquals(14, powerOf(X, pos(6, 64, 5)),
                "the host next door takes one step less: the seam with another structure is read at the "
                        + "same time as the reader's own neighbours are charged");
        assertFalse(refused.contains(pos(5, 64, 6).asLong()),
                "and it is read rather than refused: Y is not the carrier whose solve is running, so "
                        + "refusing every projection would make the seam dark again and this test red");
    }

    // --------------------------------------- 3b. the seam's own hop, charged to the receiver --

    @Test
    @DisplayName("another structure's dust across the seam is CHARGED - fourteen, not fifteen")
    void aSeamReadIsChargedAndNotRefused() {
        // ONE supply, and it stands on the other side of the seam: the reader's wire has no neighbour of its own
        // (so no inner hop), no injected value and no world block at all. The only thing it can draw from is Y's
        // dust at the world cell its south side reads, and that read is charged - not refused, and no longer free.
        //
        // THE FAR SIDE NEEDS A REAL INPUT: the solver resets every wire to zero and grows it back from its own
        // supplies, and each carrier is solved by its own pass within the same tick, so a power written by hand on
        // a carried wire is not a supply at all - a debug supply is, and the hand-set value is kept as well so the
        // read does not depend on which of the two structures is solved first.
        BlockPos reader = pos(5, 64, 5);
        carry(X, reader, ComponentType.DUST);
        Slot theirs = carryPowered(Y, pos(5, 64, 6), ComponentType.DUST, 15);
        theirs.power = 15;

        solve(1L);

        assertTrue(seamOffers.contains(pos(5, 64, 6).asLong()),
                "precondition: the world half really did hand the other structure's host to this read - "
                        + "without this, 'the seam was charged' and 'the fixture never offered it' are the "
                        + "same observation. Offered hosts, by local coordinate: " + seamOffers);
        assertEquals(15, powerOf(Y, pos(5, 64, 6)),
                "precondition: the far side really is at fifteen, whichever order the two structures are "
                        + "solved in - otherwise the near wire would read zero and 'it was charged' would "
                        + "be indistinguishable from 'the seam had nothing to say'");
        assertEquals(14, powerOf(X, reader),
                "the seam answered and the reader's hop was charged: fourteen, not a free fifteen and "
                        + "not zero");
        assertFalse(refused.contains(pos(5, 64, 6).asLong()),
                "and it was read rather than refused: Y is not the carrier whose solve is running, so the "
                        + "seam is charged, not closed (a global skip would answer 0 here)");
    }

    @Test
    @DisplayName("the charge is the RECEIVER's, so a superconductor crosses the seam untouched")
    void aSuperconductingSeamKeepsEveryStep() {
        BlockPos reader = pos(5, 64, 5);
        carry(X, reader, ComponentType.SUPERCONDUCTOR);
        // The far side's input, for the reason spelled out in the test above: a hand-set power on a carried wire
        // is drained by that structure's own pass.
        Slot theirs = carryPowered(Y, pos(5, 64, 6), ComponentType.DUST, 15);
        theirs.power = 15;
        seamReader = ComponentType.SUPERCONDUCTOR;

        solve(1L);

        assertEquals(0, ComponentType.SUPERCONDUCTOR.hopCost(), "the type really is the zero-cost wire");
        assertTrue(seamOffers.contains(pos(5, 64, 6).asLong()),
                "precondition: the seam host really was offered to this read. Offered hosts: " + seamOffers);
        assertEquals(15, powerOf(Y, pos(5, 64, 6)),
                "precondition: the seam really is offering fifteen");
        assertEquals(15, powerOf(X, reader),
                "a superconductor pays nothing for a hop, so the seam hands it the whole fifteen: the "
                        + "charge is the receiver's hop cost and must not become a flat one-per-seam-read");
        assertFalse(refused.contains(pos(5, 64, 6).asLong()),
                "and the seam was read, not refused: that is what makes this fifteen reachable at all");
    }

    @Test
    @DisplayName("the seam hop IS the solver's hop rule - the receiver pays, and only between wires")
    void theSeamHopIsTheSolversHopRule() {
        CarrierProjection.ProjectedHost theirDust = projected(Y, pos(6, 64, 5), 15);
        CarrierProjection.ProjectedHost theirLever = projected(Y, pos(6, 64, 5), ComponentType.LEVER, 15);

        assertEquals(14, InnerRedstoneNetwork.seamHop(theirDust, ComponentType.DUST, 15),
                "a dust that reads a projected dust across a frame boundary pays the one it pays on the "
                        + "ground: the seam is not a second, cheaper kind of wire");
        assertEquals(15, InnerRedstoneNetwork.seamHop(theirDust, ComponentType.SUPERCONDUCTOR, 15),
                "a superconductor receives for nothing - the charge is the receiver's hop cost");
        assertEquals(15, InnerRedstoneNetwork.seamHop(theirLever, ComponentType.DUST, 15),
                "and a projected lever is a source rather than a wire, so nothing is charged: exactly "
                        + "what vanilla says about a lever beside a wire");
        assertEquals(15, InnerRedstoneNetwork.seamHop(theirDust, null, 15),
                "a caller that names no reader leaves the read uncharged - the honest degradation, not "
                        + "a guess");
        assertEquals(15, InnerRedstoneNetwork.seamHop(null, ComponentType.DUST, 15),
                "and air has nothing to be charged for");
        assertEquals(0, InnerRedstoneNetwork.seamHop(theirDust, ComponentType.DUST, 0),
                "nothing stays nothing: a seam that answers zero is not turned into a negative");
    }

    // ------------------------------------------------------------- 4. the wiring --

    @Test
    @DisplayName("the carrier-side world read is the owner-aware one, and the world side keeps the projection")
    void theCarrierReadNamesItsReader() throws Exception {
        // The one part a unit test cannot build is a ServerLevel, and the merged read takes one, so the wiring
        // that makes the rule reachable in production is pinned at the source.
        String network = flat("src/main/java/com/jiangyuefengyu/redstonecircuit/logic/InnerRedstoneNetwork.java");
        String io = flat("src/main/java/com/jiangyuefengyu/redstonecircuit/carrier/CarrierWorldIo.java");
        String networks = flat("src/main/java/com/jiangyuefengyu/redstonecircuit/carrier/CarrierNetworks.java");
        String space = flat("src/main/java/com/jiangyuefengyu/redstonecircuit/carrier/CarrierSpace.java");

        assertTrue(io.contains("InnerRedstoneNetwork.worldSignalAt(level, worldPos, worldDirection, reader)"),
                "the bridge reads the world through the owner-aware merged read, naming its reader - not "
                        + "through a bare readSignal, and not through an unfiltered CarrierWorldSignals");
        assertTrue(networks.contains("new CarrierWorldIo(pose, bridge.signals(), carrier, ctx.level())"),
                "the reader is the carrier whose solve is running, injected where that carrier is in hand");
        assertTrue(space.contains("io.signalIntoOwnFrame(queryPos, direction)"),
                "and the solve asks the owner-aware form");
        assertTrue(network.contains("int projected = projectedSignalAt(level, worldPos, worldDirection,"
                        + " owner);"),
                "the owner is what the owner-aware merged read hands to the projected half");
        assertTrue(network.contains("if (!projectionReadableBy(host, owner)) {"),
                "and the projected half refuses exactly the owner's own host");
        assertTrue(network.contains("() -> worldSignalAt(level, worldPos, worldDirection))"),
                "while the three-argument entry point still names no reader, so nothing about it changed");

        // ---- The seam's own four: the bridge names the receiver outright, looks it up in the carrier index, and
        // the charge it applies is the solver's one hop rule.
        assertTrue(io.contains("InnerRedstoneNetwork.seamReader("),
                "the carrier bridge names the receiver outright, so the charge is exact on a machine "
                        + "that has been turned - a world direction snapped to an axis is not");
        assertTrue(io.contains("CarrierStore.indexOf(level)"),
                "looked up in the carrier index at the reading host's own LOCAL coordinate, which the "
                        + "bridge already has");
        assertTrue(network.contains("PowerSolver.afterHop(reader, host.slot().type, answer)"),
                "and the arithmetic is the solver's one hop rule, with the projected host as the sender");
        assertTrue(network.contains("return seamHop(host, seamReaderAt(level, worldPos, worldDirection),"
                        + " answer);"),
                "applied at the one place both frames read a projected host through, so the seam is charged "
                        + "wherever it is read from - the world side names no reader and is answered by the "
                        + "query's own back-step");
        assertTrue(network.contains("private static volatile ComponentType seamReader;"),
                "read-scoped and restored, exactly like the refusal it sits beside");
    }

    /** One file's text, whitespace-flattened, so a wiring assertion is not a formatting assertion. */
    private static String flat(String relative) throws Exception {
        return java.nio.file.Files.readString(java.nio.file.Path.of(relative))
                .replace("\r\n", "\n").replaceAll("\\s+", " ");
    }
}
