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
}
