package com.jiangyuefengyu.redstonecircuit.carrier;

/**
 * <b>Which of the two drawing paths owns a world position</b>, decided from two observable facts and
 * nothing else.
 *
 * <h2>The two facts</h2>
 * <ul>
 *   <li>{@code worldHost} - the client's ordinary host sync holds a component for this position, so
 *       this client believes something belongs there;</li>
 *   <li>{@code blockPresent} - the block at that position is <b>not air</b>.</li>
 * </ul>
 *
 * <h2>Why both, and why this is one class rather than two checks</h2>
 * The world renderer refuses to draw a shell in mid-air, so it draws a position only when both facts
 * hold. The carried path (a carried host, a landed record, a stale-carrier fallback) has to make the
 * <em>opposite</em> decision for the same position, and the two decisions used to be written in two
 * different places: the world path asked both questions, the carried path asked only "is a block
 * standing there". The gap between those two questions is a whole class of bug, and it is the one the
 * author hit:
 *
 * <blockquote>
 * a structure puts a component down. The server broadcasts the world host <b>immediately</b>; Create
 * puts the block back a moment later. For those frames the client holds a world host over air. The
 * world path refused to draw (no block) and the carried path had already let the record go (there was
 * a block? no - there was a <em>world host</em>) - so <b>neither path drew the component</b>, and no
 * skip counter explained it because the skip counters counted the facts each path happened to look at.
 * </blockquote>
 *
 * <p>So the question is asked once, here, and answered as a <b>two-valued decision</b> rather than as
 * one predicate and its unwritten negation: {@link #owner} returns {@link Owner#WORLD_PATH} or
 * {@link Owner#CARRIED_PATH}, and there is no third answer. That is the whole safety argument - a
 * position cannot be owned by both paths (which would draw two shells on one block) and cannot be
 * owned by neither (which would draw nothing at all), because the two cases are exhaustive by
 * construction rather than by two call sites agreeing with each other.
 *
 * <h2>Purity</h2>
 * No level, no block state, no position: two booleans in, one enum out. The renderer supplies the
 * booleans from the live level and this decides; the rule itself is therefore pinned by unit tests
 * with no game running.
 */
public final class HostPathOwnership {

    /**
     * Which path draws a position. Two values, deliberately: there is no "neither".
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
         * which the host sync has arrived but the block has not, which is the window that used to be
         * invisible.
         */
        CARRIED_PATH
    }

    private HostPathOwnership() {
    }

    /**
     * Whether the ordinary world path draws a position at all.
     *
     * @param worldHost the client's ordinary host sync holds a component for this position
     * @param blockPresent the block at this position is present, i.e. not air
     */
    public static boolean worldPathDraws(boolean worldHost, boolean blockPresent) {
        return worldHost && blockPresent;
    }

    /**
     * Which path owns a position.
     *
     * @param worldHost the client's ordinary host sync holds a component for this position
     * @param blockPresent the block at this position is present, i.e. not air
     * @return {@link Owner#WORLD_PATH} when the world path draws there, {@link Owner#CARRIED_PATH}
     *     otherwise
     */
    public static Owner owner(boolean worldHost, boolean blockPresent) {
        return worldPathDraws(worldHost, blockPresent) ? Owner.WORLD_PATH : Owner.CARRIED_PATH;
    }

    /**
     * Whether a carried or landed candidate must draw itself rather than yield.
     *
     * <p>The exact complement of {@link #worldPathDraws} - and written in terms of it, so the two can
     * never drift apart.
     *
     * @param worldHost the client's ordinary host sync holds a component for this position
     * @param blockPresent the block at this position is present, i.e. not air
     * @return true when this candidate must draw itself
     */
    public static boolean fallbackDraws(boolean worldHost, boolean blockPresent) {
        return !worldPathDraws(worldHost, blockPresent);
    }
}
