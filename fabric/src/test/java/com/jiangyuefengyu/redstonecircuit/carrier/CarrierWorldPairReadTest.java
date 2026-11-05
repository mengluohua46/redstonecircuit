package com.jiangyuefengyu.redstonecircuit.carrier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.Slot;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The world half of a carried host must make the same ordered question the structure half makes.
 *
 * <p>一个 hopCost()==0 的本体元件，旁边躺着一格超导线方块（超导线的强度写在自己的方块状态里，不属于任何载具，
 * 也没有任何 solve 会去写它），如果两边各自把对方的<em>存量</em>当供给，两个等式就是 {@code A = B} 与
 * {@code B = A}，增益恰为一 - 每一个值都是不动点。于是"谁都不再供的强度"永远立在那里，这就是那个 latch：
 * 结构表面上的超导线被点一次，源拿走之后它还亮着。
 *
 * <p>修法与 {@code CarrierSpace#structureSignal} 完全对称：不问"你持有什么"，而问"没有我你会是多少" - 那格线
 * 自己六面的供给，把本 host 的格排除掉。最大值里丢掉自己那一项只会降低答案，所以这不可能点亮原本是暗的东西，
 * 而线另一侧的真实源仍然满强度抵达。
 *
 * <p>那格线自己的供给要<b>两个 frame 一起问</b>，与结构那一半完全一致：结构 frame 里有本机器自己搬着的东西（导线格旁
 * 边的一根拉杆、这一排里的另一个 host），世界 frame 里有世界里立在画出来的导线旁的方块（原版火把、另一台机器摸到的
 * 那一格）。两个 frame 互相看不见对方的东西，只问一个就只得到一半的供给 - 在真的亮着的机器上给出 0，那是这种规则
 * 唯一不能犯的错。
 *
 * <p><b>A carrier bridge cannot be built here</b> - it needs a level, a pose and the projection table - so the
 * rule, the composition and the reads are pinned separately, each through the production code that uses them:
 * {@link CarrierSpace#worldPairRead}, {@link CarrierSpace#worldHalfAnswer}, {@link CarrierSpace#pairOwnSupply}
 * (the composition: the larger of the two frames, or the world frame alone when the structure frame cannot be
 * asked), {@link CarrierSpace#worldSupplyAround} (the world frame on its own) and {@link CarrierSpace#ownSupplyOf}
 * - the last two driven through a real, level-free {@link CarrierWorldIo}. The two end-to-end tests drive
 * {@code CarrierSpace#externalSignal} through that same level-free bridge, which is the path a component with no
 * wire block beside it really takes.
 *
 * <p><b>And one assertion is on the source text, deliberately.</b> {@code CarrierSpace#worldSignal} is private and
 * needs a live bridge and a level, so a revert of the one line that composes the two frames leaves every
 * behavioural assertion above exactly as green as it is now - which is how such a revert survived a mutation run
 * once already. That single call site is therefore pinned where it lives, the way this project's other wiring
 * tests pin a site a test cannot drive.
 */
class CarrierWorldPairReadTest {

    /** This host's structure. */
    private static final CarrierId CARRIER =
            CarrierId.subLevel(UUID.fromString("00000000-0000-0000-0000-00000000c17e"));
    /** Another, independently physicalised structure - the seam. */
    private static final CarrierId OTHER_STRUCTURE =
            CarrierId.subLevel(UUID.fromString("00000000-0000-0000-0000-00000000d17e"));

    /** The structure is pivoted here, so a local coordinate plus this is where it is in the world. */
    private static final Vector3d PIVOT = new Vector3d(10.0, 64.0, 10.0);

    /** The host carrying the component, in the structure's own frame. */
    private static final BlockPos HOST_LOCAL = new BlockPos(1, 0, 2);
    /** Where that host is drawn: {@code (10,64,10) + (1,0,2)}. */
    private static final BlockPos HOST_WORLD = new BlockPos(11, 64, 12);

    /** The cell of the superconducting wire block lying on the structure's surface, one step east. */
    private static final BlockPos WIRE_LOCAL = HOST_LOCAL.east();
    /** Where that wire block stands in the level. */
    private static final BlockPos WIRE_WORLD = new BlockPos(12, 64, 12);
    /** The wire's far side: a real source there (a torch, a lever, another machine's block). */
    private static final BlockPos FAR_SIDE_WORLD = new BlockPos(13, 64, 12);

    /**
     * A host <b>this carrier</b> carries, standing beside the wire's own cell - the machine's own lever/torch in
     * that row. Its local coordinate is one past the wire, so it is two cells from the host being asked about and
     * therefore not part of that host's network.
     */
    private static final BlockPos SOURCE_LOCAL = WIRE_LOCAL.east();
    /** Where that carried source is drawn. The same world cell the world-frame tests use, in the other table. */
    private static final BlockPos SOURCE_WORLD = new BlockPos(13, 64, 12);

    /** The interface a bridge reads the world through; a lambda is enough, and a level is not needed. */
    private static CarrierPose pose() {
        return CarrierPose.at(PIVOT);
    }

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
     * The structure's own blocks, as far as a unit test can have them.
     *
     * <p>A real {@code BlockState} needs the game's registry ({@code Blocks.AIR.defaultBlockState()} throws before
     * it returns), so no state can be fabricated and this answers {@code null} for every coordinate. That is a
     * documented answer rather than an accident - {@code InnerRedstoneNetwork#readSignal} treats "no state" as "no
     * signal" - and it is exactly the empty grid this fixture wants: the machine's own <b>hosts</b> are what supply
     * the wire here, through the carrier index, not any block of the structure's own.
     */
    private static final class FakeBlocks implements BlockGetter {

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

    /** Picks a component up, the way the assembly callback does. */
    private Slot carry(CarrierId carrier, BlockPos local, BlockPos origin, ComponentType type) {
        Slot slot = new Slot(type);
        world.set(origin, slot);
        index.claim(carrier, local, origin, world);
        return index.at(carrier, local).slot();
    }

    // ----------------------------------- 1. the latch: a pair that only feeds itself --

    @Test
    @DisplayName("a lossless wire whose only supply is that host reads zero: there is nothing left to copy")
    void aLosslessWireWhoseOnlySupplyIsTheHostReadsZero() {
        carry(CARRIER, HOST_LOCAL, HOST_WORLD, ComponentType.SUPERCONDUCTOR);
        // The world beside the wire is empty except for this host: the wire is supplied by its partner and by
        // nothing else - which is exactly the arrangement that used to stand there for ever.
        CarrierWorldIo bridge = new CarrierWorldIo(pose(),
                (worldPos, worldDirection) -> HOST_WORLD.equals(worldPos) ? 15 : 0);

        int own = CarrierSpace.worldSupplyAround(bridge, WIRE_LOCAL, HOST_LOCAL, Set.of(HOST_LOCAL));

        assertEquals(0, own,
                "the own-supply read asks the wire's six sides with this host's cell left out, so the one "
                        + "strength that kept the pair alive is gone and the answer is zero");
        assertTrue(CarrierSpace.worldPairRead(true, CARRIER, null),
                "a lossless superconducting wire block beside a lossless host, in nobody else's storage, is "
                        + "a pair");
        int stored = 15; // what the wire still HOLDS after the lever was taken away - the latch itself
        assertEquals(0, CarrierSpace.worldHalfAnswer(stored, own, CarrierSpace.worldPairRead(true, CARRIER, null)),
                "and the world half answers the own-supply read, not the stored one: the leftover the wire "
                        + "is holding is exactly what must not be copied any more");
    }

    // ------------------------------- 2. a real source on the far side still arrives --

    @Test
    @DisplayName("a source beyond the wire still arrives at full strength, from the source and not from the copy")
    void aSourceBeyondTheWireStillArrivesAtFullStrength() {
        carry(CARRIER, HOST_LOCAL, HOST_WORLD, ComponentType.SUPERCONDUCTOR);
        // A torch on the wire's far side, and nothing else: the one thing the pair rule must never lose.
        CarrierWorldIo bridge = new CarrierWorldIo(pose(),
                (worldPos, worldDirection) -> FAR_SIDE_WORLD.equals(worldPos) ? 15 : 0);

        int own = CarrierSpace.worldSupplyAround(bridge, WIRE_LOCAL, HOST_LOCAL, Set.of(HOST_LOCAL));

        assertEquals(15, own,
                "a superconductor charges nothing for the hop, so a real source one cell beyond the wire "
                        + "reaches it at full strength and the own-supply read finds it");
        // The wire's own POWER has not been solved since the torch was placed, so the STORED answer is still 0.
        // Taking the stored one would darken a live source - the pair read takes the source's instead.
        int stored = 0;
        assertEquals(15, CarrierSpace.worldHalfAnswer(stored, own, CarrierSpace.worldPairRead(true, CARRIER, null)),
                "so full strength still arrives through a pair - and it arrives from the source, which is "
                        + "what makes the rule safe when the copy is stale");
    }

    // ---------------------------------- 3. ordinary dust: never a pair, stock read --

    @Test
    @DisplayName("ordinary redstone dust beside the host keeps the stock read, unchanged")
    void ordinaryDustBesideTheHostKeepsTheStockRead() {
        carry(CARRIER, HOST_LOCAL, HOST_WORLD, ComponentType.DUST).power = 4;
        // Whatever the level holds beside the host is answered as it always was: a hop is paid, so the two
        // equations of a dust pair are contradictory and the copy comes down by itself - no rule is needed.
        CarrierSpace space = new CarrierSpace(CARRIER, index, world, null,
                new CarrierWorldIo(pose(), (worldPos, worldDirection) -> 12), true);
        space.setQueryPosition(HOST_LOCAL);

        assertEquals(12, space.externalSignal(Direction.EAST),
                "dust is not a lossless partner at all, so the neighbour's strength is read exactly as "
                        + "before - if the pair read had been taken here the answer would be the space "
                        + "around the neighbour with this host left out, which is 0");
        assertEquals(12, space.externalSignal(), "and the omnidirectional answer is that same number");
    }

    // --------------------------- 4. another structure's own block storage: the seam --

    @Test
    @DisplayName("a cell owned by another live structure keeps the stock read: the seam must keep answering")
    void anotherStructuresOwnBlockStorageKeepsTheStockRead() {
        assertTrue(CarrierSpace.worldPairRead(true, CARRIER, CARRIER),
                "one machine, one grid: a coordinate this host's own structure holds is a pair");
        assertTrue(CarrierSpace.worldPairRead(true, CARRIER, null),
                "and a wire standing in the open world, owned by nobody, is the pair's own case");
        assertFalse(CarrierSpace.worldPairRead(true, CARRIER, OTHER_STRUCTURE),
                "but a cell that belongs to ANOTHER live structure's own block storage is that machine's "
                        + "block, solved by its solve - asking it what it would be without us would be a "
                        + "question about a grid we do not own");
        assertFalse(CarrierSpace.worldPairRead(false, CARRIER, null),
                "and a neighbour that is not a lossless superconducting wire block is not a pair either");
        assertEquals(15, CarrierSpace.worldHalfAnswer(15, 0,
                        CarrierSpace.worldPairRead(true, CARRIER, OTHER_STRUCTURE)),
                "so the seam keeps answering the strength the other structure really holds - it is the only "
                        + "route by which two independently physicalised machines read each other");
    }

    // -------------------------------- 5. a failure keeps the stock read, never zero --

    @Test
    @DisplayName("a wire block that cannot be established keeps the stock read: a failure is never zero")
    void aWireThatCannotBeEstablishedKeepsTheStockRead() {
        carry(CARRIER, HOST_LOCAL, HOST_WORLD, ComponentType.SUPERCONDUCTOR).power = 15;
        // A bridge with no level: it can read the world through its signal source, and it cannot look at a
        // block state, so the wire block beside the host simply cannot be named.
        CarrierWorldIo noLevel = new CarrierWorldIo(pose(), (worldPos, worldDirection) -> 9);

        assertFalse(noLevel.holdsLosslessWireAt(WIRE_LOCAL),
                "no level means no block state, which is the answer that leaves the stock read standing");

        CarrierSpace space = new CarrierSpace(CARRIER, index, world, null, noLevel, true);
        space.setQueryPosition(HOST_LOCAL);

        assertEquals(9, space.externalSignal(Direction.EAST),
                "so the stock read stands: the bridge reads 9 and the answer is 9. A failure must never be "
                        + "turned into 0 - that would darken a host the world is feeding");
    }

    // -------------- 6. both frames: what the machine itself carries beside the wire --

    @Test
    @DisplayName("a source this machine carries beside the wire still arrives: the pair read asks BOTH frames")
    void theMachinesOwnSourceBesideTheWireStillArrivesThroughThePairRead() {
        carry(CARRIER, HOST_LOCAL, HOST_WORLD, ComponentType.SUPERCONDUCTOR);
        // The wire's real source is not in the world at all: it is a lever of THIS machine standing beside the
        // wire's own cell, so it lives in the structure frame's tables and nowhere else.
        carry(CARRIER, SOURCE_LOCAL, SOURCE_WORLD, ComponentType.LEVER).power = 15;

        // A level that answers nothing beside the wire, so the world frame has nothing to offer.
        CarrierWorldIo bridge = new CarrierWorldIo(pose(), (worldPos, worldDirection) -> 0);
        FakeBlocks blocks = new FakeBlocks();
        CarrierSpace space = new CarrierSpace(CARRIER, index, world, id -> blocks, bridge, true);
        space.setQueryPosition(HOST_LOCAL);

        assertEquals(0, CarrierSpace.worldSupplyAround(bridge, WIRE_LOCAL, HOST_LOCAL, Set.of(HOST_LOCAL)),
                "the world frame alone finds nothing here: what supplies this wire is carried by the machine, "
                        + "and that read refuses this carrier's own projected hosts by its own rule - so a pair "
                        + "read that asked the world alone would answer 0 at a wire that really holds 15");

        int own = space.ownSupplyOf(WIRE_LOCAL, blocks);
        assertEquals(15, own,
                "the structure frame finds the lever, read from its record at the wire's own side. A cell of a "
                        + "structure has two neighbourhoods and its own supply is the larger of the two, which "
                        + "is the whole reason the pair read may not ask one of them");
        assertEquals(15, CarrierSpace.worldHalfAnswer(0, own, CarrierSpace.worldPairRead(true, CARRIER, null)),
                "so the machine's own source beside the wire arrives at full strength through the pair - and it "
                        + "arrives from the source, not from anything the wire happened to be holding");
    }

    // ------------------- 7. the composition itself: both frames, and only both --

    @Test
    @DisplayName("the pair's own supply is the larger of the two frames, and neither frame may be dropped")
    void thePairSupplyIsTheLargerOfBothFrames() {
        assertEquals(15, CarrierSpace.pairOwnSupply(() -> 0, () -> 15),
                "a source the machine carries beside the wire stands in the structure frame alone: with the "
                        + "world frame empty the answer is still full strength - a composition that keeps only "
                        + "the world frame answers 0 here, at a wire that really holds 15");
        assertEquals(15, CarrierSpace.pairOwnSupply(() -> 15, () -> 0),
                "and a vanilla torch beside the drawn wire stands in the world frame alone, so keeping only the "
                        + "structure frame answers 0 in the other direction");
        assertEquals(15, CarrierSpace.pairOwnSupply(() -> 9, () -> 15),
                "the two frames are combined as the LARGER, never as one of them: nine in one frame and fifteen "
                        + "in the other is fifteen");
        assertEquals(15, CarrierSpace.pairOwnSupply(() -> 15, () -> 9),
                "in either order, because a cell's value is the maximum of what its two neighbourhoods push in");
        assertEquals(0, CarrierSpace.pairOwnSupply(() -> 0, () -> 0),
                "and a pair that supplies nothing but itself still comes down to zero, which is the whole point "
                        + "of the rule");
    }

    @Test
    @DisplayName("a structure that cannot be read keeps the world frame alone, which is the answer it made before")
    void aMissingStructureFrameAsksTheWorldFrameAlone() {
        assertEquals(15, CarrierSpace.pairOwnSupply(() -> 15, null),
                "no view means that frame cannot be asked at all, and the world frame's answer stands - which "
                        + "is what this read answered before the second frame existed");
        assertEquals(0, CarrierSpace.pairOwnSupply(() -> 0, null),
                "including when it is zero: an unreadable structure can never make the read worse than it was, "
                        + "and must never be read as a zero of its own");
        assertEquals(15, CarrierSpace.pairOwnSupply(null, () -> 15),
                "the mirror case: a bridge that cannot ask its frame leaves the structure frame's answer");
        assertEquals(0, CarrierSpace.pairOwnSupply(null, null),
                "and nothing to ask at all is zero, not an exception");
    }

    /**
     * <b>The private site itself.</b> Every read above pins a function; none of them can click into place if the
     * one line that <em>calls</em> the composition is reverted to a single frame, because {@code worldSignal} is
     * private and needs a live bridge and a level that a unit test cannot build - reverting that line leaves the
     * functions intact and every behavioural assertion green. So the private call site is pinned where it lives,
     * the way this project's other wiring tests pin a site a test cannot drive: the composed form must be there,
     * the structure frame must be offered only through the null check that keeps {@code readSignal} off a missing
     * view, and no single-frame assignment may stand in its place.
     */
    @Test
    @DisplayName("the world half composes the two frames at its private site - the revert no behaviour test can reach")
    void theWorldHalfComposesBothFramesAtItsCallSite() throws Exception {
        String space = flat("src/main/java/com/jiangyuefengyu/redstonecircuit/carrier/CarrierSpace.java");

        assertTrue(space.contains("ownSupply = pairOwnSupply("),
                "the world half's own-supply answer is the composition, not a frame");
        assertTrue(space.contains("view == null ? null : () -> structureSupplyOf(neighbour, reader, view)"),
                "and the structure frame is OFFERED through that null check only: readSignal dereferences the "
                        + "view without a guard, so a frame with no view is a question that must not be put");
        assertTrue(space.contains("() -> worldSupplyOf(neighbour, reader)"),
                "with the world frame supplied from the bridge, one cell at a time");
        assertFalse(space.contains("ownSupply = worldSupplyOf("),
                "and no single-frame assignment may stand in the composition's place - that is exactly the "
                        + "revert this line exists to catch, and it would answer 0 at a wire that really holds 15");
    }

    /** One file's text, whitespace-flattened, so a wiring assertion is not a formatting assertion. */
    private static String flat(String relative) throws Exception {
        return java.nio.file.Files.readString(java.nio.file.Path.of(relative))
                .replace("\r\n", "\n").replaceAll("\\s+", " ");
    }
}
