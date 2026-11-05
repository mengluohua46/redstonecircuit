package com.jiangyuefengyu.redstonecircuit.logic;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>The run half's symmetric pair question: the host a structure holds at a neighbour, asked of the structure the
 * host itself names instead of the structure the run's coordinate names.</b>
 *
 * <p>The live asymmetry this pins, from a diagnostic log: the carrier half printed
 * {@code PAIR SIDES ... answer=15} (it had taken the pair read, its answer from {@code ownSupplyOf}), while the run
 * half printed {@code RUN SIDES ... south=0(signal)} - the tag {@code signal} meaning the pair lookup declined and the
 * read fell through to {@code level.getSignal}. The same two cells, the same structure, two different answers about
 * whether they are a pair; each half then kept the other up, and the value flipped 15/0 on every tick instead of
 * settling.
 *
 * <p>The cause is a guard asked on one side only. {@code sameStructureLosslessPair} required
 * {@code CarrierStore#structureOwnerAt} of the wire's cell to equal the host's carrier and answered {@code false} when
 * that owner was {@code null}, while {@code CarrierSpace#losslessPartnerHere} never asks it - it names its own carrier
 * twice and reads the wire through its own structure's view. A cell of a structure's own grid can lie outside the plot
 * containment box (a wire built into a sub-level after it was physicalised), {@code plotOwnerAt} answers {@code null}
 * there, and the two halves disagree.
 *
 * <p><b>What is pinned, and how.</b> The fallback's decision is one pure function of two booleans
 * ({@link InnerRedstoneNetwork#pairByOwnView}) because a unit test has neither a {@code ServerLevel} nor a
 * superconductor <em>block state</em> - {@code Blocks.AIR.defaultBlockState()} throws before it returns, so no level
 * scene can be settled here. The wiring that feeds it is pinned the way this project already pins wiring: through the
 * <b>whitespace-flattened source text</b> (see {@code StructureHostSupplyTest} and {@code CarrierStructureSignalTest}),
 * so the assertion is about which carrier's view is read and in which order, never about formatting.
 */
class PairByOwnViewTest {

    // ------------------------------------------------------ the fallback's four combinations --

    @Test
    @DisplayName("a lossless host AND a lossless wire in the host's own view is a pair; either alone is not")
    void theFourCombinationsOfTheFallback() {
        // THE FALLBACK'S WHOLE RULE, one assertion per combination, because each of the other three is a read that
        // must stay exactly as it was.
        assertTrue(InnerRedstoneNetwork.pairByOwnView(true, true),
                "a lossless host with a lossless wire of the SAME structure's own view beside it is one machine in two "
                        + "tables: the run half must take the pair read, exactly as the carrier half already does");

        assertFalse(InnerRedstoneNetwork.pairByOwnView(true, false),
                "a lossless host with no lossless wire of its own structure beside it is not a pair: the neighbour is "
                        + "the open world's block, and the ordinary read is the one that answers for it");

        assertFalse(InnerRedstoneNetwork.pairByOwnView(false, true),
                "a wire of that structure beside a host holding dust, a torch, a repeater or a comparator is not a "
                        + "pair either: a source is read as a source, and a fading line pays a hop so a pair with it "
                        + "cannot hold itself up - {@code PowerSolver#losslessSeam} is where that half is decided");

        assertFalse(InnerRedstoneNetwork.pairByOwnView(false, false),
                "and two negatives are never a pair - the fallback is an AND, never an OR: taking either half alone "
                        + "would turn the rule ON for ordinary dust and for every host beside a wire of somebody "
                        + "else's structure, which is the one direction this change must not move");
    }

    // --------------------------------------- the view the fallback reads through is the host's OWN --

    @Test
    @DisplayName("the fallback asks structureHoldsLosslessWire on the HOST's own carrier view, at the run's cell")
    void theFallbackReadsTheWireThroughTheHostsOwnStructureView() throws Exception {
        String network = flatten("src/main/java/com/jiangyuefengyu/redstonecircuit/logic/InnerRedstoneNetwork.java");

        // THE UNGUARDED LOOKUP AT THE NEIGHBOUR: the host the structure index holds there, without asking whose
        // coordinate this is - the one question the guarded branch asked and the carrier half never asks.
        assertTrue(network.contains("CarrierStore.structureHostAtAnyOwner(server, hostCell)"),
                "the fallback takes the host at the neighbour from the unguarded structure lookup, or it is still "
                        + "asking the question that refused the host");

        // LITERALLY THE CARRIER HALF'S TEST: the wire is looked for in the HOST's own carrier's block view, at the
        // run's cell - not in the run's coordinate's owner, and not through the level. This one assertion is the
        // whole asymmetry: swap the view for another carrier's and the two halves disagree again.
        assertTrue(network.contains(
                        "structureHoldsLosslessWire(CarrierStore.blockViewOf(anyOwner.carrier()), wireCell)"),
                "the fallback asks the carrier half's own question - is the wire a block of THIS host's structure - "
                        + "through that structure's view, which is what makes 'one machine, two tables' true by "
                        + "construction on both sides");

        // AND THE TWO FACTS GO TO THE PURE FUNCTION IN ONE ORDER, so the seam cannot be handed in where the wire
        // belongs (a swapped call would answer true for a host beside nothing).
        assertTrue(network.contains("boolean pair = pairByOwnView( PowerSolver.losslessSeam(ComponentType.SUPERCONDUCTOR, anyOwner.slot().type), structureHoldsLosslessWire("),
                "the fallback's decision is the pure function, fed the seam first and the structure's own view second");

        // ASKED ONLY AFTER THE EXISTING PATH DECLINED: the owner branch keeps its answer byte for byte, and the
        // fallback is never reached before it.
        int ownerBranch = network.indexOf("losslessPairInOneStructure(ComponentType.SUPERCONDUCTOR, host.slot().type");
        int fallback = network.indexOf("CarrierStore.structureHostAtAnyOwner(server, hostCell)");
        assertTrue(ownerBranch >= 0, "the guarded branch is still there, unchanged");
        assertTrue(fallback > ownerBranch,
                "the fallback sits AFTER the owner branch: nothing that answers today is reached differently");
        assertTrue(network.contains("return structureSupplyAt(server, neighbour, readerNetworkAt(runCell));"),
                "and the pair read it leads to is unchanged: the host's own supply with the run's whole network "
                        + "left out, not a copy of the stored strength");
    }

    @Test
    @DisplayName("the new store lookup is the bare structure-index question - read-only, no ownership comparison")
    void theUnguardedLookupAsksNoOwnershipQuestion() throws Exception {
        String store = flatten("src/main/java/com/jiangyuefengyu/redstonecircuit/carrier/CarrierStore.java");

        assertTrue(store.contains("public static CarrierProjection.ProjectedHost structureHostAtAnyOwner(ServerLevel level,"),
                "the carrier half's question is an addition on the store, exactly as the ownership accessor was");

        // THE BODY IS THE LOOKUP AND NOTHING ELSE: the flattened text from the call to the method's closing braces
        // carries no plotOwnerOf and no atStructureOwnedBy, so the only difference from structureHostAt is the guard
        // that is not asked.
        assertTrue(store.contains("return PROJECTION.atStructure(level.dimension(), structurePos); } catch (RuntimeException | LinkageError unreadable) { return null; } }"),
                "the unguarded lookup performs exactly the structure-index read the guarded one performs - no "
                        + "ownership comparison, no block read, no pose arithmetic, and no state written");
    }

    /** One file's text, whitespace-flattened, so a wiring assertion is not a formatting assertion. */
    private static String flatten(String relative) throws Exception {
        return java.nio.file.Files.readString(java.nio.file.Path.of(relative))
                .replace("\r\n", "\n").replaceAll("\\s+", " ");
    }
}
