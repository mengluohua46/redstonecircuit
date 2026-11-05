package com.jiangyuefengyu.redstonecircuit.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.jiangyuefengyu.redstonecircuit.carrier.CarriedHost;
import com.jiangyuefengyu.redstonecircuit.carrier.CarriedHostIndex;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierId;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierPose;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierProjection;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierSpace;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierWorldIo;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierWorldSignals;
import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.Slot;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>Two SEPARATE physicalised structures, each carrying a lossless host, are ONE network or they are a latch.</b>
 *
 * <p>The defect this pins: each structure's own pass reads the other through the <b>world frame only</b> (the
 * structure frame answers 0 for a neighbour that is not in that structure's own index), both receivers charge
 * nothing for a hop, and the read is therefore a plain copy at gain exactly one. The pair's equations are
 * {@code A = B} and {@code B = A}, every value is a fixed point, and a strength nothing supplies any more stands
 * there for ever. {@code losslessPairInOneStructure} refuses that pair <b>by design</b> - it compares structures,
 * and the two halves are two - so the fix is not another read rule but one solve: both components are nodes of it
 * ({@code PortNode}), it starts every wire from zero, and it is only ever raised by a real supply.
 *
 * <p>What the scenes assert, in the order they run:
 * <ul>
 *   <li><b>the join</b> - a carried component, seeded through {@code collectCarriedUnion}, pulls in the component
 *       of the structure facing it, one {@code PortNode} per carrier, from either table that can name it (the
 *       projection table by world position, and the structure table by plot coordinate);</li>
 *   <li><b>the intra-union edge</b> - the value a port takes from the other port is that port's <b>node</b>
 *       value. A port's supply is read once, before the loop, and the loop starts every wire from zero, so a
 *       supply copied from the partner's <em>record</em> is zero at that moment: a scene in which the far side
 *       alone is fed therefore separates the two answers - 15 through the node, 0 through the copy;</li>
 *   <li><b>the refusal</b> - a partner whose component is not wholly lossless is left to its own structure's
 *       pass, untouched, which is today's behaviour for every seam that cannot be joined;</li>
 *   <li><b>the trigger</b> - asserted on the source text, because production's trigger is a per-tick carrier
 *       pass and a {@code ServerLevel} cannot be built here.</li>
 * </ul>
 */
class CarriedPairUnionTest {

    /** The structure that seeds the walk. */
    private static final CarrierId X = CarrierId.subLevel(UUID.fromString("00000000-0000-0000-0000-00000000a147"));
    /** The structure standing next to it - the seam. */
    private static final CarrierId Y = CarrierId.subLevel(UUID.fromString("00000000-0000-0000-0000-00000000b147"));
    /** A third structure, used only as the one real source of a scene: a torch it carries. */
    private static final CarrierId Z = CarrierId.subLevel(UUID.fromString("00000000-0000-0000-0000-00000000c147"));

    /** X's host: its own coordinate, and - with a translation pose - the world cell it is drawn on. */
    private static final BlockPos X_HOST = pos(10, 64, 10);
    /** Y's host, one cell north of X's in both frames: the cell X's north side reads. */
    private static final BlockPos Y_HOST = pos(10, 64, 11);
    /** Z's torch host, one cell north of Y's. */
    private static final BlockPos Z_HOST = pos(10, 64, 12);

    /** A pure translation, so a local coordinate names a world cell by one constant offset. */
    private static final CarrierPose AT_ORIGIN = CarrierPose.at(new Vector3d(0, 0, 0));

    private static BlockPos pos(int x, int y, int z) {
        return new BlockPos(x, y, z);
    }

    /** The world store as a map: a carried host's record lives here, at the position it was picked up from. */
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

    private final CarriedHostIndex index = new CarriedHostIndex();
    private final FakeWorld world = new FakeWorld();

    /** The pose the fixture reads each structure through; a carrier without one cannot be reached at all. */
    private static final Map<CarrierId, CarrierPose> POSES = new HashMap<>();

    @BeforeEach
    void reset() {
        POSES.clear();
    }

    // ------------------------------------------------------------------ fixture --

    /** Picks one component up into a structure, the way the assembly callback does. */
    private Slot carry(CarrierId carrier, BlockPos local, ComponentType type) {
        Slot slot = new Slot(type);
        // The record is filed at a coordinate far from every world cell of these scenes, because a carried host
        // is drawn over AIR: the level holds no block state where the machine stands, which is exactly why the
        // world store can never seed this solve.
        world.set(pos(local.getX() + 100, 64, local.getZ() + 100), slot);
        index.claim(carrier, local, pos(local.getX() + 100, 64, local.getZ() + 100), world);
        return index.at(carrier, local).slot();
    }

    private int powerOf(CarrierId carrier, BlockPos local) {
        CarriedHost host = index.at(carrier, local);
        assertNotNull(host, "the structure is carrying a host at " + local);
        assertNotNull(host.slot(), "and it holds a component");
        return host.slot().power;
    }

    /**
     * The fixture's world as the merged solve asks about it: no runs, no world component, no world store slot -
     * and the two tables that can name a carried host, each in its own frame.
     */
    private final class Seam implements SeamLookup {

        /** Every host the world half was handed, by local coordinate - "offered" is not the same as "read". */
        final List<Long> offered = new ArrayList<>();

        @Override
        public boolean runAt(BlockPos cell) {
            return false;
        }

        @Override
        public List<BlockPos> runCells(BlockPos origin) {
            return List.of();
        }

        @Override
        @Nullable
        public Slot slotAt(BlockPos pos) {
            // THE LEVEL HOLDS NOTHING WHERE A MACHINE IS DRAWN. This is the fact the whole change rests on: the
            // world store has no slot at any cell of the pair, so {@code settle} seeds nothing here.
            return null;
        }

        @Override
        public int supplyAt(BlockPos cell) {
            return 0;
        }

        @Override
        public void writeRun(List<BlockPos> cells, int strength) {
        }

        @Override
        public List<BlockPos> componentAt(BlockPos seed) {
            return List.of();
        }

        @Override
        @Nullable
        public CarrierProjection.ProjectedHost carriedAt(BlockPos worldPos) {
            return hostDrawnAt(worldPos);
        }

        @Override
        @Nullable
        public CarrierProjection.ProjectedHost structureHostAt(BlockPos structurePos) {
            return hostStoredAt(structurePos);
        }

        @Override
        @Nullable
        public SeamLookup.CarriedFrame frameOf(CarrierId carrier) {
            CarrierPose pose = CarriedPairUnionTest.POSES.get(carrier);
            if (pose == null) {
                // No pose is "I cannot say where this structure is", which is a refusal and not a zero.
                return null;
            }
            // The bridge names its reader, exactly as {@code CarrierSpace#forSolve} does; no level, so the read
            // is the unfiltered one the frozen-bridge tests already use, and the reader's own carrier is refused
            // here by the fixture's own rule below.
            CarrierWorldIo io = new CarrierWorldIo(pose, pinnedTo(carrier), carrier, null);
            return new SeamLookup.CarriedFrame(carrier, pose,
                    new CarrierSpace(carrier, index, world, null, io, false));
        }

        /**
         * <b>The world frame one carrier reads</b>: the strength the host projected at that cell <em>holds</em>,
         * which is the copy that latches. Production's rule is reproduced except for the one thing this test
         * varies: only the reading carrier's own projection is refused.
         */
        private CarrierWorldSignals pinnedTo(CarrierId reader) {
            return (worldPos, worldDirection) -> {
                CarrierProjection.ProjectedHost found = hostDrawnAt(worldPos);
                if (found == null || !InnerRedstoneNetwork.projectionReadableBy(found, reader)) {
                    return 0;
                }
                offered.add(found.localPos().asLong());
                return PowerSolver.clamp(found.slot() == null ? 0 : found.slot().power);
            };
        }
    }

    /** The host any structure <b>draws</b> at this world cell - the projection table, by world position. */
    @Nullable
    private CarrierProjection.ProjectedHost hostDrawnAt(BlockPos worldPos) {
        for (CarrierId carrier : POSES.keySet()) {
            CarrierPose pose = POSES.get(carrier);
            for (CarriedHost host : index.liveHostsOf(carrier)) {
                if (CarrierProjection.project(pose, host.localPos()).equals(worldPos)) {
                    Slot slot = host.slot();
                    return new CarrierProjection.ProjectedHost(carrier, host.localPos(), worldPos, pose, slot,
                            slot == null ? 0 : slot.power);
                }
            }
        }
        return null;
    }

    /**
     * The host any structure's own storage <b>holds</b> at this structure coordinate - the other table, keyed by
     * a plot coordinate rather than by a drawn position.
     */
    @Nullable
    private CarrierProjection.ProjectedHost hostStoredAt(BlockPos structurePos) {
        for (CarrierId carrier : POSES.keySet()) {
            CarriedHost host = index.at(carrier, structurePos);
            if (host == null) {
                continue;
            }
            Slot slot = host.slot();
            CarrierPose pose = POSES.get(carrier);
            return new CarrierProjection.ProjectedHost(carrier, host.localPos(),
                    CarrierProjection.project(pose, host.localPos()), pose, slot,
                    slot == null ? 0 : slot.power);
        }
        return null;
    }

    /**
     * The solving side of a merged solve, over the fixture: production's loop is what is being tested, so this
     * answers only what the loop asks about the <em>world</em> - and a carried-only union owns no world cell.
     */
    private static final class Env implements UnionEnvironment {

        private Map<BlockPos, PowerEnvironment.Node> owned = Map.of();

        @Override
        public void owning(Map<BlockPos, PowerEnvironment.Node> cells) {
            this.owned = cells;
        }

        @Override
        public void setQuery(BlockPos pos, @Nullable ComponentType reader) {
        }

        @Override
        public void setQueryPosition(BlockPos pos) {
        }

        @Override
        @Nullable
        public PowerEnvironment.Node nodeAt(int x, int y, int z) {
            return owned.get(new BlockPos(x, y, z));
        }

        @Override
        public int externalSignal() {
            return 0;
        }

        @Override
        public int externalSignal(Direction direction) {
            return 0;
        }

        @Override
        public boolean isRedstoneConductor(int x, int y, int z) {
            return false;
        }
    }

    // ------------------------------------------------------- 1. the pair is one solve --

    @Test
    @DisplayName("two carried lossless networks join into one solve, and drain together when nothing supplies them")
    void twoCarriedNetworksJoinAndDrainTogether() {
        carry(X, X_HOST, ComponentType.SUPERCONDUCTOR);
        carry(Y, Y_HOST, ComponentType.SUPERCONDUCTOR);
        // The latched value: both halves stand at fifteen with no source anywhere.
        index.at(X, X_HOST).slot().power = 15;
        index.at(Y, Y_HOST).slot().power = 15;
        POSES.put(X, AT_ORIGIN);
        POSES.put(Y, AT_ORIGIN);
        Seam seam = new Seam();

        InnerRedstoneNetwork.Union union = InnerRedstoneNetwork.collectCarriedUnion(seam, X, X_HOST);

        assertEquals(2, union.ports().size(),
                "one union, two ports: the carried seed pulls in the structure facing it, or the pair is never"
                        + " one network and each half keeps copying the other's record");
        assertTrue(union.hosts().isEmpty(),
                "and no world host is in it - the level holds air where these two stand, which is why the"
                        + " world store could never seed this solve");

        InnerRedstoneNetwork.settleUnion(seam, new Env(), union);

        assertEquals(0, powerOf(X, X_HOST), "with no supply the first structure must go dark");
        assertEquals(0, powerOf(Y, Y_HOST), "and so must the second: they are one network, or they are a latch");
    }

    @Test
    @DisplayName("the port takes the other port's NODE value, not its world-frame read of the same cell")
    void thePortTakesTheOtherPortsNodeValue() {
        // The only source is a torch the THIRD structure carries, standing on the far side of Y - so neither X
        // nor Y holds a supply of its own, and the 15 can only reach X through Y's NODE.
        Slot torch = carry(Z, Z_HOST, ComponentType.TORCH);
        torch.power = 15;
        carry(X, X_HOST, ComponentType.SUPERCONDUCTOR);
        carry(Y, Y_HOST, ComponentType.SUPERCONDUCTOR);
        POSES.put(X, AT_ORIGIN);
        POSES.put(Y, AT_ORIGIN);
        POSES.put(Z, AT_ORIGIN);
        Seam seam = new Seam();

        InnerRedstoneNetwork.Union union = InnerRedstoneNetwork.collectCarriedUnion(seam, X, X_HOST);
        assertEquals(2, union.ports().size(),
                "precondition: the torch's own structure is NOT a port - a torch is not a lossless partner, so"
                        + " it stays outside the solve as a supply, and only the two wires join");

        InnerRedstoneNetwork.settleUnion(seam, new Env(), union);

        assertEquals(15, powerOf(Y, Y_HOST), "Y reads the torch beside it and holds fifteen");
        assertEquals(15, powerOf(X, X_HOST),
                "and X holds fifteen through Y's NODE. Read as a supply from Y's record instead, this is ZERO:"
                        + " the loop starts every wire from zero, so the record has not been written yet - which"
                        + " is what makes the node edge and the copy two different answers, not two spellings of"
                        + " one");
        assertFalse(seam.offered.contains(Y_HOST.asLong()),
                "and Y's own cell was never offered to the world half as a supply: the side that faces it is an"
                        + " edge of this solve, not a block to read. Offered: " + seam.offered);
    }

    // ------------------------------------------------- 2. the other table, and the refusals --

    @Test
    @DisplayName("a partner drawn somewhere else joins through the STRUCTURE table, at a plot coordinate")
    void aPartnerDrawnElsewhereJoinsThroughTheStructureTable() {
        // The two structures are physicalised apart in the world - Y is drawn a hundred cells north - so no side
        // of X projects onto any cell of Y, and the projection table cannot name the seam at all. What remains is
        // the other table: the two plots are neighbours, so (10,64,11) IS a plot coordinate of Y.
        CarrierPose away = CarrierPose.at(new Vector3d(0, 0, 100));
        carry(X, X_HOST, ComponentType.SUPERCONDUCTOR);
        carry(Y, Y_HOST, ComponentType.SUPERCONDUCTOR);
        index.at(X, X_HOST).slot().power = 15;
        index.at(Y, Y_HOST).slot().power = 15;
        POSES.put(X, AT_ORIGIN);
        POSES.put(Y, away);
        Seam seam = new Seam();

        assertNull(hostDrawnAt(Y_HOST),
                "precondition: nothing is DRAWN at X's north cell, so the projection table has no seam here");
        assertNotNull(seam.structureHostAt(Y_HOST),
                "while the structure table does: this coordinate is inside Y's own plot");

        InnerRedstoneNetwork.Union union = InnerRedstoneNetwork.collectCarriedUnion(seam, X, X_HOST);

        assertEquals(2, union.ports().size(),
                "the plot-coordinate join is what pulls Y in - a walk that asked only the projection table would"
                        + " assemble one port and leave the pair latched");
        InnerRedstoneNetwork.settleUnion(seam, new Env(), union);
        assertEquals(0, powerOf(X, X_HOST), "and the joined pair drains, as it does when they are drawn adjacent");
        assertEquals(0, powerOf(Y, Y_HOST), "on both sides");
    }

    @Test
    @DisplayName("a partner whose component is not wholly lossless is left to its own structure's pass")
    void aMixedPartnerIsRefusedAndStaysOnItsOwnPass() {
        carry(X, X_HOST, ComponentType.SUPERCONDUCTOR);
        carry(Y, Y_HOST, ComponentType.SUPERCONDUCTOR);
        // A piece of ordinary dust beside Y's wire: the component is mixed, so the whole frame is refused - half a
        // network solved here and half by the structure's own pass is the defect one cell in.
        carry(Y, pos(10, 64, 12), ComponentType.DUST);
        index.at(Y, Y_HOST).slot().power = 15;
        index.at(Y, pos(10, 64, 12)).slot().power = 15;
        POSES.put(X, AT_ORIGIN);
        POSES.put(Y, AT_ORIGIN);
        Seam seam = new Seam();

        InnerRedstoneNetwork.Union union = InnerRedstoneNetwork.collectCarriedUnion(seam, X, X_HOST);

        assertEquals(1, union.ports().size(),
                "the mixed neighbour is refused: only the seeded component is a port");
        InnerRedstoneNetwork.settleUnion(seam, new Env(), union);
        assertEquals(15, powerOf(Y, Y_HOST),
                "and Y keeps whatever its own pass last wrote - a refusal is today's behaviour, never a zero");
        assertEquals(15, powerOf(Y, pos(10, 64, 12)), "on every cell of the refused component");
    }

    @Test
    @DisplayName("a released structure, and a pose that cannot be read, are refusals and not zeros")
    void anUnreachablePartnerIsARefusal() {
        carry(X, X_HOST, ComponentType.SUPERCONDUCTOR);
        carry(Y, Y_HOST, ComponentType.SUPERCONDUCTOR);
        index.at(X, X_HOST).slot().power = 15;
        index.at(Y, Y_HOST).slot().power = 15;
        POSES.put(X, AT_ORIGIN);
        // Y has no pose at all: "I cannot say where this structure is".
        Seam seam = new Seam();

        InnerRedstoneNetwork.Union union = InnerRedstoneNetwork.collectCarriedUnion(seam, X, X_HOST);

        assertEquals(1, union.ports().size(), "the unreachable structure is not joined");
        assertEquals(15, powerOf(Y, Y_HOST), "and nothing of Y's is written by a solve that could not reach it");
    }

    // ---------------------------------------------------------------- 3. the trigger --

    @Test
    @DisplayName("the trigger is the per-tick carrier pass, and it runs the join once per pass")
    void theTriggerIsTheCarrierPass() throws IOException {
        String networks = flat("src/main/java/com/jiangyuefengyu/redstonecircuit/carrier/CarrierNetworks.java");
        // THE TRIGGER IS PINNED BY THE LINE, NOT BY THE TEXT. A substring assertion anywhere in the file is
        // satisfied by a COMMENT, by a WRAPPER ("if (false) { ... }", a loop, a lambda someone forgot to call) and
        // by a NEIGHBOURING call to the same method - so the one line that makes the cross-carrier solve happen at
        // all could be deleted and the whole suite would stay green while the fix became dead code. The mutation
        // that has to be impossible is therefore "the call is gone and nothing noticed": the file must contain a
        // line whose whole trimmed text IS the call.
        List<String> lines = linesOf("src/main/java/com/jiangyuefengyu/redstonecircuit/carrier/CarrierNetworks.java");
        int call = lineOf(lines, "mergeCarriedPairs(ctx, index);");
        assertTrue(call >= 0,
                "CarrierNetworks.java must contain a line whose trimmed text is exactly"
                        + " 'mergeCarriedPairs(ctx, index);' - not a comment about it, not a call buried inside an"
                        + " if, a loop or a lambda, and not a same-named neighbour. Without this line the carrier"
                        + " pass never hands a carried pair to the merged solve, the entire cross-carrier fix is"
                        + " dead code, and every other test in this class still passes");
        int perCarrier = lineOf(lines, "SolveReport report = solve(ctx, index, carrier, state, tick);");
        assertTrue(perCarrier >= 0, "precondition: the per-carrier solve is still a line of its own");
        assertTrue(call > perCarrier,
                "and that line must come AFTER the per-carrier loop: the joined solve is the last word on the"
                        + " cells both passes touch");
        assertTrue(networks.contains("InnerRedstoneNetwork.settleCarriedUnion(level, carrier, seeds.get(carrier))"),
                "the carrier pass must hand the pair to the merged solve itself: a host riding a structure is in"
                        + " no dirty set of the world store, so {@code settle} can never seed it");
        assertTrue(networks.contains("if (covered.contains(carrier)) {"),
                "with the carriers a union covered as the key, so one pair is assembled once in a tick and never"
                        + " twice");

        String network = flat("src/main/java/com/jiangyuefengyu/redstonecircuit/logic/InnerRedstoneNetwork.java");
        assertTrue(network.contains("node = union.get(world);"),
                "and inside the solve the port's neighbour is looked up in the UNION first - the edge that makes"
                        + " the other port a node of this solve");
        assertTrue(network.contains("if (node != null) {\n                        arrivals.add(new Arrival(node,"
                        + " direction.getOpposite()));\n                        continue;"),
                "with the world read of that same cell skipped, because a copy of the partner's stored strength"
                        + " is exactly the latch the join removes");
        assertTrue(flat("src/main/java/com/jiangyuefengyu/redstonecircuit/logic/SeamLookup.java")
                        .contains("default CarrierProjection.ProjectedHost structureHostAt(BlockPos structurePos)"),
                "and the structure-coordinate join is a default method, so every existing implementation of the"
                        + " seam keeps compiling with today's answer");
        assertTrue(flat("src/main/java/com/jiangyuefengyu/redstonecircuit/carrier/CarrierStore.java")
                        .contains("public static CarrierProjection.ProjectedHost structureHostAtAnyOwner("),
                "which production answers with the unguarded structure lookup, the one this frame needs");
    }

    /** One source file with its line endings flattened, for the wiring legs above. */
    private static String flat(String relative) throws IOException {
        return Files.readString(Path.of(relative)).replace("\r\n", "\n");
    }

    /** One source file's lines, for a wiring leg that has to name a <b>line</b> and not a substring. */
    private static List<String> linesOf(String relative) throws IOException {
        return Files.readAllLines(Path.of(relative));
    }

    /**
     * The index of the one line whose <b>trimmed</b> text is exactly {@code text}, or {@code -1}.
     *
     * <p>Trimmed-equal and not "contains": the point of the assertions that use this is that a comment, a
     * wrapper and a neighbouring call must all fail, and only the first and last non-blank characters of the
     * line being the statement itself can tell those three apart from the statement.
     */
    private static int lineOf(List<String> lines, String text) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).trim().equals(text)) {
                return i;
            }
        }
        return -1;
    }
}
