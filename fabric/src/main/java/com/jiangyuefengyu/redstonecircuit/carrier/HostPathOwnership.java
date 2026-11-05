package com.jiangyuefengyu.redstonecircuit.carrier;

/**
 * Which of the two drawing paths owns a world position, decided from two observable facts and nothing else.
 *
 * <p>The two facts are {@code worldHost} - the client's ordinary host sync holds a component for this position, so this
 * client believes something belongs there - and {@code blockPresent} - the block at that position is <b>not air</b>. The
 * world renderer refuses to draw a shell in mid-air, so it draws a position only when both hold; the carried path (a
 * carried host, a landed record, a stale-carrier fallback) has to make the <em>opposite</em> decision for the same
 * position, and when the two decisions are written in two different places a position can end up drawn by neither - the
 * frame window in which the host sync has arrived but the block has not is exactly that.
 *
 * <p>So the question is asked once, here, and answered as a <b>two-valued decision</b> rather than as one predicate and
 * its unwritten negation: {@link #owner} returns {@link Owner#WORLD_PATH} or {@link Owner#CARRIED_PATH}, and there is no
 * third answer. A position therefore cannot be owned by both paths (two shells on one block) and cannot be owned by
 * neither, by construction rather than by two call sites agreeing with each other.
 *
 * <p>No level, no block state, no position: two booleans in, one enum out, so the rule is pinned by unit tests with no
 * game running.
 *
 * <p><b>One extension, and it is not a third owner.</b> A world position can hold a host record with no block in it
 * (a record that outlived its block, or a carried host announced where it is drawn), and {@link #drawsHere} is the
 * same decision with that state in it: the two-fact rule first, and the record drawn by the world path only where the
 * carried path draws nothing. {@link #owner} stays two-valued, because the ownership question is still "which of the
 * two paths", not "is this position drawn".
 */
public final class HostPathOwnership {

    /**
     * Which path draws a position. Two values, with no "neither".
     */
    public enum Owner {

        /**
         * The ordinary world path draws it: a host the client knows about, over a block that is there.
         *
         * <p>The carried path must not draw it as well - a shell over a shell is a visibly doubled
         * block, which is a worse symptom than the one this class exists to prevent.
         */
        WORLD_PATH,

        /**
         * The carried path draws it: inside its carrier's pose when there is one, or at its fallback
         * position when there is not.
         *
         * <p>This is the answer whenever the world path cannot draw - including the frame window in
         * which the host sync has arrived but the block has not.
         */
        CARRIED_PATH
    }

    private HostPathOwnership() {
    }

    /** Whether the ordinary world path draws a position at all: a known host <b>and</b> a block standing there. */
    public static boolean worldPathDraws(boolean worldHost, boolean blockPresent) {
        return worldHost && blockPresent;
    }

    /** Which path owns a position: {@link Owner#WORLD_PATH} when the world path draws there, the carried path otherwise. */
    public static Owner owner(boolean worldHost, boolean blockPresent) {
        return worldPathDraws(worldHost, blockPresent) ? Owner.WORLD_PATH : Owner.CARRIED_PATH;
    }

    /**
     * Whether a carried or landed candidate must draw itself rather than yield.
     *
     * <p>The exact complement of {@link #worldPathDraws}, written in terms of it so the two can never drift apart.
     */
    public static boolean fallbackDraws(boolean worldHost, boolean blockPresent) {
        return !worldPathDraws(worldHost, blockPresent);
    }

    /**
     * <b>The third state a world position can be in: a host record whose block is air.</b>
     *
     * <p>Two supported things put a host record at a cell the world has no block in, and neither is a fault:
     *
     * <ul>
     *   <li><b>a carried host, announced where it is drawn.</b> Its block is inside the structure, so the world
     *       has air there by construction - the carrier announces a host at the <em>projected</em> position on
     *       purpose, because neighbour wiring reads the cell. That one is drawn by the carried path.</li>
     *   <li><b>a record that outlived its block.</b> The world store's records leave only through a break
     *       event, a piston's own transfer and a capture's cut, so a block removed any other way (an explosion,
     *       a command, a structure taking it) leaves its component behind - and the store is {@code SavedData},
     *       so the record is read back on the next load and survives. Until this method existed such a
     *       component was drawn by nobody: the world path refused it for want of a block, and no carried
     *       candidate existed to take it instead.</li>
     * </ul>
     *
     * <p><b>Why this cannot draw anything twice.</b> The ordinary two-fact answer ({@link #worldPathDraws}) is
     * asked first and is unchanged, so every position drawn today is drawn by the same path as today. Only when
     * that answer is no does the third state apply, and then only when <em>no carried candidate draws here</em>
     * - {@code carriedDrawsHere} is that answer, asked of the carrier index by
     * {@code ClientHostCache#carriedPathDrawsHere}. The two paths therefore stay exact complements: a position
     * a carried host is drawn at remains the carried path's, and a position holding nothing but a record
     * becomes the world path's. A position is still drawn by one path or the other, never both and never
     * neither.
     *
     * <p>{@code loaded} has to be its own argument rather than folded into {@code blockPresent}, because the
     * case this method adds is reached with no block to ask: a chunk the client has not got has nothing to
     * draw, and a level must not be asked about a position it never sent. It was folded in before, which is
     * why the old rule happened to be right on an unloaded chunk; here it is stated.
     *
     * @param worldHost the client's host sync holds a component for this position
     * @param blockPresent a block stands at the position (it is not air)
     * @param carriedDrawsHere the carried path draws a host at this same position
     * @param loaded the client has this position's chunk
     */
    public static boolean drawsHere(boolean worldHost, boolean blockPresent, boolean carriedDrawsHere,
                                    boolean loaded) {
        if (!loaded) {
            return false;
        }
        if (worldPathDraws(worldHost, blockPresent)) {
            return true;
        }
        return worldHost && !carriedDrawsHere;
    }
}
