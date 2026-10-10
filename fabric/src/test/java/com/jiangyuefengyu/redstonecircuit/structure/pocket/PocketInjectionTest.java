package com.jiangyuefengyu.redstonecircuit.structure.pocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.jiangyuefengyu.redstonecircuit.structure.ActivationTable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * The injection ledger: which cells a structure's six faces are pushing into right now.
 *
 * <p>这是激活面模式里不涉及世界的一半，规则全是关于<em>变化</em>的: 某个面来信号、走信号、两个面点名同一格、
 * 新表不再点名的格子。弄错不会崩，只会让结构静静地通电或者一直黑着。
 */
class PocketInjectionTest {

    private static final BlockPos CELL = new BlockPos(1, 2, 3);

    private static Map<Direction, List<ActivationTable.FaceEntry>> table(Direction face, int strength) {
        return Map.of(face, List.of(new ActivationTable.FaceEntry(CELL, strength)));
    }

    /** A signal vector with the named faces energized, which is all the ledger is told about the world. */
    private static int[] signals(Direction... faces) {
        int[] signals = new int[Direction.values().length];
        for (Direction face : faces) {
            signals[face.ordinal()] = 15;
        }
        return signals;
    }

    @Test
    @DisplayName("an energized face pushes its cells at the strength the table says")
    void anEnergizedFaceDrivesItsCells() {
        PocketInjection ledger = new PocketInjection();
        assertTrue(ledger.isEmpty(), "a fresh plot is driven by nothing");

        List<BlockPos> changed = ledger.update(table(Direction.EAST, 9), signals(Direction.EAST));

        assertEquals(List.of(CELL), changed, "the cell the table names is the cell that changed");
        assertEquals(9, ledger.strengthAt(CELL), "and it carries the table's strength, not the face's 15");
        assertEquals(0, ledger.strengthAt(new BlockPos(0, 0, 0)), "its neighbours are not pushed");
        assertFalse(ledger.isEmpty());
        assertEquals(1, ledger.size());
    }

    @Test
    @DisplayName("a face with no signal drives nothing, even though the table names cells")
    void aDarkFaceDrivesNothing() {
        PocketInjection ledger = new PocketInjection();

        assertTrue(ledger.update(table(Direction.EAST, 9), signals()).isEmpty(),
                "no face is energized, so there is nothing to inject - not even a change to report");
        assertEquals(0, ledger.strengthAt(CELL));
        assertTrue(ledger.isEmpty());
    }

    @Test
    @DisplayName("a face the table does not mention does nothing when it is energized")
    void anUnfilledFaceDrivesNothing() {
        PocketInjection ledger = new PocketInjection();

        assertTrue(ledger.update(table(Direction.EAST, 9), signals(Direction.WEST, Direction.UP)).isEmpty(),
                "a face with no entries cannot push anything, so an unfilled face does nothing");
        assertTrue(ledger.isEmpty());
    }

    @Test
    @DisplayName("a cell that a new table no longer names is reported, because its power is going away")
    void aReleasedCellIsReported() {
        PocketInjection ledger = new PocketInjection();
        assertEquals(List.of(CELL), ledger.update(table(Direction.EAST, 9), signals(Direction.EAST)));

        List<BlockPos> changed = ledger.update(Map.of(), signals(Direction.EAST));

        assertEquals(List.of(CELL), changed, "the caller has to be told so it can notify that cell");
        assertEquals(0, ledger.strengthAt(CELL), "and the power is gone");
        assertTrue(ledger.isEmpty());
    }

    @Test
    @DisplayName("two faces naming one cell push as hard as the harder of them")
    void oneCellTwoFaces() {
        PocketInjection ledger = new PocketInjection();
        Map<Direction, List<ActivationTable.FaceEntry>> both = Map.of(
                Direction.EAST, List.of(new ActivationTable.FaceEntry(CELL, 5)),
                Direction.WEST, List.of(new ActivationTable.FaceEntry(CELL, 12)));

        assertEquals(List.of(CELL), ledger.update(both, signals(Direction.EAST, Direction.WEST)),
                "one cell changed once, not twice");
        assertEquals(12, ledger.strengthAt(CELL), "the stronger push is the one that counts");
    }

    @Test
    @DisplayName("a cell pushed by two faces stays pushed when only the weaker face goes dark")
    void theStrongerFaceKeepsTheCell() {
        PocketInjection ledger = new PocketInjection();
        Map<Direction, List<ActivationTable.FaceEntry>> both = Map.of(
                Direction.EAST, List.of(new ActivationTable.FaceEntry(CELL, 5)),
                Direction.WEST, List.of(new ActivationTable.FaceEntry(CELL, 12)));
        ledger.update(both, signals(Direction.EAST, Direction.WEST));

        assertTrue(ledger.update(both, signals(Direction.WEST)).isEmpty(),
                "12 was the answer before and 12 is the answer now: nothing changed, nothing to notify");
        assertEquals(12, ledger.strengthAt(CELL));
    }

    @Test
    @DisplayName("an entry of strength 0 is legal in a table and injects nothing")
    void aZeroEntryIsNotAnInjection() {
        PocketInjection ledger = new PocketInjection();

        assertTrue(ledger.update(table(Direction.EAST, 0), signals(Direction.EAST)).isEmpty(),
                "0 means 'push nothing', so there is no power and no change");
        assertEquals(0, ledger.strengthAt(CELL));
        assertTrue(ledger.isEmpty(), "and the plot still counts as undriven, which is what the gate reads");
    }

    @Test
    @DisplayName("the same signals twice report nothing the second time")
    void nothingChangedMeansNothingToNotify() {
        PocketInjection ledger = new PocketInjection();
        ledger.update(table(Direction.EAST, 9), signals(Direction.EAST));

        assertTrue(ledger.update(table(Direction.EAST, 9), signals(Direction.EAST)).isEmpty(),
                "a steady face must not wake the pocket's redstone every tick");
        assertEquals(9, ledger.strengthAt(CELL), "and the power stays");
    }

    @Test
    @DisplayName("a strength that changes is a change")
    void aDifferentStrengthIsReported() {
        PocketInjection ledger = new PocketInjection();
        ledger.update(table(Direction.EAST, 9), signals(Direction.EAST));

        assertEquals(List.of(CELL), ledger.update(table(Direction.EAST, 4), signals(Direction.EAST)));
        assertEquals(4, ledger.strengthAt(CELL));
    }

    @Test
    @DisplayName("clear() forgets everything, so nothing outlives the plot")
    void clearForgetsEverything() {
        PocketInjection ledger = new PocketInjection();
        ledger.update(table(Direction.EAST, 9), signals(Direction.EAST));

        ledger.clear();

        assertTrue(ledger.isEmpty());
        assertEquals(0, ledger.strengthAt(CELL));
    }

    @Test
    @DisplayName("a pressed manual entry pushes, and one that is not pressed does not")
    void manualEntriesPushWhileTheyArePressed() {
        PocketInjection ledger = new PocketInjection();
        List<ActivationTable.ManualEntry> panel = List.of(
                new ActivationTable.ManualEntry("key.keyboard.g", CELL, 7, "one"),
                new ActivationTable.ManualEntry("key.keyboard.h", new BlockPos(4, 0, 0), 12, "two"));

        assertEquals(List.of(CELL), ledger.update(PocketInjection.Sources.manual(panel, java.util.Set.of(0))),
                "only the pressed entry's cell changed");
        assertEquals(7, ledger.strengthAt(CELL));
        assertEquals(0, ledger.strengthAt(new BlockPos(4, 0, 0)), "the unpressed entry pushes nothing");

        // Pressing the second one as well is a second cell, and then releasing both brings both down.
        assertEquals(List.of(new BlockPos(4, 0, 0)),
                ledger.update(PocketInjection.Sources.manual(panel, java.util.Set.of(0, 1))));
        assertEquals(12, ledger.strengthAt(new BlockPos(4, 0, 0)));

        List<BlockPos> released = ledger.update(PocketInjection.Sources.manual(panel, java.util.Set.of()));
        assertEquals(2, released.size(), "both cells have to be notified when the keys come up");
        assertTrue(ledger.isEmpty(), "and nothing is left pushing");
    }

    @Test
    @DisplayName("a key and a face naming one cell push as hard as the harder of them")
    void aKeyAndAFaceOnOneCell() {
        PocketInjection ledger = new PocketInjection();
        List<ActivationTable.ManualEntry> panel =
                List.of(new ActivationTable.ManualEntry("key.keyboard.g", CELL, 4, ""));
        Map<Direction, List<ActivationTable.FaceEntry>> face = table(Direction.EAST, 11);

        assertEquals(List.of(CELL), ledger.update(new PocketInjection.Sources(face, signals(Direction.EAST),
                panel, java.util.Set.of(0))));
        assertEquals(11, ledger.strengthAt(CELL), "the face is the stronger push");

        // Letting the key up while the face is still energized must not change the cell.
        assertTrue(ledger.update(new PocketInjection.Sources(face, signals(Direction.EAST), panel,
                java.util.Set.of())).isEmpty(), "the cell is still pushed, so there is nothing to notify");
        assertEquals(11, ledger.strengthAt(CELL));
    }

    @Test
    @DisplayName("a pressed entry the panel does not have is ignored, not followed")
    void aPressForAnEntryThatIsNotThereIsIgnored() {
        PocketInjection ledger = new PocketInjection();
        List<ActivationTable.ManualEntry> panel =
                List.of(new ActivationTable.ManualEntry("key.keyboard.g", CELL, 7, ""));

        assertTrue(ledger.update(PocketInjection.Sources.manual(panel, java.util.Set.of(5))).isEmpty(),
                "a hand-written packet naming entry 5 of a one-entry panel changes nothing");
        assertTrue(ledger.isEmpty());
    }

    /** One face's table with its signal on, in the form the rate-limited offer takes. */
    private static PocketInjection.Sources eastOn() {
        return PocketInjection.Sources.faces(table(Direction.EAST, 9), signals(Direction.EAST));
    }

    /** The same table with the face dark again. */
    private static PocketInjection.Sources eastOff() {
        return PocketInjection.Sources.faces(table(Direction.EAST, 9), signals());
    }

    @Test
    @DisplayName("a cell cannot be driven twice inside the limit: the second change waits its turn")
    void aCellCannotBeDrivenTwiceInsideTheLimit() {
        // Two edges closer together than a machine can act on are what strand a piston's moving block, and that
        // block cannot be pushed - so the machine is dead while the picture still says it is fine. The limit is on
        // the cell, which is the only layer that both inputs pass through.
        PocketInjection ledger = new PocketInjection();
        assertEquals(List.of(CELL), ledger.update(eastOn(), 100, 4),
                "the first change is applied at once: there is no window to wait for yet");

        assertEquals(List.of(), ledger.update(eastOff(), 101, 4),
                "a release one tick later is inside the window, so nothing moves yet");
        assertEquals(9, ledger.strengthAt(CELL), "the cell keeps what it was driven with");
        assertTrue(ledger.hasWaiting(), "and what it is owed is remembered");

        assertEquals(List.of(CELL), ledger.update(eastOff(), 104, 4),
                "four ticks after the change the window is open and the owed release goes through");
        assertEquals(0, ledger.strengthAt(CELL), "so the cell is dark again");
        assertFalse(ledger.hasWaiting(), "and nothing is owed any more");
    }

    @Test
    @DisplayName("a pulse shorter than the window is delivered, not thinned away")
    void aFastInputIsHeldNotThinned() {
        PocketInjection ledger = new PocketInjection();
        assertEquals(List.of(CELL), ledger.update(eastOn(), 100, 4), "the first change is applied at once");
        assertEquals(List.of(), ledger.update(eastOff(), 101, 4), "a release one tick later waits its turn");
        assertEquals(List.of(), ledger.update(eastOn(), 102, 4),
                "and the edge back up does not cancel it: a release dropped as 'overtaken by the value the cell "
                        + "already holds' would latch the cell and the structure inside would stop reacting");
        assertTrue(ledger.hasWaiting(),
                "the owed value is still owed: a source that passes through the cell's current value on its way "
                        + "back is not an instruction to forget what is waiting");
        assertEquals(9, ledger.strengthAt(CELL), "the cell keeps the value it was given while its window is closed");

        assertEquals(List.of(), ledger.update(eastOff(), 103, 4), "still inside the window");
        assertEquals(List.of(CELL), ledger.update(eastOff(), 104, 4),
                "and the owed release goes through the moment the window opens, so the pulse reaches the world");
        assertEquals(0, ledger.strengthAt(CELL), "the cell ends up dark, which is where its source was going");
        assertFalse(ledger.hasWaiting(), "with nothing owed any more");
    }

    @Test
    @DisplayName("with the limit off, the ledger rule is the whole rule")
    void theLimitCanBeTurnedOff() {
        PocketInjection ledger = new PocketInjection();
        assertEquals(List.of(CELL), ledger.update(eastOn(), 100, 0), "the first change goes through");
        assertEquals(List.of(CELL), ledger.update(eastOff(), 100, 0),
                "and with no limit the next offer is applied at once, however close behind it came");
        assertEquals(0, ledger.strengthAt(CELL));
        assertFalse(ledger.hasWaiting());
    }
}
