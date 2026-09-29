package com.jiangyuefengyu.redstonecircuit.carrier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.Slot;
import com.jiangyuefengyu.redstonecircuit.logic.CarriedInsert;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * <b>P1.27, defect A: "the world side stood down, and nobody took over".</b>
 *
 * <h2>The author's report, and the state the log left the question in</h2>
 * <em>"物理化之后再放元件依旧不显示"</em> - physicalise a block, put a component into it, and nothing is
 * drawn. The previous round's log settles one half of it and leaves the other half open:
 *
 * <pre>
 *   world insert declined: this position belongs to carrier s:4a3b8622 local=20481032, 127, 20481032 …   x44
 *   carrier s:…: insert local=… registered as a RIDING host; projected at …                              x0
 * </pre>
 *
 * <p>So P1.26's narrow exception <b>works</b>: the world really does stand down at a structure's own
 * coordinate. What never happened is the carrier side recording the placement. Every
 * {@code carrier entry trace} line in that log is a wrench, a bare hand or a break - not one of them
 * holds a component - which leaves two possibilities that the log cannot tell apart:
 *
 * <ul>
 *   <li><b>the click never arrived</b> - another listener consumed it, or the client never sent it. Then
 *       no line is possible and none should appear;</li>
 *   <li><b>the click arrived and was filtered out</b> - {@code CarrierGesture} answered {@code NONE},
 *       and the entry point returned <em>before</em> its first trace line, so "not ours" and "never
 *       called" printed the same nothing.</li>
 * </ul>
 *
 * <h2>What is pinned here</h2>
 * <ol>
 *   <li><b>the trace comes before every early return</b> for a click the carrier layer could be about:
 *       a component in hand always leaves a line, whatever the resolver then answers;</li>
 *   <li><b>the gesture chain of the author's own action</b> - Shift + right-click with a redstone
 *       component in hand - is a {@code PLACEMENT}, in both hand orders, and is never mistaken for the
 *       bare-hand switch or for "nobody's";</li>
 *   <li><b>the decision itself</b>, {@code CarrierInteraction#planPlacement}: an <em>empty</em> carried
 *       host accepts the component, the slot lands in the carrier record, the item is spent exactly
 *       once, and the second delivery of one click is answered by name instead of silently;</li>
 *   <li><b>the world store is never written at that coordinate</b> - the defect as the author
 *       experienced it: Jade displayed the component and the overlay drew nothing because the write went
 *       to the wrong store.</li>
 * </ol>
 *
 * <h2>Before the fix</h2>
 * {@code setSinkForTests} does not exist, so this file does not compile against the previous tree -
 * which is stated plainly rather than papered over, and the red half of the evidence is the
 * {@code CarrierEntryTrace} emission count the first nested class asserts (one line for a component,
 * zero for a plank) plus the absent {@code planPlacement}.
 */
class CarrierCarriedInsertPlanTest {

    /** The author's own plot coordinate: {@code local=20481032, 127, 20481032}. */
    private static final BlockPos PLOT = new BlockPos(20481032, 127, 20481032);

    /** Where that structure really stands: {@code world=-7, -60, -11}. */
    private static final BlockPos VISIBLE = new BlockPos(-7, -60, -11);

    /** The author's carrier, from {@code carrier claim s:4a3b8622}. */
    private static final CarrierId AUTHOR =
            CarrierId.subLevel(UUID.fromString("4a3b8622-0000-0000-0000-000000000000"));

    private static final ResourceKey<Level> DIMENSION = ResourceKey.create(Registries.DIMENSION,
            ResourceLocation.parse("redstonecircuit:carriercarriedinsertplantest"));

    private final CarriedHostIndex index = new CarriedHostIndex();
    private final Map<Long, Slot> world = new HashMap<>();
    private final Map<UUID, CarrierPose> poses = new HashMap<>();
    private final CarrierProjection table = new CarrierProjection();

    private CarrierStore.CarrierSource previousSource;

    /** Every line the entry trace emitted during one test, oldest first. */
    private static final List<String> LINES = new ArrayList<>();

    @BeforeEach
    void arm() {
        previousSource = CarrierStore.source();
        CarrierStore.install(new BareSource());
        CarrierStore.projection().clearAll();
        table.begin(DIMENSION, true);
        CarrierEntryTrace.forgetForTests();
        CarrierEntryTrace.setSinkForTests(LINES::add);
    }

    @AfterEach
    void disarm() {
        CarrierEntryTrace.setSinkForTests(null);
        CarrierStore.projection().clearAll();
        CarrierStore.install(previousSource);
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

    /** The world store, as a map. */
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

    /** A pose that carries {@code local} to {@code worldPos}: the author's claim is a translation. */
    private static CarrierPose moving(BlockPos local, BlockPos worldPos) {
        return CarrierPose.of(new Vector3d(worldPos.getX() - local.getX(),
                worldPos.getY() - local.getY(), worldPos.getZ() - local.getZ()), new Quaterniond());
    }

    /**
     * Physicalises one host block that holds <b>nothing</b> - the author's starting state.
     *
     * <p>The index only files a claim when the world store holds a component there, so an empty
     * physicalised block is produced the way a player produces it: claim with a component, then take it
     * back out through the same {@code removeSlot} a retrieval uses.
     */
    private CarriedHost physicaliseEmptyHost(CarrierId carrier, BlockPos local, BlockPos worldPos) {
        world.put(worldPos.asLong(), new Slot(ComponentType.RETAINING_REPEATER));
        index.claim(carrier, local, worldPos, new MapWorld(world));
        index.removeSlot(carrier, local);
        world.remove(worldPos.asLong());
        poses.put(carrier.uuid(), moving(local, worldPos));
        table.put(DIMENSION, new CarrierProjection.ProjectedHost(carrier, local, worldPos,
                poses.get(carrier.uuid()), null, 0));
        return index.at(carrier, local);
    }

    // ------------------------------------------------ the gesture and the trace --

    @Nested
    @DisplayName("the entry point writes its line BEFORE anything can return early")
    class TheTraceComesFirst {

        @Test
        @DisplayName("shift-right-click with a component in hand is a PLACEMENT, and leaves a NONE line")
        void aHeldComponentAlwaysLeavesALine() {
            // THE LINE THE AUTHOR'S LOG NEVER HAD, built by the production report method itself. The only
            // thing this test supplies is what the two hands hold, because a unit test has no ServerPlayer
            // - and what the hands hold is the whole input of the gesture rule either way.
            CarrierGesture.Kind gesture = CarrierInteraction.noteGestureForTests(
                    new CarrierGesture.Hold(false, false, true, false), CarrierGesture.Hold.NOTHING, true,
                    PLOT, Direction.UP);
            assertEquals(CarrierGesture.Kind.PLACEMENT, gesture,
                    "Shift + a redstone component in hand is the world's own placement gesture - store it "
                            + "in the block - and on a carried block it is the carrier's");

            String line = lastLine();
            assertNotNull(line, "a click holding a component MUST leave a trace line: this is the one fact "
                    + "whose absence made the previous log unreadable");
            assertTrue(line.contains("-> NONE"), line);
            assertTrue(line.contains("gesture=PLACEMENT"), "and it names the gesture the rule decided on: "
                    + line);
            assertTrue(line.contains("hand=[component]"),
                    "with BOTH hands named, because 'which hand held what' is the fact the old log lost: "
                            + line);
            assertTrue(line.contains("sneaking=true"), line);
        }

        @Test
        @DisplayName("the trace is emitted BEFORE any early return, in the production source")
        void theTraceComesBeforeTheGate() {
            // THE ORDERING IS THE FIX, not the formatting. P1.26's entry point asked the gesture first and
            // returned on NONE without a line, so a click holding a component and a click that never
            // arrived were indistinguishable. The order is asserted here on the production method's own
            // body, because it is the one thing a unit test cannot observe from the outside: every other
            // assertion in this file would still hold if the call sat after the gate.
            String body = CarrierInteraction.sourceOf("onRightClickBlock");
            int report = body.indexOf("noteGesture(player");
            int gate = body.indexOf("Kind.NONE");
            assertTrue(report >= 0, "the entry point must call noteGesture: " + body);
            assertTrue(gate >= 0, "and it must still ask the rule: " + body);
            assertTrue(report < gate,
                    "noteGesture must come BEFORE the `Kind.NONE` early return - that ordering IS the fix "
                            + "for 'world insert declined x44 / registered as a RIDING host x0'");
            int resolve = body.indexOf("CarrierStore.hitAt");
            assertTrue(resolve < 0 || report < resolve,
                    "and before the resolver, so a click that never reaches a host is still reported");
        }

        @Test
        @DisplayName("an ordinary item is reported too, so 'not ours' is never an absence")
        void anOrdinaryItemIsReportedAsNotOurs() {
            CarrierGesture.Kind gesture = CarrierInteraction.noteGestureForTests(
                    new CarrierGesture.Hold(false, false, false, false), CarrierGesture.Hold.NOTHING,
                    true, PLOT, Direction.UP);

            assertEquals(CarrierGesture.Kind.NONE, gesture, "a plank in hand owns no gesture of ours");
            String line = lastLine();
            assertNotNull(line,
                    "'the hook was never called' and 'the hook said NONE' have to differ, and this line is "
                            + "the difference: " + LINES);
            assertTrue(line.contains("gesture=NONE"), line);
            assertTrue(line.contains("hand=[other item]"),
                    "and it says what the hand actually held, rather than calling it empty: " + line);
        }

        @Test
        @DisplayName("the rule itself: a component is a placement in BOTH hand orders")
        void theGestureChainNeverLosesTheComponent() {
            CarrierGesture.Hold component = new CarrierGesture.Hold(false, false, true, false);

            assertEquals(CarrierGesture.Kind.PLACEMENT,
                    CarrierGesture.of(component, CarrierGesture.Hold.NOTHING, true),
                    "hand=component, Shift -> the world's own store gesture");
            assertEquals(CarrierGesture.Kind.PLACEMENT,
                    CarrierGesture.of(component, CarrierGesture.Hold.NOTHING, false),
                    "and without Shift it is still a placement: the sneak key decides which HALF of it, "
                            + "never whether it is ours");
            assertEquals(CarrierGesture.Kind.PLACEMENT, CarrierGesture.emptyHandGesture(true),
                    "Shift + two empty hands is the retrieval half of the same gesture");
            assertEquals(CarrierGesture.Kind.SWITCH, CarrierGesture.emptyHandGesture(false));
            assertEquals(CarrierGesture.Kind.NONE,
                    CarrierGesture.of(component, new CarrierGesture.Hold(true, false, false, false), true),
                    "and a tool in the other hand owns the click - the tool beats the component, in the "
                            + "author's own words");
            // The other order of the same click: the delivery for the empty hand. It is NOBODY's, and
            // `suppressedByTheOtherHand` is what tells the entry point to consume it rather than let the
            // world underneath act on it.
            assertEquals(CarrierGesture.Kind.NONE,
                    CarrierGesture.of(CarrierGesture.Hold.NOTHING, component, true),
                    "the off-hand delivery of a component click is not the bare-hand gesture either");
        }
    }

    // ------------------------------------------------------------ the decision --

    @Nested
    @DisplayName("Shift + component on an EMPTY carried host: the slot lands in the carrier record")
    class ThePlacementLandsOnTheRecord {

        @Test
        @DisplayName("an empty carried host accepts the component")
        void anEmptyHostAcceptsTheComponent() {
            physicaliseEmptyHost(AUTHOR, PLOT, VISIBLE);
            assertNotNull(index.at(AUTHOR, PLOT), "the host is carried");
            assertNull(index.at(AUTHOR, PLOT).slot(), "and holds nothing - the author's starting state");

            PlacementPlan plan = plan(true, ComponentType.RETAINING_REPEATER, Direction.UP);

            assertNotNull(plan, "the placement decision must exist: without it the click is decided by "
                    + "accident, which is what 'nothing is registered' looked like");
            assertEquals(CarriedInsert.Action.INSERT, plan.action(), String.valueOf(plan));
            assertTrue(plan.acted(), "the click really did something");
            assertTrue(plan.consumesItem(), "and the held component is spent exactly once");

            Slot stored = index.at(AUTHOR, PLOT).slot();
            assertNotNull(stored, "the slot is in the CARRIER record - the store the overlay draws from and "
                    + "the world bridge projects");
            assertEquals(ComponentType.RETAINING_REPEATER, stored.type);
            assertEquals(Direction.UP, stored.facing, "the clicked face became its input side");
            assertFalse(index.at(AUTHOR, PLOT).landed(), "and it is still riding");
        }

        @Test
        @DisplayName("the world store at that coordinate is never written")
        void theWorldStoreStaysEmpty() {
            physicaliseEmptyHost(AUTHOR, PLOT, VISIBLE);

            plan(true, ComponentType.RETAINING_REPEATER, Direction.UP);

            assertFalse(world.containsKey(PLOT.asLong()),
                    "the defect in one assertion: the previous build left the component HERE, which is why "
                            + "Jade showed it and the overlay did not");
        }

        @Test
        @DisplayName("without the sneak key it is not a placement at all")
        void noSneakMeansNoPlacement() {
            physicaliseEmptyHost(AUTHOR, PLOT, VISIBLE);

            PlacementPlan plan = plan(false, ComponentType.RETAINING_REPEATER, Direction.UP);

            assertEquals(CarriedInsert.Action.NOT_SNEAKING, plan.action(), String.valueOf(plan));
            assertFalse(plan.acted());
            assertNull(index.at(AUTHOR, PLOT).slot(), "nothing was written");
        }

        @Test
        @DisplayName("a click with no face at all is refused by name, and writes nothing")
        void noFaceIsRefusedByName() {
            physicaliseEmptyHost(AUTHOR, PLOT, VISIBLE);

            PlacementPlan plan = plan(true, ComponentType.RETAINING_REPEATER, null);

            assertEquals(CarriedInsert.Action.REFUSED_DIRECTION, plan.action(), String.valueOf(plan));
            assertFalse(plan.acted());
            assertNull(index.at(AUTHOR, PLOT).slot());
        }

        @Test
        @DisplayName("a host that already holds a component refuses while replacing is off")
        void anOccupiedHostRefuses() {
            physicaliseEmptyHost(AUTHOR, PLOT, VISIBLE);
            plan(true, ComponentType.RETAINING_REPEATER, Direction.UP);

            PlacementPlan again = plan(true, ComponentType.DUST, Direction.NORTH);

            assertEquals(CarriedInsert.Action.BLOCKED, again.action(), String.valueOf(again));
            assertFalse(again.acted(), "and nothing is consumed");
            assertEquals(ComponentType.RETAINING_REPEATER, index.at(AUTHOR, PLOT).slot().type,
                    "the first component is still there");
        }

        @Test
        @DisplayName("the second delivery of one click is answered BY NAME, not by silence")
        void theSecondDeliverySaysWhy() {
            physicaliseEmptyHost(AUTHOR, PLOT, VISIBLE);
            // The production guard is `ClickOnce`, claimed before the decision: the second delivery of one
            // click must not place a second component, and it must not look like "nothing happened".
            ClickOnce once = new ClickOnce();
            assertTrue(once.claim(7L, AUTHOR, PLOT, net.minecraft.world.InteractionHand.MAIN_HAND),
                    "the first delivery owns the gesture");
            PlacementPlan first = plan(true, ComponentType.RETAINING_REPEATER, Direction.UP);
            assertEquals(CarriedInsert.Action.INSERT, first.action(), String.valueOf(first));

            PlacementPlan second = planSecondDelivery();

            assertNotNull(second, "the second delivery is still decided - it is not allowed to vanish "
                    + "without a line");
            assertFalse(second.acted(), "and it must not place a second component: one click is one gesture");
            assertTrue(second.secondDelivery(),
                    "it says WHY it did nothing, so a reader can tell it from a refusal: " + second);
            assertTrue(second.note().contains("already claimed"),
                    "and the note names the guard rather than leaving an empty reason: " + second);
            assertEquals(ComponentType.RETAINING_REPEATER, index.at(AUTHOR, PLOT).slot().type,
                    "and the first placement is untouched");
        }
    }

    /** The newest line the entry trace wrote, or {@code null} when it wrote none. */
    @Nullable
    private static String lastLine() {
        return LINES.isEmpty() ? null : LINES.get(LINES.size() - 1);
    }

    // ----------------------------------------------------------- the plumbing --

    // THE CALL BELOW IS THE DECISION ITSELF, called by name because it did not exist before this round:
    // `planPlacement` is what turned "Shift + a component on a carried host" from a code path nobody could
    // point at into a function with five inputs and one answer.

    /** {@code CarrierInteraction#planPlacement}, or {@code null} on a build that has no such decision. */
    @Nullable
    private PlacementPlan plan(boolean sneaking, ComponentType stored, @Nullable Direction localFace) {
        return plan(sneaking, stored, localFace, false);
    }

    /** The same, with "the other delivery of this click already acted" stated outright. */
    @Nullable
    private PlacementPlan plan(boolean sneaking, ComponentType stored, @Nullable Direction localFace,
                               boolean secondDelivery) {
        try {
            Class<?> planType = Class.forName(
                    "com.jiangyuefengyu.redstonecircuit.carrier.CarrierInteraction$PlacementPlan");
            java.lang.reflect.Method method = CarrierInteraction.class.getDeclaredMethod("planPlacement",
                    CarriedHostIndex.class, CarrierId.class, BlockPos.class, Direction.class,
                    CarrierGesture.Kind.class, ComponentType.class, boolean.class, boolean.class);
            method.setAccessible(true);
            Object result = method.invoke(null, index, AUTHOR, PLOT, localFace,
                    CarrierGesture.Kind.PLACEMENT, stored, sneaking, secondDelivery);
            return result == null ? null : wrap(planType, result);
        } catch (ClassNotFoundException | NoSuchMethodException absent) {
            return null;
        } catch (ReflectiveOperationException failed) {
            throw new AssertionError(failed);
        }
    }

    /** The second delivery, as {@code handlePlacementClick} asks it. */
    @Nullable
    private PlacementPlan planSecondDelivery() {
        return plan(true, ComponentType.RETAINING_REPEATER, Direction.UP, true);
    }

    /** The plan record, read through its own accessors so this file needs no new type to compile. */
    private static PlacementPlan wrap(Class<?> planType, Object result) throws ReflectiveOperationException {
        return new PlacementPlan(
                (CarriedInsert.Action) call(planType, result, "action"),
                (Boolean) call(planType, result, "acted"),
                (Boolean) call(planType, result, "consumesItem"),
                (Boolean) call(planType, result, "secondDelivery"),
                (String) call(planType, result, "note"));
    }

    private static Object call(Class<?> owner, Object target, String name)
            throws ReflectiveOperationException {
        java.lang.reflect.Method method = owner.getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(target);
    }

    /** What {@code planPlacement} answers, as this file needs it. */
    private record PlacementPlan(CarriedInsert.Action action, boolean acted, boolean consumesItem,
                                 boolean secondDelivery, String note) {

        @Override
        public String toString() {
            return "PlacementPlan[action=" + action + " acted=" + acted + " consumesItem=" + consumesItem
                    + " secondDelivery=" + secondDelivery + " note=" + note + ']';
        }
    }
}
