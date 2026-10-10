package com.jiangyuefengyu.redstonecircuit.carrier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.Slot;

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
 * Two physicalised blocks standing next to each other have to keep talking.
 *
 * <p>A sub-level's plot coordinates <em>are</em> level coordinates, so one integer can name a world
 * position and a plot cell at the same time. The structure-frame source may only answer for a coordinate
 * that is the found host's own structure's block; otherwise it shadows the world projection source - the
 * only source that can answer "what stands where" - and the seam reads the wrong frame, or zero.
 */
class CarrierCrossStructureSignalTest {

    /** One block, physicalised: one sub-level per block. */
    private static final CarrierId STRUCTURE_A =
            CarrierId.subLevel(UUID.fromString("00000000-0000-0000-0000-00000000a17e"));
    /** The block standing one block away, physicalised on its own: the second sub-level. */
    private static final CarrierId STRUCTURE_B =
            CarrierId.subLevel(UUID.fromString("00000000-0000-0000-0000-00000000b17e"));

    private static final ResourceKey<Level> DIMENSION = ResourceKey.create(
            net.minecraft.core.registries.Registries.DIMENSION,
            ResourceLocation.parse("redstonecircuit:cross_structure_signal_test"));

    /** A's block, inside A: the coordinate a query from B is answered at. */
    private static final BlockPos A_LOCAL = new BlockPos(-24, -60, -21);
    /** A's block's position in the world: what the projection table is keyed by. */
    private static final BlockPos A_WORLD = new BlockPos(-24, -60, -22);
    /** B's block, inside B: its own plot cell, one block from A's block. */
    private static final BlockPos B_LOCAL = new BlockPos(-24, -60, -20);
    /** B's block's position in the world: one block from A's. This is the seam. */
    private static final BlockPos B_WORLD = B_LOCAL;

    private static CarrierPose poseAt(double x, double y, double z) {
        return CarrierPose.at(new Vector3d(x, y, z));
    }

    private static Slot dust(int power) {
        Slot slot = new Slot(ComponentType.DUST);
        slot.power = power;
        return slot;
    }

    private static CarrierProjection.ProjectedHost host(CarrierId carrier, BlockPos local, BlockPos world,
                                                        CarrierPose pose, Slot slot) {
        return new CarrierProjection.ProjectedHost(carrier, local, world, pose, slot,
                slot == null ? 0 : slot.power);
    }

    /** A's dust one block from B's dust, each block its own sub-level, projected as one tick projects it. */
    private static CarrierProjection twoAdjacentStructures(Slot a, Slot b) {
        CarrierProjection projection = new CarrierProjection();
        projection.begin(DIMENSION, true);
        projection.put(DIMENSION, host(STRUCTURE_A, A_LOCAL, A_WORLD,
                poseAt(-24.0, -60.0, -22.0), a));
        projection.put(DIMENSION, host(STRUCTURE_B, B_LOCAL, B_WORLD,
                poseAt(-24.0, -60.0, -20.0), b));
        return projection;
    }

    @BeforeEach
    void theFixtureIsTheAuthorsLayout() {
        assertNotEquals(STRUCTURE_A, STRUCTURE_B, "two independently physicalised blocks");
        assertNotEquals(A_LOCAL, A_WORLD, "a real sub-level's block is never at its own world position");
        assertNotEquals(A_LOCAL, B_LOCAL, "the two structures' own cells are different numbers");
        assertEquals(2, A_WORLD.distManhattan(B_WORLD),
                "the two blocks stand one cell apart from B's own cell, and B's cell is the world seam");
    }

    // ------------------------------------------- 1. the collision itself --

    @Test
    @DisplayName("B's ask about the seam is a plot coordinate, and the structure index answers it with A")
    void theStructureIndexAnswersBsAskWithAnotherStructuresHost() {
        CarrierProjection projection = twoAdjacentStructures(dust(15), dust(0));

        // In B's frame that ask is a plot coordinate, and the number is a cell of A's: the index holds A's host.
        CarrierProjection.ProjectedHost found = projection.atStructure(DIMENSION, A_LOCAL);
        assertNotNull(found, "the structure index does hold a host at the coordinate B asked about");
        assertEquals(STRUCTURE_A, found.carrier(),
                "and that host belongs to A, not to the machine that asked");

        // The world half answers where A's block stands - what B would get with no shadow in front of it.
        CarrierProjection.ProjectedHost viaWorld = projection.at(DIMENSION, A_WORLD);
        assertNotNull(viaWorld, "the world half holds A's block where it stands");
        assertEquals(STRUCTURE_A, viaWorld.carrier());
        assertEquals(A_LOCAL, viaWorld.localPos(), "and it names A's own cell as the block's home");
    }

    @Test
    @DisplayName("a plot cell of another structure must not be answered from the structure frame")
    void anotherStructuresPlotCellIsNotAnsweredFromTheStructureFrame() {
        CarrierProjection projection = twoAdjacentStructures(dust(15), dust(0));

        CarrierProjection.ProjectedHost found = projection.atStructure(DIMENSION, A_LOCAL);
        assertNotNull(found);

        // The shadow, in one line: B's question about the seam, answered by A's host in A's frame.
        assertEquals(15, CarrierProjection.emittedTowardInStructureFrame(found, Direction.SOUTH, false,
                        false),
                "A's dust at full power, answered in A's frame for a question B asked");

        // That coordinate belongs to A, so B is not answered from it: the world source gets to speak.
        assertNull(projection.atStructureOwnedBy(DIMENSION, A_LOCAL, STRUCTURE_B),
                "a query at A's plot cell must not be answered when the asker is B");
        assertFalse(CarrierProjection.withinStructureFrame(found, STRUCTURE_B),
                "and the rule behind it is exactly that comparison");
        assertEquals(CarrierProjection.StructureRead.OTHER_STRUCTURE,
                CarrierProjection.structureRead(found, STRUCTURE_B),
                "and the reason has a name, so a log line can say which rule fired");
    }

    @Test
    @DisplayName("and when the shadowing host is empty the shadow answers zero")
    void anEmptyShadowCostsTheCrossStructureRead() {
        // A holds nothing, so the shadow is the wrong *value* too: an empty host answers 0, like no signal.
        CarrierProjection projection = twoAdjacentStructures(null, dust(12));

        CarrierProjection.ProjectedHost shadow = projection.atStructure(DIMENSION, A_LOCAL);
        assertNotNull(shadow, "the index still has an entry - A's block is still there, just empty");
        assertEquals(STRUCTURE_A, shadow.carrier());
        assertNull(shadow.slot(), "A has no component here");
        assertEquals(0, CarrierProjection.emittedTowardInStructureFrame(shadow, Direction.SOUTH, false,
                        false),
                "so the shadow answers zero, and the seam goes dark");
        assertNull(projection.atStructureOwnedBy(DIMENSION, A_LOCAL, STRUCTURE_B),
                "the guard refuses it, so the world projection source is the one that gets to speak");
        assertNotNull(projection.at(DIMENSION, A_WORLD),
                "and it does have something to say, so the read is restored rather than merely refused");
    }

    // --------------------------------- 2. the fix: both reads, both frames --

    @Test
    @DisplayName("each structure's own block is still answered in its own frame")
    void theStructureFrameStillAnswersForItsOwnStructure() {
        CarrierProjection projection = twoAdjacentStructures(dust(15), dust(9));

        CarrierProjection.ProjectedHost inA = projection.atStructureOwnedBy(DIMENSION, A_LOCAL,
                STRUCTURE_A);
        assertNotNull(inA, "A's own plot cell is in the structure index and belongs to A");
        assertEquals(STRUCTURE_A, inA.carrier());
        assertNotNull(inA.slot(), "the host holds a component, or it answers nothing at all");
        assertEquals(15, inA.slot().power);
        assertEquals(CarrierProjection.StructureRead.OWN_STRUCTURE,
                CarrierProjection.structureRead(inA, STRUCTURE_A),
                "asked from inside A, this is A's own coordinate and the structure frame owns the answer");

        // And B's own cell is answered for B: the same ability on the other machine.
        CarrierProjection.ProjectedHost inB = projection.atStructureOwnedBy(DIMENSION, B_LOCAL,
                STRUCTURE_B);
        assertNotNull(inB);
        assertEquals(STRUCTURE_B, inB.carrier());
        assertNotNull(inB.slot());
        assertEquals(9, inB.slot().power);
    }

    @Test
    @DisplayName("the cross-structure read is answered by the world projection source, in the world frame")
    void theWorldProjectionSourceCarriesTheSignalAcrossTheSeam() {
        CarrierProjection projection = twoAdjacentStructures(dust(15), dust(0));

        CarrierProjection.ProjectedHost aInWorld = projection.at(DIMENSION, A_WORLD);
        assertNotNull(aInWorld, "A's block is projected where it stands");
        assertEquals(15, CarrierProjection.emittedToward(aInWorld, Direction.NORTH, false, false),
                "dust emits on every side, so the seam reads A's full power - the answer the structure "
                        + "source was shadowing");
        assertNull(projection.atStructureOwnedBy(DIMENSION, A_LOCAL, STRUCTURE_B),
                "and that is the source that gets to answer, because the structure frame refused");
    }

    @Test
    @DisplayName("the structure frame does not rotate the answer, and the world frame does")
    void theTwoFramesKeepTheirOwnDirections() {
        Slot repeater = new Slot(ComponentType.REPEATER);
        repeater.facing = Direction.NORTH; // FACING is the input side, so it emits towards SOUTH
        repeater.power = 15;

        CarrierPose turned = CarrierPose.of(new Vector3d(-24.0, -60.0, -22.0),
                new Quaterniond().rotateY(Math.PI));
        CarrierProjection.ProjectedHost rotated =
                host(STRUCTURE_A, A_LOCAL, A_WORLD, turned, repeater);

        Direction fromAsker = Direction.NORTH;
        assertEquals(15, CarrierProjection.emittedTowardInStructureFrame(rotated, fromAsker, false,
                        false),
                "a query from inside the structure is already in the structure's frame: no rotation");
        assertEquals(0, CarrierProjection.emittedToward(rotated, fromAsker, false, false),
                "while a query from the world is rotated by the pose - which is what makes the two "
                        + "rules different, and the shadow harmful the moment the two disagree");

        assertEquals(CarrierProjection.StructureRead.OWN_STRUCTURE,
                CarrierProjection.structureRead(rotated, STRUCTURE_A));
        assertEquals(CarrierProjection.StructureRead.OTHER_STRUCTURE,
                CarrierProjection.structureRead(rotated, STRUCTURE_B));
        assertEquals(CarrierProjection.StructureRead.NOT_A_STRUCTURE,
                CarrierProjection.structureRead(rotated, null),
                "a coordinate no structure's own storage contains is an ordinary world coordinate");
    }

    // ----------------------------------------- 3. the notification half --

    @Test
    @DisplayName("a changed output still notifies the structure's own cell, and the world as always")
    void theStructureCellIsStillNotifiedWhenTheOutputChanged() {
        CarrierProjection projection = new CarrierProjection();
        Slot a = dust(0);
        projection.begin(DIMENSION, true);
        projection.put(DIMENSION, host(STRUCTURE_A, A_LOCAL, A_WORLD,
                poseAt(-24.0, -60.0, -22.0), a));
        a.power = 15;

        List<BlockPos> world = new ArrayList<>();
        List<BlockPos> structure = new ArrayList<>();
        projection.forEachChange(DIMENSION, world::add, h -> structure.add(h.localPos()), null);

        assertEquals(List.of(A_LOCAL), structure,
                "the machine's own block is woken - the notification half is not being given up");
        assertEquals(List.of(A_WORLD), world,
                "and the world position is announced exactly as it always was");
    }

    // ---------------------------------------------------- 4. the world side --

    @Test
    @DisplayName("an ordinary world coordinate is answered exactly as before: nothing at all")
    void anOrdinaryWorldCoordinateIsUntouched() {
        CarrierProjection projection = twoAdjacentStructures(dust(15), dust(15));

        assertNull(projection.atStructure(DIMENSION, new BlockPos(10, 64, 10)),
                "a coordinate no structure's own storage holds is not in the structure frame at all");
        assertNull(projection.atStructureOwnedBy(DIMENSION, new BlockPos(10, 64, 10), STRUCTURE_A),
                "and it stays nothing when the guarded form is asked");
        assertNull(projection.at(DIMENSION, new BlockPos(10, 64, 10)),
                "and nothing is projected there either: the ordinary world block keeps its own answer");
    }

    // ------------------------------------------------- 5. the frame marker --

    @Test
    @DisplayName("the diagnostics name which frame answered, and count the cross-structure declines")
    void theFrameMarkerNamesTheRuleThatFired() {
        CarrierProjection projection = twoAdjacentStructures(dust(15), dust(0));
        assertEquals(CarrierProjection.ReadMode.WORLD_PROJECTION, projection.readMode(),
                "nothing has been read yet: the world projection is the frame in force");

        projection.noteStructureQuery(projection.atStructure(DIMENSION, B_LOCAL));
        assertEquals(CarrierProjection.ReadMode.STRUCTURE_FRAME, projection.readMode(),
                "the machine reading its own host is the structure frame");
        assertEquals(1, projection.structureQueries());
        assertEquals(0, projection.structureDeclines());

        projection.noteStructureFrameDeclined(projection.atStructure(DIMENSION, A_LOCAL));
        assertEquals(CarrierProjection.ReadMode.CROSS_STRUCTURE_DECLINED, projection.readMode(),
                "and a coordinate that belongs to another machine says so");
        assertEquals(1, projection.structureQueries(),
                "a declined read is not a structure-frame read and does not inflate that count");
        assertEquals(1, projection.structureDeclines());
        assertEquals(STRUCTURE_A, projection.lastDeclinedCarrier(),
                "the line can name the machine the coordinate really belonged to");

        String line = projection.describe(DIMENSION);
        assertTrue(line.contains("via=structure-frame") && line.contains("via=world-projection"),
                "the summary carries both markers so a reader can tell the two apart: " + line);
        assertTrue(line.contains("structureDeclines=1"), line);
    }

    // ----------------------------------------------------------- 6. wiring --

    @Test
    @DisplayName("production's signal path goes through the guarded lookup, in both halves")
    void theProductionPathIsTheGuardedOne() throws Exception {
        String store = flat("src/main/java/com/jiangyuefengyu/redstonecircuit/carrier/CarrierStore.java");
        assertTrue(store.contains("atStructureOwnedBy(level.dimension(),"),
                "the signal read must ask the guarded lookup, not the raw index");
        assertTrue(store.contains("carrierSource.plotOwnerAt(level, structurePos)"),
                "and the owner it is guarded by is the plot-ownership question");
        assertTrue(store.contains("noteStructureFrameDeclined("),
                "a refused cross-structure read is counted, so the next log can name the frame");
        assertTrue(store.contains("atStructureOwnedBy(level.dimension(), structurePos,"),
                "and the notification half is guarded by the same question, so one coordinate is not "
                        + "woken twice in one tick");

        String mixin = flat(
                "src/main/java/com/jiangyuefengyu/redstonecircuit/mixin/BlockStateSignalMixin.java");
        assertTrue(mixin.contains("projected = CarrierStore.projectedHostAt(serverLevel, pos)"),
                "the world projection source is still asked first - the priority is not what changed");
        assertTrue(mixin.contains("if (projected == null) {")
                        && mixin.contains("CarrierStore.structureHostAt(serverLevel, pos)"),
                "and the structure frame is still only reached when it has nothing to say");
    }

    /** One file's text, whitespace-flattened, so a wiring assertion is not a formatting assertion. */
    private static String flat(String relative) throws Exception {
        return java.nio.file.Files.readString(java.nio.file.Path.of(relative))
                .replace("\r\n", "\n").replaceAll("\\s+", " ");
    }
}
