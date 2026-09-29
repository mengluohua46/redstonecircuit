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
 * <b>P1.41: inside a physicalised structure a run of carried dust must fade a step per block, the world
 * half must still be able to supply a cell, and another structure's projection across the seam must still
 * be read.</b>
 *
 * <h2>The author's report, and what it turned out to be</h2>
 * <em>"A whole plate of structure blocks transmits redstone: with dust the signal does not fade."</em> The
 * dust is a component of ours - {@code ComponentType.DUST} placed into a host block that the structure
 * carries - and the same wiring on the ground fades correctly, so the defect is in the carrier-side solve
 * and only there.
 *
 * <p>The hop charge itself has one implementation and was never in question: {@code PowerSolver#afterHop}
 * is called from {@code PowerSolver#innerFrom} and subtracts the <em>receiver's</em> {@code hopCost()} -
 * one for dust, nothing for a superconductor. What was wrong is that the same component arrived a
 * <b>second</b> time, through the world half, with no charge at all:
 *
 * <pre>
 *   supplyOf -> env.externalSignal()                       (CarrierSpace)
 *            -> CarrierWorldIo -> the pose
 *            -> InnerRedstoneNetwork#worldSignals
 *            -> worldSignalAt -> projectedSignalAt          (RAW emission, no hop)
 *
 *   innerFrom  -> afterHop(self, other, power)             (power - hopCost)
 *   wireTarget -> max(supply, max over sides of innerFrom) (&lt;- where the uncharged copy won)
 * </pre>
 *
 * <p>The projection table holds <b>every</b> carrier's hosts - including the reading carrier's own - because
 * that is what makes the P1.38 seam work when the host belongs to <em>another</em> structure. The fix is
 * therefore a filter on the <b>reader's own</b> hosts only:
 * {@link InnerRedstoneNetwork#projectionReadableBy}.
 *
 * <h2>What this file pins, and in which colour</h2>
 * Every fixture here has <b>exactly one</b> supply source, and that is the point: with a bridge that
 * answered 15 on every side, "the world is feeding this cell" and "the run must fade" are two mutually
 * exclusive claims, and a fixture that asserts both is asserting a contradiction.
 *
 * <table>
 *   <tr><th>test</th><th>on HEAD</th><th>after P1.41</th><th>why</th></tr>
 *   <tr><td>{@code theReadersOwnProjectionIsNotAnExternalSupply}</td><td>compile-red</td><td>green</td>
 *       <td>the predicate does not exist on HEAD, so this file cannot even compile there; it is the rule
 *       itself, in the direction that fixes the fade</td></tr>
 *   <tr><td>{@code anotherCarriersProjectionIsStillRead}</td><td>compile-red</td><td>green</td>
 *       <td>same rule, the other direction - the one that keeps the fix narrow</td></tr>
 *   <tr><td>{@code theFilterIsNarrowOnPurpose}</td><td>compile-red</td><td>green</td>
 *       <td>same rule: air stays air, and an unnamed reader filters nothing</td></tr>
 *   <tr><td>{@code aRunOfCarriedDustFadesOnePerBlock}</td><td>red (15 where 14 is required)</td>
 *       <td>green</td><td>the author's symptom, with a single supply (the head's injected 15)</td></tr>
 *   <tr><td>{@code acrossTwoHostBlocksTheHopIsStillCharged}</td><td>red (15, 15, 15)</td><td>green</td>
 *       <td>the same leak across two host blocks; the stale neighbour is what makes it visible in one
 *       pass, so it does not depend on component iteration order</td></tr>
 *   <tr><td>{@code aSettledRunStaysFaded}</td><td>red (the run jumps back to 15 on the second pass)</td>
 *       <td>green</td><td>the persistent form of the same leak, per tick rather than per component -
 *       this is the one an earlier one-pass fixture missed</td></tr>
 *   <tr><td>{@code aSuperconductingRunInsideAStructureDoesNotFade}</td><td>green</td><td>green</td>
 *       <td>a no-regression pin: the one wire whose purpose is not to be charged</td></tr>
 *   <tr><td>{@code aMixedChainChargesOnEntryAndExitOnlyWhereTheTypeSaysSo}</td><td>red</td><td>green</td>
 *       <td>the hop is the receiver's, and the leak was breaking exactly the dust-receiver hops</td></tr>
 *   <tr><td>{@code anExternalSupplyStillTopsACellUp}</td><td>green</td><td>green</td>
 *       <td>the legitimate other half: a real world block offering 15 <em>does</em> top a cell up. The
 *       fixture that demanded a fade while answering 15 everywhere was contradicting this</td></tr>
 *   <tr><td>{@code anotherCarriersProjectionIsTheSeam}</td><td>red (14 required, 15 read)</td>
 *       <td>green</td><td>the P1.38 seam must survive <em>narrowing</em>; its second assertion is what a
 *       global skip would flip</td></tr>
 *   <tr><td>{@code theCarrierReadNamesItsReader}</td><td>compile-red</td><td>green</td>
 *       <td>source wiring: the reader reaches the merge, and the three-argument entry point is unchanged</td></tr>
 * </table>
 *
 * <h2>P1.45: the seam answers, and it is no longer free</h2>
 * The author's next report was the mirror image of P1.41's: <em>"two physicalised blocks that are not
 * connected transmit to each other with no attenuation at all"</em>. That is the seam itself, and the
 * defect was that the projection-sourced answer was a <b>raw emission</b> - P1.41 had refused the
 * reader's own host, which is why the run faded, but a host of <em>another</em> structure was still handed
 * over uncharged, while every other read in the mod charges the <b>receiver</b> its
 * {@code hopCost()}. The fix is one rule ({@link InnerRedstoneNetwork#seamHop}) applied where both frames
 * read a projected host, and it <b>charges rather than refuses</b>: the three tests added below stand one
 * supply on the far side of the seam and require fourteen (dust reading dust), fifteen (a superconductor
 * receiving), and that the host was <em>read</em> rather than refused.
 *
 * <h2>Honest boundary</h2>
 * A {@code ServerLevel} cannot be built in a unit test, and the merge itself
 * ({@code worldSignalAt(level, pos, dir, owner)}) takes one. So the end-to-end read is pinned by two
 * legs: the behaviour of the pure predicate in both directions, and the source-wiring assertions that
 * production passes the reader through to it. The field judgement stays the author's.
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
     * <p>The pose is a pure translation from a host's local coordinate to itself - the geometry a Sable
     * sub-level's plot has, where a local neighbour maps onto the world neighbour - so "the neighbour one
     * block east inside the structure" and "the neighbour one block east in the world" are the same cell.
     * That is exactly what makes the leaking projection readable in the first place.
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
     * The <b>local</b> coordinates of the hosts this fixture's world half was offered and refused, i.e.
     * the reads P1.41's rule answered with air.
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
     * <b>The world half of the bridge, as production builds it - both halves.</b>
     *
     * <ul>
     *   <li>the vanilla half is {@code base} - the signal a real world block would offer at that position
     *       (a carried host stands on air in the level, so an honest fixture passes 0 unless it is
     *       deliberately modelling a block standing there);</li>
     *   <li>the projected half is "the host a carrier is carrying at that position, and what it emits",
     *       which is what {@code projectedSignalAt} answers - including the P1.41 filter, applied here by
     *       the very predicate production applies it with,
     *       {@link InnerRedstoneNetwork#projectionReadableBy}.</li>
     * </ul>
     *
     * <p>The filter belongs to the source, not to this fixture: production reaches it through
     * {@code CarrierWorldIo#signalIntoOwnFrame} then
     * {@code worldSignalAt(level, pos, dir, reader)}, and the wiring test at the bottom asserts that. What
     * the fixture must not do is pretend to be a bridge that cannot see the reader at all - such a bridge
     * would not exercise the fix, and asserting the fade on top of it would be an assertion about the
     * fixture rather than about the code.
     */
    /**
     * <b>P1.45: the receiver the running solve is reading with.</b>
     *
     * <p>The world half of this fixture is a lambda, and a lambda is never asked who is calling - so, like
     * production's bridge ({@code CarrierWorldIo#readerTypeOf}, which looks the reader up in the carrier
     * index at its own local coordinate), the fixture has to be told. It is the component under test that
     * decides the charge, so the tests below set it to the type they are solving with. Everything the
     * lookup itself does is pinned by the wiring assertions instead, for the same reason the merged read
     * is: a unit test has no {@code ServerLevel}.
     */
    private ComponentType seamReader = ComponentType.DUST;

    /**
     * <b>P1.45: every host the world half handed to a read, by local coordinate.</b>
     *
     * <p>The counterpart of {@link #refused}: {@code refused} says "offered and refused", this says
     * "offered at all". Without it, a read that never reached the seam and a read that reached it and got
     * zero look identical from the outside - which is exactly how the first version of
     * {@code aSeamReadIsChargedAndNotRefused} failed with "expected 14 but was 0" and no way to tell
     * whether the rule under test or the fixture's geometry was at fault.
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
                        // P1.45: the table holds every carrier's hosts, so when the reading carrier holds
                        // nothing at this cell, another structure's host IS the seam P1.38 opened.
                        found = hostAt(Y, worldPos);
                    }
                    if (found != null) {
                        // OFFERED is not the same fact as REFUSED: this one records that the world half
                        // really did hand a host over (see `seamOffers`), so a future zero can be read.
                        seamOffers.add(found.localPos().asLong());
                    }
                    if (!InnerRedstoneNetwork.projectionReadableBy(found, X)) {
                        // Air where the reader's own host stands, exactly as the owner-aware read answers
                        // zero for it. The refused host's LOCAL coordinate is recorded, so a test can
                        // prove that a projection really was offered and really was refused - without
                        // that, "the run faded" could also mean "the fixture never handed the projection
                        // over at all". The coordinate, not the host: the live slot's power mutates, so a
                        // record of it would not compare equal to itself a pass later.
                        if (found != null) {
                            refused.add(found.localPos().asLong());
                        }
                        return PowerSolver.clamp(base);
                    }
                    int projected = CarrierProjection.emittedToward(found, worldDirection, false, false);
                    // P1.45: and the hop the reader pays, through the SAME rule production applies
                    // ({@code InnerRedstoneNetwork#seamHop}) - the seam answers, it is not refused, and it
                    // is no longer free.
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
    @DisplayName("another carrier's projected host is still read - the P1.38 seam, kept")
    void anotherCarriersProjectionIsStillRead() {
        CarrierProjection.ProjectedHost theirs = projected(Y, pos(6, 64, 5), 15);

        assertTrue(InnerRedstoneNetwork.projectionReadableBy(theirs, X),
                "two independently physicalised blocks see each other through this and nothing else: the "
                        + "level sees air where a carried block stands, so refusing it would make A->B "
                        + "dark again - the exact regression this round must not cause");
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
                "with no reader named the reader's own host is read like any other - the behaviour every "
                        + "reader had before P1.41, and what the world-side solve asks for");
    }

    // ------------------------------------------------- 2. the defect, in the solve --

    @Test
    @DisplayName("a run of carried dust fades one per block")
    void aRunOfCarriedDustFadesOnePerBlock() {
        // The feed is the chain head's own injected supply, and there is no world block anywhere: one
        // source, so "it fades" is the only thing the fixture can mean.
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
        // The author's own scene: each block holds its own component, and the blocks are inside ONE
        // structure. The two wires downstream of the head are given the value an ongoing run would already
        // hold, which is the ordinary steady state of a live machine - and it is what makes the leak
        // visible in a SINGLE pass rather than only after a tick or two: the projection of an inner
        // neighbour only carries power once that neighbour has some.
        //
        // On HEAD the fixed read is 15, 15, 15 (each wire's stale neighbour comes back uncharged through
        // the reader's own projection and wins the max); only the first assertion holds. After the fix it
        // is 15, 14, 13.
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
        // The same run as the author's, given a previous tick to settle in: after the first pass every
        // wire that is not the head already holds its faded value, so the projection of an inner
        // neighbour is a STALE full-strength reading of exactly the kind that used to win the max. On
        // HEAD the second pass restores the whole run to 15; after the fix the faded run is stable.
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
                    "and hop " + step + " is STILL one step below the one before it: on HEAD this pass "
                            + "read each inner neighbour's stale value back through its own projection and "
                            + "restored the whole run to 15 - a stale uncharged copy of an inner neighbour "
                            + "must not be able to push a settled wire back up");
        }
        assertEquals(0, second.solvedChanges(),
                "nothing changed on the second pass: the run is already at its fixpoint, which is the "
                        + "honest answer for a settled circuit and the opposite of the pre-fix behaviour "
                        + "(four wires jumping back to fifteen)");
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
                    "hop " + step + " of a superconductor costs nothing - the fix must not start charging "
                            + "the one wire whose whole purpose is not to be charged");
        }
    }

    @Test
    @DisplayName("dust -> superconductor -> dust: nothing through the superconductor, one out of it")
    void aMixedChainChargesOnEntryAndExitOnlyWhereTheTypeSaysSo() {
        // The charge is the RECEIVER's (PowerSolver#afterHop), so the pair of interesting hops is
        // dust -> superconductor (nothing: the superconductor receives) and superconductor -> dust (one:
        // the dust receives). One supply, at the head. P1.53 did NOT change this inner rule - it changed
        // the read at a block boundary; see HostWireHopChargeTest.
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

    // ------------------------------- 3. the two things the fix must not break, and one it must allow --

    @Test
    @DisplayName("an external supply still tops a cell up to fifteen - that is the semantics, not a bug")
    void anExternalSupplyStillTopsACellUp() {
        // A real world block offering 15 is a LEGAL external supply, and an external supply does not
        // charge a hop by definition. So a cell the world feeds holds 15 even while the run beside it
        // fades - the world half was not switched off, only the uncharged copy of an INNER neighbour was.
        // This is the assertion that a fixture answering 15 on every side while demanding a fade would
        // have contradicted: here the two facts are stated where they do not overlap.
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
    @DisplayName("another carrier's projection at the coordinate this solve reads is still read - P1.38")
    void anotherCarriersProjectionIsTheSeam() {
        // The anti-regression plus the legitimate P1.38 seam, in two assertions that cannot both be
        // satisfied by a wrong filter.
        //
        // X carries a TWO host run, fed at (5,64,5). Y is an independently physicalised structure whose
        // own coordinate (5,64,6) projects onto the cell X's host at (6,64,5) reads towards - the exact
        // geometry of the author's scene, where two blocks each physicalised on their own stand one world
        // cell apart. The level sees air there, so the projection is the only route across.
        //
        // The fade is what makes the two assertions bite: X's (6,64,5) must hold 14 from the charged
        // inner hop, and on HEAD it holds 15, because Y's full-strength dust at that world cell is read
        // back as an external supply and wins the max. So this test is RED on HEAD for a reason that has
        // nothing to do with the fix over-reaching, and it stays green only if Y's projection is still
        // read at all AND X's own neighbour is still charged.
        carryPowered(X, pos(5, 64, 5), ComponentType.DUST, 15);
        carry(X, pos(6, 64, 5), ComponentType.DUST);
        Slot theirs = carry(Y, pos(5, 64, 6), ComponentType.DUST);
        theirs.power = 15;

        solve(1L);

        assertEquals(14, powerOf(X, pos(6, 64, 5)),
                "the host next door takes one step less: this is the P1.38 seam read at the same time as "
                        + "the reader's own neighbours are charged, and on HEAD the other structure's "
                        + "full-strength dust at that world cell pushes this wire to 15 instead");
        assertFalse(refused.contains(pos(5, 64, 6).asLong()),
                "and it is read rather than refused: Y is not the carrier whose solve is running, so a fix "
                        + "that refused every projection - the global skip rejected before this round - "
                        + "would make the seam dark again and this test red");
    }

    // --------------------------------------- 3b. P1.45: the seam's own hop, by the receiver --

    @Test
    @DisplayName("P1.45: another structure's dust across the seam is CHARGED - fourteen, not fifteen")
    void aSeamReadIsChargedAndNotRefused() {
        // ONE supply, and it stands on the other side of the seam: the reader's wire has no neighbour of
        // its own (so no inner hop), no injected value and no world block at all (base = 0). The only
        // thing it can draw from is Y's dust at the world cell its south side reads. On HEAD that read is
        // a raw emission and the wire holds 15; with this round it holds 14 - the same "a dust beside a
        // dust costs one" the ground has always had - and the second assertion is the one a "refuse the
        // seam" fix would flip to 0, i.e. P1.38 lost.
        //
        // THE FAR SIDE NEEDS A REAL INPUT, and this is the trap the first version of this test fell into:
        // `CarrierLightNetwork#relax` resets every WIRE to zero and grows it back from its own supplies,
        // and each carrier is solved by its own pass within the same tick - so a `slot.power = 15` written
        // by hand on a carried wire is not a supply at all. When Y's pass happens to run first it drains Y
        // to zero, the seam then reads zero, and the near wire sits at 0 with nothing wrong in the rule
        // under test. A debug supply IS an input (`CarrierLightNetwork#supplyOf` takes `injectedPower`), so
        // the fixture gives the far side one - the same `carryPowered` the run fixtures use ("the one input
        // a fixture needs") - and keeps the hand-set value so the read does not depend on which of the two
        // structures is solved first.
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
                "the seam answered and the reader's hop was charged: fourteen - not the free fifteen this "
                        + "round exists for, and not zero");
        assertFalse(refused.contains(pos(5, 64, 6).asLong()),
                "and it was read rather than refused: Y is not the carrier whose solve is running, so the "
                        + "seam is charged, not closed (a global skip would answer 0 here)");
    }

    @Test
    @DisplayName("P1.45: the charge is the RECEIVER's, so a superconductor crosses the seam untouched")
    void aSuperconductingSeamKeepsEveryStep() {
        BlockPos reader = pos(5, 64, 5);
        carry(X, reader, ComponentType.SUPERCONDUCTOR);
        // The far side's input, for the reason spelled out in the test above: a hand-set power on a
        // carried wire is drained by that structure's own pass.
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
                "and the seam was read, not refused: P1.38's ability is what makes this fifteen reachable "
                        + "at all");
    }

    @Test
    @DisplayName("P1.45: the seam hop IS the solver's hop rule - the receiver pays, and only between wires")
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
                "a caller that names no reader leaves the read as it was before this round - the honest "
                        + "degradation, not a guess");
        assertEquals(15, InnerRedstoneNetwork.seamHop(null, ComponentType.DUST, 15),
                "and air has nothing to be charged for");
        assertEquals(0, InnerRedstoneNetwork.seamHop(theirDust, ComponentType.DUST, 0),
                "nothing stays nothing: a seam that answers zero is not turned into a negative");
    }

    // ------------------------------------------------------------- 4. the wiring --

    @Test
    @DisplayName("the carrier-side world read is the owner-aware one, and the world side keeps the projection")
    void theCarrierReadNamesItsReader() throws Exception {
        // The one part a unit test cannot build is a ServerLevel, and the merged read takes one. So the
        // wiring that makes the rule reachable in production is pinned at the source, the way this
        // codebase pins its other wiring decisions.
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
                "while CarrierWorldSignals - the three-argument entry point every pre-P1.41 reader used - "
                        + "still names no reader, so nothing about it changed");

        // ---- P1.45. These four are the red light for this round at RUN time: none of the strings exists
        // on the P1.44 tree, so they fail whether or not the new API is reachable - the same shape P1.44
        // used for its three runtime-red assertions.
        assertTrue(io.contains("InnerRedstoneNetwork.seamReader("),
                "P1.45: the carrier bridge names the receiver outright, so the charge is exact on a machine "
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
