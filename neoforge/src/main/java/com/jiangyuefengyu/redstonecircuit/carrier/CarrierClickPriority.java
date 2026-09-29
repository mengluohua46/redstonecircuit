package com.jiangyuefengyu.redstonecircuit.carrier;

import com.jiangyuefengyu.redstonecircuit.RCConfig;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import org.jetbrains.annotations.Nullable;

/**
 * <b>The one exception to "the world's click path is untouched": a coordinate a structure owns belongs
 * to the carrier side.</b>
 *
 * <h2>Why an exception is needed at all, stated as the fact the world path cannot see</h2>
 * A Sable sub-level's blocks <b>are</b> level blocks: they live in the level's reserved plot region, and
 * their local coordinate <em>is</em> their level coordinate (the frame
 * {@code CarrierStore.CarrierSource#plotOwnerAt} documents, and the one P1.19 measured). So a player's
 * click on a physicalised block arrives at the ordinary block-click handler carrying a coordinate that is
 * simultaneously "a position in the level" and "a host's coordinate inside a structure", and the world
 * handler has no way to tell those apart on its own. It therefore won the click, and the component was
 * written into the <b>world's</b> store at the plot coordinate:
 *
 * <pre>
 *   the world store holds it        -&gt; Jade reads the world store   -&gt; the component HAS a display
 *   the carrier index does not      -&gt; the overlay draws from it    -&gt; nothing is drawn over the block
 *   the world bridge projects from
 *   the carrier index               -&gt; nothing is emitted there     -&gt; the component is inert outside
 *   the in-structure solve walks
 *   the index by coordinate         -&gt; ...except by accident        -&gt; inside the machine it "works"
 * </pre>
 *
 * <p>The author's report is that list, in that order: <em>"putting a redstone component after
 * physicalising gives no frame and no inner display, but Jade shows it"</em>, and the consequence,
 * <em>"it cannot actually connect"</em>.
 *
 * <h2>The exception, and how narrow it is</h2>
 * This class answers exactly one question - <b>is this position a coordinate a structure owns, and which
 * structure and host is it</b> - and the world's click handler asks it once, <b>before</b> it claims
 * anything. If the answer is yes, the world side declines the click: it does not call
 * {@code HostRules#claimsClick}, it does not place, retrieve, toggle or wrench, and it does not write the
 * world store. Nothing else about that handler changes, and no other position reaches this answer:
 *
 * <ul>
 *   <li>it is a read-only query. It writes nothing, drops nothing, consumes nothing;</li>
 *   <li>a miss - which is every ordinary world block, including a carried host's <em>origin</em> and a
 *       host that has been put down - is answered exactly as the carrier's own resolver answers it, so
 *       the world path behaves byte-for-byte as before on every coordinate that is not one of a
 *       structure's own;</li>
 *   <li>the refusal does <b>not</b> cancel the event - it sets the event's two <em>action</em> fields to
 *       {@code FALSE} instead, so vanilla does not act on the click while every listener still sees it.
 *       The carrier's own listener decides what the click means; a click it does not own is simply left
 *       undone rather than handed to vanilla. See {@link #standDown} for why those two fields and not
 *       {@code setCanceled}, and §29 of the diagnostic log for the defect that made the distinction
 *       load-bearing.</li>
 * </ul>
 *
 * <p>An <b>empty</b> host counts: a block the structure carries that the player has emptied is still the
 * structure's coordinate, and "put a component into a physicalised block" starts from exactly that state.
 *
 * <h2>Why it does not answer "is this position near a structure"</h2>
 * "Which structure owns this position" is answered by the <b>structure's own storage</b> - the compat
 * source's {@code plotOwnerAt}, which names a sub-level whose own blocks contain this level position and
 * refuses a removed one - and by the carrier's index. There is no bounding-box test anywhere in this
 * class, and that matters: {@code SubLevel#boundingBox()} is the <em>world</em> box a moving machine
 * sweeps through, so testing against it would swallow clicks on ordinary ground the machine happens to be
 * flying over. The plot lookup answers about the structure's reserved plot region instead, which is
 * exactly the set of coordinates that are simultaneously "a position in the level" and "a coordinate
 * inside a structure" (see {@link CarrierStore.CarrierSource#plotOwnerAt}).
 *
 * <p>Until P1.30 that was further narrowed to "the index holds a live record at that coordinate", which
 * is what a structure loaded from a save can never satisfy. The narrowing is now the pair described in
 * {@link #owner}: a live record, <em>or</em> a live sub-level whose plot contains the coordinate.
 *
 * <h2>What that widening admits, stated honestly</h2>
 * A plot cell of a live sub-level that the index has never heard of now declines the world's click. The
 * click is not consumed (the event is not cancelled), so the carrier's own listener still sees it - and
 * since P1.32 that listener <b>files the missing record</b> when the click is a placement or a tool
 * gesture, so the coordinate becomes an ordinary carried host in the same click rather than after the
 * sweep's next pass. Until that record exists the coordinate is owned and empty at the same time, which
 * is exactly the state a freshly physicalised block is in. The alternative - leaving the world's path
 * to act there - is worse in a way the author's log shows: vanilla's own {@code Item#useOn} places a
 * <em>real block</em> into the plot cell, and this mod's world handler writes the component into the world
 * store at that coordinate, which is the state ("Jade shows it, the overlay does not") this whole class
 * exists to end.
 *
 * <p><b>P1.32: the second identification only worked when the level was handed to it.</b> The plot query
 * is a per-level container walk, so it answers {@code null} for a {@code null} level - and the previous
 * build reached frame two with one, which made "a plot cell of a live sub-level" answer "not ours" on a
 * real server. See {@link #probeOwner} for the whole of that defect and its repair.
 *
 * <h2>Bounded, and safe when the world stops</h2>
 * One projection-table hash lookup, plus one bounded scan of the index, plus the compat source's
 * per-sub-level plot containment test, plus - only when no record exists - one sub-level liveness lookup;
 * all on a player click only, never on a tick, a solve or a render frame. It holds no state, registers
 * nothing and keeps no reference to a level, so a level stop, an unload or a removed sub-level cannot
 * leave anything behind.
 */
public final class CarrierClickPriority {

    /**
     * <b>Which of the two ways this position turned out to be a structure's</b> - the distinction P1.30
     * put on the judgement line.
     *
     * <p>The two answers mean different things about what the carrier side can do with the click next:
     *
     * <ul>
     *   <li>{@link #HOST} - the index holds a <b>live record</b> at this coordinate, so the carrier's own
     *       entry point will resolve the click and act on the component inside the block. This is the
     *       answer P1.26 could give.</li>
     *   <li>{@link #SUB_LEVEL} - the position is a plot cell of a live sub-level and the index holds
     *       <b>no record</b> there. This is the author's case: the structure was loaded from the save (or
     *       assembled with its blocks already inside it), so nothing ever took a component out of a world
     *       position and no claim exists. The world side still stands down - vanilla must not place a
     *       block into a plot cell either - and the carrier side has nothing to resolve until
     *       {@code SableComponentAdoption} files the record that is missing.</li>
     * </ul>
     *
     * <p>Written on the line so the next log separates the two without anybody having to reason about
     * which frame answered.
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
     * A position that is a coordinate a structure owns.
     *
     * @param carrier the structure that owns it
     * @param localPos the host's coordinate inside that structure - the same number as the level
     *     position, which is the whole reason this class exists
     * @param worldPos where that host currently is, from the carrier's pose
     * @param probe which of the two identifications named it; see {@link Probe}. {@link Probe#HOST} for
     *     every value built before P1.30, so the three-argument form below keeps the P1.26 meaning
     */
    public record Owned(CarrierId carrier, BlockPos localPos, BlockPos worldPos, Probe probe) {

        /** The P1.26 shape, kept so a caller that holds a live index record can still say so in one line. */
        public Owned(CarrierId carrier, BlockPos localPos, BlockPos worldPos) {
            this(carrier, localPos, worldPos, Probe.HOST);
        }

        /**
         * A one-line description for the judgement line, naming both frames <b>and which
         * identification answered</b>.
         *
         * <p>{@code owner=host} and {@code owner=sublevel} are the two facts P1.30 exists to make
         * readable: the first says the carrier's resolver will find the component, the second says the
         * coordinate is a structure's but its record is still missing.
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
     * <b>The decision the world's click path asks, once, before it claims anything.</b>
     *
     * <p>Answered {@code null} - "not the carrier's, the world keeps this click" - for every position that
     * is not a coordinate a live structure owns, and for every position at all while
     * {@code carriedInsertPriority} is off. See the class comment for the whole argument.
     *
     * <h2>P1.30: two identifications, and why the second one had to be added</h2>
     * The question asked is "is this position a coordinate a structure owns", and there are two ways for
     * that to be true:
     *
     * <ol>
     *   <li>the index holds a live record there ({@link Probe#HOST}) - what P1.26 could answer;</li>
     *   <li><b>the position is a plot cell of a live sub-level and the index has no record</b>
     *       ({@link Probe#SUB_LEVEL}). This is the state a structure loaded from a save is in: every claim
     *       is created by taking a component out of a <em>world</em> position, so a sub-level whose blocks
     *       were already inside it when it was written to disk has no claims at all - the author's log has
     *       {@code carrier claim = 0} for the whole session.</li>
     * </ol>
     *
     * <p>Without the second one the world side kept the click and wrote its own store at the plot cell,
     * which is the reported defect exactly: Jade (which reads that store) displayed the component, the
     * overlay (which reads the index) drew nothing, and the world bridge could not see it.
     *
     * <p>Read-only and bounded: one projection-table hash lookup, one pass over the level's sub-levels,
     * one bounded index scan and - only when no record exists - one sub-level liveness lookup. On a player
     * click only.
     *
     * @param level the level the click arrived in
     * @param pos the level position the click carried
     * @return the structure the position belongs to, or {@code null} when the world path keeps the click
     */
    @Nullable
    public static Owned owner(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null || !RCConfig.carriedInsertPriority()) {
            // The switch off is exactly the behaviour before this class existed: the world path keeps
            // every click, including the ones a structure owns.
            return null;
        }
        CarrierStore.CarrierSource carrierSource = CarrierStore.source();
        return probeOwner(CarrierStore.projection(), CarrierStore.indexOf(level), level, pos,
                carrierSource == null ? null : carrierSource::plotOwnerAt,
                carrier -> CarrierStore.poseOf(level, carrier),
                CarrierStore::structureIsAlive);
    }

    /**
     * The same decision, as a value, with the four things it consults supplied - so the whole of it is
     * pinned by an offline unit test that has no level to ask.
     *
     * <p>{@link #owner} is this method with the level's own projection table, index, pose source and
     * liveness question plugged in; the split exists for the same reason
     * {@code CarrierStore#hitAt}'s level-free form does, and because the author's round asked for the two
     * answers on the line to be checkable without a running game.
     *
     * <p>The order of the two questions is the definition, not an optimisation: a live index record is the
     * stronger answer (the carrier's resolver can act on the component), so it is asked first and the plot
     * test is only reached when the index cannot name a host.
     *
     * <h2>P1.32: the level is part of the question, and passing {@code null} broke the whole second frame</h2>
     * {@code LevelPlot#getBoundingBox} is a per-level region, so the compat half's {@code plotOwnerAt}
     * answers {@code null} for a {@code null} level - it has no container to walk. P1.30 reached frame two
     * through {@link #carrierOf}, which passed {@code null} there, so on the author's machine the second
     * frame answered <b>null for every coordinate</b>: the world side never stood down at a plot cell it
     * had no record for ({@code world insert declined = 0} for a whole session, while the carrier's own
     * resolver - which does pass the level - printed {@code plotOwnerAt(…) -> s:c22611c6} in the very same
     * click), and {@code planOutside} saw the same coordinate as "not a structure's".
     * {@code CarrierPlotOwnershipTest} could not catch it because its plot lookup is a dynamic proxy that
     * ignores the level argument.
     *
     * <p>So the level is threaded to both plot questions below and neither is asked with {@code null} in
     * production. The unit-test form still passes {@code null}, which is what a fake lookup does not care
     * about; it is the <em>production</em> shape that must never be handed one.
     *
     * <h2>P1.33: frame one cannot answer for a plot cell, so frame two answers for both of its states</h2>
     * Frame one is {@code CarrierStore#ownedHostAt}'s <b>level-free</b> form - the one P1.26 and P1.30's
     * tests were written against - so its second half hands the compat half a {@code null} level and can
     * therefore never name a plot cell <em>in production</em>. That matters only when the index already
     * holds a record there: P1.32's frame two read the record, saw one, assumed frame one should have
     * answered for it and refused. The author's log shows the consequence on the same click that filed
     * such a record ({@code INSIDE: NOT_INSIDE} where the line should have been locked), and
     * {@link #plotCellOwnership} is the repair: the record decides the <em>tag</em>
     * ({@code owner=host}/{@code owner=sublevel}), never whether the coordinate is ours at all.
     *
     * @param table the projection table, keyed by world position, or {@code null} in a test that has none
     * @param index the dimension's carried records, or {@code null} when it has none
     * @param level the level the click arrived in, or {@code null} in a unit test - see above
     * @param pos the level position the click carried
     * @param owners the structure's own storage lookup, or {@code null} when no structure can be named
     * @param poses the structures' poses
     * @param alive the liveness question, asked only when the index holds no record
     */
    @Nullable
    static Owned probeOwner(@Nullable CarrierProjection table, @Nullable CarriedHostIndex index,
                            @Nullable ServerLevel level, BlockPos pos,
                            @Nullable CarrierStore.PlotOwnerSource owners,
                            @Nullable CarrierStore.PoseSource poses,
                            @Nullable java.util.function.Predicate<CarrierId> alive) {        if (pos == null || poses == null) {
            return null;
        }
        // Frame one: the WHOLE of the previous build's answer, called exactly as it was, so a live record
        // resolves to the same host - through the same projection-then-structure-storage order, with the
        // same fallbacks - as it did before this round. `PoseSource` is not passed, so this is the world
        // frame plus the plot frame without the local-coordinate scan; `hitAt`, which the carrier's own
        // entry point uses and which *does* scan, is deliberately a different question (it asks for a
        // component to act on).
        CarriedBlockRemoval.Hit hit = CarrierStore.ownedHostAt(table, index, pos,
                level == null ? null : level.dimension(), owners, poses);
        if (hit != null) {
            return new Owned(hit.carrier(), hit.localPos(), hit.worldPos(), Probe.HOST);
        }
        // Frame two, P1.30's addition: a plot cell of a live sub-level - whether the index holds a record
        // there or not. ONE ANSWER FOR BOTH STATES, because the two are one physical fact: the coordinate
        // is a block of that structure's own storage, and a record at it is a component the player put
        // there or took out. See `plotCellOwnership` for the whole of it, including the P1.33 repair it
        // carries.
        CarrierId owner = carrierOf(owners, level, pos);
        java.util.function.Predicate<CarrierId> liveness =
                alive == null ? carrier -> false : alive;
        return plotCellOwnership(owner, index, pos, poses, liveness);
    }

    /**
     * <b>What one plot cell of a structure is, for ownership: {@code host} when the index holds a record
     * there and {@code sublevel} when it holds none.</b>
     *
     * <h2>P1.33: the answer is decided by the RECORD, never by which frame found it</h2>
     * P1.32's frame two asked the index for a record <em>after</em> the plot lookup named a structure, and
     * refused when it found one, on the assumption that frame one had already answered for every live
     * record. In production that assumption was false, and the author's own log is the proof: the placement
     * path filed an empty host at a plot cell, and the <b>same click</b> then asked this decision about
     * that very coordinate and was told "not ours" - so the shift-click that should have locked a line
     * towards the block beside it answered
     * {@code this carried host is EMPTY and the picked host is not this one, so there is nothing to pin
     * towards it either (INSIDE: NOT_INSIDE)}.
     *
     * <p>Why frame one cannot answer for a plot cell in production: it asks the structure's own storage
     * <b>without a level</b> (see {@code CarrierStore#ownedHostAt}'s level-free form, kept for the tests
     * P1.26 and P1.30 wrote), and the compat half's {@code plotOwnerAt} walks one level's sub-level
     * container and can only answer {@code null} for a {@code null} level. Whether a record exists is the
     * only question that <em>can</em> be answered here, and it is exactly the question that separates the
     * two values:
     *
     * <ul>
     *   <li><b>a live record</b> - {@link Probe#HOST}. The coordinate is a carried host like any other,
     *       and every later decision (the placement path, the wrench, the switch) has a record to act on;</li>
     *   <li><b>no record</b> - {@link Probe#SUB_LEVEL}, the value the migration and the placement path key
     *       off, and the state every freshly physicalised block is in;</li>
     *   <li><b>a record that has been put down</b> - {@code null}, never {@code SUB_LEVEL}: the world owns
     *       that block again, and reporting it as "the index has nothing here" would adopt a host the
     *       player has already released;</li>
     *   <li><b>no record and the structure is not alive</b> - {@code null}: a plot region nothing is
     *       standing on is not anybody's coordinate.</li>
     * </ul>
     *
     * <p>Read-only, and bounded by one index lookup, one liveness answer (only when no record exists) and
     * one pose read - the structure itself was named by the caller, which asked
     * {@code CarrierStore#PlotOwnerSource} once for the click.
     *
     * @param owner the structure whose own block storage contains {@code pos}, or {@code null} when none
     *     does
     * @param poses the structures' poses
     * @param alive asked only when the index holds no record; {@code false} means "not a live structure"
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
     * <b>The level-free form, kept only so the unit tests written before P1.32 still name the decision they
     * were written against.</b>
     *
     * <p>It answers exactly what the P1.30 build answered, and the reason is in {@link #probeOwner}: the
     * level is handed to the plot lookup as {@code null} on <b>both</b> plot questions, so a lookup that is
     * level-aware - which the compat half's is, because "which sub-level's storage contains this position"
     * walks one level's container - will answer {@code null} for every coordinate. The frame is a
     * {@code dimension} key because that is how the projection table is keyed, and a test that drives it has
     * no level to hand over.
     *
     * <p><b>Nothing in production may call this.</b> {@link #owner} calls the level-taking form above; this
     * one exists so that {@code CarrierPlotOwnershipTest}'s assertions about the P1.30 decision keep
     * meaning what they meant, and its own file records that its plot lookup ignores the level.
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
        // The plot frame is asked without a level, which is all this form has - and that is exactly why it
        // is a test-only frame. See the paragraph above.
        return probeOwner(table, index, (ServerLevel) null, pos, owners, poses, alive);
    }

    /**
     * The structure the plot lookup names for this position, or {@code null} - so "which structure is
     * this" has one implementation inside this class and "I could not tell" is answered the same way every
     * time (never as "it is mine").
     *
     * <p>The level is forwarded rather than dropped: see {@link #probeOwner}. A {@code null} level is the
     * unit-test form and answers whatever the supplied lookup answers for {@code null}.
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
     * The world-side judgement line - written when, and only when, the world declines a click.
     *
     * <p>This is the line that makes the next log readable in one glance: {@code world insert declined}
     * means the carrier side owns that click, and its absence on a click that did nothing means the click
     * never reached this decision at all - or was never a structure's coordinate. Reported under
     * {@code debugLog}, like every other per-click line.
     *
     * @param pos the level position the click carried
     * @param owned what the position turned out to be
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
     * <b>What the world side does to the event when a structure owns the coordinate: it stands down.</b>
     *
     * <h2>Why this is two event fields and not {@code setCanceled(true)}</h2>
     * Both would stop vanilla, and only one of them leaves the carrier listener its delivery. In NeoForge
     * 21.1, {@code PlayerInteractEvent.RightClickBlock#setCanceled(true)} sets {@code useBlock} and
     * {@code useItem} to {@code FALSE} <em>and</em> marks the event cancelled - and a listener registered
     * with the default {@code receiveCanceled=false} is then <b>not called at all</b>. The carrier's
     * listener is such a listener, so cancelling here would delete the very delivery this exception exists
     * to hand over.
     *
     * <p>{@code ServerPlayerGameMode#useItemOn} reads the two fields directly:
     *
     * <pre>
     *   if (event.getUseItem() != FALSE)  { onItemUseFirst(...) }            // skipped
     *   if (event.getUseBlock().isTrue() || (isDefault() &amp;&amp; !flag1)) { useItemOn(...) }   // skipped
     *   if (event.getUseItem().isTrue() || (!stack.isEmpty() &amp;&amp; !onCooldown)) {
     *       if (event.getUseItem().isFalse()) return InteractionResult.PASS;  // returns here
     *       ... Item#useOn - the BLOCK PLACEMENT ...
     *   }
     * </pre>
     *
     * <p>With both fields {@code FALSE}: {@code onItemUseFirst} never runs, the block's own use never runs,
     * and the item's {@code useOn} - which is what a retaining repeater in hand would otherwise be placed
     * by - is never reached either. The method returns {@code PASS} and the click does nothing at all in
     * vanilla, while the event itself is <b>not</b> cancelled and is delivered to every other listener,
     * the carrier's included.
     *
     * <h2>Why "nothing at all in vanilla" is the correct answer here</h2>
     * The coordinate is a block a structure is carrying. Vanilla placing the held item there would put a
     * repeater into the plot cell - a real block at a coordinate the player cannot even reach - and that is
     * the same class of failure as the world store write this exception already prevents: the world acting
     * at a coordinate that belongs to a structure's own frame. Standing the <em>action</em> down as well as
     * the world's own handler is therefore the narrow, honest completion of P1.26 rather than a new power.
     *
     * <p>Pure and level-free, so the exact decision is pinned by an offline unit test.
     */
    public record StandDown(boolean useBlock, boolean useItem, boolean canceled) {
    }

    /**
     * The decision {@link #standDown} describes, as a value, for a caller that holds <b>only</b> the
     * ownership answer.
     *
     * <p>{@code owned == null} - the coordinate is not a structure's - answers "the world keeps everything",
     * which is the state the event is in before this class is asked, so a miss changes nothing.
     *
     * <p><b>This is the P1.29 shape and it is not what production asks any more.</b> With no click named,
     * "the coordinate is a structure's" is all this can be told, so it answers the old way: both action
     * fields FALSE. That is the behaviour five existing tests pin (they name an owner and nothing else),
     * and it is what an owner-alone caller would have got from this class before P1.34. The production
     * caller - {@code InnerRedstoneInteraction#onRightClickBlock} - asks the form below with the click's
     * gesture and the clicked cell's own state, and <b>that</b> is where the author's piston stopped being
     * swallowed. See {@link #weOwnThisClick}.
     */
    public static StandDown standDown(@Nullable Owned owned) {
        return owned == null ? new StandDown(true, true, false) : new StandDown(false, false, false);
    }

    /**
     * The same decision, with the click named: {@code owned == null} answers "the world keeps
     * everything" before any of the other arguments are read.
     *
     * <p>The production form, once the click's gesture and the state of the clicked cell are known. See
     * {@link #weOwnThisClick} for the rule and the defect it repairs.
     *
     * @param owned the ownership answer, or {@code null} when the coordinate is not a structure's
     * @param gesture what the dispatched hand and the sneak state make this click; a caller with no click
     *     to describe uses {@code NONE} and gets "not ours", which is the honest answer for a question
     *     that was not asked
     * @param hand what the dispatched hand holds - the item half of the same question, read once
     * @param sneaking {@code player.isSecondaryUseActive()} - the half of the click the gesture cannot
     *     carry on its own: a component in hand is {@link CarrierGesture.Kind#PLACEMENT} either way, but
     *     only the sneaking half of it is a store (see {@link #weOwnThisClick})
     * @param cellHoldsComponent the clicked carried host holds a component of its own
     * @param carriedInsert {@code RCConfig.carriedInsert()}
     * @param carriedWrench {@code RCConfig.carriedWrench()}
     * @param carriedSwitches {@code RCConfig.carriedSwitches()}
     */
    public static StandDown standDown(@Nullable Owned owned, CarrierGesture.Kind gesture,
                                      CarrierGesture.Hold hand, boolean sneaking,
                                      boolean cellHoldsComponent, boolean carriedInsert,
                                      boolean carriedWrench, boolean carriedSwitches) {
        if (owned == null) {
            return new StandDown(true, true, false);
        }
        boolean mine = hand != null && weOwnThisClick(gesture, sneaking, hand.wrench(), hand.rod(),
                hand.component(), hand.empty(), cellHoldsComponent, carriedInsert, carriedWrench,
                carriedSwitches);
        return mine ? new StandDown(false, false, false) : new StandDown(true, true, false);
    }

    /**
     * <b>Whether the CARRIER ledger's own pick owns this shift-click - the second half of "which click is
     * ours", for a coordinate no structure owns at all.</b>
     *
     * <h2>The defect this repairs, from the author's own log</h2>
     * <em>"物理方块点击后，shift 点击相邻的外部方块，只会显示已清除所有连接"</em>. The log says exactly that,
     * thirteen times:
     *
     * <pre>
     *   12:09:02.541  carrier s:0c184903: picked the near end of a line picked=s:0c184903 local=20481032, 127, 20481032
     *   12:09:03.140  wrench clear at -16, -60, -15 -&gt; CLEARED          the world's own wrench, on the world's
     *   12:09:30.991  carrier s:0c184903: locked OUTSIDE the structure side=west (world -17, -60, -15 at -18, -60, -15)
     * </pre>
     *
     * <p>The two ledgers are separate maps and nothing kept them in step: the carrier pick was made last,
     * the CLICKED coordinate was a world block the <b>world</b> ledger still had selected, and
     * {@code InnerRedstoneInteraction} runs <b>before</b> the carrier's listener - so the world's own
     * "shift-click the block you picked again drops every override" won the click, cleared it, showed
     * 「已清除…」 and cancelled the event. The carrier listener was never called at all: in that whole log
     * there is not one {@code carrier lock attempt?}, not one {@code INSIDE:}/{@code OUTSIDE:} prefix, and
     * no {@code locked OUTSIDE the structure} for that gesture (the two that are there are on the
     * <em>other</em> side of the same machine, where the world's own rule happened to decline).
     *
     * <h2>The rule, and why the pick decides the layer</h2>
     * The wrench's shift half is ONE gesture with one near end, and the near end is whatever the player
     * picked: if the carrier ledger holds a live pick, that pick <b>is</b> the near end, and the click is
     * the carrier's own lock-towards-a-block gesture - which the carrier side answers by name (locked, or
     * {@code NOT_ADJACENT} / {@code UNREADABLE_POSE} / …). The world's shift half is for a world near end,
     * which is what {@link com.jiangyuefengyu.redstonecircuit.logic.WrenchSelection} holds.
     *
     * <p><b>One live pick per player, and it is the world's own select that ends the carrier's.</b> The
     * carrier's pick is dropped when the player picks a world block with the same wrench
     * ({@code InnerRedstoneInteraction#useWrench}, the select half), so this predicate can never hold the
     * world's gesture hostage: a player who picks a world host gets the world's shift-click back, exactly
     * as before, from that moment on. Without that the world wrench would be stalled for the rest of the
     * session after one carrier pick, which is the one way this rule could be a regression.
     *
     * <p><b>What does not change:</b> the rod is not this gesture, a plain click (no sneak key) is the
     * world's own select half and never reaches here, and with {@code carriedWrench=false} there is no
     * carrier wrench at all, so the world keeps every click. All four are inputs of this function, which is
     * read-only arithmetic on them and touches no level, no index and no ledger.
     *
     * <p><b>Why the inputs are booleans.</b> Same reason as {@link #weOwnThisClick}: the red half of this
     * round's evidence is a real test run against the build <em>before</em> it, so the method has to be
     * askable by name there (where it does not exist) rather than as a call that would not compile.
     *
     * @param wrench a wrench is in the hand this delivery is for
     * @param sneaking whether the player is holding the sneak key - the shift half of the gesture
     * @param carriedWrench {@code RCConfig.carriedWrench()}
     * @param aCarrierHostIsPicked whether {@link CarriedSelection} holds a pick for this player in this
     *     level - the fact the world side reads once, at the top of its handler
     */
    public static boolean theCarrierPickOwnsThisShiftClick(boolean wrench, boolean sneaking,
                                                           boolean carriedWrench,
                                                           boolean aCarrierHostIsPicked) {
        return carriedWrench && wrench && sneaking && aCarrierHostIsPicked;
    }

    /**
     * The production form of the same question: read the hand, the sneak key and the carrier ledger once.
     *
     * <p>{@code null} player - a click that is not a player's - is "not ours", which is the answer that
     * leaves the event exactly as it arrived.
     */
    public static boolean theCarrierPickOwnsThisShiftClick(@Nullable ServerPlayer player, boolean wrench,
                                                           boolean carriedWrench) {
        return player != null
                && theCarrierPickOwnsThisShiftClick(wrench, player.isSecondaryUseActive(), carriedWrench,
                        CarriedSelection.get(player) != null);
    }

    /**
     * <b>Whether this click is really ours - the ONE predicate the world side's stand-down is decided
     * by, and the one the carrier's entry point agrees with.</b>
     *
     * <h2>The defect this repairs: the world stood down for every click on a structure's cell</h2>
     * P1.26 stood the world down whenever {@link #owner} named a carrier, and that is a statement about
     * the <b>coordinate</b>, not about the <b>click</b>. The two are the same thing only while every click
     * on a structure's cell is one of ours, and they are not: the author's own log is the proof -
     * thirty-eight declines in one session, and by held item they are
     *
     * <pre>
     *   19x minecraft:piston x1        the author's report: "the piston cannot be placed on a physicalised
     *   12x minecraft:grass_block x1   block at all" - two ordinary blocks, neither of them ours
     *    8x redstonecircuit:redstone_wrench x1
     *    8x empty (bare hand)
     *    3x minecraft:redstone x25     the one family that really is ours
     * </pre>
     *
     * <p>Setting {@code useBlock}/{@code useItem} to {@code FALSE} for a piston stops vanilla's own
     * {@code Item#useOn}, which is exactly how a block gets placed - so "this coordinate belongs to a
     * structure" was being read as "nothing may happen here", and a player could not build a piston onto a
     * physicalised machine. That is a regression of this exception, not a property of it.
     *
     * <h2>The rule, and its shape: one predicate, three positive answers, everything else falls through</h2>
     * We own the click - and only then does the world stand down - in exactly these cases:
     *
     * <ol>
     *   <li><b>a component is in the dispatched hand AND the player is sneaking</b> ({@code component} with
     *       {@code sneaking}, i.e. {@code HostRules.componentFor != null} and
     *       {@code player.isSecondaryUseActive()}). This is the STORE half of the gesture, and it is the
     *       half that has to write into the carrier's own record. The switch is {@code carriedInsert},
     *       because with it off the placement itself refuses and the world is welcome to the click;</li>
     *   <li><b>a tool of ours is in the dispatched hand</b> ({@link CarrierGesture.Kind#WRENCH} or
     *       {@link CarrierGesture.Kind#ROD}). The carrier side's own tool gestures have to run, and the
     *       world's wrench is written against a store that holds nothing at a carried host's position.
     *       The switch is that tool's own;</li>
     *   <li><b>an EMPTY hand - and that cell holds a component of its own</b> - the retrieval
     *       ({@link CarrierGesture.Kind#PLACEMENT}, which an empty hand only reaches while sneaking) and
     *       the switch ({@link CarrierGesture.Kind#SWITCH}, switch {@code carriedSwitches}), each gated on
     *       the switch that makes it real and each requiring something to work: with an empty hand the only
     *       actions this layer has are on a component that is <b>in</b> the clicked block.</li>
     * </ol>
     *
     * <p><b>Everything else does not yield, and that is the point of this round.</b> An ordinary block or
     * item in hand (a piston, a plank, grass), an empty hand on a cell that holds <b>no</b> component of
     * its own (a piston, a lamp, a chest, a lever, plain ground), and - P1.35 - <b>a component in hand
     * without the sneak key</b> all leave the event exactly as it arrived: the world's own handler and
     * vanilla both do what they have always done. A cell that holds nothing is a cell we have nothing to do
     * with - "the structure carries this block" is not "this mod has a click here".
     *
     * <h2>P1.35: why the sneak state is an input of its own, and why that is a NARROWING</h2>
     * {@link CarrierGesture.Kind#PLACEMENT} is deliberately <b>one</b> kind for two halves - a component in
     * hand is a placement whether or not the sneak key is down, and the sneak state decides which half of
     * that gesture it is (store while sneaking, and the <b>world's</b> own block placement while not).
     * Reading only the kind therefore read "a component is in hand" as "this mod has something to do here",
     * and the author's own log is the defect: of 41 declined clicks in one session, <b>19 were a lever</b>
     * ({@code item=minecraft:lever x4 tool=component}) clicked <b>without</b> the sneak key - every one of
     * them a {@link CarrierGesture.Kind#PLACEMENT} with {@code component=true}, every one of them stopped
     * vanilla's {@code Item#useOn}, and every one of them something the player was trying to put
     * <em>down</em> on the block as an ordinary lever ("拉杆放不上去了"). The only legitimate family in that
     * log is the single {@code minecraft:redstone x30} click with {@code sneaking=true}.
     *
     * <p>So the sneak state is now read beside the gesture, and the two halves of the placement are told
     * apart by it exactly as the carrier's own placement rule tells them apart
     * ({@code CarriedInsert#preview} answers {@code NOT_SNEAKING} for the plain half). The direction of the
     * change is the safe one: <b>one row of the table stops yielding</b>, and no row starts.
     *
     * <h2>Why the cell's contents and not only its identity</h2>
     * The three positive answers are all statements about what this layer can <em>do</em> with the click.
     * For the two tool gestures and the store half of a placement the action is about the pick or the item,
     * so identity is enough; for an empty hand the only actions are on a component that is <b>in</b> the
     * clicked block, and {@code CarrierStore#hostHasComponent} is that question - the same one the carrier
     * entry point asks before it acts, which is why the two cannot disagree.
     *
     * <p>Read-only arithmetic on the gesture, the four booleans of one hand, the sneak state and three
     * constants. No level, no index, no pose, so it costs nothing on the click path and is asserted
     * exhaustively offline.
     *
     * <p><b>Why the hand's four facts are four booleans and not a {@link CarrierGesture.Hold}.</b> This is
     * the one decision in the project whose red test has to <em>run</em> against the build before it: the
     * whole defect is "the world side answered about the coordinate instead of the click", so a test that
     * pins the new answer must be able to compile and execute on the previous tree (where this method does
     * not exist), ask for it by name, get nothing, and fail with a plain message. A {@code Hold} in the
     * signature would make that impossible - the type is newer than the method - so the four facts are
     * passed as the four booleans they are, read once from one {@code Hold} at the call site.
     *
     * @param gesture what the dispatched hand and the sneak state make this click
     * @param sneaking whether the player is holding the sneak key - the half of a placement this is
     * @param wrench a wrench is in the dispatched hand
     * @param rod the rotation rod is in the dispatched hand
     * @param component a usable redstone component is in the dispatched hand
     * @param empty the dispatched hand holds nothing at all (vanilla's own {@code stack.isEmpty()})
     * @param cellHoldsComponent the clicked carried host holds a component of its own
     */
    public static boolean weOwnThisClick(CarrierGesture.Kind gesture, boolean sneaking, boolean wrench,
                                         boolean rod, boolean component, boolean empty,
                                         boolean cellHoldsComponent, boolean carriedInsert,
                                         boolean carriedWrench, boolean carriedSwitches) {
        return switch (gesture) {
            // The two halves of this gesture are told apart by the HAND and the SNEAK KEY, not by the cell:
            // SHIFT + a component in hand is stored (the cell's own contents decide replace-or-not, which
            // `allowReplace` owns), PLAIN right-click with a component in hand is vanilla placing that item
            // as a block - exactly what the world side does with the same click on a world host, and the
            // half the author could not do on a physicalised one. An empty hand only reaches this kind
            // while sneaking (see CarrierGesture#emptyHandGesture) and only has something to do where the
            // block really holds a component. The switch is one for both, because `carriedInsert` is what
            // makes a placement store a component AND what makes an empty hand take one back out.
            case PLACEMENT -> carriedInsert && sneaking && (component || (empty && cellHoldsComponent));
            case WRENCH -> carriedWrench && wrench;
            case ROD -> rod;
            case SWITCH -> carriedSwitches && empty && cellHoldsComponent;
            case NONE -> false;
        };
    }
}
