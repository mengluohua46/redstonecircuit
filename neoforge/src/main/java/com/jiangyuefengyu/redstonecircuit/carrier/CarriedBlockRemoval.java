package com.jiangyuefengyu.redstonecircuit.carrier;

import java.util.List;

import com.jiangyuefengyu.redstonecircuit.data.Slot;

import net.minecraft.core.BlockPos;

import org.jetbrains.annotations.Nullable;

/**
 * The decisions behind "a block a structure is carrying has been broken", as pure functions.
 *
 * <p>Which path owns a break, and what "the same host" means, is arithmetic over the index - no level, no
 * packet, no Minecraft type beyond the coordinates - so it lives here and the unit tests pin it; the item
 * drop, the client notice and the dirty flag live in {@link CarrierStore#dropDestroyedHost}, which asks this
 * class first.
 *
 * <p>A break event arrives <em>before</em> the block is removed, so the structure's own view still reports it
 * and cannot be the test. {@link ViewRequirement} is the difference between the callers.
 */
public final class CarriedBlockRemoval {

    /**
     * One carried host whose block has been destroyed, and where its component has to appear. {@code dropPos}
     * is the world position the block was at, so the item lands where the player broke it.
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
     * One click or break resolved to a host a structure is carrying - what {@link CarrierStore#hitAt} answers,
     * and what the destroy path and the carrier-side tool entry point both consume. {@code projected} is true
     * when the projection table answered, false when the structure's own storage had to be searched.
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
     * What the structure's own view of one local coordinate answered. Three answers rather than a boolean,
     * because "I could not read it" and "it is not there" must behave differently.
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
         * {@code BreakEvent} time the block is still there when the event fires.
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
     * The one rule that keeps the two drop paths apart: a break is the carrier layer's business exactly when
     * the component was <b>not</b> in the world store (it was riding) and the index really holds a
     * <em>live</em> record for it. A position whose component is still in the world store belongs to the
     * ordinary {@code BreakEvent} path, which drops it - the case a <em>released</em> host falls into - and a
     * landed record is a world host again.
     *
     * <p>A pure predicate over the two things it is given, so the production caller and the unit test pass the
     * same values and the test exercises the code that runs.
     */
    public static boolean ownsDestruction(@Nullable Slot storedInTheWorld,
                                          @Nullable CarriedHost carried) {
        return storedInTheWorld == null && carried != null && !carried.landed();
    }

    /**
     * Whether what the structure's own view answered is enough to act on: the pure half of
     * {@link CarrierStore#dropDestroyedHost}, shared by the break path and the self-healing sweep, and kept
     * free of level, packet and index so both behaviours are pinned offline.
     */
    public static boolean accepts(@Nullable StructureViewState view, ViewRequirement requirement) {
        if (requirement == null) {
            return false;
        }
        return switch (requirement) {
            // The projection table put this host at this world position and the player broke exactly that
            // position: the view is not evidence against it, so it is not consulted - a null view included,
            // which a structure whose reader cannot be resolved produces.
            case PROJECTION_IS_PROOF -> true;
            // Only positive evidence. UNREADABLE - a null view, a missing state, a throw - is not "gone": a
            // component is never dropped, and a record is never forgotten, because a structure could not be read.
            case VIEW_MUST_SAY_GONE -> view == StructureViewState.GONE;
        };
    }

    /**
     * The pure decision behind the self-healing sweep: one host, one view answer, yes or no - the record must
     * still be riding and the structure's own view must positively say the block is gone. Everything else is
     * "leave it alone".
     *
     * <p>A structure that is merely being moved cannot be mistaken for this: the sweep reads the structure's
     * <b>own</b> view at the host's own local coordinate, and moving changes only the pose, so the view keeps
     * answering the same block.
     */
    public static boolean selfHealWanted(@Nullable CarriedHost host,
                                         @Nullable StructureViewState view) {
        if (host == null || host.landed()) {
            return false;
        }
        return accepts(view, ViewRequirement.VIEW_MUST_SAY_GONE);
    }

    /**
     * What one break about a carried host resolves to, or why it does not.
     *
     * <p>The host's <b>current</b> world position is an argument rather than the clicked position, because a
     * break reaches the carrier layer either at a world position (the projection table's key) or at the
     * structure's own coordinate: used as the drop position, the latter puts the component at the structure's
     * storage coordinate - for a Sable plot two million blocks from anywhere a player can go, which is
     * indistinguishable from "it did not drop". {@code worldPos} is computed once from the carrier's own pose
     * and the host's local coordinate, and the world-store check, the drop and the client notice all read it.
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
     * One host as the self-healing sweep sees it: the two triggers ask different questions - a break has an
     * event and needs no view, the sweep has no event and needs the structure's own view to say the block is
     * gone - while what they share is the write-back and the fact that everything is read at the host's
     * current world position.
     *
     * <p>{@code now} is taken rather than derived, so the caller computes it once and a test can drive one
     * structure through two poses.
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
        // Built from the record rather than from an event, and a `structure-frame` hit
        // (`projected=false`), so this host can never inherit the break path's weaker evidence rule.
        return resolve(index, storedInTheWorld, new Hit(carrier, host.localPos(), now, null, false), now,
                view, ViewRequirement.VIEW_MUST_SAY_GONE);
    }

    /** What one self-healing pass did, for the caller's log and for the unit test. */
    public record SelfHealTick(int visited, int carriers, List<Destroyed> found) {

        /** True when the sweep looked at anything at all. */
        public boolean looked() {
            return visited > 0;
        }
    }
}
