package com.jiangyuefengyu.redstonecircuit.carrier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.Slot;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import org.joml.Vector3d;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>P1.38: a structure's world half must read a host <em>another</em> structure is carrying there.</b>
 *
 * <h2>The author's scene, in numbers</h2>
 * Two blocks, placed next to each other, each physicalised on its own, neither moved, one dust put
 * inside each, and an external lever thrown at one of them. From the author's log, verbatim:
 *
 * <pre>
 *   carrier s:a70018b6: insert local=20481032, 128, 20483080 ... projected at -35, -60, -32
 *   carrier s:ade64b21: insert local=20481032, 128, 20481032 ... projected at -34, -60, -32
 * </pre>
 *
 * So the two blocks are <b>one world cell apart</b> - X differs by one - while their own coordinates
 * inside their structures differ by a whole <b>plot cell</b> (2048 along Z). Nothing in a structure's
 * own coordinate space connects them: {@code CarrierSpace#structureSignal} reads the machine's own
 * blocks at <em>local</em> coordinates, and B's local neighbour is a cell of B's own plot, 2048 blocks
 * from A's. The world half is the only route, and the world half is what this pins down.
 *
 * <h2>Why it used to read zero, and why "the projection table is right" is not enough</h2>
 * The world half projects B's local neighbour into the world and reads there. That world position holds
 * <b>air</b> in the level - a physicalised block is an entity's business, not the level's - so the
 * vanilla read answers 0. The projection table is the only thing that knows A's full-power dust is
 * standing on that cell, and the carrier half never asked it:
 *
 * <pre>
 *   world sign -&gt; max(readSignal(level, worldPos, dir), projectedSignalAt(level, worldPos, dir))
 *   carrier    -&gt; readSignal(level, worldPos, dir)          &lt;- the step that was missing
 * </pre>
 *
 * <p>Which is exactly what the author's judge line says: {@code worldQueries=0} - a counter the projection
 * lookup itself increments - so the projection source was never reached, not merely unhelpful.
 *
 * <h2>What is asserted here</h2>
 * The half that is a pure function: two sources, one answer, the larger one; the hop rule still applies
 * to the vanilla half; a frozen bridge still answers only the vanilla half; and the structure someone
 * carries is read in the <b>world</b> frame, not its own. Plus the wiring assertion that the carrier half
 * and the world half now call one implementation - the property that makes the two readers unable to
 * disagree.
 *
 * <p>Not asserted here, and honestly: a real {@code ServerLevel} (with mixins applied) cannot be built in
 * a unit test, so the end-to-end read is pinned by the two pure halves, by the wiring assertion, and by
 * the author's judgement line in the field.
 */
class CarrierSeamWorldReadTest {

    private static final CarrierId STRUCTURE_A =
            CarrierId.subLevel(UUID.fromString("00000000-0000-0000-0000-00000000a4e2"));
    private static final CarrierId STRUCTURE_B =
            CarrierId.subLevel(UUID.fromString("00000000-0000-0000-0000-00000000b4e2"));

    /** A's block stands here; B's block stands one cell east of it. This is the seam. */
    private static final BlockPos A_WORLD = new BlockPos(-35, -60, -32);
    private static final BlockPos B_WORLD = new BlockPos(-34, -60, -32);

    /** A's own coordinate inside A: one plot cell away from B's, in the author's own numbers. */
    private static final BlockPos A_LOCAL = new BlockPos(20481032, 128, 20483080);
    private static final BlockPos B_LOCAL = new BlockPos(20481032, 128, 20481032);

    private final CarriedHostIndex index = new CarriedHostIndex();
    private final FakeWorld world = new FakeWorld();

    private static BlockPos pos(int x, int y, int z) {
        return new BlockPos(x, y, z);
    }

    private Slot carry(CarrierId carrier, BlockPos local, BlockPos origin, ComponentType type) {
        Slot slot = new Slot(type);
        world.set(origin, slot);
        index.claim(carrier, local, origin, world);
        return index.at(carrier, local).slot();
    }

    /**
     * <b>The production arithmetic, usable without a level:</b> the larger of the vanilla world read and
     * the host a structure is carrying at that position.
     *
     * <p>This is {@code InnerRedstoneNetwork#worldSignalAt}, which both the carrier half and the
     * world-side solve call; here the first half is a double instead of a real block read, because a unit
     * test has no level to read one from. The wiring test at the bottom stops the two from drifting apart.
     */
    private static int combinedRead(int vanilla, int projected) {
        return Math.max(vanilla, projected);
    }

    /** A structure's own blocks are not the world's; nothing here is ever read. */
    private static final class FakeBlocks implements net.minecraft.world.level.BlockGetter {

        @Override
        public net.minecraft.world.level.block.state.BlockState getBlockState(BlockPos pos) {
            throw new UnsupportedOperationException("block state read at " + pos);
        }

        @Override
        public net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public net.minecraft.world.level.material.FluidState getFluidState(BlockPos pos) {
            return net.minecraft.world.level.material.Fluids.EMPTY.defaultFluidState();
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

    /** The carried contents, the same accessor the index writes through. */
    private static final class FakeWorld implements CarriedHostIndex.SlotWorld {

        final java.util.Map<Long, Slot> slots = new java.util.HashMap<>();

        @Override
        @org.jetbrains.annotations.Nullable
        public Slot get(BlockPos pos) {
            return slots.get(pos.asLong());
        }

        @Override
        public void set(BlockPos pos, @org.jetbrains.annotations.Nullable Slot slot) {
            if (slot == null) {
                slots.remove(pos.asLong());
            } else {
                slots.put(pos.asLong(), slot);
            }
        }
    }

    @BeforeEach
    void theFixtureIsTheAuthorsScene() {
        assertEquals(1, A_WORLD.distManhattan(B_WORLD),
                "the two blocks stand face to face in the world - the scene the author built");
        assertEquals(2048, Math.abs(A_LOCAL.getZ() - B_LOCAL.getZ()),
                "while their own coordinates differ by one plot cell, so nothing in the structure frame "
                        + "can carry a signal across");
        assertNotEquals(A_LOCAL, A_WORLD, "a sub-level's own coordinate is never a world position");
        assertTrue(A_LOCAL.getX() > 1_000_000 && B_LOCAL.getX() > 1_000_000,
                "the plot region the P1 diagnostics measured");
    }

    // ------------------------------------- 1. the missing step, at the seam --

    @Test
    @DisplayName("B reads A's carried host at the world seam, where the level itself holds air")
    void theCarriedHostAtTheSeamIsReadFromTheProjection() {
        int vanilla = 0;   // the level sees air where A's physicalised block stands
        int projected = 15; // and the projection table sees A's dust at full power

        assertEquals(15, combinedRead(vanilla, projected),
                "this is the whole of P1.38: the world half takes the larger of the two, so a block that "
                        + "is only there because a structure carries it is still read");
    }

    @Test
    @DisplayName("a real world block still wins when it is the stronger source")
    void theVanillaWorldIsStillReadAndCanWin() {
        assertEquals(11, combinedRead(11, 3),
                "taking the larger cannot lose a signal the level really has");
        assertEquals(11, combinedRead(11, 11),
                "and two sources agreeing is the ordinary case for a block that is both standing and "
                        + "carried (a structure picked up mid-tick)");
    }

    @Test
    @DisplayName("nothing projected at that position leaves the vanilla answer exactly as it was")
    void nothingProjectedChangesNothing() {
        assertEquals(9, combinedRead(9, 0),
                "an ordinary level read is answered by the level: the P1/P2 behaviour, unchanged");
        assertEquals(0, combinedRead(0, 0), "and empty stays empty");
    }

    @Test
    @DisplayName("the host is answered in the WORLD frame: a turned structure's directions are converted")
    void theProjectedHostIsAnsweredInTheWorldFrame() {
        Slot repeater = carry(STRUCTURE_A, A_LOCAL, A_WORLD, ComponentType.REPEATER);
        // FACING is the INPUT side, so a repeater facing WEST emits towards EAST - which is the side B
        // stands on, and the side A's own frame would call the answer for.
        repeater.facing = Direction.WEST;
        repeater.power = 15;

        // A carries its block, turned half a circle about its own centre. The world position of that
        // block is the same integer - a turn is invisible to the key - but which world side its output
        // leaves by is the structure's own orientation, so the answer has to be turned through the pose.
        CarrierPose turned = CarrierPose.of(
                new Vector3d(A_WORLD.getX() + 0.5, A_WORLD.getY() + 0.5, A_WORLD.getZ() + 0.5),
                new org.joml.Quaterniond().rotateY(Math.PI));
        CarrierProjection.ProjectedHost host = new CarrierProjection.ProjectedHost(STRUCTURE_A, A_LOCAL,
                A_WORLD, turned, repeater, repeater.power);

        // Read from B, which stands to the east: the query direction is from B towards A, i.e. WEST, so
        // the emission being asked about is towards EAST.
        assertEquals(15, CarrierProjection.emittedTowardInStructureFrame(host, Direction.WEST, false,
                        false),
                "in the structure's own frame the repeater emits east, and the query asks about east");
        assertEquals(0, CarrierProjection.emittedToward(host, Direction.WEST, false, false),
                "while the world half turns that query back through the pose: turned 180 degrees, the "
                        + "side facing B is no longer the repeater's output side");
        assertEquals(15, CarrierProjection.emittedToward(host, Direction.EAST, false, false),
                "and the world side the repeater DOES emit towards is the one the pose rotated it onto - "
                        + "which is what makes the world half the frame a seam read arrives in, and the "
                        + "structure half the wrong one to answer it from");
    }

    @Test
    @DisplayName("a structure's own blocks are read at local coordinates, so the seam cannot come from there")
    void theStructureHalfCannotCarryTheSeam() {
        // B's own dust asks its solver's structure half about the side A is on. In B's own frame that is
        // B_LOCAL's neighbour - a cell of B's own plot, 2048 blocks from A's cell - so the answer is
        // "nothing of mine is there", and it always was. The seam has to come from the world half.
        carry(STRUCTURE_B, B_LOCAL, B_WORLD, ComponentType.DUST).power = 15;
        CarrierSpace space = new CarrierSpace(STRUCTURE_B, index, world, id -> new FakeBlocks(), null);

        space.setQueryPosition(B_LOCAL);
        assertEquals(0, space.externalSignal(Direction.WEST),
                "no bridge and no neighbour of B's own inside its plot: the structure half has nothing "
                        + "to say, which is why reading the world at the projected position is the only "
                        + "route across the seam");
    }

    @Test
    @DisplayName("the world half the seam uses is built for the reading structure's own pose")
    void theSeamReadUsesTheReadingStructuresPose() throws Exception {
        // CarrierNetworks#worldIoOf builds one CarrierWorldIo per carrier per pass, from that carrier's
        // own pose - so B's read of the world is B's frame turned into the world, not A's. Pinned as
        // wiring because the alternative (a shared bridge for the whole level) would answer every
        // structure's reads in one structure's frame.
        String networks = flat(
                "src/main/java/com/jiangyuefengyu/redstonecircuit/carrier/CarrierNetworks.java");

        assertTrue(networks.contains("CarrierPose pose = bridge.poses().poseOf(carrier);"),
                "the pose is asked for THE carrier whose solve is running");
        assertTrue(networks.contains("return pose == null ? null : new CarrierWorldIo(pose,"
                        + " bridge.signals(), carrier, ctx.level());"),
                "and one CarrierWorldIo is built from it, per carrier per pass - naming that carrier as "
                        + "the reader (P1.41) and the level the projection table is indexed by, so the "
                        + "bridge can refuse that carrier's own projected hosts and no one else's");
        assertTrue(networks.contains("if (bridge == null || bridge.signals() == null"
                        + " || bridge.poses() == null) {"),
                "with no bridge - the frozen switch - the world half is null and every read is zero");
    }

    // ------------------------------------------------------------- 2. wiring --

    @Test
    @DisplayName("the carrier half and the world half call ONE implementation of the merged read")
    void bothHalvesShareTheMergedRead() throws Exception {
        String network = flat(
                "src/main/java/com/jiangyuefengyu/redstonecircuit/logic/InnerRedstoneNetwork.java");

        assertTrue(network.contains("() -> worldSignalAt(level, worldPos, worldDirection)"),
                "the carrier's bridge (CarrierWorldSignals, handed to CarrierWorldIo by "
                        + "CarrierNetworks#worldIoOf) must go through the merged read, not a bare "
                        + "readSignal: a bare one is the defect, verbatim");
        assertTrue(network.contains("int vanilla = readSignal(level, worldPos, worldDirection);")
                        && network.contains("int projected = projectedSignalAt(level, worldPos,"
                                + " worldDirection);"),
                "and the merged read is the vanilla block plus the projected host, in that order");
        assertTrue(network.contains("return projected;"),
                "with the larger of the two taken, exactly as the world half has done since P1.20");

        int sharedCalls = count(network, "projectedSignalAt(level, neighbour, direction)");
        assertEquals(1, sharedCalls,
                "the world half calls the shared projected read; a second copy of the rule is what the "
                        + "round exists to prevent");
        assertTrue(network.contains("public static int projectedSignalAt(ServerLevel level,"
                        + " BlockPos worldPos, Direction worldDirection) {"),
                "one public implementation, so a test and a future reader can reach it");
        assertTrue(!network.contains("private int projectedSignalInto("),
                "the old private copy is gone rather than left beside the shared one");
    }

    @Test
    @DisplayName("the projection read stays behind its switch and its degradation")
    void theProjectedReadKeepsItsSwitchAndItsFallback() throws Exception {
        String network = flat(
                "src/main/java/com/jiangyuefengyu/redstonecircuit/logic/InnerRedstoneNetwork.java");

        assertTrue(network.contains("if (!RCConfig.carrierWorldReadsProjection() || level == null"
                        + " || worldPos == null || worldDirection == null) {"),
                "carrierFreezeWorldIo=true (and a null argument) must still close the projected half");
        assertTrue(network.contains("catch (RuntimeException | LinkageError failed) {"),
                "and a failure in the bridge must answer zero, not take the solve down");
    }

    @Test
    @DisplayName("the judgement line is gated on debugLog and written at most once per tick")
    void theJudgementLineIsBounded() throws Exception {
        String network = flat(
                "src/main/java/com/jiangyuefengyu/redstonecircuit/logic/InnerRedstoneNetwork.java");

        assertTrue(network.contains("public static void logProjectedSignal(ServerLevel level,"),
                "the line both halves write through");
        assertTrue(network.contains("if (!RCConfig.debugLog()) {"),
                "behind the one switch every per-change line uses");
        assertTrue(network.contains("if (projectedLoggedTick == tick) {"),
                "one line per level tick, whatever the number of structures or components in it");
        assertTrue(network.contains("long tick = level.getGameTime();"),
                "bounded by the level's own clock rather than by a growing set");
        assertTrue(network.contains("[redstonecircuit] world solve at {} side={} <- carrier"),
                "and the existing words stay, because they are what the author greps for");
    }

    @Test
    @DisplayName("the hop rule still charges a vanilla wire one step, in the merged read")
    void theHopRuleIsStillTheVanillaHalfs() throws Exception {
        String network = flat(
                "src/main/java/com/jiangyuefengyu/redstonecircuit/logic/InnerRedstoneNetwork.java");

        assertTrue(network.contains("return neighbourSignal(signal, state != null && state.is("
                        + "Blocks.REDSTONE_WIRE));"),
                "a neighbouring vanilla wire is still charged one hop, exactly as before");
        assertEquals(14, com.jiangyuefengyu.redstonecircuit.logic.InnerRedstoneNetwork
                        .neighbourSignal(15, true),
                "a full-strength vanilla wire offers one step less");
        assertEquals(15, com.jiangyuefengyu.redstonecircuit.logic.InnerRedstoneNetwork
                        .neighbourSignal(15, false),
                "and everything else passes through");
    }

    /** One file's text, whitespace-flattened, so a wiring assertion is not a formatting assertion. */
    private static String flat(String relative) throws Exception {
        return java.nio.file.Files.readString(java.nio.file.Path.of(relative))
                .replace("\r\n", "\n").replaceAll("\\s+", " ");
    }

    private static int count(String haystack, String needle) {
        int found = 0;
        int at = haystack.indexOf(needle);
        while (at >= 0) {
            found++;
            at = haystack.indexOf(needle, at + needle.length());
        }
        return found;
    }
}
