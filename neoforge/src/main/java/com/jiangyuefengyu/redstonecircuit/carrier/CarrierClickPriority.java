package com.jiangyuefengyu.redstonecircuit.carrier;

import com.jiangyuefengyu.redstonecircuit.RCConfig;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import org.jetbrains.annotations.Nullable;

/**
 * The one exception to "the world's click path is untouched": a coordinate a structure owns belongs to the
 * carrier side.
 *
 * <p>A Sable sub-level's blocks are level blocks whose local coordinate is their level coordinate, so a click
 * on a physicalised block carries a coordinate that is both a level position and a host coordinate inside a
 * structure, which the world handler cannot tell apart. This class answers that one question before the
 * handler claims anything - is this position a coordinate a structure owns, and which structure and host is
 * it - and on "yes" the world stands the click down without consuming it (see {@link #standDown}), while a
 * miss is answered exactly as the carrier's own resolver answers it, so every other coordinate behaves as it
 * always did.
 *
 * <p>Ownership is the structure's own storage ({@code plotOwnerAt} - never a bounding-box test, which would
 * swallow clicks on ordinary ground a machine flies over) plus the carrier index, and an empty host counts,
 * because that is the state "put a component into a physicalised block" starts from. Read-only and stateless:
 * it writes nothing, keeps no level reference and holds nothing across calls.
 */
public final class CarrierClickPriority {

    /**
     * Which of the two ways a position turned out to be a structure's, because the two mean different things
     * about what the carrier side can do with the click next.
     *
     * <p>{@link #HOST} means the index holds a live record there, so the carrier's entry point will resolve
     * the click and act on the component inside the block. {@link #SUB_LEVEL} means the position is a plot
     * cell of a live sub-level with no record there - the state a structure loaded from a save is in, since
     * every claim is created by taking a component out of a world position - so the world still stands down
     * but the carrier side has nothing to resolve until the missing record is filed.
     */
    public enum Probe {

        /** The index holds a live carried host at this coordinate. */
        HOST("host"),

        /** A live sub-level owns this plot cell; the index has no record here yet. */
        SUB_LEVEL("sublevel");

        private final String label;

        Probe(String label) {
            this.label = label;
        }

        /** The word the judgement line carries: {@code owner=host} or {@code owner=sublevel}. */
        public String label() {
            return label;
        }
    }

    /**
     * A position that is a coordinate a structure owns. {@code localPos} is the same number as the level
     * position, which is why this class exists; {@code probe} says which identification named it.
     */
    public record Owned(CarrierId carrier, BlockPos localPos, BlockPos worldPos, Probe probe) {

        /** The owner-alone shape, kept so a caller that holds a live index record can still say so in one line. */
        public Owned(CarrierId carrier, BlockPos localPos, BlockPos worldPos) {
            this(carrier, localPos, worldPos, Probe.HOST);
        }

        /**
         * A one-line description for the judgement line, naming both frames and which identification answered:
         * {@code owner=host} says the carrier's resolver will find the component, {@code owner=sublevel} says
         * the coordinate is a structure's but its record is still missing.
         */
        public String describe() {
            return carrier.shortId() + " owner=" + probe.label()
                    + " local=" + localPos.toShortString()
                    + " world=" + worldPos.toShortString();
        }
    }

    private CarrierClickPriority() {
    }

    /**
     * The decision the world's click path asks once, before it claims anything: the structure the position
     * belongs to, or {@code null} - "not the carrier's, the world keeps this click" - for a position no live
     * structure owns, and for every position while {@code carriedInsertPriority} is off.
     *
     * <p>Two identifications count: the index holds a live record there ({@link Probe#HOST}), or the position
     * is a plot cell of a live sub-level with no record ({@link Probe#SUB_LEVEL}). Read-only, one bounded pass
     * over the level's sub-levels and the index, plus one liveness lookup only when no record exists.
     */
    @Nullable
    public static Owned owner(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null || !RCConfig.carriedInsertPriority()) {
            // With the switch off the world path keeps every click, including a structure's own.
            return null;
        }
        CarrierStore.CarrierSource carrierSource = CarrierStore.source();
        return probeOwner(CarrierStore.projection(), CarrierStore.indexOf(level), level, pos,
                carrierSource == null ? null : carrierSource::plotOwnerAt,
                carrier -> CarrierStore.poseOf(level, carrier),
                CarrierStore::structureIsAlive);
    }

    /**
     * The same decision as a value, with the four things it consults supplied, so the whole of it is pinned by
     * an offline unit test that has no level to ask.
     *
     * <p>The order of the two questions is the definition, not an optimisation: a live index record is the
     * stronger answer, because the carrier's resolver can act on the component, so the plot test is only
     * reached when the index cannot name a host. Both plot questions are handed the level - a per-level
     * container walk cannot answer for a null one, which silently made every plot cell "not ours" - while the
     * unit-test form passes null only because its lookup ignores the level.
     *
     * <p>Only the record decides the tag ({@code owner=host} or {@code owner=sublevel}), never whether the
     * coordinate is ours at all.
     */
    @Nullable
    static Owned probeOwner(@Nullable CarrierProjection table, @Nullable CarriedHostIndex index,
                            @Nullable ServerLevel level, BlockPos pos,
                            @Nullable CarrierStore.PlotOwnerSource owners,
                            @Nullable CarrierStore.PoseSource poses,
                            @Nullable java.util.function.Predicate<CarrierId> alive) {        if (pos == null || poses == null) {
            return null;
        }
        // A live record resolves through the same projection-then-structure-storage order and fallbacks as
        // before, without the local-coordinate scan `hitAt` adds for the carrier's own entry point.
        CarriedBlockRemoval.Hit hit = CarrierStore.ownedHostAt(table, index, pos,
                level == null ? null : level.dimension(), owners, poses);
        if (hit != null) {
            return new Owned(hit.carrier(), hit.localPos(), hit.worldPos(), Probe.HOST);
        }
        // A plot cell of a live sub-level, with or without a record there: both are one physical fact - the
        // coordinate is a block of that structure's own storage - and only the record decides the tag.
        CarrierId owner = carrierOf(owners, level, pos);
        java.util.function.Predicate<CarrierId> liveness =
                alive == null ? carrier -> false : alive;
        return plotCellOwnership(owner, index, pos, poses, liveness);
    }

    /**
     * What one plot cell of a structure is, for ownership: {@code host} when the index holds a record there and
     * {@code sublevel} when it holds none. The record decides the tag, never which frame found the coordinate,
     * because the structure's own storage cannot answer for a plot cell without a level.
     *
     * <p>A record that has been put down answers {@code null}, never {@code sublevel}: the world owns that block
     * again, and "the index has nothing here" would adopt a host the player has already released. A plot region
     * of a structure that is not alive is nobody's coordinate either.
     */
    @Nullable
    static Owned plotCellOwnership(@Nullable CarrierId owner, @Nullable CarriedHostIndex index,
                                   BlockPos pos, @Nullable CarrierStore.PoseSource poses,
                                   java.util.function.Predicate<CarrierId> alive) {
        if (owner == null || pos == null || poses == null) {
            return null;
        }
        CarriedHost record = index == null ? null : index.at(owner, pos);
        if (record != null && record.landed()) {
            return null;
        }
        java.util.function.Predicate<CarrierId> liveness = alive == null ? carrier -> false : alive;
        CarriedBlockRemoval.Hit plot = CarrierStore.ownedHostAtPlot(owner, index, pos, poses, liveness);
        if (plot == null) {
            return null;
        }
        return new Owned(plot.carrier(), plot.localPos(), plot.worldPos(),
                record == null ? Probe.SUB_LEVEL : Probe.HOST);
    }

    /**
     * The level-free form, kept only so the unit tests still name the decision they were written against: it
     * hands the plot lookup a null level on both questions, which a level-aware lookup answers null for. Its
     * key is a dimension because that is how the projection table is keyed. Nothing in production may call it.
     */
    @Nullable
    static Owned probeOwner(@Nullable CarrierProjection table, @Nullable CarriedHostIndex index,
                            BlockPos pos, @Nullable ResourceKey<Level> dimension,
                            @Nullable CarrierStore.PlotOwnerSource owners,
                            @Nullable CarrierStore.PoseSource poses,
                            @Nullable java.util.function.Predicate<CarrierId> alive) {
        // Frame one asks the projection table by dimension, which is the only thing this form can supply.
        CarriedBlockRemoval.Hit hit = CarrierStore.ownedHostAt(table, index, pos, dimension, owners, poses);
        if (hit != null) {
            return new Owned(hit.carrier(), hit.localPos(), hit.worldPos(), Probe.HOST);
        }
        // The plot frame is asked without a level, which is all this form has - hence test-only.
        return probeOwner(table, index, (ServerLevel) null, pos, owners, poses, alive);
    }

    /**
     * The structure the plot lookup names for this position, or {@code null} - one implementation, and "I could
     * not tell" is always answered the same way (never as "it is mine"). The level is forwarded rather than
     * dropped, since a per-level container walk cannot answer without one.
     */
    @Nullable
    private static CarrierId carrierOf(@Nullable CarrierStore.PlotOwnerSource owners,
                                       @Nullable ServerLevel level, BlockPos pos) {
        if (owners == null) {
            return null;
        }
        try {
            return owners.plotOwnerAt(level, pos);
        } catch (RuntimeException | LinkageError failed) {
            return null;
        }
    }

    /**
     * The world-side judgement line, written when and only when the world declines a click: its presence means
     * the carrier side owns that click, its absence means the click never reached this decision or was never a
     * structure's coordinate. Reported under {@code debugLog}.
     */
    public static void logDeclined(BlockPos pos, @Nullable Owned owned) {
        if (!RCConfig.debugLog() || owned == null || pos == null) {
            return;
        }
        RCConfig.LOGGER.info("[redstonecircuit] world insert declined: this position belongs to carrier "
                        + "{} (plot cell) - the world store is NOT written at {}; the carrier entry point "
                        + "handles it instead, and files a record there when a component is really placed "
                        + "(a wrench, a rod or an empty hand files nothing)",
                owned.describe(), pos.toShortString());
    }

    /**
     * What the world side does to the event when a structure owns the coordinate: it stands down.
     *
     * <p>Two event fields and not {@code setCanceled(true)}: on NeoForge 21.1 cancelling also marks the event
     * cancelled, and a listener registered with the default {@code receiveCanceled=false} is then not called at
     * all - the carrier's listener is one, so cancelling would delete the very delivery this exception exists to
     * hand over. With {@code useBlock} and {@code useItem} set to {@code FALSE}, vanilla's own handler skips
     * {@code onItemUseFirst}, the block's own use and the item's {@code useOn}, so no real block is placed into
     * a plot cell the player cannot even reach, while every other listener still sees the click.
     *
     * <p>Pure and level-free, so the exact decision is pinned by an offline unit test.
     */
    public record StandDown(boolean useBlock, boolean useItem, boolean canceled) {
    }

    /**
     * The decision {@link #standDown} describes, as a value, for a caller that holds only the ownership answer:
     * {@code owned == null} answers "the world keeps everything", which is the state the event is in before this
     * class is asked, so a miss changes nothing.
     *
     * <p>With no click named, "the coordinate is a structure's" is all this can be told, so both action fields
     * are set FALSE. Production asks the form below with the click's gesture and the clicked cell's state - see
     * {@link #weOwnThisClick}.
     */
    public static StandDown standDown(@Nullable Owned owned) {
        return owned == null ? new StandDown(true, true, false) : new StandDown(false, false, false);
    }

    /**
     * The same decision with the click named: {@code owned == null} answers "the world keeps everything" before
     * any other argument is read. {@code gesture} describes the dispatched hand and the sneak state - a caller
     * with no click to describe passes {@code NONE} and gets "not ours" - and {@code hostAcceptsComponent} is the
     * same {@code HostRules#isValidHost} the world's own placement asks, read from the structure's own view: a
     * block that cannot hold a component does not make this click ours.
     */
    public static StandDown standDown(@Nullable Owned owned, CarrierGesture.Kind gesture,
                                      CarrierGesture.Hold hand, boolean sneaking,
                                      boolean cellHoldsComponent, boolean hostAcceptsComponent,
                                      boolean carriedInsert, boolean carriedWrench,
                                      boolean carriedSwitches) {
        if (owned == null) {
            return new StandDown(true, true, false);
        }
        boolean mine = hand != null && weOwnThisClick(gesture, sneaking, hand.wrench(), hand.rod(),
                hand.component(), hand.empty(), cellHoldsComponent, hostAcceptsComponent, carriedInsert,
                carriedWrench, carriedSwitches);
        return mine ? new StandDown(false, false, false) : new StandDown(true, true, false);
    }

    /** The same, for a caller that has not read the clicked block's own state: the block is treated as one that accepts a component. */
    public static StandDown standDown(@Nullable Owned owned, CarrierGesture.Kind gesture,
                                      CarrierGesture.Hold hand, boolean sneaking,
                                      boolean cellHoldsComponent, boolean carriedInsert,
                                      boolean carriedWrench, boolean carriedSwitches) {
        return standDown(owned, gesture, hand, sneaking, cellHoldsComponent, true, carriedInsert,
                carriedWrench, carriedSwitches);
    }

    /**
     * Whether the carrier ledger's own pick owns this shift-click - the second half of "which click is ours",
     * for a coordinate no structure owns at all.
     *
     * <p>The wrench's shift half is one gesture with one near end, and the near end is whatever the player
     * picked: a live carrier pick is that near end, so this click is the carrier's own lock-towards-a-block
     * gesture, and the world's own "shift-click the picked block again drops every override" must not take it.
     * The world's shift half is for a world near end, which is what
     * {@link com.jiangyuefengyu.redstonecircuit.logic.WrenchSelection} holds, and only one pick is live per
     * player: picking a world block with the same wrench ends the carrier's, so the world's gesture is never
     * held hostage.
     *
     * <p>The rod is not this gesture, a plain click is the world's own select half and never reaches here, and
     * with {@code carriedWrench=false} there is no carrier wrench at all. Read-only arithmetic on its inputs.
     */
    public static boolean theCarrierPickOwnsThisShiftClick(boolean wrench, boolean sneaking,
                                                           boolean carriedWrench,
                                                           boolean aCarrierHostIsPicked) {
        return carriedWrench && wrench && sneaking && aCarrierHostIsPicked;
    }

    /**
     * The production form of the same question: read the hand, the sneak key and the carrier ledger once. A null
     * player - a click that is not a player's - is "not ours", leaving the event exactly as it arrived.
     */
    public static boolean theCarrierPickOwnsThisShiftClick(@Nullable ServerPlayer player, boolean wrench,
                                                           boolean carriedWrench) {
        return player != null
                && theCarrierPickOwnsThisShiftClick(wrench, player.isSecondaryUseActive(), carriedWrench,
                        CarriedSelection.get(player) != null);
    }

    /**
     * Whether this click is really ours - the one predicate the world side's stand-down is decided by and the one
     * the carrier's entry point agrees with.
     *
     * <p>Ownership of the coordinate is not ownership of the click: standing down for every click on a
     * structure's cell stopped vanilla's own item use and made it impossible to place an ordinary block, a piston
     * or a lever, next to or on a physicalised machine. The click is ours in exactly three cases - a component in
     * the dispatched hand with the sneak key down, and only where the block may hold one, which is the store half
     * of the gesture; a tool of ours in the dispatched hand, whose gestures the carrier side has to run; and an
     * empty hand on a cell that holds a component of its own, which is the retrieval and the switch, each gated
     * on its own switch. Everything else - an ordinary block or item in hand, an empty hand on a cell that holds
     * nothing, and a component in hand without the sneak key, which is the world's own place-a-block gesture -
     * leaves the event exactly as it arrived.
     *
     * <p>The sneak state is read beside the gesture because one placement kind covers both halves: sneaking
     * stores into the carrier record, not sneaking is vanilla placing the item as an ordinary block, and the
     * direction of that narrowing is the safe one - one row stops yielding and none starts.
     *
     * <p>Read-only arithmetic on the gesture, the hand's four facts, the sneak state and three constants: no
     * level, no index, no pose, so it is asserted exhaustively offline. The four facts are passed as booleans
     * rather than a {@link CarrierGesture.Hold} so that a test can ask for this method by name on a build where
     * it does not yet exist.
     */
    public static boolean weOwnThisClick(CarrierGesture.Kind gesture, boolean sneaking, boolean wrench,
                                         boolean rod, boolean component, boolean empty,
                                         boolean cellHoldsComponent, boolean hostAcceptsComponent,
                                         boolean carriedInsert, boolean carriedWrench,
                                         boolean carriedSwitches) {
        return switch (gesture) {
            // The two halves of this gesture are told apart by the hand and the sneak key: sneaking with a
            // component in hand stores into the carrier record, a plain right-click with one is vanilla placing
            // that item as an ordinary block. An empty hand only reaches this kind while sneaking and only has
            // something to do where the block really holds a component.
            //
            // The block also has to be able to hold one: without that half the store gesture was ours on a
            // physicalised pane of glass too, the carrier's own placement refused it while the world had already
            // stood down, and the click was eaten. The retrieval half is not gated on it, because
            // taking a component back out of a block that has stopped being a legal host has to stay possible.
            case PLACEMENT -> carriedInsert && sneaking
                    && ((component && hostAcceptsComponent) || (empty && cellHoldsComponent));
            case WRENCH -> carriedWrench && wrench;
            case ROD -> rod;
            case SWITCH -> carriedSwitches && empty && cellHoldsComponent;
            case NONE -> false;
        };
    }

    /**
     * The same, for a caller that has not read the clicked block's own state: the block is treated as one that
     * accepts a component, which is the answer the world's own click path gets when {@code RCConfig#validateHosts}
     * is off.
     */
    public static boolean weOwnThisClick(CarrierGesture.Kind gesture, boolean sneaking, boolean wrench,
                                         boolean rod, boolean component, boolean empty,
                                         boolean cellHoldsComponent, boolean carriedInsert,
                                         boolean carriedWrench, boolean carriedSwitches) {
        return weOwnThisClick(gesture, sneaking, wrench, rod, component, empty, cellHoldsComponent, true,
                carriedInsert, carriedWrench, carriedSwitches);
    }
}
