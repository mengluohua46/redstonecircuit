package com.jiangyuefengyu.redstonecircuit.carrier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.Slot;
import com.jiangyuefengyu.redstonecircuit.logic.PowerSolver;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.SignalGetter;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>一条光网络是一个节点，不是一组互相供电的源</b> - in the carrier's pair read, a cell of the host's OWN network is
 * the node being solved and never a supply to it.
 *
 * <p>The arrangement, and the latch it leaves behind if the carrier half ever copies it: two SUPERCONDUCTOR hosts of
 * one structure stand side by side ({@code 20481032,128,20524040} and {@code ...20524041}), each
 * {@code hopCost() == 0}, with a lossless wire over each. A's south side holds B - a lossless host of the
 * <em>same</em> structure, adjacent to A, therefore a cell of A's own network
 * ({@code CarrierSpace#readerCells()}). A value is {@code max(own supply, what the neighbours send)}, but a
 * neighbour that belongs to the same node set is not a neighbour, it <b>IS</b> the node: copying B's stored fifteen
 * at a hop that costs nothing makes {@code A = B} and {@code B = A}, whose fixed point is <b>every value</b>.
 *
 * <p><b>What this file pins, and why it is written the way it is.</b> In this class the rule is already in force, and
 * it is in force <em>through the exclusion set</em> rather than through a second test inside the branch:
 * {@code CarrierSpace#ownSupplyOf} hands {@code readerCells()} to both frames, {@code ownSupplyOf} →
 * {@code InnerRedstoneNetwork#supplyAround(wireCell, queryPos, read, reader)} unions it with the query cell, and
 * {@code ownSupplySidesExcept} then <b>never asks</b> the side that holds a member. The index branch behind
 * {@code structureSupplyOf} therefore cannot be reached for a member at all, and the world frame maps the same set
 * through the pose and skips it too.
 *
 * <p>So these tests are the regression guard for that fact, not a proof of a new one, and each javadoc names the
 * change that turns it red: the exclusion narrowing from the reader's <b>whole network</b> back to <b>one cell</b> -
 * which is exactly the regression the run half already suffered once ({@code InnerRedstoneNetwork#readerNetworkAt}
 * records it), and the one a "leave out just this cell" reading of the pair rule would bring back. That is the red
 * these four behavioural tests and the one wiring test exist for.
 *
 * <p>A carrier bridge is not needed for any of it: {@code ownSupplyOf} is package-private for exactly this reason,
 * and a {@code null} bridge makes the world frame contribute nothing, so the numbers asserted below are the
 * <b>structure frame's</b> alone. A superconductor <em>block state</em> cannot be built offline either (the block
 * registry throws before it returns), which is why no test here drives {@code structureSignal}'s wire-block branch.
 */
class CarrierOwnNetworkSupplyTest {

    /** This host's structure - the carrier whose own light network is the node. */
    private static final CarrierId CARRIER =
            CarrierId.subLevel(UUID.fromString("00000000-0000-0000-0000-00000000e0a1"));

    /** Another, independently physicalised structure: a host of this one is the seam, never a member. */
    private static final CarrierId OTHER_STRUCTURE =
            CarrierId.subLevel(UUID.fromString("00000000-0000-0000-0000-00000000e0b2"));

    /** The carried contents, the same accessor the index writes through. */
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

    /**
     * The structure's own blocks, as far as a unit test can have them: a signal per local coordinate and no block
     * state at all.
     *
     * <p>A real {@code BlockState} needs the game's registry ({@code Blocks.AIR.defaultBlockState()} throws before it
     * returns), so {@code getBlockState} answers {@code null} - which the shared read treats as "no state to
     * inspect" - and the signal getter is what answers for a coordinate. That is the fixture the structure half's own
     * tests use.
     */
    private static final class FakeBlocks implements SignalGetter {

        private final Map<Long, Integer> signals = new HashMap<>();

        FakeBlocks signal(BlockPos local, int strength) {
            signals.put(local.asLong(), strength);
            return this;
        }

        @Override
        public int getSignal(BlockPos pos, Direction direction) {
            Integer value = signals.get(pos.asLong());
            return value == null ? 0 : value;
        }

        @Override
        @Nullable
        public BlockState getBlockState(BlockPos pos) {
            return null;
        }

        @Override
        @Nullable
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return Fluids.EMPTY.defaultFluidState();
        }

        @Override
        public int getHeight() {
            return 384;
        }

        @Override
        public int getMinBuildHeight() {
            return -64;
        }
    }

    private final CarriedHostIndex index = new CarriedHostIndex();
    private final FakeWorld world = new FakeWorld();

    private static BlockPos pos(int x, int y, int z) {
        return new BlockPos(x, y, z);
    }

    /** Picks a component up into a structure, the way the assembly callback does. */
    private Slot carry(CarrierId carrier, BlockPos local, BlockPos origin, ComponentType type) {
        Slot slot = new Slot(type);
        world.set(origin, slot);
        index.claim(carrier, local, origin, world);
        return index.at(carrier, local).slot();
    }

    /** This host's pair read with no bridge, so the number it returns is the structure frame's alone. */
    private CarrierSpace spaceWithoutBridge(BlockGetter blocks) {
        return new CarrierSpace(CARRIER, index, world, id -> blocks, null, true);
    }

    // ------------------------------ (i) a cell of the host's own network is the node --

    @Test
    @DisplayName("a neighbour that is a cell of the host's own network contributes 0 in the structure frame")
    void aCellOfTheHostsOwnNetworkContributesNothing() {
        // A and B are both lossless hosts of ONE structure and stand side by side, so B is a side of the pair's cell
        // as well as a cell of A's own network: the read that asks "what would this cell hold without A" would be
        // handed B's stored fifteen if B were treated as a supply - the copy that makes A = B, B = A a fixed point.
        BlockPos host = pos(1, 0, 2);
        BlockPos member = pos(2, 0, 2);
        BlockPos pairCell = pos(3, 0, 2);
        carry(CARRIER, host, pos(30, 64, 30), ComponentType.SUPERCONDUCTOR).power = 15;
        carry(CARRIER, member, pos(31, 64, 30), ComponentType.SUPERCONDUCTOR).power = 15;

        FakeBlocks blocks = new FakeBlocks();
        CarrierSpace space = spaceWithoutBridge(blocks);
        space.setQueryPosition(host);

        assertEquals(0, space.ownSupplyOf(pairCell, blocks),
                "RED WHEN THE EXCLUSION IS NARROWED: readerCells() is handed to the read, so the side holding a "
                        + "member is never asked and the answer is zero. Narrow that set back to the one cell the "
                        + "read is made from and this side is asked again, PowerSolver.signalAnswer(slot, ...) "
                        + "returns the other host's stored fifteen, and A = B, B = A holds every value");
    }

    // ---------------------------- (ii) everything that is not a member keeps answering --

    @Test
    @DisplayName("a neighbour outside the network still contributes its value: a lever, dust, and another component's host")
    void aNeighbourOutsideTheNetworkStillContributes() {
        FakeBlocks blocks = new FakeBlocks();

        // A lever of this carrier beside the pair's cell: connected to the host, but no wire, so the narrowing in
        // readerCells() never collects it. A real source - a superconductor charges nothing for the hop.
        BlockPos leverHost = pos(1, 0, 2);
        BlockPos lever = pos(2, 0, 2);
        BlockPos leverPair = pos(3, 0, 2);
        carry(CARRIER, leverHost, pos(40, 64, 40), ComponentType.SUPERCONDUCTOR);
        carry(CARRIER, lever, pos(41, 64, 40), ComponentType.LEVER).power = 15;
        CarrierSpace leverSpace = spaceWithoutBridge(blocks);
        leverSpace.setQueryPosition(leverHost);
        assertEquals(15, leverSpace.ownSupplyOf(leverPair, blocks),
                "RED IF THE RULE IS WRITTEN AS A BLANKET instead of as membership: 'any host of this carrier is "
                        + "this node' refuses this lever and a lit machine goes dark. The exclusion is a membership "
                        + "test on the reader's own network, so a real source still arrives at full strength");

        // Ordinary dust: a wire, but it pays a hop, so it cannot be half of a self-feeding pair and the narrowing
        // drops it. This is the case the rule must never touch - a dust machine works by being read as a source.
        BlockPos dustHost = pos(1, 4, 2);
        BlockPos dust = pos(2, 4, 2);
        BlockPos dustPair = pos(3, 4, 2);
        carry(CARRIER, dustHost, pos(42, 64, 40), ComponentType.SUPERCONDUCTOR);
        carry(CARRIER, dust, pos(43, 64, 40), ComponentType.DUST).power = 14;
        CarrierSpace dustSpace = spaceWithoutBridge(blocks);
        dustSpace.setQueryPosition(dustHost);
        assertEquals(14, dustSpace.ownSupplyOf(dustPair, blocks),
                "a dust host keeps the plain read: it pays a hop, so its pair comes down by itself and excluding it "
                        + "would change a working machine's answer for nothing");

        // And a LOSSLESS host of the same carrier that is NOT connected to this one: a genuinely separate node,
        // fed through the wire rather than through the solve. Not a member, so it still answers.
        BlockPos nearHost = pos(1, 8, 2);
        BlockPos farHost = pos(4, 8, 2);
        BlockPos farPair = pos(3, 8, 2);
        carry(CARRIER, nearHost, pos(44, 64, 40), ComponentType.SUPERCONDUCTOR);
        carry(CARRIER, farHost, pos(45, 64, 40), ComponentType.SUPERCONDUCTOR).power = 15;
        CarrierSpace farSpace = spaceWithoutBridge(blocks);
        farSpace.setQueryPosition(nearHost);
        assertEquals(15, farSpace.ownSupplyOf(farPair, blocks),
                "so a lossless host of another COMPONENT arrives at full strength - 'lossless' alone must never be "
                        + "the test, or a wire feeding a second machine inside one structure would go dark");
    }

    // -------------------------------- (iii) the pair read does not return the copy --

    @Test
    @DisplayName("the pair read does not return the other host's stored value, in both frames")
    void thePairReadDoesNotReturnTheOtherHostsStoredValue() {
        BlockPos host = pos(1, 0, 2);
        BlockPos member = pos(2, 0, 2);
        BlockPos pairCell = pos(3, 0, 2);
        carry(CARRIER, host, pos(50, 64, 50), ComponentType.SUPERCONDUCTOR).power = 15;
        Slot other = carry(CARRIER, member, pos(51, 64, 50), ComponentType.SUPERCONDUCTOR);
        other.power = 15;

        FakeBlocks blocks = new FakeBlocks();
        // A live world frame that answers nothing beside the pair's cell, so the composed read is the structure
        // frame's on one side and the world frame's own silence on the other.
        CarrierWorldIo bridge = new CarrierWorldIo(CarrierPose.at(new Vector3d(10.0, 64.0, 10.0)),
                (worldPos, worldDirection) -> 0);
        CarrierSpace space = new CarrierSpace(CARRIER, index, world, id -> blocks, bridge, true);
        space.setQueryPosition(host);

        assertEquals(15, other.power,
                "the stored fifteen the pair would have copied still stands in the record - nothing here is vacuous");
        assertEquals(15, PowerSolver.signalAnswer(other, Direction.EAST, false, false, false),
                "and the read the index branch makes of a host answers it: that is the value the pair read must "
                        + "NOT take, because it is the copy and not a supply");
        assertEquals(0, space.ownSupplyOf(pairCell, blocks),
                "RED WHEN THE EXCLUSION IS NARROWED to the one cell the read is made from: this composed pair read "
                        + "returns fifteen again, A = B, B = A has every value as its fixed point, and the "
                        + "component's own solve finds nothing to bring down");
    }

    // ---------------------------- (iv) a host of another carrier is the seam, not a node --

    @Test
    @DisplayName("a host of ANOTHER carrier still answers: only THIS carrier's own walk is the node set")
    void aHostOfAnotherCarrierStillAnswers() {
        BlockPos host = pos(1, 0, 2);
        BlockPos otherHost = pos(2, 0, 2);
        BlockPos pairCell = pos(3, 0, 2);
        carry(CARRIER, host, pos(60, 64, 60), ComponentType.SUPERCONDUCTOR);
        carry(OTHER_STRUCTURE, otherHost, pos(61, 64, 60), ComponentType.SUPERCONDUCTOR).power = 15;
        // The structure's own blocks answer at that coordinate as well: whatever stands there is read as a source,
        // and this is the strength a second, independently solved machine really holds.
        FakeBlocks blocks = new FakeBlocks().signal(otherHost, 15);

        CarrierSpace space = spaceWithoutBridge(blocks);
        space.setQueryPosition(host);

        assertEquals(15, space.ownSupplyOf(pairCell, blocks),
                "RED IF THE EXCLUSION IS EVER TAKEN FROM A WIDER SET than this carrier's own network - another "
                        + "structure's host is collected by THAT structure's walk, never by this one, so it is not "
                        + "a member and the ordinary read keeps answering it. That is the physicalisation seam");
    }

    // -------------------------------- the set is what leaves the node out of both frames --

    @Test
    @DisplayName("the reader's own network is handed to BOTH frames of the pair read, and is never re-walked")
    void theReaderSetIsHandedToBothFrames() throws Exception {
        String space = flat("src/main/java/com/jiangyuefengyu/redstonecircuit/carrier/CarrierSpace.java");

        assertTrue(space.contains("Set<BlockPos> reader = readerCells();"),
                "the set the pair read uses IS the host's own network, taken from the walk - not the one cell the "
                        + "read is made from, which would leave every longer loop of the same node standing");
        assertTrue(space.contains("return pairOwnSupply(() -> worldSupplyOf(wireCell, reader), "
                        + "() -> structureSupplyOf(wireCell, reader, blocks));"),
                "one statement of the pair read, and ONE set: the same argument reaches the world frame and the "
                        + "structure frame, so the two cannot exclude different things");
        assertTrue(space.contains("InnerRedstoneNetwork.supplyAround(wireCell, queryPos, (side, direction) -> {"),
                "the structure frame is the shared arithmetic, unchanged");
        assertTrue(space.contains("return worldSupplyAround(io, wireCell, queryPos, reader);"),
                "and the world frame is asked with the reader's network as well as the host's own cell");
        assertTrue(space.contains("excludedWorld.add(bridge.worldPosOf(cell));"),
                "mapped through the pose one cell at a time, so 'this network' means the same cells in both frames");
        assertTrue(space.contains("for (BlockPos cell : CarrierLightNetwork.collectComponent(this, queryPos)) {"),
                "and the set comes from the walk the carrier solve already runs, from this host's own coordinate");
        assertTrue(space.contains("slot != null && slot.type.isWire() && slot.type.hopCost() == 0"),
                "narrowed to lossless wires, so a lever or a torch beside the host - connected to it, but not part "
                        + "of the network whose echo this removes - is never excluded");
        assertTrue(space.contains("cells.add(queryPos.immutable());"),
                "with this host's own cell always in the set, so an unusable walk still excludes the old one cell");
    }

    /** One file's text, whitespace-flattened, so a wiring assertion is not a formatting assertion. */
    private static String flat(String relative) throws Exception {
        return java.nio.file.Files.readString(java.nio.file.Path.of(relative))
                .replace("\r\n", "\n").replaceAll("\\s+", " ");
    }
}
