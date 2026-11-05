package com.jiangyuefengyu.redstonecircuit.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.jiangyuefengyu.redstonecircuit.carrier.CarrierId;
import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.Slot;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>One machine's own surface: a lossless wire and a lossless host of the same structure read each other's
 * <em>own supply</em>, with the reader's own cell left out - they are not cut apart.</b>
 *
 * <p>Two narrower refusals both closed this seam instead: 
 * the structure half refused a lossless wire of the machine's own
 * blocks, and the run half refused a host of the wire's own structure. Both were reverted, because a machine on its
 * own surface works by exactly that pair - the inside has to be able to light the wire on the surface, and the
 * surface has to be able to light the inside. What replaces the cut is a different <em>question</em>, not a refusal:
 * a wire's value is {@code max(own supply, what its neighbours send it)}, so asking a partner what it would be
 * <b>without</b> the cell doing the asking can only lower the answer. A real source on the far side still arrives
 * (a superconductor charges nothing for the hop), and a pair that supplies nothing but itself comes down to zero
 * instead of holding what it had.
 *
 * <p>A unit test has no block registry ({@code Blocks.AIR.defaultBlockState()} throws before it returns), so a
 * superconductor <em>block state</em> cannot be built here and the scene cannot be settled through a level. What is
 * pinned is the rule as a function of the values it depends on, the arithmetic of the read, the two equations it
 * states iterated the way a solve iterates them, and the wiring that makes both sides ask it.
 */
class StructureHostSupplyTest {

    private static final CarrierId CARRIER =
            CarrierId.contraption(UUID.fromString("00000000-0000-0000-0000-0000000021aa"));
    private static final CarrierId OTHER =
            CarrierId.contraption(UUID.fromString("00000000-0000-0000-0000-0000000021bb"));

    private static BlockPos pos(int x, int y, int z) {
        return new BlockPos(x, y, z);
    }

    // ------------------------------------------------ which pairs the rule is for --

    @Test
    @DisplayName("one structure's two lossless halves are a pair - dust, a torch and another structure's host are not")
    void onlyALosslessPairOfOneStructureIsAPair() throws Exception {
        assertTrue(InnerRedstoneNetwork.losslessPairInOneStructure(
                        ComponentType.SUPERCONDUCTOR, ComponentType.SUPERCONDUCTOR, CARRIER, CARRIER),
                "a lossless wire and a lossless host of the SAME structure are one machine in two tables: they "
                        + "must read each other's own supply instead of each other's last answer");

        // The seam proper: two independently solved machines. The projection table exists for this and it must
        // keep answering with the neighbour's strength.
        assertFalse(InnerRedstoneNetwork.losslessPairInOneStructure(
                        ComponentType.SUPERCONDUCTOR, ComponentType.SUPERCONDUCTOR, CARRIER, OTHER),
                "a host of ANOTHER structure is the physicalisation seam, not this machine's surface");
        assertFalse(InnerRedstoneNetwork.losslessPairInOneStructure(
                        ComponentType.SUPERCONDUCTOR, ComponentType.SUPERCONDUCTOR, null, CARRIER),
                "and a coordinate no structure's own storage holds is the world, which keeps its supply");
        assertFalse(InnerRedstoneNetwork.losslessPairInOneStructure(
                        ComponentType.SUPERCONDUCTOR, ComponentType.SUPERCONDUCTOR, CARRIER, null),
                "the other way round too: with no owner there is no machine to be part of");

        // THE RISK THIS RULE MUST NOT WALK INTO: ordinary dust of the same structure. It pays a hop, so a pair
        // with it has contradictory equations and comes down by itself - and reading it as a pair would change a
        // working machine's answer for no reason at all.
        assertFalse(InnerRedstoneNetwork.losslessPairInOneStructure(
                        ComponentType.SUPERCONDUCTOR, ComponentType.DUST, CARRIER, CARRIER),
                "a dust inside the host beside the run is NOT a lossless partner: it keeps the plain read");
        assertFalse(InnerRedstoneNetwork.losslessPairInOneStructure(
                        ComponentType.DUST, ComponentType.SUPERCONDUCTOR, CARRIER, CARRIER),
                "and a dust run reading a lossless host is the same case from the other side");
        assertFalse(InnerRedstoneNetwork.losslessPairInOneStructure(
                        ComponentType.SUPERCONDUCTOR, ComponentType.TORCH, CARRIER, CARRIER),
                "a torch inside the host is a SOURCE, not a partner: it is read as one, which is exactly how the "
                        + "inside lights the wire on the surface");
        assertFalse(InnerRedstoneNetwork.losslessPairInOneStructure(null, ComponentType.SUPERCONDUCTOR,
                        CARRIER, CARRIER),
                "and a reader with no identity is nobody's partner");

        // The lookup degrades the way every carrier read degrades: nothing to inspect is never a pair.
        assertFalse(InnerRedstoneNetwork.sameStructureLosslessPair(null, pos(1, 0, 0), pos(2, 0, 0), null),
                "no level at all is not a pair: an absent structure must never change a working machine's read");
        assertFalse(InnerRedstoneNetwork.sameStructureLosslessPair(null, null, null, null),
                "and a question about no coordinate is not one either");

        // THE TWO SITES ASK THE SAME FUNCTION, and each excludes the OTHER member's cell.
        String network = flatten("src/main/java/com/jiangyuefengyu/redstonecircuit/logic/InnerRedstoneNetwork.java");
        String wire = flatten("src/main/java/com/jiangyuefengyu/redstonecircuit/block/SuperconductingWireBlock.java");
        String space = flatten("src/main/java/com/jiangyuefengyu/redstonecircuit/carrier/CarrierSpace.java");
        assertTrue(network.contains("losslessPairInOneStructure(ComponentType.SUPERCONDUCTOR, host.slot().type"),
                "the run side pairs the wire it stands on with the host's component kind");
        assertTrue(space.contains("InnerRedstoneNetwork.losslessPairInOneStructure(self == null ? null : self.type, ComponentType.SUPERCONDUCTOR, carrier, carrier)"),
                "the inside pairs its own component kind with the wire block - one pure function, so the two "
                        + "directions of one pair cannot drift apart");
        assertTrue(wire.contains("InnerRedstoneNetwork.supplyFromNeighbour( level, pos, neighbour, direction, here)"),
                "the run's own read goes through the rule instead of copying the neighbour's stored strength");
        assertFalse(wire.contains("structureHostCarriesNode"),
                "and the cut of the previous round is gone: nothing refuses a host of the wire's own structure");
        assertTrue(network.contains("return level.getSignal(neighbour, direction);"),
                "the stock read is still there for every other neighbour - the world, and a host of another "
                        + "structure");
    }

    // ----------------------------------------------------- which cell is left out --

    @Test
    @DisplayName("the excluded cell is the reader's own, and it is the only one")
    void theExcludedCellIsTheReaderAndOnlyThatOne() {
        BlockPos wire = pos(4, 64, 4);
        BlockPos hostCell = wire.relative(Direction.EAST);

        List<Direction> sides = InnerRedstoneNetwork.ownSupplySides(wire, hostCell);
        assertEquals(5, sides.size(), "five of six sides are asked: the sixth IS the reader");
        assertFalse(sides.contains(Direction.EAST),
                "the host's own cell is the one left out - it is the term that would hand the pair its own answer "
                        + "back");
        assertTrue(sides.contains(Direction.WEST) && sides.contains(Direction.UP) && sides.contains(Direction.DOWN),
                "every other side is asked, including the ones on the wire's far side: a real source there is "
                        + "exactly what must still arrive");
        assertEquals(6, InnerRedstoneNetwork.ownSupplySides(wire, null).size(),
                "with nothing to exclude all six sides are asked - the ordinary read of a world wire");
        assertTrue(InnerRedstoneNetwork.ownSupplySides(null, hostCell).isEmpty(),
                "and no cell is no sides: an unreadable coordinate is never a supply");

        // The value side of the same rule: what stands on the far side arrives, what stands on the reader's side
        // does not.
        Map<Long, Integer> offered = new HashMap<>();
        offered.put(wire.relative(Direction.NORTH).asLong(), 15);
        offered.put(hostCell.asLong(), 9);
        InnerRedstoneNetwork.SideSupply read = (side, direction) -> offered.getOrDefault(side.asLong(), 0);
        assertEquals(15, InnerRedstoneNetwork.supplyAround(wire, hostCell, read),
                "a source one cell along the wire still reaches this host - losslessly, because the hop is free");

        // The latch itself, in the same arithmetic: with the source gone, the only number left is the pair's own,
        // standing on the reader's cell. Excluded, it contributes nothing; counted, it is the whole answer.
        offered.remove(wire.relative(Direction.NORTH).asLong());
        offered.put(hostCell.asLong(), 15);
        assertEquals(0, InnerRedstoneNetwork.supplyAround(wire, hostCell, read),
                "a wire whose only other input is the pair itself supplies nothing: the answer is zero, not the "
                        + "pair's own last value");
        assertEquals(15, InnerRedstoneNetwork.supplyAround(wire, null, read),
                "while counting the reader's own cell hands that value straight back - which is the latch this "
                        + "exclusion is the whole of the fix for");
        assertEquals(0, InnerRedstoneNetwork.supplyAround(wire, hostCell, null),
                "a read with no implementation is zero, not an exception");
    }

    // --------------------------------------------------- the two frames a partner has --

    @Test
    @DisplayName("a partner's own supply is asked in BOTH of its frames - a source in either one lights the other half")
    void aPartnersOwnSupplyIsAskedInBothOfItsFrames() {
        BlockPos wire = pos(4, 64, 4);
        BlockPos hostCell = wire.relative(Direction.EAST);
        BlockPos besideHost = hostCell.relative(Direction.NORTH);

        Map<Long, Integer> insideTheStructure = new HashMap<>();
        Map<Long, Integer> inTheWorld = new HashMap<>();
        InnerRedstoneNetwork.SideSupply structure =
                (side, direction) -> insideTheStructure.getOrDefault(side.asLong(), 0);
        InnerRedstoneNetwork.SideSupply world =
                (side, direction) -> inTheWorld.getOrDefault(side.asLong(), 0);

        // THE DEFECT THIS PINS: the host is activated by a vanilla torch standing in the WORLD beside it. The
        // structure's own frame cannot see that block at all, so a read that asks one frame answers zero and the
        // surface wire beside a lit host stays dark - "the inside is lit, the surface is not".
        inTheWorld.put(besideHost.asLong(), 15);
        assertEquals(0, InnerRedstoneNetwork.supplyAround(hostCell, wire, structure),
                "the structure frame alone is blind to the world beside the drawn block: this is the old answer");
        assertEquals(15, InnerRedstoneNetwork.supplyAround(hostCell, wire, structure, world),
                "asking the host's other neighbourhood as well is what carries that source to the other half of "
                        + "the pair - and a superconductor charges nothing for the hop");

        // The direction that already worked keeps working: a source inside the structure.
        inTheWorld.clear();
        insideTheStructure.put(besideHost.asLong(), 15);
        assertEquals(15, InnerRedstoneNetwork.supplyAround(hostCell, wire, structure, world),
                "a torch inside the machine still reaches the run, read from the frame it stands in");
        assertEquals(15, InnerRedstoneNetwork.supplyAround(hostCell, wire, structure),
                "and the single-frame form is unchanged, so nothing that worked before this round moves");

        // THE PAIR ITSELF IS STILL EXCLUDED, IN BOTH FRAMES - or asking a second frame would put the latch back.
        insideTheStructure.clear();
        inTheWorld.clear();
        insideTheStructure.put(wire.asLong(), 15);
        inTheWorld.put(wire.asLong(), 15);
        assertEquals(0, InnerRedstoneNetwork.supplyAround(hostCell, wire, structure, world),
                "the reader's own cell is left out of the structure frame AND of the world frame: a pair that "
                        + "supplies nothing but itself still comes down to zero");

        // Nothing anywhere: zero, from either form.
        insideTheStructure.clear();
        inTheWorld.clear();
        assertEquals(0, InnerRedstoneNetwork.supplyAround(hostCell, wire, structure, world),
                "no supply in either frame is zero, not the pair's last value");
        assertEquals(0, InnerRedstoneNetwork.supplyAround(hostCell, wire, null, (InnerRedstoneNetwork.SideSupply) null),
                "and a read with no frame to ask at all is zero, not an exception");
    }

    @Test
    @DisplayName("both sites ask the partner's own supply in both frames, and both exclude the same cell")
    void bothSitesAskBothFrames() throws Exception {
        String network = flatten("src/main/java/com/jiangyuefengyu/redstonecircuit/logic/InnerRedstoneNetwork.java");
        String space = flatten("src/main/java/com/jiangyuefengyu/redstonecircuit/carrier/CarrierSpace.java");

        assertTrue(network.contains("worldFrameSupplyAround(level, carrier, excluded)"),
                "the run side's pair read names a SECOND frame beside the structure's own: a host of a machine "
                        + "has two neighbourhoods and its own supply is the larger of them");
        assertTrue(network.contains("if (skip != null && skip.contains(world)) {"),
                "and it leaves the reader's own cells out in that frame too, mapped through the pose");
        assertTrue(network.contains("return worldSignalAt(level, world, CarrierDirections.worldDirection(pose, direction), carrier)"),
                "through the one world-signal implementation, with the machine's own projections refused: a host "
                        + "of this structure must not arrive a second time as an uncharged copy");
        assertTrue(network.contains("if (SuperconductingWireBlock.isSuperconductor(level.getBlockState(world))) {"),
                "and a wire of ours is a node of the solve rather than a supply, in this frame as in supplyAt");

        assertTrue(space.contains("return pairOwnSupply(() -> worldSupplyOf(wireCell, reader), "
                        + "() -> structureSupplyOf(wireCell, reader, blocks));"),
                "the inside's pair read is the mirror image: the larger of the wire block's two frames, taken "
                        + "through the SAME composition the world half uses - one statement of the rule, so the "
                        + "two halves cannot combine the frames differently");
        assertTrue(space.contains("excludedWorld.add(bridge.worldPosOf(cell));"),
                "and it excludes this host's own network there as well - the pair's term, mapped per cell "
                        + "through the pose, one world coordinate for one local one");
        assertTrue(space.contains("excludedWorld.add(bridge.worldPosOf(hostCell));"),
                "this host's own cell with it, so the cell the pair's read is made from can never answer");
        assertTrue(space.contains("InnerRedstoneNetwork.supplyAround(wireCell, queryPos, (side, direction) -> {"),
                "the structure-frame read is the one the rule installs, byte for byte");
        assertTrue(space.contains("return bridge.signalIntoOwnFrame(wireCell, direction);"),
                "the world half goes through the bridge's owner-aware read, the same one the host's own input "
                        + "uses - started at the wire's cell, so the cell it asks is the one the exclusion above "
                        + "guards, and not the cell beyond it");
    }

    // --------------------------------------- the exclusion is the reader's whole network --

    @Test
    @DisplayName("two cells of one reader network stand beside the same host: excluding one leaves the other, so it holds")
    void twoCellsOfOneReaderNetworkNoLongerHoldTheHostUp() {
        // THE SHAPE. A superconductor run is a NETWORK: two of its cells can lie against the same host. Ask that
        // host "what would you be without me" and excluding only the cell the read is made from leaves the run's
        // OTHER cell standing at full strength - the run then feeds the host, the host feeds the run, and the pair
        // holds fifteen with nothing supplying it.
        BlockPos host = pos(4, 64, 4);
        BlockPos runA = host.relative(Direction.EAST);
        BlockPos runB = host.relative(Direction.NORTH);
        Map<Long, Integer> stock = new HashMap<>();
        stock.put(runA.asLong(), 15);
        stock.put(runB.asLong(), 15);
        InnerRedstoneNetwork.SideSupply read = (side, direction) -> stock.getOrDefault(side.asLong(), 0);

        assertEquals(15, InnerRedstoneNetwork.supplyAround(host, runA, read, Set.of(runA)),
                "one cell excluded: the run's other cell answers fifteen, and the loop stands - this is the red the "
                        + "whole-network exclusion turns green");
        assertEquals(0, InnerRedstoneNetwork.supplyAround(host, runA, read, Set.of(runA, runB)),
                "the reader's whole network left out: the host has nothing beside it, so the run measures nothing "
                        + "and comes down");
        assertEquals(0, InnerRedstoneNetwork.supplyAround(host, runA, read, Set.of(runA, runB, host)),
                "and naming extra cells can only lower it further - the exclusion is never a way to raise a value");
    }

    @Test
    @DisplayName("three cells of one reader network: the same, and a source outside it still arrives")
    void threeCellsOfOneReaderNetworkAndASourceOutsideIt() {
        BlockPos host = pos(6, 64, 6);
        BlockPos runA = host.relative(Direction.EAST);
        BlockPos runB = host.relative(Direction.NORTH);
        BlockPos runC = host.relative(Direction.WEST);
        BlockPos lever = host.relative(Direction.SOUTH);
        Map<Long, Integer> stock = new HashMap<>();
        Set<BlockPos> run = Set.of(runA, runB, runC);
        InnerRedstoneNetwork.SideSupply read = (side, direction) -> stock.getOrDefault(side.asLong(), 0);

        stock.put(runA.asLong(), 15);
        stock.put(runB.asLong(), 15);
        stock.put(runC.asLong(), 15);
        assertEquals(15, InnerRedstoneNetwork.supplyAround(host, runA, read, Set.of(runA)),
                "three cells of one run, one excluded: the other two still answer, so the ring of three holds "
                        + "exactly as the ring of two does");
        assertEquals(0, InnerRedstoneNetwork.supplyAround(host, runA, read, run),
                "all three excluded: nothing beside the host, so nothing to hold it up - the ring comes down "
                        + "whatever its size, which is what 'the whole network' buys over 'the one cell'");

        stock.clear();
        stock.put(lever.asLong(), 15);
        stock.put(runB.asLong(), 15);
        assertEquals(15, InnerRedstoneNetwork.supplyAround(host, runA, read, run),
                "and a real source OUTSIDE the reader's network is untouched by it: the lever is not a member, so "
                        + "it is still asked and still arrives at full strength");
        assertEquals(0, InnerRedstoneNetwork.supplyAround(host, runA, read, Set.of(runA, runB, runC, lever)),
                "only naming the source itself removes it - membership, not a blanket");
    }

    @Test
    @DisplayName("a real source outside the reader's network still arrives: the wider exclusion cuts no source")
    void aSourceOutsideTheNetworkStillArrives() {
        // A lever standing beside the partner and NOT part of the reader's network: the whole point of the
        // exclusion being a set of the READER's cells is that it never swallows anything else.
        BlockPos cell = pos(2, 64, 2);
        Map<Long, Integer> stock = new HashMap<>();
        BlockPos wireA = cell.relative(Direction.WEST);
        BlockPos wireB = cell.relative(Direction.EAST);
        BlockPos lever = cell.relative(Direction.NORTH);
        stock.put(wireA.asLong(), 15);
        stock.put(wireB.asLong(), 15);
        stock.put(lever.asLong(), 15);
        InnerRedstoneNetwork.SideSupply read = (side, direction) -> stock.getOrDefault(side.asLong(), 0);

        assertEquals(15, InnerRedstoneNetwork.supplyAround(cell, wireA, read, Set.of(wireA)),
                "with only its own cell excluded, the OTHER cell of the same run is still read - the loop the set "
                        + "exists to remove, and the reason a run wrapping a host could hold it up");
        assertEquals(15, InnerRedstoneNetwork.supplyAround(cell, wireA, read, Set.of(wireA, wireB)),
                "exclude the whole run and the lever is still there: nothing outside the reader's network is cut");
        assertEquals(0, InnerRedstoneNetwork.supplyAround(cell, wireA, read, Set.of(wireA, wireB, lever)),
                "only when the source itself is named does the answer fall - which is what proves the exclusion is "
                        + "a membership test and not a blanket");
    }

    @Test
    @DisplayName("both sites take the reader's network from the walk that already exists, and fall back to one cell")
    void bothSitesExcludeTheReadersWholeNetwork() throws Exception {
        String network = flatten("src/main/java/com/jiangyuefengyu/redstonecircuit/logic/InnerRedstoneNetwork.java");
        String space = flatten("src/main/java/com/jiangyuefengyu/redstonecircuit/carrier/CarrierSpace.java");
        String wire = flatten("src/main/java/com/jiangyuefengyu/redstonecircuit/block/SuperconductingWireBlock.java");

        // THE RUN SIDE: the cells it already collected for its own solve, scoped around the arithmetic.
        assertTrue(wire.contains("InnerRedstoneNetwork.readingFor(Set.copyOf(run), () -> {"),
                "the run hands its own cells to the pair read - the same set updateComponent already collected, no "
                        + "second walk");
        assertTrue(network.contains("return structureSupplyAt(server, neighbour, readerNetworkAt(runCell));"),
                "and the read asks for that network rather than for its own single cell");
        assertTrue(network.contains("return cell == null ? Set.<BlockPos>of() : Set.of(cell);"),
                "a read with nothing scoped for its cell falls back to one cell: a failure may exclude less, never "
                        + "the whole machine");

        // THE HOST SIDE: the carrier's own component walk, narrowed to lossless wires.
        assertTrue(space.contains("for (BlockPos cell : CarrierLightNetwork.collectComponent(this, queryPos)) {"),
                "the inside takes the network from the walk the carrier solve already uses");
        assertTrue(space.contains("slot != null && slot.type.isWire() && slot.type.hopCost() == 0"),
                "narrowed to lossless wires, so a lever or a torch beside the host - connected to it, but not part "
                        + "of the network whose echo this removes - is never excluded");
        assertTrue(space.contains("cells.add(queryPos.immutable());"),
                "and this host's own cell is always in the set, so the fallback is the old exclusion");

        // BOTH FRAMES EXCLUDE THE SAME SET.
        assertTrue(network.contains("public static int supplyAroundExcept(@Nullable BlockPos cell, @Nullable Set<BlockPos> excluded,"),
                "one read for a whole set, so neither frame can exclude a different thing");
        assertTrue(network.contains("for (BlockPos cell : excluded) { excludedWorld.add(CarrierProjection.project(pose, cell)); }")
                        || network.contains("excludedWorld.add(CarrierProjection.project(pose, cell));"),
                "the world frame maps the same set through the pose");
        assertTrue(network.contains("return supplyAroundExcept(cell, except(excluded, readerCells), read, null);"),
                "and the single-cell form is a delegate, so every older caller keeps its old answer");
    }

    // ------------------------------------------- the two equations, iterated as a solve --

    @Test
    @DisplayName("a pair that supplies nothing but itself comes down to zero in finite passes and stays there")
    void aPairThatSuppliesNothingComesDownToZeroAndStaysThere() {
        // The state this pins: a lever was on, both halves were fifteen, and the lever went off. What each
        // half reads from the other is the whole difference between the two rules.
        int[] latched = {15, 15};

        int[] unpaired = latched;
        for (int pass = 0; pass < 20; pass++) {
            unpaired = pairPass(false, 0, 0, unpaired);
        }
        assertEquals(15, unpaired[0],
                "the rule as it stood - each half copying the other's stored strength - is what held fifteen for "
                        + "ever: this is the latch, reproduced so the test is not vacuous");

        int[] paired = latched;
        int passes = 0;
        while (passes < 20 && (paired[0] != 0 || paired[1] != 0)) {
            paired = pairPass(true, 0, 0, paired);
            passes++;
        }
        assertEquals(0, paired[0], "asking each half what it would be WITHOUT the other brings the pair down");
        assertEquals(0, paired[1], "both halves, not one of them");
        assertTrue(passes <= 2, "in finite passes - a solve is allowed one round per half, not a countdown");

        int[] staysZero = paired;
        for (int pass = 0; pass < 20; pass++) {
            staysZero = pairPass(true, 0, 0, staysZero);
            assertEquals(0, staysZero[0] + staysZero[1], "and it stays down: nothing re-lights it from nothing");
        }

        // THE OTHER HALF OF THE SAME RULE: with a real source the pair still works. A lever on the inside lights
        // the wire on the surface, and a lever on the surface lights the component inside - neither is darkened
        // by the new read, because the source is not the reader.
        assertEquals(15, pairPass(true, 0, 15, latched)[0],
                "a real source on the inside still lights the run beside it, at full strength: a superconductor "
                        + "pays nothing for the hop");
        assertEquals(15, pairPass(true, 15, 0, latched)[1],
                "and a real source on the surface still lights the component inside it");
    }

    // ------------------------------------------------ the original mechanism, untouched --

    @Test
    @DisplayName("the original mechanism is kept in both directions, and ordinary dust still pays its hop")
    void theOriginalMechanismIsKeptBothWays() throws Exception {
        // Inside -> surface. The host beside the run holds a lever: not a lossless partner, so the plain read
        // stands and the run measures a full source.
        Slot hostLever = new Slot(ComponentType.LEVER);
        hostLever.power = 15;
        assertEquals(15, BlockSignalSources.hopChargedByReader(hostLever, 15, false),
                "a lever inside a host is still a full-strength source for the wire beside it - the read the "
                        + "new rule leaves alone");

        // Surface -> inside, through a host holding ordinary dust: one hop short, exactly as before.
        Slot hostDust = new Slot(ComponentType.DUST);
        hostDust.power = 14;
        assertEquals(13, BlockSignalSources.hopChargedByReader(hostDust, 14, true),
                "the ordinary wire beside a host still measures thirteen, never fifteen and never zero");

        // 粉不变: two ordinary wires still cost one hop each way, so a dust ring cannot hold itself up and the
        // new read never applies to it.
        assertEquals(14, PowerSolver.afterHop(ComponentType.DUST, ComponentType.DUST, 15),
                "two ordinary wires still cost one, in either direction");
        assertEquals(14, PowerSolver.afterHop(ComponentType.DUST, ComponentType.SUPERCONDUCTOR, 15),
                "a dust receiving from a superconductor pays its own hop, so a dust half of a pair comes down by "
                        + "itself - which is why the new read must never apply to it");
        assertEquals(15, PowerSolver.afterHop(ComponentType.SUPERCONDUCTOR, ComponentType.SUPERCONDUCTOR, 15),
                "two superconductors copy each other unchanged: gain exactly one, which is why the pair could "
                        + "hold a dead value for ever and why it now asks a different question instead");
        assertEquals(15, PowerSolver.afterHop(ComponentType.SUPERCONDUCTOR, ComponentType.DUST, 15),
                "a lossless receiver of a dust is free - the receiver is what pays");

        String store = flatten("src/main/java/com/jiangyuefengyu/redstonecircuit/carrier/CarrierStore.java");
        assertTrue(store.contains("public static CarrierId structureOwnerAt(ServerLevel level, BlockPos structurePos)"),
                "the ownership question is a read-only accessor on the store, not a new write path");
    }

    /**
     * <b>One pass of the two-cell model</b>: two lossless halves that are each other's only neighbour, plus an
     * optional real source beside each of them (on the side that is not the pair).
     *
     * @param paired {@code true} for the rule this round installs - each half reads the other's own supply with its
     *     own cell left out - and {@code false} for the rule it replaced, where each half copies the other's stored
     *     strength
     */
    private static int[] pairPass(boolean paired, int sourceIn, int sourceOnSurface, int[] held) {
        int[] next = new int[2];
        int[] source = {sourceIn, sourceOnSurface};
        for (int half = 0; half < 2; half++) {
            int other = held[1 - half];
            int ownSupply = source[1 - half];
            int read = paired ? ownSupply : other;
            next[half] = Math.max(source[half],
                    PowerSolver.afterHop(ComponentType.SUPERCONDUCTOR, ComponentType.SUPERCONDUCTOR, read));
        }
        return next;
    }

    /** One file's text, whitespace-flattened, so a wiring assertion is not a formatting assertion. */
    private static String flatten(String relative) throws Exception {
        return java.nio.file.Files.readString(java.nio.file.Path.of(relative))
                .replace("\r\n", "\n").replaceAll("\\s+", " ");
    }
}
