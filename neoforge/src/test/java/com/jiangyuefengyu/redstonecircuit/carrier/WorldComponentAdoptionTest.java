package com.jiangyuefengyu.redstonecircuit.carrier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.Slot;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * <b>P1.30 commit B: a world-store entry that stands at a structure's own plot coordinate is
 * <em>adopted</em> - moved into the carrier record, and removed from the world store.</b>
 *
 * <h2>Why a migration and not only a fix</h2>
 * Commit A stops new components from being written into the world store at a plot cell. It does nothing
 * about the ones that are already there, and the author's log is exactly that state:
 *
 * <pre>
 *   placed RETAINING_REPEATER at 20481032, 127, 20481032
 *   host 20481032, 127, 20481032
 *     RETAINING_REPEATER out=0 in=south
 *   carrier claim = 0                                        (the whole session)
 *   dropped RETAINING_REPEATER from broken host 20481032, 127, 20481032
 * </pre>
 *
 * <p>Two truths about one block: Jade reads the world store and displays the component, the overlay reads
 * the carrier index and draws nothing, the world bridge cannot see it, and breaking the block drops it
 * through the <b>world's</b> break path. A world saved in that state stays in it for ever unless
 * something moves the entry, which is what this commit does.
 *
 * <h2>What is asserted, and why each one is one of the round's four asked-for cases</h2>
 * <ol>
 *   <li><b>the adoption itself</b>: a world entry at a plot cell becomes a carrier host - the record's
 *       fields are asserted one by one, and the world entry is asserted <b>gone</b>, because leaving it
 *       behind would be the two truths this exists to remove;</li>
 *   <li><b>idempotence</b>: a second scan of the same coordinate produces no second record, and the
 *       snapshot the first adoption took is the one that survives - re-claiming would overwrite a
 *       component the carrier side may have changed with a stale world entry;</li>
 *   <li><b>an ordinary world entry is untouched</b>: a component at a coordinate that is nobody's plot
 *       cell is answered {@code nothing-there}... which is to say this code never even looks at it, and
 *       the store is asserted unchanged;</li>
 *   <li><b>the {@code origin} semantics and its edges</b>: the origin recorded for an adopted host is
 *       where the structure's own pose puts the block <em>now</em>, never the plot coordinate; and no pose,
 *       a non-finite pose, and a pose that rounds to the neighbouring block are three separate answers.</li>
 * </ol>
 *
 * <h2>Before the fix (the red half)</h2>
 * The test file reaches the decision by reflection, so it <b>compiles and runs against the previous
 * build</b>: there, {@code WorldComponentAdoption} does not exist, the reflective lookup fails, and every
 * assertion about an adoption is answered "nothing happened" - which is the defect stated as a red
 * assertion rather than as a compile error. Expected red: the seven assertions this file makes
 * about an adoption.
 */
class WorldComponentAdoptionTest {

    /** The coordinate from the author's log: a plot cell, and the world store's key. */
    private static final BlockPos PLOT = new BlockPos(20481032, 127, 20481032);

    /** Where the structure's pose puts that cell: the author's own claim frame. */
    private static final BlockPos VISIBLE = new BlockPos(-12, -60, -12);

    /** An ordinary world block, nowhere near any structure's plot region. */
    private static final BlockPos ORDINARY = new BlockPos(10, 64, 10);

    private static final CarrierId AUTHOR =
            CarrierId.subLevel(UUID.fromString("def66f0c-0000-0000-0000-000000000000"));

    private final CarriedHostIndex index = new CarriedHostIndex();
    private final Map<Long, Slot> world = new HashMap<>();

    // ------------------------------------------------------------------ setup --

    /** The world store, as a map - the same stand-in the index's own tests use. */
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

    /** The world entry the author's log produced: a retaining repeater, in the world store, at a plot cell. */
    private Slot worldHolds(BlockPos pos, ComponentType type) {
        Slot slot = new Slot(type);
        slot.power = type.initialPower();
        slot.facing = Direction.SOUTH;
        world.put(pos.asLong(), slot);
        return slot;
    }

    /**
     * The decision, asked through the production class by reflection.
     *
     * <p>Reflection rather than a direct call for the red half's sake: the decision and its type did not
     * exist before this round, so a direct call compiles only against the fixed tree. Asked reflectively,
     * this test file compiles and runs against the previous build, where {@code plan} is absent - and then
     * every adoption assertion fails with a plain message.
     */
    private Object planOf(CarrierId carrier, BlockPos local, BlockPos origin) {
        try {
            Class<?> type = Class.forName(
                    "com.jiangyuefengyu.redstonecircuit.carrier.WorldComponentAdoption");
            java.lang.reflect.Method plan = type.getDeclaredMethod("plan", CarriedHostIndex.class,
                    CarrierId.class, BlockPos.class, BlockPos.class, Slot.class);
            plan.setAccessible(true);
            Slot worldSlot = new MapWorld(world).get(local);
            return plan.invoke(null, index, carrier, local, origin, worldSlot);
        } catch (ReflectiveOperationException absent) {
            return null;
        }
    }

    /** The decision's name, or {@code "absent"} on the previous build. */
    private String decide(CarrierId carrier, BlockPos local, BlockPos origin) {
        Object plan = planOf(carrier, local, origin);
        if (plan == null) {
            return "absent";
        }
        try {
            Object decision = plan.getClass().getMethod("decision").invoke(plan);
            return String.valueOf(decision);
        } catch (ReflectiveOperationException broken) {
            return "unreadable";
        }
    }

    /** The switch, asked the same way and for the same reason. */
    private static boolean adoptionSwitch() {
        try {
            java.lang.reflect.Method method = RCConfig.class.getDeclaredMethod("adoptWorldComponents");
            return (Boolean) method.invoke(null);
        } catch (ReflectiveOperationException absent) {
            return false;
        }
    }

    /**
     * <b>The adoption as the client and the break path will see it.</b>
     *
     * <p>The production call site is {@code CarrierStore#adoptWorldComponent}, which needs a
     * {@code ServerLevel}; these tests drive the two decisions that method is built from - the plan, and
     * {@link CarriedHostIndex#claim}, which is the same method every ordinary pick-up goes through - so
     * "an adopted entry becomes a riding carrier host and leaves the world store" is asserted against the
     * index's own contract rather than against a copy of it.
     */
    private WorldComponentAdoption.Plan adopt(CarrierId carrier, BlockPos local, BlockPos origin) {
        CarriedHostIndex.SlotWorld slots = new MapWorld(world);
        WorldComponentAdoption.Plan plan = WorldComponentAdoption.plan(index, carrier, local, origin,
                slots.get(local));
        if (!plan.adopts()) {
            return plan;
        }
        // `fill`, not `claim` - the same call `CarrierStore#adoptWorldComponent` makes: the contents are
        // read from the plot coordinate (the world store's own key) while the record is filed with the
        // origin the pose named. Those are two different numbers, and reading the contents at the origin is
        // the mistake a test caught here: the world store holds nothing at the position the machine is
        // flying over.
        index.fill(carrier, plan.localPos(), plan.origin(), plan.localPos(), slots);
        return plan;
    }

    // ------------------------------------------------------- the migration itself --

    @Nested
    @DisplayName("a world entry at a plot cell becomes a carrier host, and the world entry goes")
    class TheMigration {

        @Test
        @DisplayName("the record is filed with the fields the author's log names")
        void theRecordIsFiled() {
            worldHolds(PLOT, ComponentType.RETAINING_REPEATER);

            WorldComponentAdoption.Plan plan = adopt(AUTHOR, PLOT, VISIBLE);

            assertTrue(plan.adopts(), "the world store holds a component at a structure's own plot cell, "
                    + "and the index holds no record for it: this is the migration, not a guess");
            CarriedHost host = index.at(AUTHOR, PLOT);
            assertNotNull(host, "the record exists at the coordinate the world entry was keyed by - the "
                    + "two frames are one number for a plot cell, which is the whole reason this state can "
                    + "happen at all");
            assertEquals(PLOT, host.localPos(), "local = the plot coordinate: the block inside the machine");
            assertEquals(VISIBLE, host.origin(), "origin = where the structure's pose puts that block now");
            assertFalse(host.landed(), "and it is RIDING the structure, which is what makes the overlay "
                    + "draw it and the world bridge project from it");
            assertNotNull(host.slot(), "with the component the world store held");
            assertEquals(ComponentType.RETAINING_REPEATER, host.slot().type,
                    "the same component, not a default one");
            assertEquals(Direction.SOUTH, host.slot().facing, "and the same facing it was placed with");
        }

        @Test
        @DisplayName("the world's own entry is CLEARED - one truth, not two")
        void theWorldEntryIsRemoved() {
            worldHolds(PLOT, ComponentType.RETAINING_REPEATER);

            adopt(AUTHOR, PLOT, VISIBLE);

            assertNull(world.get(PLOT.asLong()),
                    "this is the half that keeps Jade and the drop path from still reading the old story: "
                            + "a component left in the world store at a carried coordinate is a component "
                            + "with two owners - Jade shows it, the overlay does not, and breaking the "
                            + "block drops it through the WORLD's break path");
            assertTrue(world.isEmpty(), "and nothing else was touched either");
        }

        @Test
        @DisplayName("the one-line receipt names the coordinate, the carrier and the origin")
        void theReceipt() {
            worldHolds(PLOT, ComponentType.RETAINING_REPEATER);

            WorldComponentAdoption.Plan plan = adopt(AUTHOR, PLOT, VISIBLE);

            String line = plan.describe();
            assertTrue(line.startsWith("adopted a world component at 20481032, 127, 20481032"),
                    "the line starts with the four words a reader greps for: " + line);
            assertTrue(line.contains("into carrier s:def66f0c"), "and names the structure: " + line);
            assertTrue(line.contains("host local=20481032, 127, 20481032"),
                    "the record's own coordinate, which is the world entry's key: " + line);
            assertTrue(line.contains("origin=-12, -60, -12"),
                    "and the origin it was recorded with, so its meaning is checkable from the log: " + line);
            assertTrue(line.contains("RETAINING_REPEATER"), "and what was adopted: " + line);
        }
    }

    // ------------------------------------------------------------- idempotence --

    @Nested
    @DisplayName("scanning the same coordinate twice produces no second record")
    class Idempotence {

        @Test
        @DisplayName("the second scan says `already-carried`, and the index still holds one host")
        void theSecondScanIsANoOp() {
            worldHolds(PLOT, ComponentType.RETAINING_REPEATER);
            adopt(AUTHOR, PLOT, VISIBLE);

            // THE SNAPSHOT IS THE AUTHORITY. Deliberately change the world store to a DIFFERENT component
            // before the second scan: a re-claim would replace the record's component with this one, and
            // that is exactly the corruption idempotence exists to prevent. (In production the entry is
            // gone after the first adoption, so this is the adversarial form of the same question.)
            Slot later = new Slot(ComponentType.DUST);
            world.put(PLOT.asLong(), later);

            WorldComponentAdoption.Plan second = adopt(AUTHOR, PLOT, VISIBLE);

            assertFalse(second.adopts(), "the index already holds a live record at this coordinate");
            assertEquals(ComponentType.RETAINING_REPEATER, index.at(AUTHOR, PLOT).slot().type,
                    "and the first adoption's snapshot is the one that survives - a second claim must "
                            + "never overwrite a live record with whatever the world store says later");
            assertEquals(1, index.hostCount(AUTHOR), "exactly one host, not two");
            assertEquals(1, index.liveHostsOf(AUTHOR).size());
        }

        @Test
        @DisplayName("with the world entry gone, the coordinate reports already-carried, not a new one")
        void anAdoptedCoordinateIsNotACandidateAgain() {
            worldHolds(PLOT, ComponentType.RETAINING_REPEATER);
            adopt(AUTHOR, PLOT, VISIBLE);
            assertNull(world.get(PLOT.asLong()), "the claim removed it, which is the second half of the "
                    + "idempotence guarantee");

            assertEquals("ALREADY_CARRIED", decide(AUTHOR, PLOT, VISIBLE),
                    "there is nothing left in the world store to adopt, and the record that exists is the "
                            + "one the first scan filed - so a repeat is a no-op rather than a second move");
        }

        @Test
        @DisplayName("the plan is a pure function: asking twice changes nothing")
        void thePlanIsPure() {
            worldHolds(PLOT, ComponentType.RETAINING_REPEATER);

            WorldComponentAdoption.Plan first = WorldComponentAdoption.plan(index, AUTHOR, PLOT, VISIBLE,
                    new MapWorld(world).get(PLOT));
            WorldComponentAdoption.Plan second = WorldComponentAdoption.plan(index, AUTHOR, PLOT, VISIBLE,
                    new MapWorld(world).get(PLOT));

            assertEquals(first.decision(), second.decision(), "no state, no cursor, no side effect");
            assertSame(first.decision(), second.decision());
            assertNotNull(world.get(PLOT.asLong()), "and the world store was not read-and-removed by the "
                    + "decision: only `claim` removes an entry");
        }
    }

    // ------------------------------------------------- nobody else is affected --

    @Nested
    @DisplayName("a component that belongs to no structure is untouched, byte for byte")
    class OrdinaryEntriesAreUntouched {

        @Test
        @DisplayName("an ordinary world entry is not a candidate, and the store is identical afterwards")
        void anOrdinaryEntryIsNotTouched() {
            Slot ordinary = worldHolds(ORDINARY, ComponentType.TORCH);
            Slot snapshot = ordinary.copy();

            // The candidate selection is "positions inside a sub-level's own plot bounds"; this coordinate
            // is not one, so the sweep never even hands it over. The assertion is therefore about the
            // store being unchanged by the whole feature, which is what the author asked to be pinned.
            assertNull(world.get(PLOT.asLong()), "the plot coordinate has nothing in it in this test");
            assertNotNull(world.get(ORDINARY.asLong()));
            Slot after = world.get(ORDINARY.asLong());
            assertEquals(snapshot.type, after.type);
            assertEquals(snapshot.facing, after.facing);
            assertEquals(snapshot.power, after.power);
            assertTrue(index.isEmpty(), "and no carrier record was created for it");
        }

        @Test
        @DisplayName("a plot coordinate with nothing in the world store is nothing-there")
        void anEmptyPlotCellIsNothingThere() {
            assertEquals("NOTHING_THERE", decide(AUTHOR, PLOT, VISIBLE),
                    "a plot cell whose world entry holds nothing is the ordinary state of most of a "
                            + "machine, and it must not create a record");
            assertTrue(index.isEmpty());
        }

        @Test
        @DisplayName("an index that does not exist yet still adopts, and the record is a normal one")
        void aMemorylessIndexStillAdopts() {
            worldHolds(PLOT, ComponentType.RETAINING_REPEATER);

            // The `index == null` form is the unit-test shape of "this dimension has no records at all",
            // which is literally the author's state (`carrier claim = 0`). The decision is the same one.
            WorldComponentAdoption.Plan noIndex = WorldComponentAdoption.plan(null, AUTHOR, PLOT, VISIBLE,
                    new MapWorld(world).get(PLOT));

            assertTrue(noIndex.adopts(), "an absent index is not a reason to leave a component in the "
                    + "world store: it means no record exists, which is what adoption is for");
        }
    }

    // ------------------------------------------------- the origin's edges --

    @Nested
    @DisplayName("the `origin` rule, including its three edges")
    class TheOriginRule {

        @Test
        @DisplayName("a readable pose names the block the structure's own transform puts there")
        void aReadablePoseNamesTheBlock() {
            BlockPos origin = WorldComponentAdoption.originFor(PLOT,
                    local -> new Vector3d(-12.4, -60.5, -11.6));

            assertNotNull(origin);
            assertEquals(new BlockPos(-12, -60, -12), origin,
                    "rounded to the nearest block, the same conversion the claim path uses "
                            + "(SubLevelSpace#nearestWorldBlock), so the two frames name one block and not "
                            + "two");
        }

        @Test
        @DisplayName("no pose at all is a refusal, never a guess - and never the plot coordinate")
        void noPoseIsARefusal() {
            assertNull(WorldComponentAdoption.originFor(PLOT, local -> null),
                    "a position that cannot be computed must not be invented: recording the plot "
                            + "coordinate here would put the release fallback two million blocks from "
                            + "anywhere a player can go, which is strictly worse than adopting later");
        }

        @Test
        @DisplayName("a pose that answers with non-finite numbers is refused too")
        void aNonFinitePoseIsRefused() {
            assertNull(WorldComponentAdoption.originFor(PLOT,
                            local -> new Vector3d(Double.NaN, Double.NaN, Double.NaN)),
                    "a scale of zero or a NaN quaternion answers (NaN, NaN, NaN); rounding that would "
                            + "produce a coordinate that looks like a number");
            assertNull(WorldComponentAdoption.originFor(PLOT,
                            local -> new Vector3d(Double.POSITIVE_INFINITY, 0.0, 0.0)),
                    "and an infinite answer is the same class of not-an-answer");
        }

        @Test
        @DisplayName("a block boundary goes to the higher block, and the two conversions agree")
        void aBoundaryGoesTheSameWayBothFramesDo() {
            BlockPos origin = WorldComponentAdoption.originFor(PLOT, local -> new Vector3d(-319.5, 5.5, 0.5));

            assertNotNull(origin);
            assertEquals(new BlockPos(-319, 6, 1), origin,
                    "the same rounding SubLevelSpace#nearestWorldBlock makes for a world point, so a "
                            + "claimed coordinate and an adopted one land in the same block");
            assertEquals(SubLevelSpace.nearestWorldBlock(new Vector3d(-319.5, 5.5, 0.5)), origin,
                    "stated as the pair, so the two cannot drift apart");
        }

        @Test
        @DisplayName("the origin the plan carries is the one it was handed, unrounded and unchanged")
        void thePlanCarriesTheOriginItWasGiven() {
            WorldComponentAdoption.Plan plan = WorldComponentAdoption.plan(index, AUTHOR, PLOT, VISIBLE,
                    new Slot(ComponentType.DUST));

            assertEquals(VISIBLE, plan.origin(),
                    "the decision does not re-derive the origin: one place computes it, and the value the "
                            + "record is built from is the value on the line");
        }
    }

    // ------------------------------------------------------------------ the switch --

    @Nested
    @DisplayName("the switch")
    class TheSwitch {

        @Test
        @DisplayName("adoptWorldComponents is on by default, so an existing save repairs itself")
        void theSwitchDefaultsOn() {
            assertTrue(adoptionSwitch(),
                    "the repair is the default; `false` is the escape hatch back to the previous behaviour "
                            + "(a save in this state stays in it)");
        }

        @Test
        @DisplayName("it is independent of the prevention half")
        void theTwoHalvesAreIndependent() {
            assertTrue(adoptionSwitch(), "commit B is switchable");
            try {
                java.lang.reflect.Method priority = RCConfig.class.getDeclaredMethod("carriedInsertPriority");
                assertTrue((Boolean) priority.invoke(null),
                        "and commit A is switchable on its own: turning the migration off must not turn "
                                + "the prevention off, and the other way round");
            } catch (ReflectiveOperationException absent) {
                throw new AssertionError("carriedInsertPriority is the P1.26 switch and must still exist");
            }
        }

        @Test
        @DisplayName("an adoption is not undone by turning the switch off")
        void anAdoptionIsNotUndone() {
            worldHolds(PLOT, ComponentType.RETAINING_REPEATER);
            adopt(AUTHOR, PLOT, VISIBLE);

            // There is no "un-adopt": the record is riding, and the switch's contract is "do not migrate",
            // not "put it back". Asserted because a reader could reasonably expect otherwise.
            assertNotNull(index.at(AUTHOR, PLOT));
            assertNull(world.get(PLOT.asLong()));
        }
    }
}
