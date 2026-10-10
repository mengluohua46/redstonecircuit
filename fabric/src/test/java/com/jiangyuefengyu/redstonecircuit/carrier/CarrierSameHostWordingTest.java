package com.jiangyuefengyu.redstonecircuit.carrier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.ConnectionState;
import com.jiangyuefengyu.redstonecircuit.data.Slot;
import com.jiangyuefengyu.redstonecircuit.logic.CarriedTool;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * What the judgement line says when a player clicks the same carried block twice.
 *
 * <p>A plain wrench click <em>picks</em> the near end of a line, so it must not be reported as a refusal - and the
 * ledger has no case for a select, so it needs a name of its own. The shifted click on the picked host is the world's
 * own {@code clear} gesture, and the carrier side has three answers for it: an override was dropped (cleared); there
 * was nothing left to clear, and the pick survives the click as {@link CarrierLinkLedger.Reason#SAME_HOST}; or the
 * picked host holds no component, which is a genuine refusal rather than a wording problem.
 *
 * <p>Asserted: the three answers are three different lines and none of them reads {@code not a link}; the plain-click
 * line carries its own heading and names the pick; and all four answers have action-bar words in both language files.
 */
class CarrierSameHostWordingTest {

    private static final CarrierId MINE =
            CarrierId.subLevel(UUID.fromString("7061d621-0000-0000-0000-000000000000"));

    /** The picked host. */
    private static final BlockPos A = new BlockPos(1, 0, 0);

    /** Its neighbour inside the same structure, one face to the east. */
    private static final BlockPos B = new BlockPos(2, 0, 0);

    private static final BlockPos A_WORLD = new BlockPos(100, 64, 100);
    private static final BlockPos B_WORLD = new BlockPos(101, 64, 100);

    /**
     * One machine, an index and a world store - built fresh per case, so no test can be read through
     * another one's state.
     */
    private static final class Fixture {

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

        /** One host holding a component, and a neighbour to draw a line to. */
        Fixture twoHosts() {
            carry(A, A_WORLD);
            carry(B, B_WORLD);
            return this;
        }

        /** A locked line between the two, so the picked host has an override to drop. */
        Fixture locked() {
            CarriedTool.link(index, MINE, A, B);
            assertEquals(ConnectionState.ON, CarriedTool.stateTowards(index, MINE, A, B),
                    "the setup is a locked line");
            return this;
        }

        /** The pick has been emptied: the structure carries the block, and it holds nothing. */
        Fixture emptied() {
            index.removeSlot(MINE, A);
            assertNotNull(index.at(MINE, A), "the record is still there");
            assertNull(index.at(MINE, A).slot(), "the setup is an emptied host");
            return this;
        }

        private void carry(BlockPos local, BlockPos worldPos) {
            world.put(worldPos.asLong(), new Slot(ComponentType.DUST));
            index.claim(MINE, local, worldPos, new MapWorld(world));
        }

        /** The shift-click on the picked host itself, through the production decision. */
        CarriedTool.Action shiftClickThePickedHost() {
            CarriedTool.Action action = CarrierInteraction.planWrench(null, pick(), index, MINE, A,
                    A_WORLD, null);
            assertNotNull(action, "the decision must exist");
            return action;
        }

        /** The ledger line for one action, as the production writer builds it. */
        String lineOf(CarriedTool.Action action, @Nullable BlockPos pickedLocal) {
            CarrierLinkLedger.LinkLine line = CarrierLinkLedger.of(action, index, MINE, pickedLocal, MINE,
                    A);
            assertNotNull(line, "the line a report is grepped for must be buildable");
            return line.describe();
        }
    }

    private static CarriedSelection.Selection pick() {
        return new CarriedSelection.Selection(Level.OVERWORLD, MINE, A, A_WORLD);
    }

    // ------------------------------------------------------------------ cases --

    @Nested
    @DisplayName("shift-clicking the picked host is the world's own `clear` gesture")
    class TheThreeAnswers {

        @Test
        @DisplayName("cleared: there was an override to drop, and the line says so")
        void cleared() {
            Fixture fixture = new Fixture().twoHosts().locked();

            CarriedTool.Action action = fixture.shiftClickThePickedHost();

            assertTrue(action instanceof CarriedTool.Action.Cleared,
                    "the world's own `shift-right-click the selected block` drops every override: "
                            + action.describe());
            assertEquals(ConnectionState.AUTO, CarriedTool.stateTowards(fixture.index, MINE, A, B),
                    "and the lock is really gone");
            String line = fixture.lineOf(action, A);
            assertTrue(line.contains("cleared: every override on this block is gone"), line);
            assertFalse(line.contains("not a link"), line);
        }

        @Test
        @DisplayName("already automatic: SAME_HOST names the pick instead of denying it")
        void alreadyAutomatic() {
            Fixture fixture = new Fixture().twoHosts();
            assertEquals(ConnectionState.AUTO, CarriedTool.stateTowards(fixture.index, MINE, A, B),
                    "the setup: nothing to clear");

            CarriedTool.Action action = fixture.shiftClickThePickedHost();

            assertTrue(action instanceof CarriedTool.Action.AlreadyClear,
                    "an already-automatic host has nothing to clear, and the pick SURVIVES the click - the "
                            + "player has not asked for it to be forgotten: " + action.describe());
            String line = fixture.lineOf(action, A);
            assertTrue(line.contains("the pick and the click are the same host"),
                    "the line names the pick: " + line);
            assertTrue(line.contains("nothing left to clear"),
                    "and it says what the click found, so `cleared` and `already automatic` are two "
                            + "different lines: " + line);
            assertTrue(line.contains("picked=" + MINE.shortId() + " local=1, 0, 0"),
                    "with the pick's own coordinate on it: " + line);
            assertFalse(line.contains("not a link"), line);
        }

        @Test
        @DisplayName("a genuine refusal: the picked host holds no component")
        void aGenuineRefusal() {
            Fixture fixture = new Fixture().twoHosts().emptied();

            CarriedTool.Action action = fixture.shiftClickThePickedHost();

            assertTrue(action instanceof CarriedTool.Action.NoComponent,
                    "there is no component left at the picked host, so there is nothing to route - a real "
                            + "answer and not a wording problem: " + action.describe());
            String line = fixture.lineOf(action, A);
            assertTrue(line.contains("the picked host holds no component"), line);
            assertFalse(line.contains("not a link"), line);
        }

        @Test
        @DisplayName("and the three are three different lines")
        void theThreeAreDistinct() {
            Fixture clearedFixture = new Fixture().twoHosts().locked();
            String cleared = clearedFixture.lineOf(clearedFixture.shiftClickThePickedHost(), A);
            Fixture automaticFixture = new Fixture().twoHosts();
            String automatic = automaticFixture.lineOf(automaticFixture.shiftClickThePickedHost(), A);
            Fixture refusedFixture = new Fixture().twoHosts().emptied();
            String refused = refusedFixture.lineOf(refusedFixture.shiftClickThePickedHost(), A);

            assertFalse(cleared.equals(automatic), "cleared vs already automatic:\n" + cleared + "\n"
                    + automatic);
            assertFalse(automatic.equals(refused), "already automatic vs a refusal:\n" + automatic + "\n"
                    + refused);
            assertFalse(cleared.equals(refused), "cleared vs a refusal:\n" + cleared + "\n" + refused);
        }
    }

    @Nested
    @DisplayName("a plain select click is not a refusal, and must not read like one")
    class ASelectIsNotARefusal {

        @Test
        @DisplayName("its own heading, its own phrase, and the pick on the line")
        void theSelectLineSaysWhatHappened() {
            Fixture fixture = new Fixture().twoHosts();

            CarriedTool.Action action = CarriedTool.select(fixture.index, MINE, A);
            assertTrue(action instanceof CarriedTool.Action.Selected, action.describe());

            // The ACTION's own carrier and coordinate are what the line names, so a select that has just
            // been recorded cannot be printed as some earlier pick.
            CarrierLinkLedger.LinkLine line = CarrierLinkLedger.of(action, fixture.index, MINE, null, MINE,
                    A);

            assertNotNull(line);
            assertTrue(line.describe().startsWith("picked the near end of a line"),
                    "a select must never read as a refusal: "
                            + line.describe());
            assertFalse(line.describe().contains("not a link"), line.describe());
            assertFalse(line.describe().contains("refused"),
                    "nothing was refused - this click picked the near end of the next line: "
                            + line.describe());
            assertTrue(line.describe().contains("picked=" + MINE.shortId() + " local=1, 0, 0"),
                    line.describe());
            assertTrue(line.describe().contains("(DUST)"), line.describe());
            assertTrue(line.describe().contains("shift-right-click a block beside it"),
                    "and it tells the player what the next half of the gesture is: " + line.describe());
        }
    }

    @Nested
    @DisplayName("the action bar says the same thing, in both languages")
    class TheActionBarHasWords {

        @Test
        @DisplayName("the keys the four answers use exist in en_us and zh_cn")
        void everyAnswerHasAWording() {
            for (String key : new String[] {
                    "message.redstonecircuit.wrench.selected_carrier",
                    "message.redstonecircuit.wrench.cleared_carrier",
                    "message.redstonecircuit.wrench.same_block",
                    "message.redstonecircuit.wrench.no_component"}) {
                for (String file : new String[] {"en_us.json", "zh_cn.json"}) {
                    assertTrue(lang(file).contains("\"" + key + "\""),
                            "`" + key + "` must exist in " + file + ": the judgement line is for the "
                                    + "report, and the action bar is what the player reads");
                }
            }
        }

        /** One language file, from this project's own directory. */
        private static String lang(String fileName) {
            Path relative = Path.of("src", "main", "resources", "assets", "redstonecircuit", "lang",
                    fileName);
            for (Path candidate : new Path[] {relative}) {
                try {
                    if (Files.isReadable(candidate)) {
                        return Files.readString(candidate, StandardCharsets.UTF_8);
                    }
                } catch (java.io.IOException unreadable) {
                    throw new AssertionError("could not read " + candidate, unreadable);
                }
            }
            throw new AssertionError("could not find " + fileName + " from "
                    + Path.of("").toAbsolutePath());
        }
    }
}
