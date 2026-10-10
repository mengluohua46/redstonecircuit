package com.jiangyuefengyu.redstonecircuit.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import com.jiangyuefengyu.redstonecircuit.carrier.CarrierId;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierProjection;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierPose;
import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.Slot;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import org.joml.Vector3d;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The hop a vanilla redstone wire charges when it reads a host block - and the loop that hop closed.
 *
 * <p>A lever lights an outer redstone wire to fifteen, the wire lies against a host block holding a component
 * of ours, the next host block stands against that one, and the group is repeated. The chain reaches fifteen
 * all the way along, and with the chain cut the first block is fourteen: the fifteens are a <b>closed loop</b>
 * fed by the chain's own output, with two of its four edges charging nothing.
 *
 * <p>The missing half is the reader's hop. A redstone wire can only recognise a neighbouring wire by its
 * <b>block state</b>, and a component inside an ordinary host block is a block of stone to that scan - so it
 * entered vanilla's wire arithmetic as a source and the hop was never charged. The rule is the solver's own
 * {@code afterHop}, with the ordinary wire as the reader and the host's component as the sender, so a reader
 * that is <b>not</b> a wire (a repeater, a comparator, a lamp, a piston) still reads the host at full strength,
 * exactly as vanilla does.
 *
 * <p>A fixture carries one supply only: every assertion below states one emitting component and one reader,
 * and the carrier seam and the two refusals are asserted to be untouched rather than re-derived.
 */
class HostWireHopChargeTest {

    /** One physicalised block, so the seam assertions have two structures to tell apart. */
    private static final CarrierId STRUCTURE_A =
            CarrierId.subLevel(UUID.fromString("00000000-0000-0000-0000-00000000a4e1"));
    private static final CarrierId STRUCTURE_B =
            CarrierId.subLevel(UUID.fromString("00000000-0000-0000-0000-00000000b4e1"));

    private static final BlockPos A_WORLD = new BlockPos(-35, -60, -32);
    private static final BlockPos A_LOCAL = new BlockPos(20481032, 128, 20483080);

    private static Slot dust(int power) {
        Slot slot = new Slot(ComponentType.DUST);
        slot.power = power;
        return slot;
    }

    private static Slot component(ComponentType type, int power) {
        Slot slot = new Slot(type);
        slot.power = power;
        return slot;
    }

    private static CarrierProjection.ProjectedHost host(CarrierId carrier, BlockPos local, BlockPos world,
                                                        Slot slot) {
        return new CarrierProjection.ProjectedHost(carrier, local, world,
                CarrierPose.at(new Vector3d(world.getX() + 0.5, world.getY() + 0.5, world.getZ() + 0.5)),
                slot, slot == null ? 0 : slot.power);
    }

    // ---------------------------------------------- 1. the chain, one hop at a time --

    @Test
    @DisplayName("the chain fades by one on every hop: 15 -> 14 -> 13 -> 12")
    void theAuthorsChainFadesOnePerHop() {
        // Hop 1 - the outer wire into the first host: the world-side read rule, where a neighbouring vanilla
        // wire offers one step less.
        int firstHost = InnerRedstoneNetwork.neighbourSignal(15, true);
        assertEquals(14, firstHost, "outer wire 15 -> dust inside the block beside it");

        // Hop 2 - host into host. The receiver's cost: an ordinary wire pays one, a superconductor none.
        int secondHost = PowerSolver.afterHop(ComponentType.DUST, ComponentType.DUST, firstHost);
        assertEquals(13, secondHost, "the second host must measure thirteen, never fifteen");

        // Hop 3 - host back out into an outer wire: the hop that was never charged, because the wire took the
        // host's emission as a source at full strength.
        int outerWire = BlockSignalSources.hopChargedByReader(dust(secondHost), secondHost, true);
        assertEquals(12, outerWire, "and the wire outside it must measure twelve");
    }

    @Test
    @DisplayName("the same host read by anything that is not a wire keeps its full strength")
    void onlyAReaderThatIsAWireCharges() {
        Slot hostDust = dust(14);

        assertEquals(14, BlockSignalSources.hopChargedByReader(hostDust, 14, false),
                "a repeater, comparator, lamp or piston reading the host block sees what vanilla has "
                        + "always given it: the source value at full strength, no hop");
        assertEquals(13, BlockSignalSources.hopChargedByReader(hostDust, 14, true),
                "while a redstone wire beside it charges the hop, because that is what a wire does to "
                        + "a neighbouring wire");
    }

    @Test
    @DisplayName("a component inside a host block that is not a wire stays a full-strength source")
    void aComponentThatIsNotAWireIsNotCharged() {
        // A lever, torch or repeater is a source for the wire beside it in vanilla, so its value goes out
        // unchanged: only wire-to-wire hops cost anything.
        assertEquals(15, BlockSignalSources.hopChargedByReader(component(ComponentType.LEVER, 15), 15,
                true), "a lever inside a block lights the wire beside it to fifteen");
        assertEquals(11, BlockSignalSources.hopChargedByReader(component(ComponentType.REPEATER, 11), 11,
                true), "and a retaining repeater hands on exactly what it was given");
        assertEquals(15, BlockSignalSources.hopChargedByReader(component(ComponentType.TORCH, 15), 15,
                true), "a redstone torch is a source too");
    }

    @Test
    @DisplayName("a superconductor inside a host block costs nothing in EITHER direction")
    void aSuperconductorInsideKeepsItsTwoHalves() {
        // A superconductor's whole promise is a zero-cost hop, so it has to hold for the wire beside the
        // machine as well as for the run inside it - and for a superconductor RECEIVING from dust, where the
        // cheaper of the two wires pays.
        assertEquals(15, BlockSignalSources.hopChargedByReader(component(ComponentType.SUPERCONDUCTOR, 15),
                15, true), "a superconductor inside a host block lights the ordinary wire to fifteen");
        // And the other direction: a superconductor receiving from dust is free too, because the cheaper of
        // the two wires pays.
        assertEquals(15, PowerSolver.afterHop(ComponentType.SUPERCONDUCTOR, ComponentType.DUST, 15),
                "a superconductor receiving from dust keeps all fifteen");
        assertEquals(15, PowerSolver.afterHop(ComponentType.SUPERCONDUCTOR, ComponentType.SUPERCONDUCTOR,
                15), "and a whole run of them carries what it was given unchanged");
        // Dust beside dust still pays: the loop the charge exists for is untouched.
        assertEquals(14, PowerSolver.afterHop(ComponentType.DUST, ComponentType.DUST, 15),
                "two ordinary wires still cost one, which is what keeps a dust ring from holding");
    }

    @Test
    @DisplayName("an empty host, a zero answer and a wire at one never go below zero")
    void nothingAnswersBelowZero() {
        assertEquals(0, BlockSignalSources.hopChargedByReader(dust(0), 0, true),
                "a dark wire stays dark");
        assertEquals(0, PowerSolver.afterHop(ComponentType.DUST, ComponentType.DUST, 1),
                "and the last step of a fading line reaches zero rather than going negative");
        assertEquals(7, BlockSignalSources.hopChargedByReader(null, 7, true),
                "nothing in the block: there is no sender to charge a hop for");
        assertEquals(7, PowerSolver.afterHop(null, ComponentType.DUST, 7),
                "and a reader with no identity to give is the unfiltered form, as it always was");
    }

    // ------------------------------------------- 2. the rule is composed, not duplicated --

    @Test
    @DisplayName("the source rule still answers the emission: the hop is charged once, by the reader")
    void theEmissionAndTheReadersHopAreTwoStatements() {
        Slot hostDust = dust(14);

        // What `select` answers - which source, and what it emits - is one statement of the rule.
        BlockSignalSources.Read read = BlockSignalSources.select(hostDust, null, null, false, false,
                Direction.EAST, false, false);
        assertEquals(BlockSignalSources.Source.WORLD_STORE, read.source());
        assertEquals(14, read.answer(), "the host's own emission, before any hop");

        // The hop is a fact about the reader, applied once, on the value `select` chose.
        assertEquals(13, BlockSignalSources.hopChargedByReader(read.slot(), read.answer(), true),
                "one charge for a wire reader");
        assertEquals(14, BlockSignalSources.hopChargedByReader(read.slot(), read.answer(), false),
                "and none for anybody else - so the charge cannot be paid twice");
    }

    // --------------------------------------------------- 2b. the closed loop, edge by edge --

    /**
     * The chain as arithmetic: the four cells of {@code [wire][host][host][wire]} with the four edges of the
     * loop written out, iterated to a fixed point with a hard bound.
     *
     * <p>The edges are the wire reading a host block (the reader's hop - the one that was free), a wire's
     * strong power read through the host block beside it (vanilla arithmetic, which is not read through a
     * block that holds a component), a host reading a neighbouring wire, and a host reading a neighbouring
     * host's dust. Cutting the chain removes the second wire, the cell that carries the value back, so the
     * first block keeps the one charged hop it always had.
     *
     * <p>Both fixes are needed: with the reader's hop charged but the strong-power edge still read, the second
     * block is still fifteen. That edge needs a {@code ServerLevel} and real block states, which no offline
     * test has, so this is a model of the loop rather than a call into the game - and it is the loop itself
     * that is asserted, not one hop of it.
     */
    private static int[] settleAuthorsChain(int cells, boolean chargeTheReaderHop,
                                            boolean readThroughHostBlocks) {
        // cell % 3 == 0 is a vanilla redstone wire, the others are host blocks holding our dust.
        int[] value = new int[cells];
        int[] wireSeed = new int[cells];
        wireSeed[0] = 15; // the lever's wire: the ONE supply in this fixture
        for (int round = 0; round < 64; round++) {
            int[] next = value.clone();
            for (int i = 0; i < cells; i++) {
                if (i % 3 == 0) {
                    continue; // wires are updated below, from the hosts they touch
                }
                // A host reads: every adjacent wire costs one hop, every adjacent host's own dust costs one,
                // and an adjacent host's *block* hands over the strong power a wire gave it, uncharged,
                // because that is a source reading rather than a wire hop.
                int best = 0;
                for (int step = -1; step <= 1; step += 2) {
                    int at = i + step;
                    if (at < 0 || at >= cells) {
                        continue;
                    }
                    if (at % 3 == 0) {
                        best = Math.max(best, InnerRedstoneNetwork.neighbourSignal(next[at], true));
                    } else {
                        best = Math.max(best, PowerSolver.afterHop(ComponentType.DUST, ComponentType.DUST,
                                next[at]));
                        if (readThroughHostBlocks) {
                            // The strong-power edge: a block that holds a component is not a block of stone, so
                            // this term is not read at all.
                            for (int behind = -1; behind <= 1; behind += 2) {
                                int wire = at + behind;
                                if (wire >= 0 && wire < cells && wire % 3 == 0) {
                                    best = Math.max(best, next[wire]);
                                }
                            }
                        }
                    }
                }
                next[i] = best;
            }
            for (int i = 0; i < cells; i += 3) {
                int best = wireSeed[i];
                for (int step = -1; step <= 1; step += 2) {
                    int host = i + step;
                    if (host < 0 || host >= cells || host % 3 == 0) {
                        continue;
                    }
                    // The reader's edge: uncharged it is the host's own emission, which is the behaviour this
                    // fixture is written against.
                    best = Math.max(best, chargeTheReaderHop
                            ? BlockSignalSources.hopChargedByReader(dust(next[host]), next[host], true)
                            : next[host]);
                }
                next[i] = best;
            }
            if (java.util.Arrays.equals(next, value)) {
                return value;
            }
            value = next;
        }
        return value; // the bound is the loop's ceiling; a settled chain returns above
    }

    @Test
    @DisplayName("with the chain cut, the first block is fourteen - the one hop it always paid")
    void withNoWireAfterItTheFirstBlockIsFourteen() {
        // Two cells only: the lever's wire and the first host - the one hop the first block always paid.
        assertEquals(14, settleAuthorsChain(2, false, true)[1], "no chain: the wire's one hop, uncharged");
        assertEquals(14, settleAuthorsChain(2, true, true)[1], "no chain, reader hop charged");
        assertEquals(14, settleAuthorsChain(2, true, false)[1], "no chain, nothing to read back through");
    }

    @Test
    @DisplayName("with the chain continued the old rules hold every cell at fifteen: the loop's fixed point")
    void withTheChainContinuedTheOldRulesHoldEverythingAtFifteen() {
        // The old rules: the strong power a wire pushes into the conducting block beside it is read through
        // that block, and the wire beside a host is worth what the host is worth - so fifteen feeds itself.
        int[] settled = settleAuthorsChain(11, false, true);
        // Every cell with a wire AFTER it is held at fifteen by the loop. The last cell is the chain end:
        // there is nothing after it to read back through, so the loop cannot close there and it keeps the one
        // hop it always paid. The defect is the ten cells that ARE held up, not the tail.
        for (int i = 0; i < settled.length - 1; i++) {
            assertEquals(15, settled[i],
                    "cell " + i + " of " + java.util.Arrays.toString(settled)
                            + ": a cell with a wire after it is held at fifteen by the loop");
        }
        assertEquals(14, settled[settled.length - 1],
                "the chain end of " + java.util.Arrays.toString(settled)
                        + " has no wire after it, so the loop cannot close there and it keeps its one "
                        + "paid hop");
    }

    @Test
    @DisplayName("closing one edge alone is not enough: the loop needs both rules to be broken")
    void closingOneEdgeAloneIsNotEnough() {
        // The reader's hop charged but the strong-power edge still read: the group fades, yet the block a wire
        // strongly powers still answers fifteen to its neighbour, so the second cell never comes down.
        int[] hopOnly = settleAuthorsChain(11, true, true);
        assertEquals(14, hopOnly[1], "the first block is fourteen");
        assertEquals(15, hopOnly[2], "but the second is still fifteen: it reads the strong power the "
                + "first block received from the wire beside it");
        assertEquals(14, hopOnly[3], "only the wire beyond it has come down");

        // The strong-power edge closed but the reader's hop still free: the "all fifteen" latch is gone, yet
        // the wire beside a host is worth exactly what the host is worth, and the fade is halved.
        int[] bridgeOnly = settleAuthorsChain(11, false, false);
        assertEquals(13, bridgeOnly[2], "the second block does come down to thirteen");
        assertEquals(bridgeOnly[2], bridgeOnly[3],
                "but the wire beside it is worth the same as the host, with no hop charged: "
                        + java.util.Arrays.toString(bridgeOnly));
        assertEquals(11, bridgeOnly[5], "so the chain fades by two per group instead of one");
    }

    @Test
    @DisplayName("with both edges closed the chain fades by exactly one per cell: 15, 14, 13, 12, ...")
    void withBothEdgesClosedTheChainFadesOnePerCell() {
        int[] settled = settleAuthorsChain(11, true, false);

        assertEquals(15, settled[0], "the lever's wire, the one supply");
        for (int i = 1; i < settled.length; i++) {
            assertEquals(settled[i - 1] - 1, settled[i],
                    "every cell is one step below the one before it: " + java.util.Arrays.toString(settled));
        }
        assertEquals(14, settled[1], "the first block: fourteen, exactly as when the chain is cut");
        assertEquals(13, settled[2], "the second block: thirteen rather than fifteen");
        assertEquals(12, settled[3], "the next outer dust: twelve");
        assertEquals(5, settled[10], "and the fifth group is still fading rather than holding: "
                + java.util.Arrays.toString(settled));
    }

    // ----------------------------------------------- 3. the seam and the refusals, untouched --

    @Test
    @DisplayName("the seam read is not touched: the reading structure still reads another structure's host")
    void theSeamStillAnswers() {
        CarrierProjection.ProjectedHost aHost = host(STRUCTURE_A, A_LOCAL, A_WORLD, dust(9));

        assertFalse(CarrierProjection.readableBy(aHost, STRUCTURE_A),
                "a reader never takes its own projected host as an external supply");
        assertTrue(CarrierProjection.readableBy(aHost, STRUCTURE_B),
                "and another structure's host at that position is the seam, which must keep "
                        + "answering");
        assertTrue(CarrierProjection.readableBy(aHost, null),
                "the world-side read names no reader and filters nothing, as before");
    }

    @Test
    @DisplayName("the fix is not in the carrier read path, and not in the world-side read rule")
    void theCarrierPathAndTheWorldRuleAreUnchanged() throws Exception {
        String space = flat(
                "src/main/java/com/jiangyuefengyu/redstonecircuit/carrier/CarrierSpace.java");
        assertTrue(space.contains("io.signalIntoOwnFrame(queryPos, direction)"),
                "the carrier half still reads the world through the owner-aware form");
        assertTrue(space.contains("Math.max(worldSignal, structureSignal)"),
                "and the two halves are still combined as the larger");

        String network = flat(
                "src/main/java/com/jiangyuefengyu/redstonecircuit/logic/InnerRedstoneNetwork.java");
        assertTrue(network.contains("refusingOwnProjectionAt"),
                "the read-scoped refusal is still there");
        assertTrue(network.contains("projectionMayAnswer"),
                "asked at the one accessor both halves of a merged read go through");
        assertTrue(network.contains("return neighbourSignal(signal, state != null && state.is("
                        + "Blocks.REDSTONE_WIRE));"),
                "and the world-side hop rule is still the vanilla half's, word for word");
    }

    // ----------------------------------------------------------------- 4. wiring --

    @Test
    @DisplayName("the mixin applies the reader's hop, on the weak path only, behind the wire gate")
    void theMixinChargesTheReaderOnTheWeakPathOnly() throws Exception {
        String mixin = flat(
                "src/main/java/com/jiangyuefengyu/redstonecircuit/mixin/BlockStateSignalMixin.java");

        // Asserted on the mixin's source text: the free hop was a single return of the host's raw emission.
        assertFalse(mixin.contains("return read.answer();"),
                "the mixin must not hand the reader the host's raw emission: that single return is "
                        + "the free hop the loop was built on");
        assertTrue(mixin.contains("BlockSignalSources.hopChargedByReader("),
                "the mixin asks the rule for the reader's hop instead of returning the emission");
        assertTrue(mixin.contains("int answer = read.answer();"),
                "and it keeps what the host emits as the value the hop is charged on");
        assertTrue(mixin.contains("!strong && answer > 0 && read.slot() != null "
                        + "&& read.slot().type.isWire()"),
                "the one extra block read is behind the gate that can make it matter: a weak query, a "
                        + "non-zero answer, and a component that is itself a wire");
        assertTrue(mixin.contains("pos.relative(direction.getOpposite())"),
                "the reader is the block one step back along the query direction - the same asker the "
                        + "strong branch already looks up");
        assertTrue(mixin.contains("serverLevel.getBlockState(reader).is(Blocks.REDSTONE_WIRE)"),
                "and it is named by its block state, because that is all a signal query tells us");

        String sources = flat(
                "src/main/java/com/jiangyuefengyu/redstonecircuit/logic/BlockSignalSources.java");
        assertTrue(sources.contains("return PowerSolver.afterHop(ComponentType.DUST, slot.type, answer);"),
                "the arithmetic is the solver's one hop rule, with the vanilla wire as the receiver");

        String solver = flat(
                "src/main/java/com/jiangyuefengyu/redstonecircuit/logic/PowerSolver.java");
        assertTrue(solver.contains("public static int afterHop(@Nullable ComponentType receiver, "
                        + "@Nullable ComponentType sender,"),
                "stated on types so a caller that is not a node of a solve can use it");
        assertTrue(solver.contains("return afterHop(receiver.type(), sender.type(), value);"),
                "and the node form delegates to it, so there is still exactly one implementation");
    }

    @Test
    @DisplayName("a block that holds a component is never read as a conductor: the loop's return edge")
    void aHostBlockIsNotAConductor() throws Exception {
        String network = flat(
                "src/main/java/com/jiangyuefengyu/redstonecircuit/logic/InnerRedstoneNetwork.java");

        // Asserted on the source text: the strong-power-inclusive read is refused for a coordinate that holds
        // a component, because the block there IS the component rather than a block of stone.
        assertTrue(network.contains("RCConfig.hostAcceptsStrongPower() && !holdsComponentAt(blocks, pos) "
                        + "&& blocks instanceof SignalGetter signals)"),
                "the strong-power-inclusive read is refused for a coordinate that holds a component: the "
                        + "block there IS the component, not a block of stone - the mirror of "
                        + "PowerSolver#signalAnswer's neighbourHoldsComponent branch");
        assertTrue(network.contains("private static boolean holdsComponentAt(BlockGetter blocks, "
                        + "BlockPos pos)"),
                "asked through one named predicate");
        assertTrue(network.contains("return store != null && !store.isEmpty() && store.slotAt(pos) != null;"),
                "and answered from the world store, which is where a world host's component lives - with "
                        + "the cheapest guard first, because a level with no components is the common case");
        assertTrue(network.contains("RCConfig.hostAcceptsStrongPower()"),
                "the existing switch keeps its meaning for plain neighbours: how strictly a block that is "
                        + "not ours is read");
    }

    @Test
    @DisplayName("the trace reports what the world received rather than what the host emitted")
    void theTraceTakesTheFinalAnswer() throws Exception {
        String store = flat(
                "src/main/java/com/jiangyuefengyu/redstonecircuit/carrier/CarrierStore.java");

        assertNotNull(store);
        assertTrue(store.contains("public static void noteSignalRead(@Nullable ServerLevel level, "
                        + "BlockSignalSources.Read read, @Nullable BlockPos askedPos, "
                        + "@Nullable Direction direction, int answer)"),
                "the line is handed the value the query was finally answered with");
        assertTrue(store.contains("frame, read.source(), answer);"),
                "and it prints that, not the pre-hop emission");
    }

    /** One file's text, whitespace-flattened, so a wiring assertion is not a formatting assertion. */
    private static String flat(String relative) throws Exception {
        return java.nio.file.Files.readString(java.nio.file.Path.of(relative))
                .replace("\r\n", "\n").replaceAll("\\s+", " ");
    }
}
