package com.jiangyuefengyu.redstonecircuit.carrier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.Slot;
import com.jiangyuefengyu.redstonecircuit.logic.CarriedTool;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Which layer owns a wrench shift-click while a carrier host is picked, and what the carrier answers with.
 *
 * <p>The world's ledger and the carrier's ledger are separate maps, and the carrier's own callback answers a
 * click first: it is installed in a phase ordered before the default one the world's callback sits in. So a
 * carrier pick followed by a shift-click on a world block would be decided by the world's own rule that drops
 * every override - it clears the connection and puts that sentence on the action bar - while the carrier's
 * callback has no answer of its own for the click the world already acted on.
 *
 * <p>What is pinned here: the rule that decides the layer, asked by name over its whole table (a live carrier
 * pick owns the wrench's shift half, and nothing else does); the wiring, over the source, where the world's own
 * wrench asks that rule once the click is already wrench-shaped and answers {@code WrenchOutcome.CARRIER} - an
 * answer of its own rather than a cancellation, so the click is left where the carrier's callback has already
 * seen it, while the world's select half drops the carrier pick; the placement of both callbacks in the click's
 * phase order; the words and the line being one decision, since {@code needsANamedAnswer} is asked by both halves
 * of {@code reportOutside}; and the trace reaching the acted-on path as well as the refused one.
 *
 * <p>The rule is asked through reflection so this file still compiles against a build without it, where every
 * call answers "the world keeps the click".
 */
class CarrierPickOwnsTheClickTest {

    /** The carrier this fixture drives, keyed by a sub-level id. */
    private static final CarrierId AUTHOR =
            CarrierId.subLevel(UUID.fromString("0c184903-0000-0000-0000-000000000000"));

    private static final ResourceKey<Level> DIMENSION = ResourceKey.create(Registries.DIMENSION,
            ResourceLocation.parse("redstonecircuit:carrierpickownstheclicktest"));

    /** The picked host, inside the structure. */
    private static final BlockPos LOCAL = new BlockPos(20481032, 127, 20481032);

    /** Where the pose puts it: the world cell {@code -17, -60, -15}. */
    private static final BlockPos WORLD = new BlockPos(-17, -60, -15);

    /** The ordinary world block beside it. */
    private static final BlockPos WORLD_BESIDE = WORLD.east();

    // ------------------------------------------------------------ the rule --

    /**
     * {@code CarrierClickPriority#theCarrierPickOwnsThisShiftClick(boolean, boolean, boolean, boolean)}, asked
     * by name so this file still runs against a build without the rule, where the answer is {@code false}.
     */
    private static boolean thePickOwns(boolean wrench, boolean sneaking, boolean carriedWrench,
                                       boolean picked) {
        try {
            java.lang.reflect.Method method = CarrierClickPriority.class.getDeclaredMethod(
                    "theCarrierPickOwnsThisShiftClick", boolean.class, boolean.class, boolean.class,
                    boolean.class);
            method.setAccessible(true);
            return Boolean.TRUE.equals(method.invoke(null, wrench, sneaking, carriedWrench, picked));
        } catch (ReflectiveOperationException absent) {
            return false;
        }
    }

    @Nested
    @DisplayName("the rule: a live carrier pick owns the wrench's SHIFT half, and nothing else does")
    class TheLayerRule {

        @Test
        @DisplayName("the whole table: 2 x 2 x 2 x 2 rows, each one asserted")
        void theWholeTable() {
            int rows = 0;
            for (boolean wrench : new boolean[] {false, true}) {
                for (boolean sneaking : new boolean[] {false, true}) {
                    for (boolean carriedWrench : new boolean[] {false, true}) {
                        for (boolean picked : new boolean[] {false, true}) {
                            boolean expected = wrench && sneaking && carriedWrench && picked;
                            assertEquals(expected, thePickOwns(wrench, sneaking, carriedWrench, picked),
                                    "row: wrench=" + wrench + " sneaking=" + sneaking
                                            + " carriedWrench=" + carriedWrench
                                            + " aCarrierHostIsPicked=" + picked
                                            + " - the carrier owns the wrench's shift half exactly when "
                                            + "there is a wrench in hand, the sneak key is down, the carrier "
                                            + "wrench is switched on and a carrier host is picked");
                            rows++;
                        }
                    }
                }
            }
            assertEquals(16, rows, "the table is exhaustive, not sampled");
        }

        @Test
        @DisplayName("picked inside the machine, then a shift-click on the world block beside it")
        void theAuthorsClickIsTheCarriers() {
            assertTrue(thePickOwns(true, true, true, true),
                    "a pick followed a tick later by a shift-click must reach the carrier layer instead of "
                            + "the world's own wrench clear");
        }

        @Test
        @DisplayName("NOT SNEAKING is the world's own select half: this clause can never clear anything")
        void thePlainClickIsNotTheCarriers() {
            assertFalse(thePickOwns(true, false, true, true),
                    "a plain right-click with a live carrier pick is the world's own SELECT half (and it "
                            + "forgets the carrier pick - see the wiring below), so the world has to be the "
                            + "one that answers it");
        }

        @Test
        @DisplayName("no pick, or the switch off, and the world keeps the click byte for byte")
        void nothingElseOwnsIt() {
            assertFalse(thePickOwns(true, true, true, false),
                    "with no carrier pick there is no near end in that ledger, so this is the world's own "
                            + "shift-click - and every answer the carrier layer gives for it is silent "
                            + "(NOTHING_PICKED), which is why this row may not stand the world down");
            assertFalse(thePickOwns(true, true, false, true),
                    "carriedWrench=false: the carrier's whole wrench is off, so the world's own wrench is "
                            + "the only one there is");
            assertFalse(thePickOwns(false, true, true, true),
                    "and without a wrench in the dispatched hand this is not a wrench click at all");
        }
    }

    // -------------------------------------------------------- the wiring --

    /** One source file with every run of whitespace flattened, so a statement can be quoted exactly. */
    private static String flat(String relative) {
        try {
            return Files.readString(Path.of(relative)).replace("\r\n", "\n").replaceAll("\\s+", " ");
        } catch (IOException unreadable) {
            throw new AssertionError("could not read " + relative + " (run the tests from the fabric "
                    + "directory)", unreadable);
        }
    }

    private static String flatWorldHandler() {
        return flat("src/main/java/com/jiangyuefengyu/redstonecircuit/InnerRedstoneInteraction.java");
    }

    private static String flatCarrierHandler() {
        return flat("src/main/java/com/jiangyuefengyu/redstonecircuit/carrier/CarrierInteraction.java");
    }

    @Nested
    @DisplayName("the production wiring: the world's own wrench hands the shift-click over")
    class TheWorldHandsItOver {

        @Test
        @DisplayName("the one predicate is asked, with the fact the world side alone can read")
        void theWorldAsksTheOnePredicate() {
            String source = flatWorldHandler();

            assertTrue(source.contains("CarrierClickPriority.theCarrierPickOwnsThisShiftClick(player, "
                            + "RCRegistry.isWrench(player.getItemInHand(hand)), "
                            + "RCConfig.carriedWrench())"),
                    "the handover is decided by the carrier layer's own rule, read from the world side with "
                            + "a real read of the dispatched hand - not a literal `true`");
            assertTrue(source.contains("return WrenchOutcome.CARRIER;"),
                    "and its answer is the third outcome, which is neither \"mine\" nor \"not mine\"");
            assertTrue(source.contains("private enum WrenchOutcome {"),
                    "whose three answers are named where the world's wrench is written");
        }

        @Test
        @DisplayName("asked where the click is already wrench-shaped, and before the goggles gate")
        void theHandoverIsAskedAfterTheClickIsKnownToBeWrenchShaped() {
            String source = flatWorldHandler();
            int notOurClickAtAll = source.indexOf("if (!adjacent && !holdsComponent) {");
            int handover = source.indexOf("theCarrierPickOwnsThisShiftClick(player,");
            int goggles = source.indexOf("if (refuseWithoutGoggles(player)) {", notOurClickAtAll + 1);

            assertTrue(notOurClickAtAll > 0 && handover > 0 && goggles > 0,
                    "the three steps have to be findable in the source at all");
            assertTrue(notOurClickAtAll < handover,
                    "every click the world's own rule hands back to vanilla anyway (`KEEP`) must stay "
                            + "untouched: a chest or a door with a live carrier pick still opens, because "
                            + "that click was never this method's to claim");
            assertTrue(handover < goggles,
                    "and the handover comes before the goggles sentence, because that sentence belongs to "
                            + "the world's gesture - and this click is not it. A player with a carrier pick "
                            + "and no goggles must still be answered by the carrier layer");
        }

        @Test
        @DisplayName("standing down is an answer of its own: nothing underneath runs, nothing claimed, no cancellation")
        void theCarrierOutcomeDoesNotCancel() {
            String source = flatWorldHandler();

            assertTrue(source.contains("if (outcome == WrenchOutcome.CONSUMED) { "
                            + "return InteractionResult.SUCCESS; }"),
                    "the world's own wrench answer is the result that claims the click, so nothing underneath "
                            + "it runs");
            assertTrue(source.contains("// CARRIER and KEEP are both \"not the world's click\""),
                    "while the carrier's half and vanilla's are both answered PASS - the click left exactly "
                            + "as it arrived, for the carrier's own callback and for vanilla");
            assertTrue(source.contains("return InteractionResult.FAIL;"),
                    "and the stand-down for a coordinate a structure owns is FAIL, this loader's answer for "
                            + "\"declined\": nothing underneath may act on it and nothing is claimed as done "
                            + "either, where PASS would hand the click to vanilla and vanilla would set the "
                            + "held block down at the structure's own coordinate");
            assertTrue(source.contains("{@code FAIL} is that answer rather than")
                            && source.contains("{@code PASS}: PASS would hand the click back to vanilla"),
                    "with that reason written where the answer is returned");
            assertFalse(source.contains("setCanceled"),
                    "and nothing here cancels: there is no cancellable click event on this loader, so every "
                            + "stand-down and every claim is one of these answers");
        }

        @Test
        @DisplayName("one live pick per player: the world's select half drops the carrier's pick")
        void theWorldSelectForgetsTheCarrierPick() {
            String source = flatWorldHandler();
            int select = source.indexOf("WrenchSelection.select(player, pos);");
            int clear = source.indexOf("CarriedSelection.clear(player);");
            int shiftHalf = source.indexOf("BlockPos selected = WrenchSelection.get(player);");

            assertTrue(select > 0 && clear > 0,
                    "the world's select half has to say so out loud: this block is the near end now");
            assertTrue(select < clear,
                    "the pick is made first and the carrier ledger is dropped beside it, in that order");
            assertTrue(clear < shiftHalf,
                    "and both are in the SELECT branch, before the shift branch that reads the pick - so the "
                            + "handover above can never hold the world's own wrench hostage for the rest of "
                            + "a session: picking a world host hands the shift-click straight back");
            assertTrue(source.contains("import com.jiangyuefengyu.redstonecircuit.carrier.CarriedSelection;"),
                    "the one carrier-side name the world handler needs");
        }

        @Test
        @DisplayName("the carrier's callback really does run first - which is why the world side is the backstop")
        void theCarrierListenerRunsFirst() {
            String entry = flat("src/main/java/com/jiangyuefengyu/redstonecircuit/RedstoneCircuit.java");
            String carrier = flatCarrierHandler();

            assertTrue(entry.contains("InnerRedstoneInteraction.register();")
                            && entry.contains("com.jiangyuefengyu.redstonecircuit.carrier."
                                    + "CarrierInteraction.register();"),
                    "both hooks have to be installed at all");
            assertTrue(carrier.contains("UseBlockCallback.EVENT.addPhaseOrdering(CARRIER_CLICK_PHASE, "
                            + "Event.DEFAULT_PHASE);"),
                    "this loader has one call site per click, so the ownership rule is expressed as callback "
                            + "PHASE ordering rather than as a registration order: the carrier's callback is "
                            + "put in a phase ordered before the default one, which is where the world's own "
                            + "callback lives");
            assertTrue(carrier.contains("UseBlockCallback.EVENT.register(CARRIER_CLICK_PHASE, "
                            + "interaction::onRightClickBlock);"),
                    "and that is what makes the carrier's callback answer a click before the world's, on both "
                            + "sides");
            assertTrue(carrier.contains("CARRIER_CLICK_PHASE = RedstoneCircuit.id(\"carrier_click\")"),
                    "in a phase this mod names itself, so a reorder is a decision rather than an accident");
            assertTrue(flatWorldHandler().contains("return InteractionResult.FAIL;"),
                    "the world's own callback is therefore a backstop rather than the first answer, and its "
                            + "stand-down has to be FAIL so a click it declines still cannot reach vanilla");
        }
    }

    // ------------------------------------------------- the words and the line --

    @Nested
    @DisplayName("the words and the line are ONE decision: neither half can speak alone")
    class TheWordsAndTheLine {

        /** Every status the chain can answer with, read off the enum rather than listed by hand. */
        private CarrierInteraction.OutsideStatus[] allStatuses() {
            try {
                Class<?> status = Class.forName(
                        "com.jiangyuefengyu.redstonecircuit.carrier.CarrierInteraction$OutsideStatus");
                Object[] constants = status.getEnumConstants();
                CarrierInteraction.OutsideStatus[] typed =
                        new CarrierInteraction.OutsideStatus[constants.length];
                for (int i = 0; i < constants.length; i++) {
                    typed[i] = (CarrierInteraction.OutsideStatus) constants[i];
                }
                return typed;
            } catch (ClassNotFoundException absent) {
                return new CarrierInteraction.OutsideStatus[0];
            }
        }

        @Test
        @DisplayName("five statuses are named on both halves, and the other six on neither")
        void theTwoHalvesAreTheSameList() {
            CarrierInteraction.OutsideStatus[] statuses = allStatuses();
            assertEquals(11, statuses.length,
                    "the enum's own members are the list this invariant is asserted over");

            int named = 0;
            for (CarrierInteraction.OutsideStatus status : statuses) {
                String name = String.valueOf(status);
                boolean expected = switch (name) {
                    case "NOT_ADJACENT", "UNREADABLE_POSE", "UNREADABLE_POSITION", "NO_SUCH_BLOCK",
                         "FAR_END_NOT_THE_PICKED_STRUCTURE" -> true;
                    default -> false;
                };
                assertEquals(expected, CarrierPickOwnsTheClickTest.named(status),
                        "status " + name + ": a named refusal speaks on the action bar AND writes a line; "
                                + "everything else says nothing on either, and the two halves read this one "
                                + "predicate so they cannot drift");
                if (expected) {
                    named++;
                }
            }
            assertEquals(5, named, "five of the eleven have something to say");
        }

        @Test
        @DisplayName("reportOutside asks that predicate on both halves")
        void bothHalvesReadIt() {
            String source = flatCarrierHandler();
            int first = source.indexOf("if (needsANamedAnswer(status)) {");
            int second = source.indexOf("if (needsANamedAnswer(status)) {", first + 1);

            assertTrue(first > 0, "the action bar half reads it");
            assertTrue(second > first, "and the ledger half reads the same one");
            assertFalse(source.contains("|| decision.status() == OutsideStatus."),
                    "so no hand-written status list can drift from the predicate");
            assertTrue(source.contains("message(player, CarrierMessages.refused(refusalMessage(status)));"),
                    "the sentence comes from one mapping, which is the only place a status can acquire "
                            + "words");
        }

        @Test
        @DisplayName("no new language keys: both statuses reuse the world's own sentences")
        void noNewLanguageKeys() throws IOException {
            String source = flatCarrierHandler();
            for (String key : new String[] {"message.redstonecircuit.wrench.not_adjacent_carrier",
                    "message.redstonecircuit.wrench.outside_no_pose",
                    "message.redstonecircuit.wrench.nothing_there"}) {
                assertTrue(source.contains("\"" + key + "\""),
                        "the refusal mapping must use " + key);
                for (String language : new String[] {"zh_cn.json", "en_us.json"}) {
                    assertTrue(language(language).contains("\"" + key + "\""),
                            language + " must already hold " + key + " - no key is added, so every sentence "
                                    + "it can show already exists");
                }
            }
        }

        private static String language(String file) throws IOException {
            return Files.readString(Path.of("src", "main", "resources", "assets", "redstonecircuit", "lang",
                    file), StandardCharsets.UTF_8);
        }
    }

    // ------------------------------------------------------------------ trace --

    @Nested
    @DisplayName("the trace reaches the ACTED-ON path too, not only the refused one")
    class TheLockAttemptTrace {

        @Test
        @DisplayName("a successful lock writes `carrier lock attempt?` as well as its action line")
        void theActedOnPathIsTraced() {
            String source = flatCarrierHandler();

            assertTrue(source.contains("null, inside, pickedPosForTrace(player, true)); reportOutside("
                            + "serverLevel, player, inside, \"RIGHT_CLICK_BLOCK\");"),
                    "the inside gesture's SUCCESS path has to write the trace before it reports: a call at the "
                            + "end of the refused path only is never reached by a lock that succeeded, so the "
                            + "trace would name no successful lock at all");
            assertTrue(source.contains("null, outside, pickedPosForTrace(player, false)); reportOutside("
                            + "serverLevel, player, outside, \"RIGHT_CLICK_BLOCK\");"),
                    "and the same for the outside gesture");
            assertTrue(source.contains("return InteractionResult.SUCCESS; } OutsideDecision outside = "
                            + "planOutside(serverLevel, player, hitResult.getBlockPos());"),
                    "with the acted-on click consumed by answering SUCCESS - this loader's \"this click is "
                            + "mine, so nothing underneath it runs\" - which is what OutsideDecision#handled "
                            + "documents");
        }

        @Test
        @DisplayName("and the trace is still built in one place, still behind debugLog, still a wrench line")
        void theTraceIsUnchanged() {
            String source = flatCarrierHandler();

            assertTrue(source.contains("private static void lockAttempt("),
                    "one builder, so its shape cannot drift from what it describes");
            assertTrue(source.contains("if (!RCConfig.debugLog() || player == null) { return; }"),
                    "behind the same switch as every other per-click line");
            assertTrue(source.contains("if (gesture != CarrierGesture.Kind.WRENCH) {"),
                    "and still a wrench line: without the tool there is no lock gesture to have lost");
            assertEquals(5, occurrences(source, "lockAttempt(player,"),
                    "five call sites: the two acted-on exits, the refused tail, and the empty-host "
                            + "branch's two - and they are mutually exclusive, so a lock attempt writes "
                            + "exactly one trace");
        }

        private static int occurrences(String haystack, String needle) {
            int count = 0;
            for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + 1)) {
                count++;
            }
            return count;
        }
    }

    // --------------------------------------------------------------- the answer --

    /** The world store, as a map - the same stand-in every carrier test uses. */
    private record MapWorld(Map<Long, Slot> slots) implements CarriedHostIndex.SlotWorld {

        @Override
        public Slot get(BlockPos position) {
            return slots.get(position.asLong());
        }

        @Override
        public void set(BlockPos position, @Nullable Slot slot) {
            if (slot == null) {
                slots.remove(position.asLong());
            } else {
                slots.put(position.asLong(), slot);
            }
        }
    }

    /** The translation that carries {@code LOCAL} to {@code WORLD}. */
    private static CarrierPose moving() {
        return CarrierPose.of(new Vector3d(WORLD.getX() - LOCAL.getX(), WORLD.getY() - LOCAL.getY(),
                WORLD.getZ() - LOCAL.getZ()), new Quaterniond());
    }

    /**
     * {@code CarrierInteraction#needsANamedAnswer(OutsideStatus)}, asked by name so this file still runs against
     * a build where there is no such predicate and the answer is {@code false}.
     */
    private static boolean named(CarrierInteraction.OutsideStatus status) {
        try {
            java.lang.reflect.Method method = CarrierInteraction.class.getDeclaredMethod(
                    "needsANamedAnswer", CarrierInteraction.OutsideStatus.class);
            method.setAccessible(true);
            return Boolean.TRUE.equals(method.invoke(null, status));
        } catch (ReflectiveOperationException absent) {
            return false;
        }
    }

    @Nullable
    private static Object read(Object target, String name) {
        try {
            java.lang.reflect.Method accessor = target.getClass().getDeclaredMethod(name);
            accessor.setAccessible(true);
            return accessor.invoke(target);
        } catch (ReflectiveOperationException broken) {
            return null;
        }
    }

    @Nested
    @DisplayName("the answer itself: this click always reaches a named answer")
    class TheAnswerExists {

        /**
         * The same click, driven through the real decision: the pick is a host of {@link #AUTHOR} and the click
         * is the world block beside it, so the decision is {@code LOCKED} and its line is the one a report greps
         * for.
         */
        @Test
        @DisplayName("picked + an adjacent world block ⇒ LOCKED, with the OUTSIDE line")
        void theAuthorsAnswerIsALock() {
            CarriedHostIndex index = new CarriedHostIndex();
            Map<Long, Slot> world = new HashMap<>();
            world.put(WORLD.asLong(), new Slot(ComponentType.DUST));
            index.claim(AUTHOR, LOCAL, WORLD, new MapWorld(world));

            CarriedSelection.Selection picked = new CarriedSelection.Selection(DIMENSION, AUTHOR, LOCAL,
                    WORLD);

            Object decision = CarrierInteraction.planOutside(index, picked, WORLD_BESIDE, false, moving(),
                    WORLD);

            assertNotNull(decision, "the decision exists on this build");
            assertEquals("LOCKED", String.valueOf(read(decision, "status")),
                    "the click is a lock once the carrier layer is allowed to see it: " + decision);
            Object action = read(decision, "action");
            assertNotNull(action, "and it wrote something");
            assertTrue(String.valueOf(read(decision, "blockedBy")).startsWith("locked"),
                    "with the step that answered it on the line as well");

            String line = CarrierLinkLedger.outsideLine((CarriedTool.Action) action, index);
            assertNotNull(line);
            assertTrue(line.startsWith("locked OUTSIDE the structure"), line);
            assertTrue(line.contains("world -17, -60, -15 at -16, -60, -15"),
                    "both ends as world coordinates, which is how the one conversion is checked: " + line);
        }

        @Test
        @DisplayName("nothing picked is NOTHING_PICKED, and its step says `no-pick`")
        void nothingPickedIsNamedInTheStep() {
            Object decision = CarrierInteraction.planOutside(null, null, WORLD_BESIDE, false, null, null);

            assertNotNull(decision);
            assertEquals("NOTHING_PICKED", String.valueOf(read(decision, "status")),
                    "the existing answer for a click with no near end: " + decision);
            String blockedBy = String.valueOf(read(decision, "blockedBy"));
            assertTrue(blockedBy.startsWith("no-pick"),
                    "and the step says that the near end was what was missing, so a report can tell "
                            + "\"nothing picked\" from \"nothing answered\": " + blockedBy);
            assertFalse(named(CarrierInteraction.OutsideStatus.NOTHING_PICKED),
                    "it is silent on both halves: it is the world's own wrench gesture");
            assertTrue(named(CarrierInteraction.OutsideStatus.NOT_ADJACENT),
                    "while a real refusal is named on both - the invariant above, read from here");
        }
    }
}
