package com.jiangyuefengyu.redstonecircuit.carrier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.Slot;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.joml.Vector3d;

/**
 * <b>The three-state verdict on a miss, and the bounded local-coordinate scan behind it.</b>
 *
 * <p>一次 miss 必须能区分"这个坐标没有记录"、"有记录但已经落下"、"根本没走到索引"三种情况，
 * 所以裁定文本以 {@code RECORD_ABSENT} / {@code RECORD_NOT_LIVE} / {@code RECORD_AMBIGUOUS} 开头，
 * 扫描按 {@code MAX_CARRIERS_SCANNED} 封顶。
 */
class CarrierMissVerdictTest {

    /** The carrier the click landed on, and its local coordinate. */
    private static final CarrierId THE_REPEATER =
            CarrierId.subLevel(UUID.fromString("00000000-0000-0000-0000-00000000b22c"));
    private static final BlockPos REPEATER_LOCAL = new BlockPos(20481032, 127, 20499464);

    /** A second carrier, claimed at another local coordinate. */
    private static final CarrierId THE_DUST =
            CarrierId.subLevel(UUID.fromString("00000000-0000-0000-0000-00000000088a"));
    private static final BlockPos DUST_LOCAL = new BlockPos(20481032, 128, 20501512);

    /** An ordinary world coordinate nothing was ever claimed at. */
    private static final BlockPos NOWHERE = new BlockPos(-79, -60, 16);

    /** The dimension the projection table is opened for; the resolver has to be able to ask one. */
    private static final ResourceKey<Level> DIMENSION = Level.OVERWORLD;

    private final CarriedHostIndex index = new CarriedHostIndex();
    private final Map<Long, Slot> world = new HashMap<>();

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

    private void carry(CarrierId carrier, BlockPos local, BlockPos origin, ComponentType type) {
        world.put(origin.asLong(), new Slot(type));
        index.claim(carrier, local, origin, new MapWorld(world));
    }

    // ------------------------------------------------------------------- the scan --

    @Test
    @DisplayName("six carriers resolve by coordinate alone, with no pose and no level")
    void theAuthorsWorldIsFoundByCoordinateAlone() {
        // Six claims, each one host; the two that were clicked are among them, unchanged.
        carry(CarrierId.subLevel(UUID.randomUUID()), new BlockPos(20481032, 127, 20485128),
                new BlockPos(-61, -60, 5), ComponentType.SUPERCONDUCTOR);
        carry(CarrierId.subLevel(UUID.randomUUID()), new BlockPos(20481032, 127, 20487176),
                new BlockPos(-56, -60, 2), ComponentType.SUPERCONDUCTOR);
        carry(CarrierId.subLevel(UUID.randomUUID()), new BlockPos(20481032, 128, 20497416),
                new BlockPos(-90, -60, 27), ComponentType.REPEATER);
        carry(CarrierId.subLevel(UUID.randomUUID()), new BlockPos(20481032, 128, 20489224),
                new BlockPos(-82, -60, 26), ComponentType.REPEATER);
        carry(THE_REPEATER, REPEATER_LOCAL, new BlockPos(-30, -60, -40),
                ComponentType.RETAINING_REPEATER);
        carry(THE_DUST, DUST_LOCAL, new BlockPos(-31, -60, -40), ComponentType.DUST);

        CarrierStore.LocalScan repeater = CarrierStore.scanLocalCoordinate(index, REPEATER_LOCAL);
        assertEquals(1, repeater.records().size(), "exactly one record is keyed by the clicked coordinate");
        assertEquals(THE_REPEATER, repeater.records().get(0).carrier(),
                "and it is the carrier claimed at that coordinate");
        assertEquals(6, repeater.scanned(), "all six carriers were examined");
        assertFalse(repeater.capped());
        assertFalse(repeater.records().get(0).host().landed(), "the record is riding");

        CarrierStore.LocalScan dust = CarrierStore.scanLocalCoordinate(index, DUST_LOCAL);
        assertEquals(1, dust.records().size());
        assertEquals(THE_DUST, dust.records().get(0).carrier());

        assertTrue(CarrierStore.scanLocalCoordinate(index, NOWHERE).isEmpty(),
                "a world coordinate that was never a local coordinate resolves to nothing");
        assertTrue(CarrierStore.scanLocalCoordinate(null, REPEATER_LOCAL).isEmpty());
        assertTrue(CarrierStore.scanLocalCoordinate(index, null).isEmpty());
    }

    @Test
    @DisplayName("the scan is capped, and says so rather than walking an unbounded list")
    void theScanIsCapped() {
        int cap = CarrierStore.MAX_CARRIERS_SCANNED;
        for (int i = 0; i <= cap; i++) {
            carry(CarrierId.subLevel(UUID.randomUUID()), new BlockPos(i, 0, 0), new BlockPos(i, 0, 0),
                    ComponentType.DUST);
        }
        CarrierStore.LocalScan scan = CarrierStore.scanLocalCoordinate(index, new BlockPos(0, 0, 0));
        assertTrue(scan.scanned() <= cap, "the loop's ceiling holds: " + scan.scanned());
        assertEquals(cap, scan.scanned(), "and it used the whole budget");
        assertTrue(scan.capped(), "the caller is told it stopped looking");
    }

    @Test
    @DisplayName("two carriers at one coordinate are both returned, so a caller can refuse to guess")
    void twoCarriersAtOneCoordinateAreBothReturned() {
        BlockPos shared = new BlockPos(1, 0, 0);
        carry(THE_REPEATER, shared, new BlockPos(10, 0, 0), ComponentType.REPEATER);
        carry(THE_DUST, shared, new BlockPos(20, 0, 0), ComponentType.DUST);

        CarrierStore.LocalScan scan = CarrierStore.scanLocalCoordinate(index, shared);
        assertEquals(2, scan.records().size(), "the scan is a question, not a guess");

        String verdict = CarrierEntryTrace.localVerdict(scan, shared);
        assertTrue(verdict.startsWith(CarrierEntryTrace.RECORD_AMBIGUOUS), verdict);
        assertTrue(verdict.contains(THE_REPEATER.shortId()) && verdict.contains(THE_DUST.shortId()), verdict);
    }

    // ------------------------------------------------------------------ the verdict --

    @Test
    @DisplayName("record absent: nothing is keyed by this coordinate")
    void recordAbsentReadsAsItsOwnShape() {
        carry(THE_REPEATER, REPEATER_LOCAL, new BlockPos(-30, -60, -40), ComponentType.RETAINING_REPEATER);

        String verdict = CarrierEntryTrace.localVerdict(
                CarrierStore.scanLocalCoordinate(index, NOWHERE), NOWHERE);
        assertTrue(verdict.startsWith(CarrierEntryTrace.RECORD_ABSENT), verdict);
        assertTrue(verdict.contains("-79, -60, 16"), verdict);
        assertTrue(verdict.contains("scanned 1 carrier(s)"), verdict);
        assertFalse(verdict.contains(CarrierEntryTrace.RECORD_NOT_LIVE), verdict);
    }

    @Test
    @DisplayName("record present but NOT live: the record exists and has been put down")
    void aLandedRecordReadsAsItsOwnShape() {
        carry(THE_REPEATER, REPEATER_LOCAL, new BlockPos(-30, -60, -40), ComponentType.RETAINING_REPEATER);
        index.release(THE_REPEATER, REPEATER_LOCAL, landing -> new BlockPos(-30, -60, -40),
                new MapWorld(world));
        assertTrue(index.at(THE_REPEATER, REPEATER_LOCAL).landed(), "the setup is what this test is about");

        String verdict = CarrierEntryTrace.localVerdict(
                CarrierStore.scanLocalCoordinate(index, REPEATER_LOCAL), REPEATER_LOCAL);
        assertTrue(verdict.startsWith(CarrierEntryTrace.RECORD_NOT_LIVE), verdict);
        assertTrue(verdict.contains("landed=true"), verdict);
        assertTrue(verdict.contains(THE_REPEATER.shortId()), verdict);
        assertTrue(verdict.contains("slot=RETAINING_REPEATER"), verdict);
        assertFalse(verdict.contains("a LIVE record IS keyed by"), verdict);
    }

    @Test
    @DisplayName("a LIVE record at the clicked coordinate is named as exactly that")
    void aLiveRecordSaysSoInSoManyWords() {
        carry(THE_REPEATER, REPEATER_LOCAL, new BlockPos(-30, -60, -40), ComponentType.RETAINING_REPEATER);
        carry(THE_DUST, DUST_LOCAL, new BlockPos(-31, -60, -40), ComponentType.DUST);

        String verdict = CarrierEntryTrace.localVerdict(
                CarrierStore.scanLocalCoordinate(index, REPEATER_LOCAL), REPEATER_LOCAL);
        assertTrue(verdict.contains("a LIVE record IS keyed by"), verdict);
        assertTrue(verdict.contains(THE_REPEATER.shortId()), verdict);
        assertTrue(verdict.contains("(sub-level)"), "the carrier's kind is named: " + verdict);
        assertTrue(verdict.contains("landed=false"), verdict);
        assertTrue(verdict.contains("slot=RETAINING_REPEATER"), verdict);
        assertTrue(verdict.contains("looking the record up by something else"),
                "the verdict says what a miss here means: " + verdict);
    }

    @Test
    @DisplayName("a capped scan says it stopped looking, so 'absent' cannot be read as 'not there'")
    void aCappedScanSaysSo() {
        int cap = CarrierStore.MAX_CARRIERS_SCANNED;
        for (int i = 0; i <= cap; i++) {
            carry(CarrierId.subLevel(UUID.randomUUID()), new BlockPos(i, 0, 0), new BlockPos(i, 0, 0),
                    ComponentType.DUST);
        }
        String verdict = CarrierEntryTrace.localVerdict(
                CarrierStore.scanLocalCoordinate(index, new BlockPos(99999, 0, 0)), new BlockPos(99999, 0, 0));
        assertTrue(verdict.contains("capped at " + cap), verdict);
    }

    @Test
    @DisplayName("a missing scan is not silently read as 'absent with no explanation'")
    void aNullScanIsStillAReadableVerdict() {
        String verdict = CarrierEntryTrace.localVerdict(null, REPEATER_LOCAL);
        assertTrue(verdict.startsWith(CarrierEntryTrace.RECORD_ABSENT), verdict);
        assertTrue(verdict.contains("scanned 0 carrier(s)"), verdict);
    }

    // -------------------------------------------------------- the explanation itself --

    @Test
    @DisplayName("the explanation is bounded, and its steps are in the order the resolver took them")
    void theExplanationIsBounded() {
        CarrierEntryTrace.Miss miss = new CarrierEntryTrace.Miss();
        for (int i = 0; i < CarrierEntryTrace.Miss.MAX_STEPS * 3; i++) {
            miss.step("step-" + i);
        }
        miss.verdict("a verdict");
        assertEquals(CarrierEntryTrace.Miss.MAX_STEPS, miss.size(), "the ceiling holds");

        String text = miss.describe();
        assertTrue(text.startsWith("verdict=a verdict steps=["), text);
        assertTrue(text.contains("step-0"), "the first steps are the ones kept: " + text);
        assertTrue(text.contains("step-" + (CarrierEntryTrace.Miss.MAX_STEPS - 1)), text);
        assertFalse(text.contains("step-" + CarrierEntryTrace.Miss.MAX_STEPS),
                "and the ones past the ceiling are dropped: " + text);
        assertEquals(1, text.split("\n").length, "one line, always");
    }

    @Test
    @DisplayName("the resolver's own explanation records the stages, and the verdict is last")
    void theResolverExplanationNamesItsStages() {
        carry(THE_REPEATER, REPEATER_LOCAL, new BlockPos(-30, -60, -40), ComponentType.RETAINING_REPEATER);
        CarrierEntryTrace.Miss miss = new CarrierEntryTrace.Miss();

        // The carrier layer has to be armed to get past the resolver's first guard, and the projection
        // table has to name a dimension to be asked at all; both are restored afterwards.
        CarrierStore.CarrierSource previous = CarrierStore.source();
        try {
            CarrierStore.install(new BareSource());
            CarrierStore.projection().clearAll();
            CarrierStore.projection().begin(DIMENSION, true);
            // One projected host somewhere else, so the table names its dimension (which is what
            // `onlyDimension` is for) and still answers nothing at the coordinate under test.
            CarrierStore.projection().put(DIMENSION, new CarrierProjection.ProjectedHost(THE_REPEATER,
                    REPEATER_LOCAL, new BlockPos(1, 2, 3), CarrierPose.at(new Vector3d(1.0, 2.0, 3.0)),
                    new Slot(ComponentType.RETAINING_REPEATER), 0));

            CarriedBlockRemoval.Hit hit = CarrierStore.hitAt(null, CarrierStore.projection(), index,
                    new BlockPos(3, 4, 5), miss, null);
            assertNull(hit, "the setup is a coordinate nothing projects");

            String text = miss.describe();
            assertTrue(text.contains("projection table lookup at 3, 4, 5 -> no entry"), text);
            assertTrue(text.contains("the table is keyed by WORLD position"), text);
            assertTrue(text.contains("no level was given"), text);
            assertTrue(text.contains("recordsAt(3, 4, 5)"), text);
            assertTrue(text.startsWith("verdict=" + CarrierEntryTrace.RECORD_ABSENT),
                    "the three-state verdict is first, so one grep finds it: " + text);
        } finally {
            CarrierStore.projection().clearAll();
            CarrierStore.install(previous);
        }
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
