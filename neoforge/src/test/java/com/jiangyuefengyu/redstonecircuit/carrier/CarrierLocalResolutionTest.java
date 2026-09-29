package com.jiangyuefengyu.redstonecircuit.carrier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.Slot;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>The click that arrived in the structure's own frame, and the resolver that could not read it.</b>
 *
 * <h2>The author's log, and what it settles</h2>
 * P1.22's acceptance run produced <b>101 trace lines and 101 of them were MISS</b>, and the coordinates
 * say the whole thing:
 *
 * <pre>
 *   carrier tool trace: hook RIGHT_CLICK_BLOCK called by Meng_LuoHua raw=20481032, 127, 20499464
 *       face=up tool=wrench -> MISS (MISS: no live carried host answers for this position)        x 72
 *   ... raw=20481032, 128, 20501512 ... -> MISS                                                   x 28
 *   carrier claim s:b22c7587 world=-30, -60, -40 local=20481032, 127, 20499464 type=RETAINING_REPEATER
 *   carrier claim s:088a5ad7 world=-31, -60, -40 local=20481032, 128, 20501512 type=DUST
 * </pre>
 *
 * <p>The click's raw coordinate <b>is</b> the record's local coordinate, character for character, and
 * the claim is eighteen seconds older than the click. So the record was there, live, under exactly that
 * key - and the resolver answered nothing, because it looked the record up by inverting the pose (a
 * question about a <em>world</em> position) rather than by asking the index for its own key.
 *
 * <h2>What is asserted here</h2>
 * The six carriers of that world, at the coordinates that log gives, one host each; the two the author
 * clicked are among them. Then, with <b>no level and no projection entry</b> - only the index and the
 * poses - the coordinate resolves to the right record, under the right carrier, with the world position
 * the author's own claim names. And the things that must keep NOT resolving: a landed record, a
 * contraption whose local coordinate equals an ordinary world block, two carriers at one coordinate, a
 * carrier with no pose, and a pose that cannot round-trip the block.
 *
 * <p>The "before" half is asserted too: the same resolver with the local-coordinate branch switched off
 * (which is exactly its pre-P1.24 shape) answers <b>null</b> for these inputs - the author's 101 misses,
 * reproduced offline.
 */
class CarrierLocalResolutionTest {

    /**
     * From the log: the carrier the author clicked seventy-two times. The UUID's first eight hex digits
     * are the ones the log's {@code s:b22c7587} short id prints.
     */
    private static final CarrierId THE_REPEATER =
            CarrierId.subLevel(UUID.fromString("b22c7587-0000-0000-0000-000000000000"));
    private static final BlockPos REPEATER_LOCAL = new BlockPos(20481032, 127, 20499464);
    private static final BlockPos REPEATER_WORLD = new BlockPos(-30, -60, -40);

    /** And the one it clicked twenty-eight times, {@code s:088a5ad7} in the log. */
    private static final CarrierId THE_DUST =
            CarrierId.subLevel(UUID.fromString("088a5ad7-0000-0000-0000-000000000000"));
    private static final BlockPos DUST_LOCAL = new BlockPos(20481032, 128, 20501512);
    private static final BlockPos DUST_WORLD = new BlockPos(-31, -60, -40);

    /** An ordinary world coordinate: a contraption's local space and the level's must not be confused. */
    private static final BlockPos A_WORLD_BLOCK = new BlockPos(1, 0, 0);

    private final CarriedHostIndex index = new CarriedHostIndex();
    private final Map<Long, Slot> world = new HashMap<>();
    private final Map<UUID, CarrierPose> poses = new HashMap<>();

    private CarrierStore.CarrierSource previousSource;

    /**
     * Arms the carrier layer and opens the projection table for one dimension.
     *
     * <p>The layer has to be armed for the resolver to get past its first guard, and the projection table
     * has to be able to name a dimension because the level-free form has no level to ask - the same two
     * pieces of global state {@code CarriedBlockRemovalTest} sets up, and both restored afterwards.
     */
    @BeforeEach
    void arm() {
        previousSource = CarrierStore.source();
        CarrierStore.install(new BareSource());
        CarrierStore.projection().clearAll();
        CarrierStore.projection().begin(Level.OVERWORLD, true);
        // One entry somewhere else, so the table can name its dimension (`onlyDimension`) and still
        // answers nothing at the coordinates under test. That is the honest picture: the projection table
        // is keyed by WORLD positions, and these clicks never carried one.
        CarrierStore.projection().put(Level.OVERWORLD, new CarrierProjection.ProjectedHost(THE_DUST,
                new BlockPos(9, 9, 9), new BlockPos(9, 9, 9), CarrierPose.at(new Vector3d(9.0, 9.0, 9.0)),
                new Slot(ComponentType.DUST), 0));
    }

    @AfterEach
    void disarm() {
        CarrierStore.projection().clearAll();
        CarrierStore.install(previousSource);
    }

    private record MapWorld(Map<Long, Slot> slots) implements CarriedHostIndex.SlotWorld {

        @Override
        public Slot get(BlockPos position) {
            return slots.get(position.asLong());
        }

        @Override
        public void set(BlockPos position, Slot slot) {
            if (slot == null) {
                slots.remove(position.asLong());
            } else {
                slots.put(position.asLong(), slot);
            }
        }
    }

    /** A pose that carries {@code local} to {@code worldPos}: the author's six claims are translations. */
    private static CarrierPose moving(BlockPos local, BlockPos worldPos) {
        return CarrierPose.of(new Vector3d(worldPos.getX() - local.getX(),
                worldPos.getY() - local.getY(), worldPos.getZ() - local.getZ()), new Quaterniond());
    }

    private void carry(CarrierId carrier, BlockPos local, BlockPos origin, BlockPos worldPos,
                       ComponentType type) {
        world.put(origin.asLong(), new Slot(type));
        index.claim(carrier, local, origin, new MapWorld(world));
        poses.put(carrier.uuid(), moving(local, worldPos));
    }

    /** The author's world: six carriers, one host each, at the coordinates the log gives. */
    private void theAuthorsWorld() {
        carry(CarrierId.subLevel(UUID.randomUUID()), new BlockPos(20481032, 127, 20485128),
                new BlockPos(-61, -60, 5), new BlockPos(-61, -60, 5), ComponentType.SUPERCONDUCTOR);
        carry(CarrierId.subLevel(UUID.randomUUID()), new BlockPos(20481032, 127, 20487176),
                new BlockPos(-56, -60, 2), new BlockPos(-56, -60, 2), ComponentType.SUPERCONDUCTOR);
        carry(CarrierId.subLevel(UUID.randomUUID()), new BlockPos(20481032, 128, 20497416),
                new BlockPos(-90, -60, 27), new BlockPos(-90, -60, 27), ComponentType.REPEATER);
        carry(CarrierId.subLevel(UUID.randomUUID()), new BlockPos(20481032, 128, 20489224),
                new BlockPos(-82, -60, 26), new BlockPos(-82, -60, 26), ComponentType.REPEATER);
        carry(THE_REPEATER, REPEATER_LOCAL, REPEATER_WORLD, REPEATER_WORLD,
                ComponentType.RETAINING_REPEATER);
        carry(THE_DUST, DUST_LOCAL, DUST_WORLD, DUST_WORLD, ComponentType.DUST);
    }

    /** The pose source production builds out of the level; here it is the map the claims filled. */
    private CarrierStore.PoseSource poseSource() {
        return carrier -> poses.get(carrier.uuid());
    }

    // -------------------------------------------------------------- the defect --

    @Test
    @DisplayName("the author's clicked coordinate resolves by coordinate alone - and did not before")
    void theClickedCoordinateResolvesWithNoPoseRoundTripAndNoProjectionEntry() {
        theAuthorsWorld();
        CarrierStore.PoseSource poses = poseSource();

        // BEFORE: the resolver exactly as P1.22 left it - the projection table plus the pose round trip,
        // and nothing that asks the index by its own key. This is the author's log, reproduced offline:
        // the record is live, the coordinate matches it character for character, and the answer is still
        // "no hit".
        assertNull(CarrierStore.hitAt(null, CarrierStore.projection(), index, REPEATER_LOCAL, null, null),
                "the pre-P1.24 resolver answers nothing at this coordinate: 101 misses, one shape");
        assertNull(CarrierStore.hitAt(null, CarrierStore.projection(), index, DUST_LOCAL, null, null));

        // AFTER: the index is asked with the coordinate the click carried.
        CarriedBlockRemoval.Hit hit = CarrierStore.hitAt(null, CarrierStore.projection(), index,
                REPEATER_LOCAL, null, poses);
        assertNotNull(hit, "the record is keyed by exactly this coordinate and it is live");
        assertEquals("s:b22c7587", hit.carrier().shortId(), "the carrier the author's own claim names");
        assertEquals(THE_REPEATER, hit.carrier());
        assertEquals(REPEATER_LOCAL, hit.localPos(),
                "and the local coordinate IS the clicked coordinate - nothing was inverted");
        assertEquals(REPEATER_WORLD, hit.worldPos(),
                "the pose is used for exactly one thing: naming where that host is in the world");
        assertTrue(hit.projected(),
                "positive identification, which is what lets the break path skip the view at "
                        + "BreakEvent time");

        CarriedBlockRemoval.Hit dust = CarrierStore.hitAt(null, CarrierStore.projection(), index,
                DUST_LOCAL, null, poses);
        assertNotNull(dust);
        assertEquals(THE_DUST, dust.carrier());
        assertEquals(DUST_WORLD, dust.worldPos());
    }

    @Test
    @DisplayName("a world coordinate is still nobody's: the branch does not claim ordinary blocks")
    void anOrdinaryWorldCoordinateStillResolvesToNothing() {
        theAuthorsWorld();
        assertNull(CarrierStore.hitAt(null, CarrierStore.projection(), index, new BlockPos(-79, -60, 16),
                        null, poseSource()),
                "the author clicked one plain world block too, and that MISS was correct");
    }

    // --------------------------------------------------------- what must not change --

    @Test
    @DisplayName("a landed record is not a hit through this branch either")
    void aLandedRecordIsStillNotAHit() {
        theAuthorsWorld();
        index.release(THE_REPEATER, REPEATER_LOCAL, landing -> REPEATER_WORLD, new MapWorld(world));
        assertTrue(index.at(THE_REPEATER, REPEATER_LOCAL).landed(), "the setup is the point");

        assertNull(CarrierStore.hitAt(null, CarrierStore.projection(), index, REPEATER_LOCAL, null,
                        poseSource()),
                "a put-down host is a world host: the carrier layer must not act on it");
        // ...and the other record at the other coordinate is unaffected by its neighbour's release.
        assertNotNull(CarrierStore.hitAt(null, CarrierStore.projection(), index, DUST_LOCAL, null,
                poseSource()));
    }

    @Test
    @DisplayName("a Create contraption's local coordinate is never matched to a level coordinate")
    void aContraptionIsNeverMatchedByALevelCoordinate() {
        // A contraption host whose LOCAL coordinate is (1, 0, 0) - a perfectly ordinary world block's
        // coordinate. A contraption's blocks are not level blocks, so a level position can never be one
        // of its local coordinates; if this branch did not refuse it, breaking a world block at (1,0,0)
        // would consume the record and drop the component of a machine somewhere else entirely.
        carry(CarrierId.contraption(UUID.randomUUID()), A_WORLD_BLOCK, A_WORLD_BLOCK,
                new BlockPos(2775, 77, -1395), ComponentType.RETAINING_REPEATER);
        assertNull(CarrierStore.hitAt(null, CarrierStore.projection(), index, A_WORLD_BLOCK, null,
                        poseSource()),
                "a contraption's coordinates are its own space, not the level's");

        // The same coordinate under a SUB-LEVEL is a real level block, and is matched.
        carry(CarrierId.subLevel(UUID.randomUUID()), REPEATER_LOCAL, REPEATER_WORLD, REPEATER_WORLD,
                ComponentType.DUST);
        assertNotNull(CarrierStore.hitAt(null, CarrierStore.projection(), index, REPEATER_LOCAL, null,
                poseSource()));
    }

    @Test
    @DisplayName("two live sub-levels at one coordinate are refused rather than guessed at")
    void twoCarriersAtOneCoordinateAreRefused() {
        carry(CarrierId.subLevel(UUID.randomUUID()), REPEATER_LOCAL, REPEATER_WORLD, REPEATER_WORLD,
                ComponentType.REPEATER);
        carry(CarrierId.subLevel(UUID.randomUUID()), REPEATER_LOCAL, REPEATER_WORLD, REPEATER_WORLD,
                ComponentType.DUST);

        CarrierEntryTrace.Miss miss = new CarrierEntryTrace.Miss();
        assertNull(CarrierStore.hitAt(null, CarrierStore.projection(), index, REPEATER_LOCAL, miss,
                        poseSource()),
                "two answers is not an answer: the resolver refuses and says why");
        assertTrue(miss.describe().contains("refused, 2 live sub-level carriers"), miss.describe());
    }

    @Test
    @DisplayName("no pose means no world position, so the record is left alone")
    void noPoseMeansNoHit() {
        carry(THE_REPEATER, REPEATER_LOCAL, REPEATER_WORLD, REPEATER_WORLD,
                ComponentType.RETAINING_REPEATER);

        CarrierEntryTrace.Miss miss = new CarrierEntryTrace.Miss();
        assertNull(CarrierStore.hitAt(null, CarrierStore.projection(), index, REPEATER_LOCAL, miss,
                        carrier -> null),
                "every caller acts at a world position - a highlight, a broadcast, a drop - so a hit "
                        + "without one would be worse than no hit");
        assertTrue(miss.describe().contains("no readable pose"), miss.describe());
    }

    @Test
    @DisplayName("a degenerate pose that cannot round-trip the block is refused, not guessed at")
    void aDegeneratePoseIsRefused() {
        carry(THE_REPEATER, REPEATER_LOCAL, REPEATER_WORLD, REPEATER_WORLD,
                ComponentType.RETAINING_REPEATER);
        CarrierPose degenerate = new CarrierPose(new Vector3d(-20481062.0, -187.0, -20499504.0),
                new Quaterniond(), new Vector3d(0.5, 0.5, 0.5), 0.0);

        CarrierEntryTrace.Miss miss = new CarrierEntryTrace.Miss();
        assertNull(CarrierStore.hitAt(null, CarrierStore.projection(), index, REPEATER_LOCAL, miss,
                        carrier -> degenerate),
                "a pose that cannot round-trip the block names no position");
        assertTrue(miss.describe().contains("cannot round-trip"), miss.describe());
    }

    @Test
    @DisplayName("the scan reads one dimension's index, so another dimension cannot answer")
    void anotherDimensionAnswersNothing() {
        theAuthorsWorld();
        // The scan reads ONE level's index (indexOf(level) in production), so another dimension is
        // another index - and an empty one answers nothing.
        assertNull(CarrierStore.hitAt(null, CarrierStore.projection(), new CarriedHostIndex(),
                REPEATER_LOCAL, null, poseSource()));
        // ...and with the branch switched off there is still no answer, which is the pre-fix shape.
        assertNull(CarrierStore.hitAt(null, CarrierStore.projection(), index, REPEATER_LOCAL, null, null));
    }

    @Test
    @DisplayName("the trace explains the fix, in the resolver's own words")
    void theExplanationNamesTheLocalCoordinateMatch() {
        theAuthorsWorld();
        CarrierEntryTrace.Miss miss = new CarrierEntryTrace.Miss();
        CarriedBlockRemoval.Hit hit = CarrierStore.hitAt(null, CarrierStore.projection(), index,
                REPEATER_LOCAL, miss, poseSource());

        assertNotNull(hit);
        String text = miss.describe();
        assertTrue(text.contains("local-coordinate match at 20481032, 127, 20499464"), text);
        assertTrue(text.contains("s:b22c7587"), text);
        assertTrue(text.contains("world=-30, -60, -40"), text);
        assertTrue(text.contains("the coordinate IS the record's own key"), text);
    }

    /** A source that can answer nothing: the shape a build without the compat half has. */
    private static final class BareSource implements CarrierStore.CarrierSource {

        @Override
        public CarrierPose poseOf(net.minecraft.server.level.ServerLevel level, CarrierId carrier) {
            return null;
        }

        @Override
        public CarrierId carrierAt(net.minecraft.server.level.ServerLevel level, BlockPos worldPos) {
            return null;
        }
    }
}
