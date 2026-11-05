package com.jiangyuefengyu.redstonecircuit.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.jiangyuefengyu.redstonecircuit.carrier.CarrierId;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierPose;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierProjection;
import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.Slot;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import org.joml.Vector3d;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>The wire side of a lossless pair that lives in the projection table.</b>
 *
 * <p>A carried host is drawn over air, so the level's own store can never answer for it: the projection table
 * ({@code CarrierStore#projectedHostAt}) is the only place that can, and {@code structureHostAt} - keyed by the
 * structure's <em>own</em> coordinate - names a host of a structure's own blocks instead. Read through the level
 * query, a host that is being drawn therefore hands the wire beside it its <b>stored</b> power at the wire's own
 * hop cost, which for a superconductor is nothing: two copies at gain exactly one in both directions, so every
 * value is a fixed point and a strength nothing supplies any more stands there for ever - the observation that, 
 * 外层线一碰到里面就卡住 (as soon as an outer line and the inside touch, it sticks).
 *
 * <p>What a pair answers instead is the other half's <b>own supply</b>: {@code max(own supply, what the
 * neighbours send)} read around the host with the run's own <em>network</em> left out. Dropping the reader's own
 * term from a maximum can only lower an answer, so this cannot light anything that was dark, and a real source on
 * the far side still arrives in full - in either of the two frames a host of a structure really has: the
 * structure's own blocks, and the world the structure is drawn in.
 *
 * <p>A {@code ServerLevel} and a superconductor <em>block state</em> cannot be built in a unit test, so what is
 * pinned here is the rule as a function of the values it depends on: which neighbour is a pair at all, which cells
 * the read leaves out, and the arithmetic of the read in both frames. The level-taking wrapper
 * ({@code InnerRedstoneNetwork#projectedHostSupplyAt}) only supplies the two frames and the exclusion.
 */
class ProjectedHostPairReadTest {

    /** The structure whose host is being drawn - 被搬运的一方, the one the projection table answers for. */
    private static final CarrierId CARRIER =
            CarrierId.subLevel(UUID.fromString("00000000-0000-0000-0000-00000000c151"));
    /** An independently physicalised structure, for the answers this rule must NOT change. */
    private static final CarrierId OTHER =
            CarrierId.subLevel(UUID.fromString("00000000-0000-0000-0000-00000000c152"));

    /**
     * The pose's translation: an <b>offset</b>, a whole number of blocks, so a local coordinate and the world block
     * it is drawn at differ by exactly this - which is what makes "the run's cell in the world" and "the run's cell
     * in the structure's own frame" two coordinates of one pair, both of them worth excluding.
     *
     * <p>An offset and not an anchor, and the y is the part that bites: {@code project} adds the whole translation
     * to the local coordinate, so an anchor's {@code y = 64} would be counted twice and draw the host at
     * {@code y = 128}. Every coordinate below is derived from this one and from the host's own cell, so the
     * fixture cannot disagree with itself again.
     */
    private static final BlockPos SHIFT = pos(100, 0, 100);
    private static final CarrierPose POSE =
            CarrierPose.at(new Vector3d(SHIFT.getX(), SHIFT.getY(), SHIFT.getZ()));

    /** The host's own coordinate inside the structure ... */
    private static final BlockPos HOST_LOCAL = pos(5, 64, 5);
    /** ... and the world block it is drawn at: the local cell plus the pose's translation, by construction. */
    private static final BlockPos HOST_WORLD =
            HOST_LOCAL.offset(SHIFT.getX(), SHIFT.getY(), SHIFT.getZ());
    /** The superconducting wire block in the world: it touches the drawn host on its EAST side. */
    private static final BlockPos RUN = HOST_WORLD.relative(Direction.EAST);
    /** The same cell in the structure's own frame - where the pair's own term stands in that frame. */
    private static final BlockPos RUN_LOCAL = HOST_LOCAL.relative(Direction.EAST);
    /** Vanilla's direction as the wire's own read names it: from the run towards the neighbour. */
    private static final Direction RUN_TO_HOST = Direction.WEST;

    private static BlockPos pos(int x, int y, int z) {
        return new BlockPos(x, y, z);
    }

    /**
     * A projected host, built the way the projection table's builder builds one: the pose, the coordinate inside
     * the structure, the block it is drawn at, and the live slot. {@code stored} is the value the slot already
     * holds - the number a <em>stored</em> read would hand over.
     */
    private static CarrierProjection.ProjectedHost projected(CarrierId carrier, BlockPos local,
                                                             ComponentType type, int stored) {
        Slot slot = new Slot(type);
        slot.power = stored;
        BlockPos world = CarrierProjection.project(POSE, local);
        return new CarrierProjection.ProjectedHost(carrier, local, world, POSE, slot, stored);
    }

    /** One frame of the read, as a table of what each side of the host offers - a {@code SideSupply} stub. */
    private static InnerRedstoneNetwork.SideSupply frame(Map<Long, Integer> offered) {
        return (side, direction) -> offered.getOrDefault(side.asLong(), 0);
    }

    /** The exclusion the run's own cell deserves, in the structure's frame. */
    private static Set<BlockPos> exclusion() {
        return InnerRedstoneNetwork.localPairExclusion(POSE, HOST_LOCAL, RUN, RUN_TO_HOST);
    }

    // ------------------------------------------- 1. nothing beside the host: the answer is 0 --

    @Test
    @DisplayName("a lossless projected host with nothing beside it: the wire reads 0, not the host's stored power")
    void aLosslessProjectedHostWithNothingBesideItReadsZero() {
        CarrierProjection.ProjectedHost host = projected(CARRIER, HOST_LOCAL, ComponentType.SUPERCONDUCTOR, 15);
        assertEquals(HOST_LOCAL.offset(SHIFT.getX(), SHIFT.getY(), SHIFT.getZ()), host.worldPos(),
                "the contract of the whole fixture: the host's own cell plus the pose's translation IS the world "
                        + "cell the run touches. Stated through the projection, so a pose that counted its "
                        + "translation twice would fail here rather than quietly drawing the host at another y");
        assertEquals(HOST_WORLD, host.worldPos(),
                "and that is the constant every other line of this fixture is written against");

        assertTrue(InnerRedstoneNetwork.projectedLosslessPair(ComponentType.SUPERCONDUCTOR, null, host),
                "a lossless wire block and a lossless host drawn beside it ARE a pair: two halves at gain exactly "
                        + "one in both directions, so neither may be handed the other's last answer");

        // THE READ THIS REPLACES. That is literally what the level query answers through the mixin's projected
        // half, and it is a copy: fifteen in, fifteen out, at a superconductor's hop cost of nothing.
        assertEquals(15, CarrierProjection.emittedToward(host, RUN_TO_HOST, false, false),
                "the host is holding fifteen, and the stored read would hand it over unchanged - gain one");

        // The pair's own term really is the only thing beside the host: it stands where the run stands in each
        // frame. Both are excluded, and the answer is nothing.
        Map<Long, Integer> structure = new HashMap<>();
        Map<Long, Integer> world = new HashMap<>();
        structure.put(RUN_LOCAL.asLong(), 15);
        world.put(RUN_LOCAL.asLong(), 15);

        assertEquals(15, InnerRedstoneNetwork.projectedPairSupply(HOST_LOCAL, Set.of(), frame(structure),
                        frame(world)),
                "counting the run's own cell hands the pair's own value straight back - the latch itself, and the "
                        + "reason the exclusion is the whole of the fix");
        assertEquals(0, InnerRedstoneNetwork.projectedPairSupply(HOST_LOCAL, exclusion(), frame(structure),
                        frame(world)),
                "with the run's own network left out the host's own supply is nothing, so the run measures "
                        + "nothing: the answer is zero, never the pair's last value");
    }

    // --------------------------------------- 2. a real source beside the host still arrives --

    @Test
    @DisplayName("a torch carried beside the projected host: full strength still arrives, in either frame")
    void aTorchCarriedBesideTheProjectedHostStillArrivesAtFullStrength() {
        // The host itself holds NOTHING - the slot is at zero - so a fifteen in the answer can only be a supply.
        CarrierProjection.ProjectedHost host = projected(CARRIER, HOST_LOCAL, ComponentType.SUPERCONDUCTOR, 0);
        assertTrue(InnerRedstoneNetwork.projectedLosslessPair(ComponentType.SUPERCONDUCTOR, null, host),
                "the pair is still a pair: a source feeding it must reach the wire beside it");

        BlockPos torchLocal = HOST_LOCAL.relative(Direction.NORTH);
        Map<Long, Integer> structure = new HashMap<>();
        Map<Long, Integer> world = new HashMap<>();

        // 机器里面的火把 - a torch of the structure's own blocks: the first frame a host of a structure has.
        structure.put(torchLocal.asLong(), 15);
        assertEquals(15, InnerRedstoneNetwork.projectedPairSupply(HOST_LOCAL, exclusion(), frame(structure),
                        frame(world)),
                "a torch inside the machine still reaches the wire on its surface: the pair asks what the host is "
                        + "supplied with, and a superconductor charges nothing for the hop");

        // 世界里的火把 - a vanilla torch standing in the world beside the block that is drawn there. The
        // structure's own blocks cannot see that block at all; only the second frame can.
        structure.clear();
        world.put(torchLocal.asLong(), 15);
        assertEquals(15, InnerRedstoneNetwork.projectedPairSupply(HOST_LOCAL, exclusion(), frame(structure),
                        frame(world)),
                "and a source that lives in the world the structure is drawn in arrives too - a read that asked one "
                        + "frame alone would answer zero here and leave the surface dark while the inside is lit");

        // THE SECOND FRAME IS A REAL HALF AND NOT A DUPLICATE: with the torch in the world half alone, a read that
        // asked the structure frame only answers nothing - which is exactly the defect this round closes, "the
        // inside is lit and the surface is not".
        assertEquals(0, InnerRedstoneNetwork.projectedPairSupply(HOST_LOCAL, exclusion(), frame(structure), null),
                "the structure frame alone is blind to the world beside the drawn block: this is the old answer");
        assertEquals(15, InnerRedstoneNetwork.projectedPairSupply(HOST_LOCAL, exclusion(), frame(structure),
                        frame(world)),
                "asking the host's other neighbourhood as well is what carries that source to the wire");

        // Nothing anywhere: zero, from either form - the honest answer, never an exception and never the pair's
        // own value.
        world.clear();
        assertEquals(0, InnerRedstoneNetwork.projectedPairSupply(HOST_LOCAL, exclusion(), frame(structure),
                        frame(world)),
                "no supply in either frame is zero");
        assertEquals(0, InnerRedstoneNetwork.projectedPairSupply(HOST_LOCAL, exclusion(), null,
                        (InnerRedstoneNetwork.SideSupply) null),
                "and a read with no frame to ask at all is zero, not an exception");
    }

    // -------------------------------- 3. ordinary dust beside the wire keeps the unchanged read --

    @Test
    @DisplayName("ordinary redstone dust beside the wire is not a pair: the stock read stays exactly as it is")
    void ordinaryDustBesideTheWireKeepsTheStockRead() {
        CarrierProjection.ProjectedHost lossless = projected(CARRIER, HOST_LOCAL, ComponentType.SUPERCONDUCTOR, 15);

        assertFalse(InnerRedstoneNetwork.projectedLosslessPair(ComponentType.DUST, null, lossless),
                "ordinary dust beside the wire pays a hop, so a pair with it cannot hold itself up: it keeps the "
                        + "stock read - which is what every fading line already is, and it must not change");
        assertFalse(InnerRedstoneNetwork.projectedLosslessPair(null, null, lossless),
                "and a run with no identity is nobody's partner");

        // The component INSIDE the host decides it too, and only the lossless one makes a pair.
        assertFalse(InnerRedstoneNetwork.projectedLosslessPair(ComponentType.SUPERCONDUCTOR, null,
                        projected(CARRIER, HOST_LOCAL, ComponentType.DUST, 15)),
                "a dust inside the host beside the run pays a hop as well: 普通红石粉 is not a lossless partner");
        assertFalse(InnerRedstoneNetwork.projectedLosslessPair(ComponentType.SUPERCONDUCTOR, null,
                        projected(CARRIER, HOST_LOCAL, ComponentType.TORCH, 15)),
                "a torch inside it is a SOURCE, read as one - that is how the inside lights the surface");
        assertFalse(InnerRedstoneNetwork.projectedLosslessPair(ComponentType.SUPERCONDUCTOR, null,
                        projected(CARRIER, HOST_LOCAL, ComponentType.REPEATER, 15)),
                "and a repeater inside it is a diode, not a partner");
        assertFalse(InnerRedstoneNetwork.projectedLosslessPair(ComponentType.SUPERCONDUCTOR, null,
                        projected(CARRIER, HOST_LOCAL, ComponentType.COMPARATOR, 15)),
                "nor is a comparator");

        // Nothing to inspect is never a pair: an unreadable table must look like a jar without the bridge.
        assertFalse(InnerRedstoneNetwork.projectedLosslessPair(ComponentType.SUPERCONDUCTOR, null, null),
                "nothing projected at that cell is not a pair");
        assertFalse(InnerRedstoneNetwork.projectedLosslessPair(ComponentType.SUPERCONDUCTOR, null,
                        new CarrierProjection.ProjectedHost(CARRIER, HOST_LOCAL, HOST_WORLD, POSE, null, 0)),
                "a projected host that holds no component is not one either");

        // AND THE STOCK READ KEEPS ITS ANSWER: a dust inside a projected host still offers its own strength to the
        // wire beside it, which is what every other neighbour in the world does.
        assertEquals(15, CarrierProjection.emittedToward(
                        projected(CARRIER, HOST_LOCAL, ComponentType.DUST, 15), RUN_TO_HOST, false, false),
                "the unchanged read: a dust's strength, at the wire's own hop cost");
    }

    // --------------------------- 4. a neighbour the structure index resolves keeps its own read --

    @Test
    @DisplayName("a host the structure index already resolves keeps the read that path makes - unchanged")
    void aHostResolvedByTheStructureIndexKeepsItsOwnRead() {
        CarrierProjection.ProjectedHost lossless = projected(CARRIER, HOST_LOCAL, ComponentType.SUPERCONDUCTOR, 15);

        // The structure's own coordinate answered for this neighbour, so this second table must not answer as well:
        // one neighbour, one read. That path - structureSupplyAt, the machine's own surface - is asked first and
        // keeps its answer exactly.
        assertFalse(InnerRedstoneNetwork.projectedLosslessPair(
                        ComponentType.SUPERCONDUCTOR, ComponentType.SUPERCONDUCTOR, lossless),
                "a neighbour the structure index resolves is read by that path, not by this one: the projection "
                        + "rule is an extension of the pair lookup, never a second answer for one cell");

        // And the structure-index rule itself is untouched, stated where it cannot be confused with this one.
        assertTrue(InnerRedstoneNetwork.losslessPairInOneStructure(
                        ComponentType.SUPERCONDUCTOR, ComponentType.SUPERCONDUCTOR, CARRIER, CARRIER),
                "one structure's two lossless halves are still one machine in two tables");
        assertFalse(InnerRedstoneNetwork.losslessPairInOneStructure(
                        ComponentType.SUPERCONDUCTOR, ComponentType.SUPERCONDUCTOR, CARRIER, OTHER),
                "and a host of ANOTHER structure is still the physicalisation seam, whose own rule must keep "
                        + "answering");
        assertFalse(InnerRedstoneNetwork.sameStructureLosslessPair(null, pos(1, 0, 0), pos(2, 0, 0), null),
                "with no level at all that lookup is still not a pair - an absent structure never changes a "
                        + "working machine's read");
    }

    // -------------------------------- 5. the exclusion is the run's network, carried across the pose --

    @Test
    @DisplayName("the run's whole network is left out of the pair read, in the structure's own frame")
    void theRunsWholeNetworkIsLeftOutOfThePairRead() {
        BlockPos secondCell = RUN.relative(Direction.SOUTH);
        Set<BlockPos> network = Set.of(RUN.immutable(), secondCell.immutable());

        // Scoped the way production scopes it while a run recalculates (readingFor), so the read below sees the
        // network and not just the one cell the read happens to be made from.
        Set<BlockPos> excluded = InnerRedstoneNetwork.readingFor(network, ProjectedHostPairReadTest::exclusion);

        assertTrue(excluded.contains(RUN_LOCAL),
                "the run's own cell, in the structure's frame, is the smallest form of the loop and is removed");
        assertTrue(excluded.contains(RUN_LOCAL.relative(Direction.SOUTH)),
                "and so is the rest of the run where it reaches across the pose: a wire that bites the same host "
                        + "from two cells would otherwise leave the longer loop standing");
        assertFalse(excluded.contains(HOST_LOCAL.relative(Direction.NORTH)),
                "nothing else is excluded - a real source beside the host is exactly what must still arrive");

        // The minimum is available even when the inverse cannot prove a coordinate: the run's own cell is one step
        // from the host along the side it lies on.
        Set<BlockPos> noCells = InnerRedstoneNetwork.localPairExclusion(POSE, HOST_LOCAL, null, RUN_TO_HOST);
        assertTrue(noCells.contains(RUN_LOCAL),
                "one step from the host towards the run, in the local frame: the exclusion can never be lost to an "
                        + "inverse that answers nothing, because excluding more can only lower the answer");
        assertTrue(InnerRedstoneNetwork.localPairExclusion(null, HOST_LOCAL, RUN, RUN_TO_HOST).isEmpty(),
                "and a pose that cannot be read excludes nothing rather than guessing");

        // The network the read is given is the one a solve scoped; outside a solve it is the cell alone, the
        // conservative exclusion this round replaces.
        assertNotNull(InnerRedstoneNetwork.readerNetworkAt(RUN));
        assertEquals(Set.of(RUN), InnerRedstoneNetwork.readerNetworkAt(RUN),
                "outside a scoped solve the network is the one cell, exactly as before");
    }
}
