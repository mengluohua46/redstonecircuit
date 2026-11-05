package com.jiangyuefengyu.redstonecircuit.carrier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests the rule that decides <b>which of the two drawing paths owns a world position</b>.
 *
 * <p>A structure puts a component down: the server broadcasts the world host at once, Create puts the block back a
 * moment later, so there are frames in which the client holds a world host over air. The world renderer refuses to
 * draw a shell in mid-air, so if the carried path also let its landed record go on the strength of the host broadcast
 * alone, <b>neither path would draw the component</b> - a frame reporting {@code drew 0} with every skip counter at
 * zero.
 *
 * <p>What has to hold: the world path draws a position only with <b>both</b> a host the sync knows about <b>and</b> a
 * block standing there; a landed or carried candidate draws itself <b>exactly</b> when the world path does not, so
 * every combination is owned by one path - never both (two shells on one block), never neither; and the ambiguous
 * case (a world host announced, the block not back yet) resolves to the carried path, because the world path cannot
 * draw and something must.
 *
 * <p>Pure logic: two booleans in, one answer out. The renderer and the client cache both call this class, which is
 * what keeps their two answers from drifting.
 */
class HostPathOwnershipTest {

    @Test
    @DisplayName("the world path draws only with a host AND a block - neither fact alone is enough")
    void theWorldPathNeedsBothFacts() {
        assertTrue(HostPathOwnership.worldPathDraws(true, true),
                "a host the sync knows about over a real block: the world path draws it");
        assertFalse(HostPathOwnership.worldPathDraws(true, false),
                "the sync has announced it but the block has not come back yet: the world path refuses");
        assertFalse(HostPathOwnership.worldPathDraws(false, true),
                "a block with no component of ours in it is not a host at all");
        assertFalse(HostPathOwnership.worldPathDraws(false, false), "nothing to draw there either way");
    }

    @Test
    @DisplayName("a world host with AIR underneath is owned by the carried path, not by neither")
    void aWorldHostOverAirIsDrawnByTheCarriedPath() {
        // Claiming this combination for the world path - which refuses to draw without a block - while the
        // landed record yields is what would leave nothing drawn; it belongs to the carried path instead.
        assertEquals(HostPathOwnership.Owner.CARRIED_PATH,
                HostPathOwnership.owner(true, false));
        assertTrue(HostPathOwnership.fallbackDraws(true, false),
                "the record must draw itself: it is the only path left");
    }

    @Test
    @DisplayName("host and block together are owned by the world path, and the record yields")
    void hostAndBlockAreOwnedByTheWorldPath() {
        assertEquals(HostPathOwnership.Owner.WORLD_PATH, HostPathOwnership.owner(true, true));
        assertFalse(HostPathOwnership.fallbackDraws(true, true),
                "yielding here is what keeps a second shell off the same block");
    }

    @Test
    @DisplayName("a block with no world host is still the carried path's: the world path has no component")
    void aBlockWithoutAWorldHostIsTheCarriedPaths() {
        // A block standing where the component belongs while this client's host sync has nothing for that
        // position: the component is only in the carried record, so the record draws itself.
        assertEquals(HostPathOwnership.Owner.CARRIED_PATH, HostPathOwnership.owner(false, true));
        assertTrue(HostPathOwnership.fallbackDraws(false, true));
    }

    @Test
    @DisplayName("no host and no block is the carried path's")
    void nothingAtAllIsStillTheCarriedPaths() {
        // The ordinary put-down case: the landing position is bare world, and the landed record is the only
        // thing holding the component.
        assertEquals(HostPathOwnership.Owner.CARRIED_PATH, HostPathOwnership.owner(false, false));
        assertTrue(HostPathOwnership.fallbackDraws(false, false));
    }

    @Test
    @DisplayName("every combination is owned by exactly one path - the two answers are exhaustive")
    void everyCombinationIsOwnedExactlyOnce() {
        for (boolean worldHost : new boolean[] {true, false}) {
            for (boolean blockPresent : new boolean[] {true, false}) {
                boolean world = HostPathOwnership.worldPathDraws(worldHost, blockPresent);
                boolean carried = HostPathOwnership.fallbackDraws(worldHost, blockPresent);
                HostPathOwnership.Owner owner = HostPathOwnership.owner(worldHost, blockPresent);

                String where = "host=" + worldHost + " block=" + blockPresent;
                assertNotEquals(world, carried, "exactly one path: " + where);
                assertTrue(world || carried, "and one of them always draws: " + where);
                assertFalse(world && carried, "and never both: " + where);
                assertEquals(world ? HostPathOwnership.Owner.WORLD_PATH
                                : HostPathOwnership.Owner.CARRIED_PATH,
                        owner, "the decision and the two predicates must agree: " + where);
            }
        }
    }

    @Test
    @DisplayName("a record with no block is drawn by the world path - unless the carried path draws that position")
    void aRecordWithNoBlockDrawsUnlessACarriedRecordDoes() {
        // THE STATE THIS METHOD WAS ADDED FOR. Before `drawsHere`, "a host record with no block" was drawn by
        // nobody: `worldPathDraws(true, false)` is false, and the carried path has no candidate for a record no
        // structure holds. A record CAN outlive its block - the store is SavedData and its only removal paths are
        // a break event, a piston's own transfer and a capture's cut - so the component was invisible for ever.
        assertTrue(HostPathOwnership.drawsHere(true, false, false, true),
                "a record whose block is air, with no carried candidate at that position, is drawn");

        assertFalse(HostPathOwnership.drawsHere(true, false, true, true),
                "and NOT when the carried path is already drawing that position: two shells on one cell is the "
                        + "other defect, so a carried host keeps the position it is projected onto");
        assertTrue(HostPathOwnership.drawsHere(true, true, true, true),
                "an ordinary host over a real block is unchanged, carried candidate or not");
        assertFalse(HostPathOwnership.drawsHere(false, false, false, true),
                "no record at all is still nothing to draw");
        assertFalse(HostPathOwnership.drawsHere(true, false, false, false),
                "and nothing is drawn in a chunk the client has not got: the blockless case is exactly the one "
                        + "with no block read to fail on, so 'loaded' has to be its own argument");
    }

    @Test
    @DisplayName("the two-fact rule itself is unchanged, and drawsHere differs from it in exactly one combination")
    void drawsHereMatchesTheOldRuleEverywhereButTheBlocklessCombination() {
        // 1. THE OLD RULE, ROW BY ROW, PLUS ITS COMPLEMENT. Not "drawsHere equals the old rule" - that is false by
        // construction, because the blockless state IS the change. What has to stay put is the predicate every
        // existing caller reads: the world path's own two facts, and the complement the carried path yields on.
        assertTrue(HostPathOwnership.worldPathDraws(true, true), "a known host over a real block: drawn");
        assertFalse(HostPathOwnership.worldPathDraws(true, false),
                "a host with air under it: this rule still refuses, and that is what makes the change a change");
        assertFalse(HostPathOwnership.worldPathDraws(false, true), "a block with no component of ours");
        assertFalse(HostPathOwnership.worldPathDraws(false, false), "nothing to draw either way");
        for (boolean worldHost : new boolean[] {true, false}) {
            for (boolean blockPresent : new boolean[] {true, false}) {
                assertNotEquals(HostPathOwnership.worldPathDraws(worldHost, blockPresent),
                        HostPathOwnership.fallbackDraws(worldHost, blockPresent),
                        "the complement the carried path yields on is still exact: host=" + worldHost
                                + " block=" + blockPresent);
            }
        }

        // 2. `drawsHere` IS THE OLD RULE EVERYWHERE EXCEPT THE ONE COMBINATION THIS FEATURE IS: a record with air
        // under it, in a chunk the client has. Three of the four (worldHost, blockPresent) rows answer exactly as
        // the old rule does for both values of `carriedDrawsHere`, and the fourth is decided by that argument
        // alone - nothing else in the space moved.
        for (boolean worldHost : new boolean[] {true, false}) {
            for (boolean blockPresent : new boolean[] {true, false}) {
                for (boolean carriedDrawsHere : new boolean[] {true, false}) {
                    String where = "host=" + worldHost + " block=" + blockPresent
                            + " carried=" + carriedDrawsHere;
                    if (worldHost && !blockPresent) {
                        // THE ONE ROW THAT CHANGED, both ways round: drawn when no carried candidate draws
                        // here, and left to the carried path when one does.
                        assertEquals(!carriedDrawsHere, HostPathOwnership.drawsHere(worldHost, blockPresent,
                                        carriedDrawsHere, true),
                                "a record with no block is drawn unless a carried candidate draws that "
                                        + "position: " + where);
                    } else {
                        assertEquals(HostPathOwnership.worldPathDraws(worldHost, blockPresent),
                                HostPathOwnership.drawsHere(worldHost, blockPresent, carriedDrawsHere, true),
                                "every other combination is the old rule, unchanged: " + where);
                    }
                    // And no combination is drawn in a chunk the client has not got. The row above is exactly the
                    // one with no block left to read, which is why `loaded` is its own argument and not folded
                    // into `blockPresent`: an unloaded chunk answers "air" for everything, so folding them would
                    // draw the whole of a chunk the client was never sent.
                    assertFalse(HostPathOwnership.drawsHere(worldHost, blockPresent, carriedDrawsHere, false),
                            "an unloaded chunk draws nothing, whatever the other three facts say: " + where);
                }
            }
        }
    }

    @Test
    @DisplayName("an Owner has two values and no third: 'neither path' is not representable")
    void thereIsNoNeither() {
        assertEquals(2, HostPathOwnership.Owner.values().length,
                "a third value would be a way to say 'nothing draws this', which is the defect");
    }
}
