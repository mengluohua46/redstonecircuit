package com.jiangyuefengyu.redstonecircuit.carrier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.Slot;
import com.jiangyuefengyu.redstonecircuit.logic.SolverNotifications;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>P1.36: the machine's own blocks have to be told that a carried host's output changed.</b>
 *
 * <h2>The defect, in the author's words and in coordinates</h2>
 * "The redstone inside a physicalised block cannot drive the piston above it." The mechanism is a
 * missing notification, not a missing value: a host the mod is carrying was announced at the world
 * position it is <em>projected onto</em> and nowhere else, while a piston the machine is carrying lives
 * at its coordinate <em>inside</em> the structure - which for a Sable sub-level is a real level position
 * (plot coordinate == level coordinate, P1.30 section 30.1.2). Two different numbers, one of them never
 * woken.
 *
 * <h2>What this class drives, and what it deliberately does not</h2>
 * The two decisions that make up the fix are production code and are driven here:
 *
 * <ol>
 *   <li><b>Which hosts have news for the structure frame</b> - {@link CarrierProjection#forEachChange}
 *       with its structure half, on a real {@link CarrierProjection} filled the way the tick fills it:
 *       {@code begin}, {@code put} (snapshotting the value), the solve (the test moves the live slot),
 *       then the walk.</li>
 *   <li><b>How often one coordinate may be announced</b> - the real
 *       {@link SolverNotifications#structurePass(ResourceKey, long, java.util.function.Consumer,
 *       boolean)}, which is the same §24 rule the world side uses and is subject to the same switch.
 *   </li>
 * </ol>
 *
 * <p>What cannot be driven offline is the level itself, so the "recording fake level" the brief asks
 * for is the seam §24's own tests already use: the notification is a {@code Consumer<BlockPos>} and the
 * recorder is a list. {@code ServerLevel} is a class with a running server behind its constructor and
 * {@code updateNeighborsAt} is a final-ish method on it, so a fake level is impossible; what is asserted
 * instead is every decision <em>up to</em> the call - which coordinate, how many times, and in which
 * frame - plus, in {@link #theProductionWiringCallsTheStructurePass}, that production is wired to this
 * decision and not to a copy of it.
 */
class CarrierStructureNoticeTest {

    private static final CarrierId SUB_LEVEL =
            CarrierId.subLevel(UUID.fromString("00000000-0000-0000-0000-000000005ab1"));
    private static final CarrierId CONTRAPTION =
            CarrierId.contraption(UUID.fromString("00000000-0000-0000-0000-00000000c0ff"));
    private static final ResourceKey<Level> DIMENSION = ResourceKey.create(
            net.minecraft.core.registries.Registries.DIMENSION,
            ResourceLocation.parse("redstonecircuit:structure_notice_test"));

    /** Where the machine's own block sits in the level; a plot coordinate in the real thing. */
    private static final BlockPos LOCAL = new BlockPos(20481032, 127, 20481032);
    /** Where that block currently is in the world, a machine's width away. */
    private static final BlockPos WORLD = new BlockPos(-12, 64, -8);

    private static BlockPos pos(int x, int y, int z) {
        return new BlockPos(x, y, z);
    }

    private static CarrierPose poseAt(double x, double y, double z) {
        return CarrierPose.at(new Vector3d(x, y, z));
    }

    /** A component whose power the test can move between passes, exactly as the solve does. */
    private static Slot dust(int power) {
        Slot slot = new Slot(ComponentType.DUST);
        slot.power = power;
        return slot;
    }

    /** One host, snapshotted the way {@code CarrierStore#project} snapshots it. */
    private static CarrierProjection.ProjectedHost host(CarrierId carrier, BlockPos local, BlockPos world,
                                                        CarrierPose pose, Slot slot) {
        return new CarrierProjection.ProjectedHost(carrier, local, world, pose, slot,
                slot == null ? 0 : slot.power);
    }

    /**
     * One level tick of the notification pass, in production's shape.
     *
     * <p>{@code CarrierStore#notifyProjectionChanges}, one line at a time: ask the coalescer for this
     * tick's structure pass, hand every world position to the world recorder, and offer every changed
     * host's structure coordinate to the pass. With the switch off the pass is {@code null} and the
     * structure half is simply not offered - which is the previous build's behaviour.
     */
    private static final class Pass {

        final List<BlockPos> world = new ArrayList<>();
        final List<BlockPos> structure = new ArrayList<>();

        /** The real coalescer, or {@code null} when the switch is off. */
        final SolverNotifications.StructurePass pass;

        Pass(long tick, boolean enabled) {
            pass = SolverNotifications.structurePass(DIMENSION, tick, structure::add, enabled);
        }

        boolean enabled() {
            return pass != null;
        }

        void run(CarrierProjection projection) {
            projection.forEachChange(DIMENSION, world::add,
                    pass == null ? null : host -> pass.notify(host.localPos()), null);
        }
    }

    @BeforeEach
    void clearCoalescer() {
        SolverNotifications.clearAll();
    }

    // ------------------------------------------------- 1. the fix, one pass --

    @Test
    @DisplayName("a carried host whose output changed gives the structure's own coordinate exactly one notice")
    void theStructureCoordinateIsNotifiedWhenTheOutputChanged() {
        CarrierProjection projection = new CarrierProjection();
        Pass pass = new Pass(1L, true);
        Slot slot = dust(12);

        // This tick's pass, in production order: the table is (re)built, the host is projected with its
        // value at that moment, the solve changes it, and then the change pass runs.
        projection.begin(DIMENSION, true);
        projection.put(DIMENSION, host(SUB_LEVEL, LOCAL, WORLD, poseAt(-12.0, 64.0, -8.0), slot));
        slot.power = 9;

        pass.run(projection);

        assertEquals(List.of(LOCAL), pass.structure,
                "the structure's own coordinate is where the machine's piston lives, so it is what has "
                        + "to be woken");
        assertEquals(List.of(WORLD), pass.world,
                "and the world position is still announced, once, exactly as before this round");
        assertFalse(LOCAL.equals(WORLD), "the fixture has to be two different coordinates");
    }

    @Test
    @DisplayName("a machine that only moved says nothing to its own frame, and the world is told as always")
    void movingIsNotAnOutputChange() {
        CarrierProjection projection = new CarrierProjection();
        Slot slot = dust(7);

        // Tick one: the machine is here, at power 7.
        Pass first = new Pass(1L, true);
        projection.begin(DIMENSION, true);
        projection.put(DIMENSION, host(SUB_LEVEL, LOCAL, WORLD, poseAt(-12.0, 64.0, -8.0), slot));
        first.run(projection);
        assertEquals(List.of(LOCAL), first.structure);

        // Tick two: nothing changed except where the machine is. Every projected position moves, so the
        // world has to be told about both ends - and the structure's own coordinate has heard nothing.
        Pass second = new Pass(2L, true);
        BlockPos moved = pos(-11, 64, -8);
        projection.begin(DIMENSION, true);
        projection.put(DIMENSION, host(SUB_LEVEL, LOCAL, moved, poseAt(-11.0, 64.0, -8.0), slot));
        second.run(projection);

        assertEquals(List.of(WORLD, moved), second.world, "the place it left and the place it arrived at");
        assertTrue(second.structure.isEmpty(),
                "no component's output changed, so the machine's own blocks have nothing to re-read: "
                        + second.structure);
    }

    @Test
    @DisplayName("a Create contraption is never announced at its local coordinate, which is not a level position")
    void aContraptionIsNotAnnouncedAtItsLocalCoordinate() {
        CarrierProjection projection = new CarrierProjection();
        Pass pass = new Pass(1L, true);
        Slot slot = dust(15);
        BlockPos contraptionLocal = pos(1, 0, 2);

        projection.begin(DIMENSION, true);
        projection.put(DIMENSION, host(CONTRAPTION, contraptionLocal, WORLD, poseAt(-12.0, 64.0, -8.0),
                slot));
        slot.power = 3;

        pass.run(projection);

        assertTrue(pass.structure.isEmpty(),
                "a contraption's local coordinates are 0..15 around its anchor; announcing them would "
                        + "wake whatever happens to sit near the world origin");
        assertNull(projection.atStructure(DIMENSION, contraptionLocal),
                "and nothing is indexed by them either, so a signal query there cannot be answered");
        assertEquals(List.of(WORLD), pass.world, "the world half is untouched");
    }

    // ----------------------------------------------------- 2. once per tick --

    @Test
    @DisplayName("one coordinate is announced at most once per tick, however often it is offered")
    void oneCoordinateIsAnnouncedOncePerTick() {
        Pass tickOne = new Pass(1L, true);
        assertTrue(tickOne.pass.notify(LOCAL));
        assertFalse(tickOne.pass.notify(LOCAL), "the same coordinate again in the same tick is not news");
        assertFalse(tickOne.pass.notify(LOCAL));
        assertEquals(List.of(LOCAL), tickOne.structure);
        assertEquals(1, tickOne.pass.sent());

        // Asking for this tick's pass again returns the pass that is already open - the property that
        // makes "once per tick" hold even if a second driver ever runs in the same tick.
        SolverNotifications.StructurePass again =
                SolverNotifications.structurePass(DIMENSION, 1L, tickOne.structure::add, true);
        assertSame(tickOne.pass, again);
        assertFalse(again.notify(LOCAL));
        assertEquals(1, tickOne.structure.size());

        // The next tick is a new pass, and the same coordinate is news again.
        SolverNotifications.StructurePass next =
                SolverNotifications.structurePass(DIMENSION, 2L, tickOne.structure::add, true);
        assertNotNull(next);
        assertTrue(next.notify(LOCAL));
        assertEquals(List.of(LOCAL, LOCAL), tickOne.structure);
    }

    @Test
    @DisplayName("two dimensions keep their own pass, and clearing one leaves the other alone")
    void passesArePerDimension() {
        ResourceKey<Level> other = ResourceKey.create(
                net.minecraft.core.registries.Registries.DIMENSION,
                ResourceLocation.parse("redstonecircuit:structure_notice_other"));
        List<BlockPos> mine = new ArrayList<>();
        List<BlockPos> theirs = new ArrayList<>();
        SolverNotifications.StructurePass here =
                SolverNotifications.structurePass(DIMENSION, 5L, mine::add, true);
        SolverNotifications.StructurePass there =
                SolverNotifications.structurePass(other, 5L, theirs::add, true);
        assertNotNull(here);
        assertNotNull(there);

        assertTrue(here.notify(LOCAL));
        assertTrue(there.notify(LOCAL));
        assertEquals(List.of(LOCAL), mine);
        assertEquals(List.of(LOCAL), theirs);

        SolverNotifications.clear(DIMENSION);
        assertEquals(List.of(LOCAL), mine);
        SolverNotifications.StructurePass afterClear =
                SolverNotifications.structurePass(DIMENSION, 5L, mine::add, true);
        assertNotNull(afterClear);
        assertTrue(afterClear.notify(LOCAL), "the pass went with the level it belonged to");
        assertFalse(there.notify(LOCAL), "and the other dimension was not touched");
    }

    // ------------------------------------------------------ 3. never twice --

    @Test
    @DisplayName("when the structure coordinate is the world coordinate it is notified once, not twice")
    void anIdenticalWorldAndStructureCoordinateIsNotifiedOnce() {
        CarrierProjection projection = new CarrierProjection();
        Pass pass = new Pass(1L, true);
        Slot slot = dust(11);
        // The degenerate pose: the machine is where its own coordinates already are. It cannot happen
        // for a real sub-level, but the rule has to hold without depending on that.
        BlockPos same = pos(4, 70, 4);

        projection.begin(DIMENSION, true);
        projection.put(DIMENSION, host(SUB_LEVEL, same, same, poseAt(0.0, 0.0, 0.0), slot));
        slot.power = 5;

        pass.run(projection);

        assertEquals(List.of(same), pass.world, "the coordinate was announced by the world half");
        assertTrue(pass.structure.isEmpty(),
                "and the structure half has nothing to add: one coordinate, one notification");
        int total = pass.world.size() + pass.structure.size();
        assertEquals(1, total, "one changed coordinate must not be woken twice in one tick");
    }

    // --------------------------------------------------------- 4. switches --

    @Test
    @DisplayName("with the switch off nothing is offered, and the world half is byte-for-byte the same")
    void switchedOffRestoresThePreviousBehaviour() {
        CarrierProjection on = new CarrierProjection();
        CarrierProjection off = new CarrierProjection();
        Pass onPass = new Pass(1L, true);
        Pass offPass = new Pass(1L, false);
        assertTrue(onPass.enabled(), "the default is on");
        assertFalse(offPass.enabled(), "off is null: no pass, nothing offered, nothing sent");

        Slot onSlot = dust(6);
        Slot offSlot = dust(6);
        on.begin(DIMENSION, true);
        off.begin(DIMENSION, true);
        on.put(DIMENSION, host(SUB_LEVEL, LOCAL, WORLD, poseAt(-12.0, 64.0, -8.0), onSlot));
        off.put(DIMENSION, host(SUB_LEVEL, LOCAL, WORLD, poseAt(-12.0, 64.0, -8.0), offSlot));
        onSlot.power = 3;
        offSlot.power = 3;

        onPass.run(on);
        offPass.run(off);

        assertEquals(List.of(LOCAL), onPass.structure, "on: the machine's own block is told");
        assertTrue(offPass.structure.isEmpty(), "off: exactly the behaviour before this round");
        assertEquals(onPass.world, offPass.world,
                "and the world-side notification is identical either way - one line of it was not "
                        + "touched by this round");
        assertEquals(List.of(WORLD), offPass.world);
    }

    @Test
    @DisplayName("a frozen bridge closes the structure frame with the rest of it")
    void aFrozenBridgeAnswersNothingInEitherFrame() {
        CarrierProjection projection = new CarrierProjection();
        projection.begin(DIMENSION, false);
        projection.put(DIMENSION, host(SUB_LEVEL, LOCAL, WORLD, poseAt(-12.0, 64.0, -8.0), dust(9)));

        assertNull(projection.at(DIMENSION, WORLD), "carrierFreezeWorldIo still closes the world half");
        assertNull(projection.atStructure(DIMENSION, LOCAL),
                "and the structure frame is part of the same bridge, so it is closed too");
        assertEquals(0, projection.structureSize(DIMENSION));
    }

    // ------------------------------------------------- 5. the world side --

    @Test
    @DisplayName("the world positions and turn reports are unchanged by the second half's presence")
    void theWorldHalfIsUnchanged() {
        List<BlockPos> oldApi = new ArrayList<>();
        List<BlockPos> newApi = new ArrayList<>();
        List<String> oldTurns = new ArrayList<>();
        List<String> newTurns = new ArrayList<>();

        CarrierProjection forOld = new CarrierProjection();
        CarrierProjection forNew = new CarrierProjection();

        // A scripted run that exercises every branch the world comparison has: appear, park, change
        // value, move, turn on the spot, and vanish.
        CarrierPose here = poseAt(-12.0, 64.0, -8.0);
        CarrierPose turned = CarrierPose.of(new Vector3d(-12.0, 64.0, -8.0),
                new Quaterniond().rotateY(Math.PI));
        Slot oldSlot = dust(10);
        Slot newSlot = dust(10);

        for (int tick = 0; tick < 6; tick++) {
            BlockPos world = tick >= 3 ? pos(-11, 64, -8) : WORLD;
            CarrierPose pose = tick >= 4 ? turned : here;
            boolean present = tick < 5;
            int power = tick == 2 ? 4 : 10;

            forOld.begin(DIMENSION, true);
            forNew.begin(DIMENSION, true);
            if (present) {
                forOld.put(DIMENSION, host(SUB_LEVEL, LOCAL, world, pose, oldSlot));
                forNew.put(DIMENSION, host(SUB_LEVEL, LOCAL, world, pose, newSlot));
            }
            oldSlot.power = power;
            newSlot.power = power;

            forOld.forEachChange(DIMENSION, oldApi::add, turned1 -> oldTurns.add(turned1.describe()));
            forNew.forEachChange(DIMENSION, newApi::add, host -> { }, turned1 -> newTurns.add(
                    turned1.describe()));
        }

        assertEquals(oldApi, newApi,
                "the world positions must come out in the same order, however many seams are added "
                        + "beside them: " + oldApi + " vs " + newApi);
        assertEquals(oldTurns, newTurns, "and so must the turn reports");
        assertTrue(oldApi.size() >= 5, "the fixture has to actually exercise the branches: " + oldApi);
        assertTrue(oldTurns.size() >= 1, "including a turn: " + oldTurns);
    }

    // ------------------------------------------------------- 6. the frame --

    @Test
    @DisplayName("a query made inside the structure is answered in the structure's own frame, pose and all")
    void theStructureFrameIsTheQueryFrame() {
        Slot repeater = new Slot(ComponentType.REPEATER);
        repeater.facing = Direction.NORTH; // FACING is the input side, so this one emits towards SOUTH
        repeater.power = 9;
        CarrierPose straight = poseAt(-12.0, 64.0, -8.0);
        CarrierPose turned = CarrierPose.of(new Vector3d(-12.0, 64.0, -8.0),
                new Quaterniond().rotateY(Math.PI));

        CarrierProjection.ProjectedHost still =
                host(SUB_LEVEL, LOCAL, WORLD, straight, repeater);
        CarrierProjection.ProjectedHost rotated =
                host(SUB_LEVEL, LOCAL, WORLD, turned, repeater);

        // A query from the block NORTH of the host: the direction from asker to host is NORTH, and the
        // signal travels SOUTH - which is exactly what this repeater emits.
        Direction fromAsker = Direction.NORTH;
        assertEquals(9, CarrierProjection.emittedTowardInStructureFrame(still, fromAsker, false, false));
        assertEquals(9, CarrierProjection.emittedToward(still, fromAsker, false, false),
                "with no turn the two frames are the same question and must agree");

        // Turned 180 degrees, the world-frame answer rotates with the machine - and the structure-frame
        // one must not: inside the machine, the neighbour is still the neighbour.
        assertEquals(9, CarrierProjection.emittedTowardInStructureFrame(rotated, fromAsker, false, false),
                "a query that already arrives in the structure's own frame needs no pose conversion");
        assertEquals(0, CarrierProjection.emittedToward(rotated, fromAsker, false, false),
                "while the world-frame rule does rotate - which is what makes the pair meaningful");
    }

    @Test
    @DisplayName("the structure frame is indexed only for hosts a structure's own coordinate names")
    void theStructureFrameIndexesOnlySubLevelHosts() {
        CarrierProjection projection = new CarrierProjection();
        CarrierPose pose = poseAt(-12.0, 64.0, -8.0);
        projection.begin(DIMENSION, true);
        projection.put(DIMENSION, host(SUB_LEVEL, LOCAL, WORLD, pose, dust(9)));
        projection.put(DIMENSION, host(CONTRAPTION, pos(0, 0, 0), pos(1, 64, 1), pose, dust(9)));

        assertEquals(2, projection.size(DIMENSION), "both hosts are in the world table");
        assertEquals(1, projection.structureSize(DIMENSION), "only the sub-level one is in the other");
        CarrierProjection.ProjectedHost found = projection.atStructure(DIMENSION, LOCAL);
        assertNotNull(found);
        assertEquals(LOCAL, found.localPos());
        assertSame(SUB_LEVEL, found.carrier());
        assertNull(projection.atStructure(DIMENSION, pos(0, 0, 0)),
                "a contraption's local coordinate is not a level position and is not indexed");
        assertNull(projection.atStructure(DIMENSION, pos(1, 64, 1)),
                "and the world position is not the structure coordinate either");
    }

    // ----------------------------------------------------------- 7. wiring --

    @Test
    @DisplayName("production calls the structure pass and the new read, and still calls the world one")
    void theProductionWiringCallsTheStructurePass() throws Exception {
        String store = flat("src/main/java/com/jiangyuefengyu/redstonecircuit/carrier/CarrierStore.java");
        assertTrue(store.contains("SolverNotifications.structurePass(level,"),
                "the notification pass must ask the coalescer for this tick's structure pass");
        assertTrue(store.contains("structures == null ? null : host -> structures.notify(host.localPos())"),
                "and offer it each changed host's structure coordinate");
        assertTrue(store.contains("forEachChange(dimension, pos -> notifyWorld(level, pos),"),
                "the world half is still the same call it has always been");
        assertTrue(store.contains("InnerRedstoneNetwork.notifyNeighboursNow(level, structurePos, "
                        + "host.slot())"),
                "and the structure coordinate is notified with the carried record's own component");

        String mixin = flat(
                "src/main/java/com/jiangyuefengyu/redstonecircuit/mixin/BlockStateSignalMixin.java");
        assertTrue(mixin.contains("CarrierStore.structureHostAt(serverLevel, pos)"),
                "a signal query at a structure's own coordinate has to be answered");
        assertTrue(mixin.contains("CarrierStore.projectedHostAt(serverLevel, pos)"),
                "and the world-frame read is still there");
        // P1.38 moved WHICH answer wins out of the mixin and into one pure rule, so the structure frame's
        // own-frame answer is asserted where it now lives; the mixin is asserted to ask that rule.
        assertTrue(mixin.contains("BlockSignalSources.select("),
                "the mixin asks the rule rather than carrying the branch itself");
        String sources = flat(
                "src/main/java/com/jiangyuefengyu/redstonecircuit/logic/BlockSignalSources.java");
        assertTrue(sources.contains("CarrierProjection.emittedTowardInStructureFrame("),
                "in the structure's own frame - no second pose conversion");
        assertTrue(sources.contains("CarrierProjection.emittedToward(projected,"),
                "while the world projection is answered through the pose");
    }

    /** One file's text, whitespace-flattened, so a wiring assertion is not a formatting assertion. */
    private static String flat(String relative) throws Exception {
        return Files.readString(Path.of(relative)).replace("\r\n", "\n").replaceAll("\\s+", " ");
    }
}
