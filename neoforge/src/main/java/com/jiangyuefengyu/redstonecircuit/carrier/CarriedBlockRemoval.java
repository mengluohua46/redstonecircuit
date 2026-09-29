package com.jiangyuefengyu.redstonecircuit.carrier;

import java.util.List;

import com.jiangyuefengyu.redstonecircuit.data.Slot;

import net.minecraft.core.BlockPos;

import org.jetbrains.annotations.Nullable;

/**
 * <b>The decisions behind "a block a structure is carrying has been broken", as pure functions.</b>
 *
 * <h2>The defect</h2>
 * Breaking a host block standing in the world drops the component inside it - that has always been the
 * {@code BlockEvent.BreakEvent} path in {@code InnerRedstoneInteraction#cleanup}. Breaking a host block
 * a structure is <em>carrying</em> dropped nothing: the block is removed by whoever owns that structure's
 * blocks (a Sable plot, a Create contraption), our own record of the component kept riding it, and the
 * only thing that would ever have happened to it is a release that writes it back into a block that no
 * longer exists.
 *
 * <h2>Why the policy is here and the level work is not</h2>
 * The part that can be wrong in a way nobody sees is the <em>decision</em>: which of the two paths owns
 * a break, and what "the same host" means. That is arithmetic over the index, so it lives here - no
 * level, no packet, no Minecraft type beyond the coordinates - and the unit tests pin it. Dropping the
 * item, telling the client and marking the level dirty need a running server, so those live in
 * {@link CarrierStore#dropDestroyedHost}, which asks this class first.
 *
 * <h2>P1.20: why "the structure's own view says the block is gone" is not enough on the break path</h2>
 * The first version of this class accepted a break only when the structure's own block view answered
 * <em>air</em> at the host's local coordinate. That is the right test for a self-healing poll and the
 * wrong one for a break event: <b>a break event is delivered before the block is removed</b> - it is the
 * vanilla "this block is about to go" callback, and it is cancellable for exactly that reason - so at the
 * moment the event arrives the structure's view still reports the block that is being broken. The check
 * therefore answered "the block is still there" for the one click that mattered, the record was never
 * consumed, nothing dropped, and the client kept drawing the component inside a block that had already
 * gone: the author's "the first time it does not drop, and the outline and the components stay visible".
 * The second attempt worked because by then the block really was air and the world's own break path had
 * taken over.
 *
 * <p>So the two callers ask for different things, and {@link ViewRequirement} is that difference:
 *
 * <ul>
 *   <li><b>a break event</b> already knows the host - the position is one the projection table answers
 *       for, i.e. a coordinate P4 computed from this very carrier's pose and local coordinate - and does
 *       not need the view to confirm it ({@link ViewRequirement#PROJECTION_IS_PROOF});</li>
 *   <li><b>the self-healing sweep</b> has no event at all and must never act on a guess, so it requires
 *       the structure's own view to say the block is gone ({@link ViewRequirement#VIEW_MUST_SAY_GONE}).</li>
 * </ul>
 *
 * <p>Both still refuse a landed record and a position the world's own store holds, so a release, a
 * landing and a break of an ordinary world host are all somebody else's business - exactly as before.
 */
public final class CarriedBlockRemoval {

    /**
     * One carried host whose block has been destroyed, and where its component has to appear.
     *
     * @param carrier the structure that was carrying it
     * @param localPos its coordinate inside that structure - the coordinate the record is keyed by
     * @param dropPos where the item is dropped: the world position the block was at, so it lands where
     *     the player broke it rather than at the structure's storage coordinates
     * @param worldPos where that local coordinate currently is in the world
     * @param slot the component that was inside the block
     */
    public record Destroyed(CarrierId carrier, BlockPos localPos, BlockPos dropPos, BlockPos worldPos,
                            Slot slot) {

        /** One line for the log: which structure, which coordinate, what, and where it landed. */
        public String describe() {
            return carrier.shortId() + " local=" + localPos.toShortString()
                    + " type=" + (slot == null ? "-" : slot.type)
                    + " droppedAt=" + dropPos.toShortString();
        }
    }

    /**
     * One click or break resolved to a host a structure is carrying.
     *
     * <p>What {@link CarrierStore#hitAt} answers, and what the destroy path and the carrier-side tool
     * entry point both consume - so "which host is this click about" has exactly one implementation.
     *
     * @param carrier the structure carrying it
     * @param localPos its coordinate inside that structure
     * @param worldPos where it currently is in the world - the position the player acted on
     * @param pose the carrier's pose at resolution time; where an item is dropped and where a highlight
     *     is drawn are both computed from it
     * @param projected true when the <b>projection table</b> answered - i.e. this coordinate was computed
     *     from this carrier's own pose and its own local coordinate, which is positive identification;
     *     false when the structure's own block storage had to be searched instead
     */
    public record Hit(CarrierId carrier, BlockPos localPos, BlockPos worldPos, CarrierPose pose,
                      boolean projected) {

        /** One line for the log. */
        public String describe() {
            return carrier.shortId() + " local=" + localPos.toShortString()
                    + " world=" + worldPos.toShortString()
                    + " via=" + (projected ? "projection" : "structure blocks");
        }
    }

    /**
     * What the structure's own view of one local coordinate answered.
     *
     * <p>Three answers rather than a boolean, because "I could not read it" and "it is not there" must
     * lead to different behaviour: a component is never dropped, and a record is never forgotten, on the
     * strength of a view that could not be read at all.
     */
    public enum StructureViewState {
        /** The structure's view says a block is there. */
        PRESENT,
        /** The structure's view says that coordinate is air: the block has been removed. */
        GONE,
        /** The view could not be resolved or threw: <b>never</b> read as "gone". */
        UNREADABLE
    }

    /** How much evidence a caller needs before a candidate is accepted. */
    public enum ViewRequirement {
        /**
         * The caller already knows the host: the position came from a break event at a coordinate the
         * projection table answers for. The structure's view does not have to agree, because at
         * {@code BreakEvent} time it legitimately cannot - the block is still there when the event fires,
         * which is the whole of the defect this enumeration exists for.
         */
        PROJECTION_IS_PROOF,
        /**
         * The caller has no event and must not act on a guess: the structure's own view has to say the
         * block is gone. {@code UNREADABLE} answers "do nothing" for ever, which is the only safe reading
         * of "I cannot tell".
         */
        VIEW_MUST_SAY_GONE
    }

    private CarriedBlockRemoval() {
    }

    /**
     * <b>The one rule that keeps the two drop paths apart.</b>
     *
     * <p>A break is the carrier layer's business exactly when the block's component was <b>not</b> in the
     * world store (it was riding) and the index really holds a <em>live</em> record for it. Both halves
     * matter:
     *
     * <ul>
     *   <li>a position whose component is still in the world store belongs to the ordinary
     *       {@code BreakEvent} path, which drops it - dropping it here too would be two items for one
     *       block, and it is also the case a <em>released</em> host falls into;</li>
     *   <li>a record that has been put down ({@code landed}) is a world host again - the release wrote the
     *       component back - so it is the world store that owns it, whether or not the store read happened
     *       to answer in the same tick.</li>
     * </ul>
     *
     * <p>Deliberately a pure predicate over the two things it is given: the production caller passes the
     * live store read and the index lookup, and the unit test passes the same two values directly, so the
     * test exercises the code that actually runs.
     *
     * @param storedInTheWorld what the level's own store holds at the broken position, or {@code null}
     * @param carried the index's record for that position's local coordinate, or {@code null}
     */
    public static boolean ownsDestruction(@Nullable Slot storedInTheWorld,
                                          @Nullable CarriedHost carried) {
        return storedInTheWorld == null && carried != null && !carried.landed();
    }

    /**
     * <b>Whether what the structure's own view answered is enough to act on.</b>
     *
     * <p>The pure half of {@link CarrierStore#dropDestroyedHost}, and the reason the break path and the
     * self-healing sweep can share one write-back while asking different questions. Kept here - with no
     * level, no packet and no index - so both behaviours are pinned by offline unit tests.
     *
     * @param view what the structure's own block view answered for the host's local coordinate
     * @param requirement how much evidence the caller demands
     * @return true when the record may be consumed and its component dropped
     */
    public static boolean accepts(@Nullable StructureViewState view, ViewRequirement requirement) {
        if (requirement == null) {
            return false;
        }
        return switch (requirement) {
            // The projection table put this host at this world position, and the player broke exactly
            // that position. The view may still be reporting the block because the event precedes the
            // removal, and there may be no view to read at all - a structure whose reader cannot be
            // resolved - and neither of those is evidence AGAINST the table's answer. So the view is not
            // consulted, and a null view is accepted here for exactly the reason a PRESENT one is.
            case PROJECTION_IS_PROOF -> true;
            // Only positive evidence. UNREADABLE - a null view, a missing state, a throw - is
            // deliberately not "gone": a component is never dropped, and a record is never forgotten,
            // because a structure could not be read.
            case VIEW_MUST_SAY_GONE -> view == StructureViewState.GONE;
        };
    }

    /**
     * <b>The pure decision behind the self-healing sweep.</b>
     *
     * <p>One host, one view answer, yes or no: the record must still be riding, and the structure's own
     * view must positively say the block is gone. Everything else - a landed record, a view that could
     * not be read, a block still present - is "leave it alone".
     *
     * <h2>Why "the structure is merely being moved" cannot be mistaken for this</h2>
     * The sweep reads the structure's <b>own</b> view at the host's <b>own local coordinate</b>, and never
     * the world. A structure that moves changes its <em>pose</em>: its blocks stay at the local
     * coordinates they have always been at, so the view keeps answering the same block and this returns
     * false. Only a change at that coordinate - the block gone - is a removal, which is exactly the
     * question the sweep is entitled to answer.
     */
    public static boolean selfHealWanted(@Nullable CarriedHost host,
                                         @Nullable StructureViewState view) {
        if (host == null || host.landed()) {
            return false;
        }
        return accepts(view, ViewRequirement.VIEW_MUST_SAY_GONE);
    }

    /**
     * <b>What one break about a carried host resolves to, or why it does not - as a pure function.</b>
     *
     * <h2>Why the host's CURRENT world position is an argument, and not the clicked position</h2>
     * The author's third report on this feature: <em>"breaking a physicalised block after it has moved
     * does not drop the component, and the component stays where it was."</em> Breaking one that has
     * <b>not</b> moved works, and that difference is exactly what the two coordinate frames look like
     * from the outside: a break reaches the carrier layer either at a <b>world</b> position (the
     * projection table's key) or at the structure's own position (the fallback, for a caller that could
     * only name the structure's storage). The old rule used the clicked position for everything - the
     * world-store check and the drop - so for a structure-frame hit a component could be dropped
     * <em>at the structure's storage coordinate</em>, which for a Sable plot is two million blocks from
     * anywhere a player can go, and is therefore indistinguishable from "it did not drop".
     *
     * <p>So the one thing that has to be computed once, from the carrier's own pose and the host's own
     * local coordinate, is {@code worldPos} - where this host is <b>right now</b> - and everything
     * downstream reads it: the world store is consulted there, the item is dropped there, and the client
     * is told there. The clicked position is deliberately not part of this function at all, so "the drop
     * landed where the block was when it was picked up" cannot be written here by accident.
     *
     * <p>A stale pose is not something this can detect, and it must not try: the caller that has a live
     * structure passes the pose the structure reports <em>now</em>. What it does guarantee is that the
     * three readers above agree, which is the half that was wrong.
     *
     * @param index the carried index the host must still be live in
     * @param storedInTheWorld what the level's own store holds at the host's <b>current</b> world
     *     position, or {@code null} when it holds nothing
     * @param hit the resolved click
     * @param worldPos where the host's local coordinate currently is in the world
     * @param view what the structure's own view says about the host's local coordinate; only consulted
     *     when the hit came from the structure's own frame (see {@link ViewRequirement})
     * @param requirement how much evidence this caller demands
     */
    @Nullable
    public static Destroyed resolve(@Nullable CarriedHostIndex index, @Nullable Slot storedInTheWorld,
                                    @Nullable Hit hit, @Nullable BlockPos worldPos,
                                    @Nullable StructureViewState view, ViewRequirement requirement) {
        if (index == null || hit == null || worldPos == null) {
            return null;
        }
        CarriedHost host = index.at(hit.carrier(), hit.localPos());
        if (!ownsDestruction(storedInTheWorld, host)) {
            return null;
        }
        if (!accepts(view, requirement)) {
            return null;
        }
        BlockPos at = worldPos.immutable();
        return new Destroyed(hit.carrier(), hit.localPos(), at, at, host.slot());
    }

    /**
     * <b>One host as the self-healing sweep sees it</b> - the sweep's whole decision, as a pure function.
     *
     * <h2>Why it is separate from the break path's rule</h2>
     * The two triggers ask different questions and must keep doing so: a break has an event and needs no
     * view, while the sweep has no event and needs the structure's own view to say the block is gone.
     * What they share is the write-back ({@code CarrierStore#dropCandidate}) and the fact that
     * <b>everything is read at the host's current world position</b> - which is the half that did not
     * converge after a structure had moved.
     *
     * <p>This method takes {@code now} rather than deriving it, so the caller that owns a level computes
     * it once and the test can drive the rule with two poses for one structure.
     *
     * @param index the carried index the host must still be live in
     * @param carrier the structure the sweep is walking
     * @param host the record the sweep is looking at
     * @param view what the structure's own view answered for the host's local coordinate
     * @param now where that local coordinate currently is in the world
     * @param storedInTheWorld what the level's own store holds at {@code now}
     */
    @Nullable
    public static Destroyed selfHealCandidate(@Nullable CarriedHostIndex index,
                                              @Nullable CarrierId carrier,
                                              @Nullable CarriedHost host,
                                              @Nullable StructureViewState view,
                                              @Nullable BlockPos now,
                                              @Nullable Slot storedInTheWorld) {
        if (index == null || carrier == null || host == null || now == null
                || !selfHealWanted(host, view)) {
            return null;
        }
        // The hit is built from the record rather than from an event, and it is deliberately a
        // `structure-frame` hit - `projected=false` - so this host is resolved with the evidence rule the
        // sweep asks for and can never inherit the break path's weaker one.
        return resolve(index, storedInTheWorld, new Hit(carrier, host.localPos(), now, null, false), now,
                view, ViewRequirement.VIEW_MUST_SAY_GONE);
    }

    /**
     * What one self-healing pass did, for the caller's log and for the unit test.
     *
     * @param visited how many hosts were examined
     * @param carriers how many structures were touched
     * @param found the hosts that turned out to have been destroyed
     */
    public record SelfHealTick(int visited, int carriers, List<Destroyed> found) {

        /** True when the sweep looked at anything at all. */
        public boolean looked() {
            return visited > 0;
        }
    }
}
