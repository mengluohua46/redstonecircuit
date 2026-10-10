package com.jiangyuefengyu.redstonecircuit.carrier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.jiangyuefengyu.redstonecircuit.RCConfig;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import org.jetbrains.annotations.Nullable;

/**
 * The one trace line every "a click or a break arrived at a carrier entry point" writes, and the bounded, throttled
 * candidate list that goes with a miss.
 *
 * <p><b>Diagnostics only.</b> Nothing here resolves a host, cancels an event, writes a record or drops an item; every
 * method either formats a string, decides whether a line may be printed, or reads the index to describe it. Which
 * entry point was called, with which raw coordinate, and what the resolver made of it, is the whole question.
 *
 * <p>The outcomes are {@link Outcome#MATCH} (the coordinate resolved to a live carried host, and the line carries the
 * carrier, the host's <b>local</b> coordinate and the world position the pose projects it to right now),
 * {@link Outcome#MISS} (it did not resolve; the line says which resolver and which frame were used, because "I asked
 * the wrong table" and "the table is empty" are different faults) and {@link Outcome#REFUSED} (it resolved and
 * something downstream said no; the refusal travels verbatim).
 *
 * <p>A hit is always written. A miss or a refusal is throttled by shape - the entry point, the outcome and, only while
 * the dimension holds at least one carrier, the raw coordinate - so a repeated failing click prints one line per
 * {@value #REPEAT_WINDOW_TICKS} ticks, while two <em>different</em> coordinates always print. The candidate list has
 * its own, coarser window: at most one block per {@value #CANDIDATE_WINDOW_TICKS} ticks.
 *
 * <p>On a dimension that does hold live carriers a miss also prints the coordinates the index holds - for each carrier
 * (at most {@value #CANDIDATE_CARRIER_LIMIT}) the pose and the first {@value #CANDIDATE_HOST_LIMIT} live hosts as
 * {@code local=(…) world=(…)}, since the two frames side by side is what says whether a click arrived in the
 * structure's frame or in the world's. The host walk is bounded by that limit rather than by the machine's size, and
 * the whole block runs inside a {@code try}/{@code catch} so a structure going away cannot make a diagnostic throw on
 * a click path.
 */
public final class CarrierEntryTrace {

    /**
     * How many carriers a candidate list prints in full.
     *
     * <p>Three, because the question the list answers is "is the click's frame the frame the index uses",
     * and that is decided by the carrier the player is standing at plus, at most, the one or two others a
     * test world has in flight. A larger number buys nothing: a mismatch in the first three is visible in
     * the first three.
     */
    public static final int CANDIDATE_CARRIER_LIMIT = 3;

    /**
     * How many hosts of one carrier are printed.
     *
     * <p>Six, about one small machine: its hosts sit within a couple of blocks of each other, so six consecutive
     * local coordinates cover the block being clicked <em>and</em> its neighbours. The total is therefore at most
     * {@value #CANDIDATE_CARRIER_LIMIT} x {@value #CANDIDATE_HOST_LIMIT} = 18 coordinate pairs per miss, which fits
     * on three log lines.
     */
    public static final int CANDIDATE_HOST_LIMIT = 6;

    /** The window (in game ticks) inside which the same miss/refusal shape is printed only once. */
    public static final int REPEAT_WINDOW_TICKS = 20;

    /** The window (in game ticks) inside which at most one candidate list is printed. */
    public static final int CANDIDATE_WINDOW_TICKS = 20;

    /** How many distinct shapes the throttle remembers before it evicts the oldest. A hard ceiling. */
    public static final int MAX_REMEMBERED_SHAPES = 32;

    /** How many break events the "did a BreakEvent ever arrive" ledger remembers. A hard ceiling. */
    public static final int MAX_REMEMBERED_BREAKS = 32;

    /** What an entry point made of the event it was handed. */
    public enum Outcome {

        /** The raw coordinate resolved to a live carried host. */
        MATCH,
        /** The entry point ran, the coordinate did not resolve, and nothing was acted on. */
        MISS,
        /** The coordinate resolved and something downstream said no; the line carries the reason. */
        REFUSED,
        /**
         * The entry point ran, and the gesture rule said this click is nobody's.
         *
         * <p>A fourth outcome rather than "no line", because "the entry point was never called" and "it was called and
         * the rule answered NONE" are opposite conclusions - a hook that is not installed against a rule that is
         * wrong - and without this they read the same in the log. A click the carrier layer could have been about is
         * therefore reported whatever the rule says, with the rule's own answer and both hands, <b>before every early
         * return</b>; see {@code CarrierInteraction#noteGesture}.
         */
        NONE
    }

    // ------------------------------------------------------- the three miss shapes --

    /**
     * <b>{@code record absent}</b> - no carrier in this dimension holds a record keyed by the coordinate
     * the click carried. The click landed somewhere nothing of ours was ever claimed.
     */
    public static final String RECORD_ABSENT = "record absent";

    /**
     * <b>{@code record present but NOT live}</b> - a record <em>is</em> keyed by this coordinate, and it
     * is not a live one: it has been put down ({@code landed}), or its carrier is no longer an active
     * structure. The click was about a real host and the resolver refused it on purpose.
     */
    public static final String RECORD_NOT_LIVE = "record present but NOT live";

    /**
     * <b>{@code carrier resolution failed}</b> - the resolver could not even name which structure the
     * coordinate belongs to: the reverse lookup answered nothing, there was no pose, or the pose did not
     * put the coordinate back where the click was. Nothing was looked up in the index at all.
     */
    public static final String CARRIER_UNRESOLVED = "carrier resolution failed";

    /**
     * <b>{@code record present in more than one carrier}</b> - two or more live carriers hold a host at
     * this very coordinate. The resolver refuses rather than guessing which structure the player meant.
     */
    public static final String RECORD_AMBIGUOUS = "record present in more than one carrier";

    /**
     * {@code plot cell of a live sub-level, and the index has no record here} - the coordinate is a block inside a
     * living structure, and nothing has ever been filed for it.
     *
     * <p>A verdict of its own and not {@code record absent}, because the two are opposite facts: "nothing of ours is
     * here" is an ordinary world coordinate and the world path keeps it, while "a block of a living structure with no
     * record yet" is a coordinate the world side already stands down at - the state <b>every freshly physicalised
     * block is in</b>, and the one a click that wants to put a component in arrives with. For such a coordinate the
     * plot already names the structure and the coordinate <em>is</em> the local coordinate, so inverting the pose to
     * look it up would produce a doubled answer that can never be found.
     */
    public static final String RECORD_MISSING_IN_PLOT = "plot cell of a live sub-level with no record yet";

    /**
     * Why one resolution missed, as the steps the resolver actually took.
     *
     * <p>The miss shapes above are indistinguishable from the outside - all of them are "no hit" - so the resolver is
     * handed one of these for the call it is already making and writes down what it did: which frame it asked first,
     * what the reverse lookup answered, what the record scan found. It changes no decision; it is written to, not read
     * from, by the resolver. Bounded twice ({@link #MAX_STEPS} steps and one verdict string) and allocated only when
     * the diagnostics are on.
     */
    public static final class Miss {

        /** How many resolver steps one explanation remembers. A hard ceiling. */
        public static final int MAX_STEPS = 10;

        private final List<String> steps = new ArrayList<>();
        private String verdict = "not reached";

        /** One step the resolver took, in order. Ignored once the ceiling is reached. */
        public void step(String text) {
            if (text != null && steps.size() < MAX_STEPS) {
                steps.add(text);
            }
        }

        /** The three-state (or four-state) answer, and what it means. */
        public void verdict(String text) {
            if (text != null) {
                this.verdict = text;
            }
        }

        /** The verdict first, then the steps, on one line. */
        public String describe() {
            return "verdict=" + verdict + " steps=[" + String.join("; ", steps) + ']';
        }

        /** How many steps were recorded; never more than {@link #MAX_STEPS}. */
        public int size() {
            return steps.size();
        }
    }

    /** The production throttle. Static because the entries are static and share one gesture's worth. */
    private static final Throttle THROTTLE = new Throttle(REPEAT_WINDOW_TICKS, MAX_REMEMBERED_SHAPES);

    /** Warned once, so a diagnostic that cannot format cannot warn once per click. */
    private static boolean warned;

    // ------------------------------------------------- the test seam (tests only) --

    /**
     * A sink the unit tests install so an emitted line can be asserted without a running game.
     *
     * <p>The rule that decides <em>which</em> clicks are reported lives in {@code CarrierInteraction#noteGesture}, and
     * the only thing that cannot be reached offline is the logger itself. So the last step of the two emitting
     * methods is this list: a test adds a consumer, calls the production method, and asserts <b>how many lines</b> came
     * out and what they say.
     *
     * <p>Inert in production: nothing installs a sink, so the list is empty and every {@code emit} call is one
     * {@code isEmpty()} check. A test that throws inside its own consumer cannot take an entry point down - the
     * emission is wrapped and a failure is dropped, exactly as the log line is.
     */
    private static final List<java.util.function.Consumer<String>> SINKS = new ArrayList<>();

    /** How many lines have been emitted since the last {@link #forgetForTests()}; tests only. */
    private static int emitted;

    /** Installs (or clears, with {@code null}) the unit tests' line sink. Tests only. */
    public static void setSinkForTests(@Nullable java.util.function.Consumer<String> sink) {
        SINKS.clear();
        if (sink != null) {
            SINKS.add(sink);
        }
    }

    /** Forgets the emission count and every throttle, so one test cannot silence the next. Tests only. */
    public static void forgetForTests() {
        emitted = 0;
        reset();
    }

    /** How many lines the entry points emitted since the last {@link #forgetForTests()}. Tests only. */
    public static int emittedForTests() {
        return emitted;
    }

    /**
     * Hands one finished line to the unit tests' sinks, if any are installed.
     *
     * <p>Deliberately <b>not</b> gated on {@code debugLog}: a test asserts the rule and the ordering, and
     * a config switch is what the operator uses to silence the log, not what makes the rule exist.
     */
    private static void emit(String line) {
        if (SINKS.isEmpty() || line == null) {
            return;
        }
        emitted++;
        for (java.util.function.Consumer<String> sink : SINKS) {
            try {
                sink.accept(line);
            } catch (RuntimeException | LinkageError ignored) {
                // A test sink is not allowed to take an entry point down; the log line is the evidence.
            }
        }
    }

    private CarrierEntryTrace() {
    }

    // ------------------------------------------------------------------ the throttle --

    /**
     * <b>How often a repeated line may be printed: pure, bounded and driven by an explicit tick.</b>
     *
     * <p>An object rather than static state so the window and the ceiling are pinned by offline unit tests
     * that can drive it with any tick they like - which is the only way "the same miss prints once per
     * twenty ticks" can be an assertion rather than a claim about a running server.
     *
     * <p>The map is bounded by {@code maxKeys}: the oldest shape is evicted rather than the map cleared,
     * so one pathological entry point cannot make every other entry point start repeating.
     */
    public static final class Throttle {

        private final int window;
        private final int maxKeys;
        private final Map<String, Long> lastPrinted = new LinkedHashMap<>();
        private long lastCandidates = Long.MIN_VALUE;
        private boolean candidatesEverPrinted;

        public Throttle(int window, int maxKeys) {
            this.window = Math.max(1, window);
            this.maxKeys = Math.max(1, maxKeys);
        }

        /**
         * True when {@code shape} may be printed at {@code tick}, and records that it was.
         *
         * <p>A tick that goes backwards (a world that was reloaded) is treated as "not the same shape any
         * more" and allowed: a diagnostic must never be silenced by a clock it does not control.
         */
        public boolean allow(String shape, long tick) {
            if (shape == null) {
                return true;
            }
            Long previous = lastPrinted.get(shape);
            if (previous != null && tick >= previous && tick - previous < window) {
                return false;
            }
            lastPrinted.remove(shape);
            lastPrinted.put(shape, tick);
            while (lastPrinted.size() > maxKeys) {
                String oldest = lastPrinted.keySet().iterator().next();
                lastPrinted.remove(oldest);
            }
            return true;
        }

        /** True when a candidate list may be printed at {@code tick}; its own, coarser window. */
        public boolean allowCandidates(long tick) {
            if (candidatesEverPrinted && tick >= lastCandidates
                    && tick - lastCandidates < CANDIDATE_WINDOW_TICKS) {
                return false;
            }
            candidatesEverPrinted = true;
            lastCandidates = tick;
            return true;
        }

        /** How many shapes are remembered right now; never more than {@code maxKeys}. */
        public int size() {
            return lastPrinted.size();
        }

        /** Forgets everything; used by the unit tests so one test cannot throttle the next. */
        public void clear() {
            lastPrinted.clear();
            lastCandidates = Long.MIN_VALUE;
            candidatesEverPrinted = false;
        }
    }

    /**
     * <b>What makes two events "the same shape" for the throttle.</b>
     *
     * <p>The entry point and the outcome always; the raw coordinate <b>only when the dimension really
     * holds a carrier</b>. That last clause is the whole of "an unrelated click never fills the log":
     * with nothing in flight there is nothing a coordinate could distinguish, so every such miss shares
     * one shape and prints at most once per window - while an acceptance run, which does have a carrier,
     * still gets a line per distinct coordinate.
     */
    public static String shape(String entry, Outcome outcome, @Nullable BlockPos rawPos,
                               boolean anyCarrier) {
        String where = anyCarrier && rawPos != null ? rawPos.toShortString() : "any";
        return entry + "|" + outcome + "|" + where;
    }

    // ----------------------------------------------------------------- the lines --

    /**
     * The entry line: one shape for every entry point, so a reader compares positions and not formats. Pure - every
     * field is a string the caller has already turned into one - so the format is asserted offline.
     */
    public static String entryLine(String entry, String who, String raw, String face, String hand,
                                   String item, String tool, Outcome outcome, String detail) {
        return "[redstonecircuit] carrier entry trace: entry=" + entry
                + " by=" + who
                + " raw=" + raw
                + " face=" + face
                + " hand=" + hand
                + " item=" + item
                + " tool=" + tool
                + " -> " + outcome
                + (detail == null || detail.isEmpty() ? "" : " " + detail);
    }

    /**
     * The placement path's own line: the one entry whose <em>inputs</em> - sneak state, what was in the hand, which
     * local face, what the host held before - decide the outcome and are otherwise invisible.
     */
    public static String insertLine(String entry, String who, String raw, String face, String hand,
                                    String item, String tool, Outcome outcome, String detail) {
        return "[redstonecircuit] carrier insert trace: entry=" + entry
                + " by=" + who
                + " raw=" + raw
                + " face=" + face
                + " hand=" + hand
                + " item=" + item
                + " tool=" + tool
                + " -> " + outcome
                + (detail == null || detail.isEmpty() ? "" : " " + detail);
    }

    /** The header of a candidate list: what missed, and how much is out there to compare against. */
    public static String candidateHeader(String entry, String raw, String dimension, int carriers,
                                        int more) {
        return "[redstonecircuit] carrier entry candidates: " + carriers + " live carrier(s) in "
                + dimension + " (entry=" + entry + " missed at " + raw
                + "); K=" + CANDIDATE_CARRIER_LIMIT + " M=" + CANDIDATE_HOST_LIMIT
                + ", local=(…) world=(…) per host"
                + (more > 0 ? ", ...+" + more + " more carrier(s)" : "");
    }

    /** One carrier of a candidate list: its pose, then its hosts as {@code local=(…) world=(…)}. */
    public static String candidateLine(String carrierId, String pose, String hosts) {
        return "[redstonecircuit]   carrier " + carrierId + " pose=" + pose + " hosts=[" + hosts + "]";
    }

    /**
     * <b>The {@code local=(…) world=(…)} pairs of a candidate list, hard-bounded.</b>
     *
     * <p>The world position is what the pose projects the local coordinate to <em>now</em>, which is the
     * frame a click from the world would arrive in - so a click whose raw coordinate is a plot
     * coordinate and a projection that names a world one sit side by side and the difference is visible.
     * A host whose projection cannot be computed says {@code world=?} rather than a guess.
     *
     * <p>Bounded by {@code limit} in the loop <em>and</em> by the truncation marker, so a machine with two
     * hundred hosts costs at most {@code limit} projections and one {@code ...+N}.
     *
     * @param pose the carrier's pose, or {@code null} when the structure cannot be read
     * @param liveHosts only the hosts still riding, in the index's own order
     * @param limit how many to print
     */
    public static String hostPairs(@Nullable CarrierPose pose, List<CarriedHost> liveHosts, int limit) {
        if (liveHosts == null || liveHosts.isEmpty() || limit <= 0) {
            return "none";
        }
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (CarriedHost host : liveHosts) {
            if (shown >= limit) {
                break;
            }
            if (shown > 0) {
                sb.append(' ');
            }
            sb.append("local=(").append(host.localPos().toShortString()).append(") world=(");
            BlockPos world = null;
            try {
                world = pose == null ? null : CarrierProjection.project(pose, host.localPos());
            } catch (RuntimeException | LinkageError unreadable) {
                // A pose that cannot project a coordinate names no world position; saying "?" is the
                // honest answer and is deliberately not a guess.
                world = null;
            }
            sb.append(world == null ? "?" : world.toShortString()).append(')');
            shown++;
        }
        if (liveHosts.size() > shown) {
            sb.append(" ...+").append(liveHosts.size() - shown);
        }
        return sb.toString();
    }

    // -------------------------------------------------------------- the production --

    /**
     * The one call the entry points make: describe an event, and write the line if it is allowed.
     *
     * @param level the level the event arrived in, or {@code null} when there is none
     * @param entry the entry point's name - one of {@code RIGHT_CLICK_BLOCK}, {@code CONTRAPTION_PACKET},
     *     {@code BREAK_EVENT}, {@code SELF_HEAL}
     * @param player the player behind it, or {@code null} for an event no player caused
     * @param rawPos the position the event carried, unchanged
     * @param face the face the event carried - {@code null} for a break event, which has none
     * @param hand the hand the click was delivered for, or {@code null}
     * @param stack the item that hand held, or {@code null}
     * @param outcome what the entry point made of it
     * @param hit the resolved host when {@code outcome} is {@link Outcome#MATCH}, else {@code null}
     * @param detail the outcome's detail: for a MATCH the carrier/local/world pair is appended by this
     *     method, so {@code detail} carries only the extra facts (the refusal reason, the frame note)
     */
    public static void entry(@Nullable ServerLevel level, String entry, @Nullable ServerPlayer player,
                             @Nullable BlockPos rawPos, @Nullable Direction face,
                             @Nullable InteractionHand hand, @Nullable ItemStack stack,
                             Outcome outcome, @Nullable CarriedBlockRemoval.Hit hit,
                             @Nullable String detail) {
        if (!RCConfig.debugLog() && SINKS.isEmpty()) {
            return;
        }
        try {
            long tick = level == null ? 0L : level.getGameTime();
            boolean anyCarrier = hasLiveCarriers(level);
            // A NONE outcome is never throttled: it is the one answer an operator reads to find out whether the
            // gesture rule, and not the hook, is what dropped the click. It is at most one line per click anyway.
            boolean throttle = outcome != Outcome.MATCH && outcome != Outcome.NONE;
            if (throttle && !THROTTLE.allow(shape(entry, outcome, rawPos, anyCarrier), tick)) {
                return;
            }
            String line = entryLine(entry, whoOf(player), posText(rawPos), faceText(face),
                    handText(hand), itemText(stack), toolText(stack), outcome,
                    matchDetail(outcome, hit, rawPos, detail));
            emit(line);
            if (RCConfig.debugLog()) {
                RCConfig.LOGGER.info("{}", line);
            }
            if (outcome == Outcome.MISS && anyCarrier) {
                emitCandidates(level, entry, rawPos, tick);
            }
        } catch (RuntimeException | LinkageError failed) {
            warnOnce(failed);
        }
    }

    /**
     * The placement path's decision line; see {@link #insertLine}.
     *
     * <p>Written after {@link #entry} for the same event, and it is one line per placement gesture: the
     * caller reaches it only once the click has already resolved to a live carried host, so an unrelated
     * click can never produce it and no throttle is needed for it.
     */
    public static void insert(@Nullable ServerLevel level, String entry, @Nullable ServerPlayer player,
                              @Nullable BlockPos rawPos, @Nullable Direction face,
                              @Nullable InteractionHand hand, @Nullable ItemStack stack,
                              Outcome outcome, @Nullable String detail) {
        if (!RCConfig.debugLog() && SINKS.isEmpty()) {
            return;
        }
        try {
            String line = insertLine(entry, whoOf(player), posText(rawPos), faceText(face),
                    handText(hand), itemText(stack), toolText(stack), outcome, detail);
            emit(line);
            if (RCConfig.debugLog()) {
                RCConfig.LOGGER.info("{}", line);
            }
        } catch (RuntimeException | LinkageError failed) {
            warnOnce(failed);
        }
    }

    /**
     * The two "what was I looking in" strings a miss needs, as one line's worth.
     *
     * <p>Deliberately read <em>after</em> the resolver answered, so the numbers printed are the ones the
     * resolver saw and not a later tick's.
     */
    public static String lookupAt(@Nullable ServerLevel level, String resolver,
                                  @Nullable BlockPos pos) {
        StringBuilder sb = new StringBuilder("(looked up with ").append(resolver)
                .append(" at ").append(posText(pos));
        if (level != null) {
            sb.append(" in ").append(level.dimension().location());
        }
        sb.append("; ");
        try {
            CarriedHostIndex index = level == null ? null : CarrierStore.indexOf(level);
            if (index == null) {
                sb.append("no carrier index for this level");
            } else {
                sb.append("carrierStore=").append(CarrierStore.isActive() ? "on" : "off")
                        .append(" carriers=").append(index.carrierCount())
                        .append(" hosts=").append(index.size())
                        .append(" riding=").append(index.carriedCount())
                        .append(" empty=").append(index.isEmpty());
            }
        } catch (RuntimeException | LinkageError unreadable) {
            sb.append("index unreadable (").append(unreadable.getClass().getSimpleName()).append(')');
        }
        return sb.append(')').toString();
    }

    /**
     * True when this dimension holds anything the carrier layer could resolve at all.
     *
     * <p>One {@code isEmpty()} check, and the input to the throttle's coarse shape: with nothing in
     * flight, position is not information and every miss shares one shape.
     */
    public static boolean hasLiveCarriers(@Nullable ServerLevel level) {
        if (level == null || !CarrierStore.isActive()) {
            return false;
        }
        try {
            CarriedHostIndex index = CarrierStore.indexOf(level);
            return index != null && !index.isEmpty();
        } catch (RuntimeException | LinkageError unreadable) {
            return false;
        }
    }

    /**
     * <b>The candidate list: what the index holds, beside the coordinate the click arrived at.</b>
     *
     * <p>Bounded twice over - at most {@value #CANDIDATE_CARRIER_LIMIT} carriers, at most
     * {@value #CANDIDATE_HOST_LIMIT} live hosts each - and wrapped so that a structure being removed
     * mid-walk cannot throw on the click path. Returns lines ready to log rather than logging itself, so
     * the bound is an assertion rather than a claim.
     */
    public static List<String> candidates(@Nullable ServerLevel level, String entry,
                                          @Nullable BlockPos rawPos, long tick) {
        List<String> out = new ArrayList<>();
        if (level == null) {
            return out;
        }
        try {
            if (!CarrierStore.isActive() || !THROTTLE.allowCandidates(tick)) {
                return out;
            }
            CarriedHostIndex index = CarrierStore.indexOf(level);
            if (index == null || index.isEmpty()) {
                return out;
            }
            List<CarrierId> carriers = index.carriers();
            if (carriers.isEmpty()) {
                return out;
            }
            out.add(candidateHeader(entry, posText(rawPos), level.dimension().location().toString(),
                    Math.min(carriers.size(), CANDIDATE_CARRIER_LIMIT),
                    Math.max(0, carriers.size() - CANDIDATE_CARRIER_LIMIT)));
            int shown = 0;
            for (CarrierId carrier : carriers) {
                if (shown >= CANDIDATE_CARRIER_LIMIT) {
                    break;
                }
                shown++;
                CarrierPose pose = CarrierStore.poseOf(level, carrier);
                List<CarriedHost> live = index.liveHostsOf(carrier);
                out.add(candidateLine(carrier.shortId(), pose == null ? "none (no pose)" : pose.describe(),
                        hostPairs(pose, live, CANDIDATE_HOST_LIMIT)));
            }
        } catch (RuntimeException | LinkageError unreadable) {
            // A candidate list is a courtesy; the miss line above it is the evidence. Never let this
            // reach the click path, and never warn twice for one structure.
            out.add("[redstonecircuit] carrier entry candidates: unavailable ("
                    + unreadable.getClass().getSimpleName() + ')');
        }
        return out;
    }

    /** The production candidate block: build it and log it. */
    private static void emitCandidates(@Nullable ServerLevel level, String entry,
                                      @Nullable BlockPos rawPos, long tick) {
        for (String line : candidates(level, entry, rawPos, tick)) {
            RCConfig.LOGGER.info("{}", line);
        }
    }

    /**
     * <b>The self-healing sweep's candidate decision, as an entry line.</b>
     *
     * <p>The sweep has no event and no player: it walks the index and asks the structure's own block view
     * whether the block at one local coordinate is gone. That decision - and whether a break event was
     * ever seen for this host - is what this line records, because "the sweep ran and left it alone" and
     * "the sweep never ran" are indistinguishable otherwise.
     *
     * <p>Called only for a host the view already reports as <b>gone</b>, which is the definition of a
     * candidate, so it is inherently rare and needs no throttle of its own.
     *
     * @param view what the structure's own view answered (always {@code GONE} at this point, passed on
     *     so the line carries the evidence rather than the claim)
     * @param now where the host's local coordinate is projected right now, or {@code null} when it
     *     cannot be projected at all
     * @param destroyed the decision, or {@code null} when the sweep left the host alone
     * @param refusal why it was left alone, when {@code destroyed} is {@code null}
     */
    public static void selfHeal(@Nullable ServerLevel level, CarrierId carrier, CarriedHost host,
                                @Nullable CarriedBlockRemoval.StructureViewState view,
                                @Nullable BlockPos now,
                                @Nullable CarriedBlockRemoval.Destroyed destroyed,
                                @Nullable String refusal) {
        if (!RCConfig.debugLog() || carrier == null || host == null) {
            return;
        }
        Outcome outcome = destroyed != null ? Outcome.MATCH : Outcome.REFUSED;
        String detail = "view=" + (view == null ? "unreadable" : view.name())
                + " now=" + posText(now)
                + " breakEventSeenAtThisHost=" + breakEventSeenForHost(carrier, host.localPos())
                + (destroyed != null
                        ? " " + destroyDetail(destroyed)
                        : " refused: " + (refusal == null ? "the sweep left it alone" : refusal));
        entry(level, "SELF_HEAL", null, host.localPos(), null, null, null, outcome, null, detail);
    }

    /**
     * One line per sweep that looked at anything, throttled by the same window as everything else.
     *
     * <p>Exists because the other two self-heal lines are conditional: a sweep that runs and finds every
     * block still present writes nothing, and "the sweep is not running at all" - the config set to zero,
     * or no carrier - is then indistinguishable from "the sweep ran and everything is fine". One line per
     * {@value #REPEAT_WINDOW_TICKS} ticks while carriers exist, carrying the budget's own numbers.
     */
    public static void selfHealPass(@Nullable ServerLevel level, int visited, int carriers, int dropped) {
        if (!RCConfig.debugLog()) {
            return;
        }
        try {
            long tick = level == null ? 0L : level.getGameTime();
            if (!THROTTLE.allow("SELF_HEAL_PASS|PASS|any", tick)) {
                return;
            }
            RCConfig.LOGGER.info("[redstonecircuit] carrier self-heal trace: pass visited={} "
                            + "carrier(s)={} dropped={}; a pass that visits anything prints this, so its "
                            + "absence means the sweep is switched off (carrierSelfHealCheckPerTick=0) or "
                            + "there is nothing riding a structure",
                    visited, carriers, dropped);
        } catch (RuntimeException | LinkageError failed) {
            warnOnce(failed);
        }
    }

    // ------------------------------------------------- the "did a break arrive" ledger --

    /**
     * The break events seen, most recent last, keyed by the host they resolved to.
     *
     * <p>This is the ledger that answers the one question no line at break time can: a break event that
     * is never delivered writes nothing, so the only way "this block was removed without a BreakEvent" is
     * readable is from the path that finds it <em>later</em> - the self-healing sweep. When the sweep
     * consumes a host it asks this ledger whether a break event was ever filed for that host, and says so.
     *
     * <p>Bounded to {@value #MAX_REMEMBERED_BREAKS} entries: it remembers "the last few breaks", which is
     * what the operator's own test produces, and it can never grow with the world.
     */
    private static final Set<String> BREAK_SEEN = new LinkedHashSet<>();

    /** Files one break event: the host it resolved to, or {@code null} plus the raw position. */
    public static void noteBreakEvent(@Nullable CarrierId carrier, @Nullable BlockPos localPos,
                                      @Nullable BlockPos rawPos) {
        String key = breakKey(carrier, localPos);
        if (key == null) {
            key = "raw|" + posText(rawPos);
        }
        try {
            if (!BREAK_SEEN.add(key)) {
                return;
            }
            while (BREAK_SEEN.size() > MAX_REMEMBERED_BREAKS) {
                String oldest = BREAK_SEEN.iterator().next();
                BREAK_SEEN.remove(oldest);
            }
        } catch (RuntimeException | LinkageError ignored) {
            // A diagnostic ledger is never allowed to fail a break.
        }
    }

    /**
     * True when a break event was filed for exactly this host.
     *
     * <p>Read by the self-healing sweep: {@code false} means the block left the structure without a break
     * event this mod could see - a Create drill, another mod's edit, a sub-level removal - which is a
     * different defect from "the break arrived and did not resolve".
     */
    public static boolean breakEventSeenForHost(@Nullable CarrierId carrier,
                                                @Nullable BlockPos localPos) {
        String key = breakKey(carrier, localPos);
        return key != null && BREAK_SEEN.contains(key);
    }

    /** How many break events the ledger remembers; never more than {@link #MAX_REMEMBERED_BREAKS}. */
    public static int rememberedBreaks() {
        return BREAK_SEEN.size();
    }

    @Nullable
    private static String breakKey(@Nullable CarrierId carrier, @Nullable BlockPos localPos) {
        return carrier == null || localPos == null ? null
                : carrier.shortId() + "|" + localPos.toShortString();
    }

    // ------------------------------------------------------------------ formatting --

    /** The player's name, or {@code -} when an event had no player (a sweep, a test). */
    public static String whoOf(@Nullable ServerPlayer player) {
        if (player == null) {
            return "-";
        }
        try {
            return player.getGameProfile().getName();
        } catch (RuntimeException | LinkageError unknown) {
            return "?";
        }
    }

    /** A position, or {@code ?} when the event carried none. */
    public static String posText(@Nullable BlockPos pos) {
        return pos == null ? "?" : pos.toShortString();
    }

    /** A face, or {@code n/a} - a break event and a sweep carry none. */
    public static String faceText(@Nullable Direction face) {
        return face == null ? "n/a" : face.getName();
    }

    /** The hand a click was delivered for, or {@code n/a}. */
    public static String handText(@Nullable InteractionHand hand) {
        return hand == null ? "n/a" : hand.name();
    }

    /**
     * The item a hand held: its registry id and count, {@code empty} for a bare hand, or {@code n/a}
     * when the event had no hand at all (a break event with no player, a sweep).
     *
     * <p>An id rather than a display name on purpose - a display name is localised and a log line has to
     * be comparable between two runs.
     */
    public static String itemText(@Nullable ItemStack stack) {
        if (stack == null) {
            return "n/a";
        }
        if (stack.isEmpty()) {
            return "empty";
        }
        ResourceLocation id = null;
        try {
            id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        } catch (RuntimeException | LinkageError noRegistry) {
            id = null;
        }
        return (id == null ? "unregistered" : id.toString()) + " x" + stack.getCount();
    }

    /**
     * Which of the gestures the held item means.
     *
     * <p>The same classification the entry points themselves make, written once so the line and the
     * decision cannot disagree about what a click was: {@code wrench}, {@code rotation rod},
     * {@code component}, {@code bare hand}, {@code other} for an item no gesture here owns, or
     * {@code n/a} when there is no hand at all.
     */
    public static String toolText(@Nullable ItemStack stack) {
        if (stack == null) {
            return "n/a";
        }
        if (stack.isEmpty()) {
            return "bare hand";
        }
        try {
            if (com.jiangyuefengyu.redstonecircuit.RCRegistry.isWrench(stack)) {
                return "wrench";
            }
            if (com.jiangyuefengyu.redstonecircuit.RCRegistry.isRotationRod(stack)) {
                return "rotation rod";
            }
            if (com.jiangyuefengyu.redstonecircuit.HostRules.componentFor(stack) != null) {
                return "component";
            }
        } catch (RuntimeException | LinkageError noRegistry) {
            // A log line must never be the thing that fails a click; "other" is the honest fallback.
            return "other";
        }
        return "other";
    }

    /**
     * The three-state verdict on a miss, read from a bounded scan of the index by local coordinate.
     *
     * <p>A miss whose coordinate is a record's own key but whose record is live is a <em>resolver</em> defect and must
     * not read like "there was nothing there"; a miss whose record is landed is the resolver working as designed; a
     * miss with no record at all is a different thing again (the click was not on a host, or nothing was ever claimed
     * there). Pure, and it names the record's own state rather than a boolean, so the three cannot be confused.
     */
    public static String localVerdict(@Nullable CarrierStore.LocalScan scan,
                                      @Nullable BlockPos localPos) {
        String at = posText(localPos);
        if (scan == null || scan.records().isEmpty()) {
            return RECORD_ABSENT + " (no carrier in this dimension holds a record keyed by " + at
                    + "; scanned " + (scan == null ? 0 : scan.scanned()) + " carrier(s)"
                    + capNote(scan) + ')';
        }
        int live = 0;
        StringBuilder described = new StringBuilder();
        for (CarrierStore.LocalRecord record : scan.records()) {
            if (described.length() > 0) {
                described.append(", ");
            }
            boolean carried = !record.host().landed();
            if (carried) {
                live++;
            }
            described.append(record.carrier().shortId())
                    .append(record.carrier().isSubLevel() ? " (sub-level)" : " (contraption)")
                    .append(" landed=").append(record.host().landed())
                    .append(" at=").append(record.host().at().toShortString())
                    .append(" slot=").append(record.host().slot() == null ? "empty"
                            : record.host().slot().type);
        }
        String tail = " scanned " + scan.scanned() + " carrier(s)" + capNote(scan) + ']';
        if (live == 0) {
            return RECORD_NOT_LIVE + ": a record IS keyed by " + at + ", and none of them is riding ["
                    + described + tail;
        }
        if (live > 1) {
            return RECORD_AMBIGUOUS + ": " + live + " live carriers hold a host at " + at + " ["
                    + described + tail;
        }
        return "a LIVE record IS keyed by " + at + ": " + described
                + " - the clicked coordinate is exactly a live host's own key, so a resolver that misses "
                + "here is looking the record up by something else" + tail;
    }

    private static String capNote(@Nullable CarrierStore.LocalScan scan) {
        return scan != null && scan.capped()
                ? ", capped at " + CarrierStore.MAX_CARRIERS_SCANNED : "";
    }

    /**
     * The tail of a MATCH line: the carrier, the host's local coordinate and where the pose projects it.
     *
     * <p>Formatted here rather than at each call site, so that "local is the structure's frame, world is the
     * projection" is kept true in one place. Package-visible so the format is pinned offline.
     */
    static String matchDetail(Outcome outcome, @Nullable CarriedBlockRemoval.Hit hit,
                                      @Nullable BlockPos rawPos, @Nullable String detail) {
        StringBuilder sb = new StringBuilder();
        if (outcome == Outcome.MATCH) {
            if (hit != null) {
                sb.append("carrier=").append(hit.carrier().shortId())
                        .append(" local=").append(posText(hit.localPos()))
                        .append(" world=").append(posText(hit.worldPos()))
                        .append(" via=").append(hit.projected() ? "projection" : "structure-block")
                        .append(" rawIsLocal=").append(hit.localPos().equals(rawPos));
            } else {
                // A MATCH the caller described itself - the break path resolves through
                // CarriedBlockRemoval#resolve, which answers a Destroyed and not a Hit. Saying so is
                // better than printing a `via=` this method would have had to invent.
                sb.append("resolved (the caller's own detail below; no Hit record)");
            }
        }
        if (detail != null && !detail.isEmpty()) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(detail);
        }
        if (sb.length() == 0) {
            sb.append(outcome == Outcome.MATCH ? "resolved" : "not a carried host");
        }
        return sb.toString();
    }

    /**
     * <b>What the break path resolved, as the MATCH half of a break line.</b>
     *
     * <p>{@code CarriedBlockRemoval#resolve} answers a {@link CarriedBlockRemoval.Destroyed} rather than a
     * {@code Hit}, so the carrier, the local coordinate and the world position are named here instead of
     * by {@link #entry}'s hit formatting. They are spelled {@code resolved*} on purpose: the break path's
     * line can carry both an ordinary {@code Hit} (what {@code hitAt} answered) and this record (what the
     * drop decided on), and in the structure-frame case the two legitimately name different positions -
     * the clicked coordinate and the current projection. Printing both under different names is the
     * difference between evidence and a contradiction.
     *
     * <p>{@code dropPos} is printed beside {@code resolvedWorld} because the two being equal is the whole
     * of the "it dropped where I hit it" promise.
     */
    public static String destroyDetail(@Nullable CarriedBlockRemoval.Destroyed destroyed) {
        if (destroyed == null) {
            return "resolved to nothing";
        }
        return "resolvedCarrier=" + destroyed.carrier().shortId()
                + " resolvedLocal=" + posText(destroyed.localPos())
                + " resolvedWorld=" + posText(destroyed.worldPos())
                + " dropPos=" + posText(destroyed.dropPos())
                + " type=" + (destroyed.slot() == null ? "-" : destroyed.slot().type);
    }

    private static void warnOnce(Throwable failed) {
        if (!warned) {
            warned = true;
            RCConfig.LOGGER.warn("[redstonecircuit] carrier entry trace failed (reported once): {}",
                    failed.toString());
        }
    }

    // ------------------------------------------------------------------ for the tests --

    /** Forgets the throttle and the break ledger; the unit tests' own reset, and nothing else's. */
    public static void reset() {
        THROTTLE.clear();
        BREAK_SEEN.clear();
        warned = false;
    }

    /** The production throttle, exposed so its state can be asserted offline. */
    public static Throttle throttle() {
        return THROTTLE;
    }
}
