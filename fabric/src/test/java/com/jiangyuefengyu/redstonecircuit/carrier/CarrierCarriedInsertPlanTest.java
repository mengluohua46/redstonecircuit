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
 * Shift + a component on a carried block: the gesture chain, the entry trace's ordering, and the placement
 * decision.
 *
 * <p>The world insert declines at a structure's own coordinate, so the carrier side has to take the placement
 * over - and a click that arrived and was filtered out ({@code CarrierGesture} answering NONE, with the entry
 * point returning before its first trace line) prints the same nothing as a click that never arrived at all.
 *
 * <p>What is pinned here: the trace comes before every early return, so a component in hand always leaves a
 * line whatever the resolver then answers; the gesture chain of that action - Shift + right-click with a
 * redstone component in hand - is a {@code PLACEMENT} in both hand orders and is never mistaken for the
 * bare-hand switch or for "nobody's"; the decision itself accepts the component into an <em>empty</em> carried
 * host, lands the slot in the carrier record, spends the item exactly once, and answers the second delivery of
 * one click by name rather than silently; and the world store is never written at that coordinate, which is
 * what makes Jade and the overlay agree.
 */
class CarrierCarriedInsertPlanTest {

    /** The plot coordinate a Sable sub-level answers at: {@code local=20481032, 127, 20481032}. */
    private static final BlockPos PLOT = new BlockPos(20481032, 127, 20481032);

    /** Where that structure really stands: {@code world=-7, -60, -11}. */
    private static final BlockPos VISIBLE = new BlockPos(-7, -60, -11);

    /** The carrier this fixture drives, keyed by the same kind of id a claim uses. */
    private static final CarrierId AUTHOR =
            CarrierId.subLevel(UUID.fromString("4a3b8622-0000-0000-0000-000000000000"));

    private static final ResourceKey<Level> DIMENSION = ResourceKey.create(Registries.DIMENSION,
            ResourceLocation.parse("redstonecircuit:carriercarriedinsertplantest"));

    /** The entry point's own source, for the one wiring assertion a name-anchored reader cannot serve. */
    private static final String INTERACTION_SOURCE =
            "src/main/java/com/jiangyuefengyu/redstonecircuit/carrier/CarrierInteraction.java";

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

    /** A pose that carries {@code local} to {@code worldPos}: the claim is a translation. */
    private static CarrierPose moving(BlockPos local, BlockPos worldPos) {
        return CarrierPose.of(new Vector3d(worldPos.getX() - local.getX(),
                worldPos.getY() - local.getY(), worldPos.getZ() - local.getZ()), new Quaterniond());
    }

    /**
     * Physicalises one host block that holds <b>nothing</b> - the starting state every placement needs.
     *
     * <p>The index only files a claim when the world store holds a component there, so an empty physicalised
     * block is produced the way a player produces it: claim with a component, then take it back out through
     * the same {@code removeSlot} a retrieval uses.
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
            // A line built by the production report method itself. The only thing this test supplies is what
            // the two hands hold, because a unit test has no ServerPlayer - and what the hands hold is the
            // whole input of the gesture rule either way.
            CarrierGesture.Kind gesture = CarrierInteraction.noteGestureForTests(
                    new CarrierGesture.Hold(false, false, true, false), CarrierGesture.Hold.NOTHING, true,
                    PLOT, Direction.UP);
            assertEquals(CarrierGesture.Kind.PLACEMENT, gesture,
                    "Shift + a redstone component in hand is the world's own placement gesture - store it "
                            + "in the block - and on a carried block it is the carrier's");

            String line = lastLine();
            assertNotNull(line, "a click holding a component MUST leave a trace line: this is the one fact "
                    + "whose absence makes the log unreadable");
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
            // The ordering is the point, not the formatting: an entry point that asks the gesture first and
            // returns on NONE without a line makes a click holding a component and a click that never arrived
            // indistinguishable. The order is asserted on the production method's own body, because it is the
            // one thing a unit test cannot observe from the outside.
            String body = CarrierInteraction.sourceOf("onRightClickBlock");
            int report = body.indexOf("noteGesture(player");
            int gate = body.indexOf("Kind.NONE");
            assertTrue(report >= 0, "the entry point must call noteGesture: " + body);
            assertTrue(gate >= 0, "and it must still ask the rule: " + body);
            assertTrue(report < gate,
                    "noteGesture must come BEFORE the `Kind.NONE` early return - that ordering is what makes "
                            + "an unanswered click visible at all");
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
                    "and a tool in the other hand owns the click: the tool beats the component");
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
            assertNull(index.at(AUTHOR, PLOT).slot(), "and holds nothing - the starting state");

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
                    "a component left HERE is why Jade showed it and the overlay did not");
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

    // ------------------------------------------------- the host's own rule --

    @Nested
    @DisplayName("a physicalised block that cannot hold redstone refuses it, as it does standing still")
    class TheHostRuleReachesTheCarrierRecord {

        @Test
        @DisplayName("sneaking with a component in hand, and the block still refuses it")
        void aBlockThatMayNotHostRefusesTheInsert() {
            physicaliseEmptyHost(AUTHOR, PLOT, VISIBLE);

            PlacementPlan plan = plan(true, ComponentType.RETAINING_REPEATER, Direction.UP, false, false);

            assertNotNull(plan, "the click is still decided - a refusal with a name, never a silence");
            assertEquals(CarriedInsert.Action.NOT_A_HOST, plan.action(), String.valueOf(plan));
            assertFalse(plan.acted(), "nothing was stored");
            assertFalse(plan.consumesItem(), "and nothing is spent: the world's own refusal spends nothing "
                    + "either, so a player cannot lose a component to a glass block");
            assertNull(index.at(AUTHOR, PLOT).slot(),
                    "物理化后的玻璃、结构方块、工作台、台阶: the click is refused and the carrier record is left "
                            + "exactly as it was");
        }

        @Test
        @DisplayName("the same click on a block that may hold one still stores it")
        void anOrdinaryHostStillStores() {
            physicaliseEmptyHost(AUTHOR, PLOT, VISIBLE);

            PlacementPlan plan = plan(true, ComponentType.RETAINING_REPEATER, Direction.UP, false, true);

            assertEquals(CarriedInsert.Action.INSERT, plan.action(), String.valueOf(plan));
            assertNotNull(index.at(AUTHOR, PLOT).slot(),
                    "an ordinary carried block still takes the component");
        }

        @Test
        @DisplayName("and a bare hand still takes the component back OUT of it")
        void aRetrievalIsNeverRefused() {
            physicaliseEmptyHost(AUTHOR, PLOT, VISIBLE);
            plan(true, ComponentType.RETAINING_REPEATER, Direction.UP);

            PlacementPlan taken = plan(true, null, Direction.UP, false, false);

            assertEquals(CarriedInsert.Action.RETRIEVE, taken.action(), String.valueOf(taken));
            assertNull(index.at(AUTHOR, PLOT).slot(),
                    "the world's own retrieval (InnerRedstoneInteraction#tryRetrieve) asks nothing about the "
                            + "host either, so a component a player put in must always come back out - "
                            + "refusing it here would trap it in a block that stopped being a legal host");
        }

        @Test
        @DisplayName("the placement path asks the world's own rule, of the structure's own view")
        void thePlacementPathAsksTheHostRule() {
            // The body is read from the file rather than through `CarrierInteraction#sourceOf`, because that
            // helper anchors on a NAME's first occurrence and this method is CALLED (from the gesture switch)
            // a long way above its own definition - so it would hand back the caller's block instead.
            String body = interactionSource();

            assertTrue(body.contains(
                            "CarrierStore.hostAcceptsComponent(hit.carrier(), hit.localPos())"),
                    "the click's host state is read from the structure, because a physicalised block's state "
                            + "is not in the level to be read");
            String decision = CarrierInteraction.sourceOf("planPlacement");
            assertTrue(decision.contains(
                            "CarriedInsert.apply(index, carrier, localPos, hostAcceptsComponent,"),
                    "and the answer is handed to the same rule that decides everything else about the "
                            + "placement, rather than to a second copy of it: " + decision);
        }

        /** {@code CarrierInteraction}'s own source; see {@link #thePlacementPathAsksTheHostRule}. */
        private static String interactionSource() {
            try {
                return java.nio.file.Files.readString(java.nio.file.Path.of(INTERACTION_SOURCE));
            } catch (java.io.IOException unreadable) {
                throw new AssertionError("CarrierInteraction's source could not be read at "
                        + INTERACTION_SOURCE, unreadable);
            }
        }
    }

    // ----------------------------------------------------------- the plumbing --

    // The call below is the decision itself, called by name: `planPlacement` is what turns "Shift + a
    // component on a carried host" from a code path nobody could point at into a function with five inputs
    // and one answer.

    /** {@code CarrierInteraction#planPlacement}, or {@code null} on a build that has no such decision. */
    @Nullable
    private PlacementPlan plan(boolean sneaking, ComponentType stored, @Nullable Direction localFace) {
        return plan(sneaking, stored, localFace, false);
    }

    /** The same, with "the other delivery of this click already acted" stated outright. */
    @Nullable
    private PlacementPlan plan(boolean sneaking, ComponentType stored, @Nullable Direction localFace,
                               boolean secondDelivery) {
        return plan(sneaking, stored, localFace, secondDelivery, true);
    }

    /**
     * The same, with the host rule stated: {@code hostAccepts=false} is a block that may not hold a component
     * at all - a physicalised pane of glass, a structure block, a crafting table, a slab.
     */
    @Nullable
    private PlacementPlan plan(boolean sneaking, ComponentType stored, @Nullable Direction localFace,
                               boolean secondDelivery, boolean hostAccepts) {
        try {
            Class<?> planType = Class.forName(
                    "com.jiangyuefengyu.redstonecircuit.carrier.CarrierInteraction$PlacementPlan");
            java.lang.reflect.Method method = CarrierInteraction.class.getDeclaredMethod("planPlacement",
                    CarriedHostIndex.class, CarrierId.class, BlockPos.class, Direction.class,
                    CarrierGesture.Kind.class, ComponentType.class, boolean.class, boolean.class,
                    boolean.class);
            method.setAccessible(true);
            Object result = method.invoke(null, index, AUTHOR, PLOT, localFace,
                    CarrierGesture.Kind.PLACEMENT, stored, sneaking, hostAccepts, secondDelivery);
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
