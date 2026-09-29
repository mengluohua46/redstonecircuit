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
 * <h2>The defect these tests pin down</h2>
 * A structure puts a component down. The server broadcasts the world host at once; Create puts the
 * block back a moment later. So there are frames in which the client holds a world host over air. The
 * world renderer refuses to draw a shell in mid-air, and the carried path used to let its landed
 * record go on the strength of the host broadcast alone - <b>neither path drew the component</b>, and
 * the frame reported {@code drew 0} with every skip counter at zero, which is the shape that made this
 * defect take a round of live testing to find.
 *
 * <h2>What has to hold</h2>
 * <ul>
 *   <li>the world path draws a position only with <b>both</b> a host the sync knows about <b>and</b> a
 *       block standing there - the same two facts the world renderer itself uses;</li>
 *   <li>a landed or carried candidate draws itself <b>exactly</b> when the world path does not, so
 *       every combination is owned by one path: never both (two shells on one block), never neither
 *       (the invisible window above);</li>
 *   <li>the ambiguous-looking case - a world host announced, the block not back yet - resolves to the
 *       carried path, because the world path cannot draw and something must.</li>
 * </ul>
 *
 * <p>Pure logic, so no game, no level and no block state is needed: two booleans in, one answer out.
 * The renderer and the client cache both call this class, which is what keeps their two answers from
 * drifting.
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
        // The one line the whole round of work is about. Before the fix this combination was claimed by
        // the world path (the host broadcast had arrived) while that path was refusing to draw (no
        // block), and the landed record had already yielded - so nothing was drawn. It is now the
        // carried path's, by construction.
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
        // A block standing where the component belongs, while this client's host sync has nothing for
        // that position - the component is only in the carried record. The world path has no slot to
        // draw, so the record draws itself. (When a block is broken the server removes the host too;
        // this is the state before that removal arrives.)
        assertEquals(HostPathOwnership.Owner.CARRIED_PATH, HostPathOwnership.owner(false, true));
        assertTrue(HostPathOwnership.fallbackDraws(false, true));
    }

    @Test
    @DisplayName("no host and no block is the carried path's, as it always was")
    void nothingAtAllIsStillTheCarriedPaths() {
        // The ordinary put-down case: the landing position is bare world. The world path has nothing to
        // draw and the landed record is the only thing holding the component.
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
    @DisplayName("an Owner has two values and no third: 'neither path' is not representable")
    void thereIsNoNeither() {
        assertEquals(2, HostPathOwnership.Owner.values().length,
                "a third value would be a way to say 'nothing draws this', which is the defect");
    }
}
