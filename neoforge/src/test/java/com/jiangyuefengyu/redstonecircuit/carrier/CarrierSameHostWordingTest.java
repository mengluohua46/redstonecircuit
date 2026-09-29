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
 * <b>P1.33 commit C: what the judgement line says when a player clicks the same carried block twice.</b>
 *
 * <h2>The seven lines the author's log is full of</h2>
 * <pre>
 *   carrier s:7061d621: select DUST at local=20481032, 127, 20481032 riding s:7061d621 local=20481032, 127, 20481032 …
 *   carrier s:7061d621: link refused: not a link picked=s:7061d621 local=20481032, 127, 20481032 (DUST)
 *       on=s:7061d621 local=20481032, 127, 20481032 side=? entry=RIGHT_CLICK_BLOCK
 * </pre>
 *
 * <p>The first line is the wrench doing exactly what it should - a plain click <em>picks</em> the near end
 * of a line. The second is the same click being reported as a refusal, because the ledger had no case for
 * a select and fell through to its "no case observed" text ({@code not a link}). Every one of the
 * author's eight such lines is a select - the {@code sneaking=false} column beside them says so - and each
 * names the picked coordinate and the clicked coordinate as the same number: they clicked one block over
 * and over, and every time the tool worked and the log said it had failed.
 *
 * <h2>The world's own semantics, which the carrier side has to match</h2>
 * {@code InnerRedstoneInteraction#useWrench} has three answers for a wrench click on a host, and the
 * carrier side already had all three decisions - what was missing was the last one's <em>name</em>:
 *
 * <table>
 *   <caption>The three answers, and the action bar each one shows</caption>
 *   <tr><th></th><th>the world</th><th>the carrier side</th><th>action bar</th></tr>
 *   <tr><td>plain click on a host</td><td>{@code WrenchSelection#select},
 *       {@code wrench.selected}</td><td>{@code CarriedTool#select},
 *       {@code selected_carrier}</td><td>已选中 …</td></tr>
 *   <tr><td>shift-click the picked host, overrides present</td>
 *       <td>{@code WrenchLinks#clear}, {@code wrench.cleared}</td>
 *       <td>{@code CarriedTool#clearChecked} → {@code Cleared}</td>
 *       <td>{@code cleared_carrier}: 已清除这里的全部连接设定，恢复自动连接</td></tr>
 *   <tr><td>shift-click the picked host, already automatic</td>
 *       <td>the same {@code clear} - "nothing to drop" is still "cleared" there</td>
 *       <td>{@code AlreadyClear}: the pick survives, so the line names it</td>
 *       <td>{@code same_block}: 这就是你选中的那个方块；连线要在紧挨着的两个方块之间</td></tr>
 * </table>
 *
 * <p>The third row is the one place the carrier side is deliberately different from the world: the pick
 * has to survive that click (see {@code CarriedTool#clearChecked}), so the click gets a name of its own -
 * {@link CarrierLinkLedger.Reason#SAME_HOST} - instead of a refusal. What it must never be is
 * {@code not a link}.
 *
 * <h2>What is asserted</h2>
 * <ol>
 *   <li><b>cleared</b>: an override was dropped, and the line says so;</li>
 *   <li><b>already automatic</b>: nothing was left to clear, the pick is still named, and the line is
 *       tellable apart from the first;</li>
 *   <li><b>a genuine refusal</b>: the picked host holds no component - a real "there is nothing here to
 *       route", not a wording problem;</li>
 *   <li><b>a select is not a refusal</b>: the plain-click line carries its own heading and never says
 *       {@code link refused} or {@code not a link};</li>
 *   <li><b>the action bar has words for all four</b>, in both language files.</li>
 * </ol>
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

        /** The author's machine: one host holding a component, and a neighbour to draw a line to. */
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
                    "the line names the pick, which is the answer the author's log needed: " + line);
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
                    "the author's log printed `link refused: not a link` here, for a click that worked: "
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

        /** One language file, found from either the project directory or the repository root. */
        private static String lang(String fileName) {
            Path relative = Path.of("src", "main", "resources", "assets", "redstonecircuit", "lang",
                    fileName);
            for (Path candidate : new Path[] {relative, Path.of("neoforge").resolve(relative)}) {
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
