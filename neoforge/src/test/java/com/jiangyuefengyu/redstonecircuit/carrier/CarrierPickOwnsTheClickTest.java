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
 * <b>P1.35 commit B: "I picked the block inside the machine, shift-clicked the ordinary block beside it,
 * and was told 已清除所有连接 - and not one lock judgement was ever reached."</b>
 *
 * <h2>What the author's own log contains, counted</h2>
 * <pre>
 *   picked the near end of a line        15      the pick really was recorded
 *   locked OUTSIDE the structure          2      the OTHER side of the same machine, where the world's own
 *   locked INSIDE the structure           6      rule happened to decline and the click fell through
 *   wrench clear at -16, -60, -15 -> CLEARED  13  the world's own wrench, on the world's own ledger
 *   carrier lock attempt?                 0      ← the line P1.34 added, never once written
 *   link refused                          0
 *   NOT_ADJACENT / NOT_OUTSIDE / NOT_INSIDE / FAR_END_…   0, 0, 0, 0
 *   INSIDE: …  /  OUTSIDE: …              0
 * </pre>
 *
 * <p>The two ledgers are separate maps and nothing kept them in step, and {@code InnerRedstoneInteraction}
 * runs <b>before</b> the carrier's listener: the carrier pick was made last, the clicked coordinate was a
 * world block the <em>world</em> ledger still had selected from an earlier click, and the world's own
 * "shift-click the block you picked again drops every override" won the click - cleared it, put 「已清除…」
 * on the action bar, and cancelled the event. The carrier's listener was never called at all, which is why
 * every answer it can give is absent from that log: <b>not refused - never asked</b>.
 *
 * <h2>What is pinned here</h2>
 * <ol>
 *   <li><b>the rule that decides the layer</b> ({@code CarrierClickPriority#theCarrierPickOwnsThisShiftClick}),
 *       asked by name over its whole table: a live carrier pick owns the wrench's shift half, and nothing
 *       else does;</li>
 *   <li><b>the wiring, over the source</b> - the world's own wrench asks that rule at the one point where
 *       the click is already known to be wrench-shaped, before the goggles gate, and answers
 *       {@code WrenchOutcome.CARRIER}: both action fields FALSE and <b>not</b> cancelled, so the carrier's
 *       listener answers it. The world's select half drops the carrier pick, which is what keeps the world's
 *       own line reachable after one carrier pick;</li>
 *   <li><b>the words and the line are one decision</b> - {@code needsANamedAnswer} is asked by both halves
 *       of {@code reportOutside}, so no status can speak on the action bar without writing a line, or the
 *       reverse. That is the author's contradiction, stated as an invariant over the whole enum;</li>
 *   <li><b>the trace reaches the acted-on path too</b> - the line P1.34 added is written for a lock that
 *       succeeded, not only for one that was refused (a successful lock returns before the refused tail,
 *       which is where P1.34 put the only call);</li>
 *   <li><b>the answer exists</b> - the author's own click, driven through the real decision, is
 *       {@code LOCKED} with the {@code OUTSIDE} line, and "nothing picked" is {@code NOTHING_PICKED} with a
 *       step that says so;</li>
 *   <li><b>no new language keys</b> - the two statuses that used to write a line and say nothing reuse the
 *       world's own sentences, and both keys are asserted to exist in both language files.</li>
 * </ol>
 *
 * <h2>Why the new rule is asked by name</h2>
 * The red half of this round's evidence is a real run against the build <em>before</em> it, where this
 * method does not exist: the reflective lookup fails, every call answers "the world keeps the click", and
 * every assertion that expects the carrier to own the click fails as a plain assertion failure. The rows
 * that expect {@code false} stay green there on purpose - the old build's wrong answer coincides with the
 * right one on those - and they are the rows that keep this rule from swallowing the world's own wrench.
 */
class CarrierPickOwnsTheClickTest {

    /** The author's own carrier, from {@code carrier s:0c184903}. */
    private static final CarrierId AUTHOR =
            CarrierId.subLevel(UUID.fromString("0c184903-0000-0000-0000-000000000000"));

    private static final ResourceKey<Level> DIMENSION = ResourceKey.create(Registries.DIMENSION,
            ResourceLocation.parse("redstonecircuit:carrierpickownstheclicktest"));

    /** The picked host, inside the structure. */
    private static final BlockPos LOCAL = new BlockPos(20481032, 127, 20481032);

    /** Where the author's pose put it: {@code world=-17, -60, -15} in their log. */
    private static final BlockPos WORLD = new BlockPos(-17, -60, -15);

    /** The ordinary world block beside it - the block the author shift-clicked. */
    private static final BlockPos WORLD_BESIDE = WORLD.east();

    // ------------------------------------------------------------ the rule --

    /**
     * {@code CarrierClickPriority#theCarrierPickOwnsThisShiftClick(boolean, boolean, boolean, boolean)},
     * asked by name so this file runs against the previous build.
     *
     * <p>On that build the method does not exist and the answer is {@code false}: the world keeps the
     * shift-click, which is exactly the defect (the carrier's gesture is never entered). Every assertion
     * below that expects the carrier to own the click is therefore red there.
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
        @DisplayName("the author's own click: picked inside the machine, then shift-clicked the world block")
        void theAuthorsClickIsTheCarriers() {
            assertTrue(thePickOwns(true, true, true, true),
                    "this is the click from the author's log - `picked the near end of a line` followed by a "
                            + "shift-click one tick later - and it must reach the carrier layer instead of "
                            + "the world's own `wrench clear … -> CLEARED`");
        }

        @Test
        @DisplayName("NOT SNEAKING is the world's own select half: this clause can never clear anything")
        void thePlainClickIsNotTheCarriers() {
            assertFalse(thePickOwns(true, false, true, true),
                    "a plain right-click with a live carrier pick is the world's own SELECT half (and it "
                            + "forgets the carrier pick - see the wiring below), so the action bar can never "
                            + "say 「已清除…」 through this clause: the world must be the one to answer it");
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
            throw new AssertionError("could not read " + relative + " (run the tests from the neoforge "
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
        @DisplayName("standing down is the two action fields, and NOT cancellation")
        void theCarrierOutcomeDoesNotCancel() {
            String source = flatWorldHandler();

            assertTrue(source.contains("} else if (outcome == WrenchOutcome.CARRIER) {"),
                    "the outcome is acted on where the wrench's answer is acted on");
            assertTrue(source.contains("event.setUseBlock(net.neoforged.neoforge.common.util.TriState.FALSE);"),
                    "both action fields go FALSE, so vanilla cannot act on the click underneath");
            assertTrue(source.contains("event.setUseItem(net.neoforged.neoforge.common.util.TriState.FALSE);"));
            assertFalse(source.contains("} else if (outcome == WrenchOutcome.CARRIER) { "
                            + "event.setUseBlock(net.neoforged.neoforge.common.util.TriState.FALSE); "
                            + "event.setUseItem(net.neoforged.neoforge.common.util.TriState.FALSE); "
                            + "event.setCanceled(true);"),
                    "and it must NOT cancel: the carrier's listener is registered after this one and is a "
                            + "default `receiveCanceled=false` listener, so cancelling here would delete the "
                            + "very delivery this handover exists to hand over");
            assertTrue(source.contains("if (outcome == WrenchOutcome.CONSUMED) { event.setCanceled(true); }"),
                    "while the world's OWN answer still cancels, exactly as it did before");
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
                    "which is the one carrier-side name this commit adds to the world handler");
        }

        @Test
        @DisplayName("the world's listener really does run first - which is why it has to stand down")
        void theWorldListenerStillRunsFirst() {
            String source = flat("src/main/java/com/jiangyuefengyu/redstonecircuit/RedstoneCircuit.java");
            int world = source.indexOf("NeoForge.EVENT_BUS.register(new InnerRedstoneInteraction());");
            int carrier = source.indexOf("NeoForge.EVENT_BUS.register(new "
                    + "com.jiangyuefengyu.redstonecircuit.carrier.CarrierInteraction());");

            assertTrue(world > 0 && carrier > 0, "both listeners have to be registered at all");
            assertTrue(world < carrier,
                    "the world's listener is registered FIRST, which is the whole reason the handover has to "
                            + "be written on the world side: a click it consumes never reaches the carrier's "
                            + "listener at all. This assertion is here so a future reorder is a decision and "
                            + "not an accident");
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
        @DisplayName("reportOutside asks that predicate on both halves, and no `||` chain is left")
        void bothHalvesReadIt() {
            String source = flatCarrierHandler();
            int first = source.indexOf("if (needsANamedAnswer(status)) {");
            int second = source.indexOf("if (needsANamedAnswer(status)) {", first + 1);

            assertTrue(first > 0, "the action bar half reads it");
            assertTrue(second > first, "and the ledger half reads the same one - this is the fix");
            assertFalse(source.contains("|| decision.status() == OutsideStatus."),
                    "the two hand-written lists that could drift apart are gone");
            assertTrue(source.contains("message(player, CarrierMessages.refused(refusalMessage(status)));"),
                    "the sentence comes from one mapping, which is the only place a status can acquire "
                            + "words");
        }

        @Test
        @DisplayName("no new language keys: the two that had a line and no sentence reuse the world's own")
        void noNewLanguageKeys() throws IOException {
            String source = flatCarrierHandler();
            for (String key : new String[] {"message.redstonecircuit.wrench.not_adjacent_carrier",
                    "message.redstonecircuit.wrench.outside_no_pose",
                    "message.redstonecircuit.wrench.nothing_there"}) {
                assertTrue(source.contains("\"" + key + "\""),
                        "the refusal mapping must use " + key);
                for (String language : new String[] {"zh_cn.json", "en_us.json"}) {
                    assertTrue(language(language).contains("\"" + key + "\""),
                            language + " must already hold " + key + " - this commit adds no key, so every "
                                    + "sentence it can show is one CarrierMessageTextTest already renders");
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
    @DisplayName("the trace reaches the ACTED-ON path too, which is what P1.34 promised")
    class TheLockAttemptTrace {

        @Test
        @DisplayName("a successful lock writes `carrier lock attempt?` as well as its action line")
        void theActedOnPathIsTraced() {
            String source = flatCarrierHandler();

            assertTrue(source.contains("null, inside, pickedPosForTrace(player, true)); reportOutside("
                            + "serverLevel, player, inside, \"RIGHT_CLICK_BLOCK\");"),
                    "the inside gesture's SUCCESS path has to write the trace before it reports: P1.34 put "
                            + "the call only at the end of the refused path, which a lock returns before, so "
                            + "`grep \"carrier lock attempt?\"` found nothing on a lock that WORKED");
            assertTrue(source.contains("null, outside, pickedPosForTrace(player, false)); reportOutside("
                            + "serverLevel, player, outside, \"RIGHT_CLICK_BLOCK\");"),
                    "and the same for the outside gesture");
            assertTrue(source.contains("event.setCanceled(true); return; } OutsideDecision outside = "
                            + "planOutside(serverLevel, player, event.getPos());"),
                    "with the acted-on click consumed, which is what OutsideDecision#handled documents");
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

    /** The translation the author's log printed for that structure: {@code LOCAL -> WORLD}. */
    private static CarrierPose moving() {
        return CarrierPose.of(new Vector3d(WORLD.getX() - LOCAL.getX(), WORLD.getY() - LOCAL.getY(),
                WORLD.getZ() - LOCAL.getZ()), new Quaterniond());
    }

    /**
     * {@code CarrierInteraction#needsANamedAnswer(OutsideStatus)}, asked by name so this file compiles and
     * runs against the build before it - where the two hand-written lists are still separate and there is
     * no such predicate, so the answer is {@code false}.
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
    @DisplayName("the answer itself: this click can no longer end with no answer at all")
    class TheAnswerExists {

        /**
         * <b>The author's own click, driven through the real decision.</b>
         *
         * <p>The pick is a host of {@link #AUTHOR} and the click is the world block beside it: the decision
         * is {@code LOCKED} and its line is the one a report greps for. This answer was unreachable in the
         * author's session - not because the decision was wrong, but because the world's listener consumed
         * the click a moment earlier.
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
                    "the author's click is a lock, once the carrier layer is allowed to see it: "
                            + decision);
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
                    "it is deliberately silent on both halves: it is the world's own wrench gesture");
            assertTrue(named(CarrierInteraction.OutsideStatus.NOT_ADJACENT),
                    "while a real refusal is named on both - the invariant above, read from here");
        }
    }
}
