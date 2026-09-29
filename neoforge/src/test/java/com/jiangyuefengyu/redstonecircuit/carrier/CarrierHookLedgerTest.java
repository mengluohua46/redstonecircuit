package com.jiangyuefengyu.redstonecircuit.carrier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>The Create-side carrier-tool hook registration ledger, asserted offline.</b>
 *
 * <h2>The two facts it has to keep apart</h2>
 * "The wrench does nothing on a physicalised block" has two causes on a contraption, and the
 * registration count alone cannot tell them apart:
 *
 * <ul>
 *   <li>the block type was <b>never attempted</b> - it has never held a component;</li>
 *   <li>the block type was <b>refused</b> - another mod already registered a
 *       {@code MovingInteractionBehaviour} for it, and the first-wins rule left it alone.</li>
 * </ul>
 *
 * <p>{@code CarrierToolHook#registeredCount()} answers the first question (it counts attempts, because a
 * refused type is deliberately never retried). This ledger answers the second, and the assertions here
 * are that the refusal is <em>kept</em>, that it says {@code not hooking ...} so a grep from the log and a
 * line of the report are the same words, and that neither list can grow without a bound.
 */
class CarrierHookLedgerTest {

    @Test
    @DisplayName("an accepted and a refused block type are counted separately")
    void acceptedAndRefusedAreCountedSeparately() {
        CarrierHookLedger ledger = new CarrierHookLedger();

        ledger.hook("minecraft:oak_planks");
        ledger.hook("minecraft:stone");
        ledger.refuse("minecraft:iron_block", "another MovingInteractionBehaviour is already registered");

        assertEquals(2, ledger.hookedCount());
        assertEquals(1, ledger.refusedCount());
        assertEquals(List.of("minecraft:oak_planks", "minecraft:stone"), ledger.hookedBlocks());
        assertEquals(1, ledger.refusals().size());
        assertTrue(ledger.refusals().get(0).startsWith("not hooking minecraft:iron_block"),
                "the same words the log line uses: " + ledger.refusals().get(0));
        assertTrue(ledger.refusals().get(0).contains("MovingInteractionBehaviour"),
                "and the reason, so the report says which mod's behaviour won");
    }

    @Test
    @DisplayName("the report says the count is attempts, so the two numbers cannot be read as one")
    void theReportExplainsTheDifference() {
        CarrierHookLedger ledger = new CarrierHookLedger();
        ledger.hook("minecraft:oak_planks");
        ledger.refuse("minecraft:iron_block", "somebody else owns it");

        List<String> lines = ledger.describe();
        assertFalse(lines.isEmpty());
        assertTrue(lines.get(0).contains("hooked=1 refused=1"), lines.get(0));
        assertTrue(lines.get(0).contains("equal only when nothing was refused"),
                "the number a reader already has and this one are not the same question: " + lines.get(0));
        assertTrue(lines.stream().anyMatch(line -> line.startsWith("    not hooking minecraft:iron_block")),
                "the refusal is one of the printed lines: " + lines);
        assertTrue(lines.stream().anyMatch(line -> line.contains("CREATE CONTRAPTION")),
                "and it says what the consequence is, and where it does NOT apply: " + lines);
    }

    @Test
    @DisplayName("nothing refused means no refusal output at all")
    void nothingRefusedPrintsNothingExtra() {
        CarrierHookLedger ledger = new CarrierHookLedger();
        ledger.hook("minecraft:oak_planks");

        List<String> lines = ledger.describe();
        assertEquals(2, lines.size(), "the breakdown and the hooked list, and nothing else: " + lines);
        assertTrue(lines.get(0).contains("refused=0"), lines.get(0));
        assertFalse(lines.stream().anyMatch(line -> line.contains("not hooking")), lines.toString());
    }

    @Test
    @DisplayName("both lists are bounded, and the totals survive the truncation")
    void bothListsAreBounded() {
        CarrierHookLedger ledger = new CarrierHookLedger();
        int cap = CarrierHookLedger.LIMIT;
        for (int i = 0; i < cap * 3; i++) {
            ledger.hook("block:hooked_" + i);
            ledger.refuse("block:refused_" + i, "first wins");
        }

        assertEquals(cap * 3, ledger.hookedCount(), "the total counts everything that happened");
        assertEquals(cap * 3, ledger.refusedCount());
        assertEquals(cap, ledger.hookedBlocks().size(), "the printed list holds the ceiling");
        assertEquals(cap, ledger.refusals().size());
        assertTrue(ledger.hookedBlocks().get(0).endsWith("hooked_" + (cap * 3 - cap)),
                "the oldest entries are the ones dropped: " + ledger.hookedBlocks().get(0));

        String report = String.join("\n", ledger.describe());
        assertTrue(report.contains("...+" + (cap * 3 - cap) + " more refusal(s)"),
                "the truncation is a count, never more lines: " + report);
    }

    @Test
    @DisplayName("clear forgets everything, so one test cannot leak into the next")
    void clearForgetsEverything() {
        CarrierHookLedger ledger = new CarrierHookLedger();
        ledger.hook("minecraft:oak_planks");
        ledger.refuse("minecraft:iron_block", "somebody else owns it");
        ledger.clear();

        assertEquals(0, ledger.hookedCount());
        assertEquals(0, ledger.refusedCount());
        assertTrue(ledger.hookedBlocks().isEmpty());
        assertTrue(ledger.refusals().isEmpty());
        assertEquals(1, ledger.describe().size(), "only the zero breakdown is left");
    }
}
