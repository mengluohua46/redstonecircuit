package com.jiangyuefengyu.redstonecircuit.carrier;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

import com.jiangyuefengyu.redstonecircuit.RCConfig;

import net.minecraft.core.BlockPos;

import org.jetbrains.annotations.Nullable;

/**
 * <b>One shared safety net under every carrier entry point, so no carrier action can take the server
 * thread down.</b>
 *
 * <h2>Why this exists</h2>
 * The carrier layer is reached from six places that are all somebody else's call stack: a NeoForge
 * player-interaction event, Create's contraption-interaction packet, {@code BlockEvent.BreakEvent},
 * the tick handler's own sweep, the host-removal notice and the in-structure solve. An exception thrown
 * out of any of them is not "our action failed" - it is a server crash (an event bus rethrows, and the
 * tick handler's exception aborts the tick and stops the game). The author's P1.23 run is the proof:
 * a host a structure was still carrying had had its component taken out, one path called
 * {@code slot().copy()} on it, and the game died with a {@code NullPointerException} out of
 * {@code LevelTickEvent.Post}.
 *
 * <p>Each entry point used to carry its own {@code try/catch} - three of them did, two did not, and the
 * two that did each had their own private "warned once" boolean. That is how the crash got through:
 * the guard was a property of a <em>call site</em> rather than of the layer. Here it is a property of
 * the layer: one {@link Guard} per entry point, each one a single expression that answers "unhandled"
 * and writes exactly one warning the first time it fails, so "this hook never ran" and "this hook ran
 * and threw" cannot look the same in the log.
 *
 * <h2>What it returns, and why that is the safe answer</h2>
 * Every caller of these entry points treats the value as <b>"did the carrier layer own this
 * gesture"</b>: {@code false} means the click, break or packet is left to vanilla and to whatever other
 * mod would have had it. So the fallback is {@code false} - and a failure must produce it, never a
 * guess. The failure also never retries, never loops and never grows a table: one boolean and one
 * counter per guard, both process-lifetime.
 *
 * <h2>Bounded, and free when nothing fails</h2>
 * The whole of it is one {@code try}/{@code catch} and a boolean test on the way out. The ledger is at
 * most {@link #MAX_LEDGER_ENTRIES} rows of fixed text, written only when something has actually gone
 * wrong, and read only by {@code /rc physics}.
 */
public final class CarrierGuards {

    /** How many distinct entry points the failure ledger remembers before the rest are pooled. */
    public static final int MAX_LEDGER_ENTRIES = 12;

    /**
     * Who a guard is protecting, and what the log should say when it fires.
     *
     * <p>Named rather than anonymous because the line has to answer "which hook threw" - the author's
     * log had a generic {@code carrier tool click failed} that named a position and no hook at all.
     */
    public enum Entry {
        /** The ordinary block click Sable sub-levels deliver (plot coordinates). */
        BLOCK_CLICK("carrier block click"),
        /** Create's contraption-interaction packet. */
        CONTRAPTION_PACKET("carrier contraption packet"),
        /** The break path: a carried host's block was destroyed. */
        BREAK("carrier break"),
        /** The periodic sweep that rescues hosts of a structure that died without standing down. */
        SELF_HEAL("carrier self-heal"),
        /** The explicit host-removal notice. */
        HOST_REMOVAL("carrier host removal"),
        /** Storing a component in a carried block, or taking one out. */
        INSERT("carrier insert"),
        /** A bare-hand click working a carried component. */
        SWITCH("carrier bare-hand switch"),
        /** The per-tick pose / projection pass. */
        POSE("carrier pose pass"),
        /** Anything else that is still this layer's business. */
        OTHER("carrier action");

        private final String label;

        Entry(String label) {
            this.label = label;
        }

        /** The words the warning line starts with. */
        public String label() {
            return label;
        }
    }

    /**
     * One entry point's safety net: it answers "unhandled" and warns once.
     *
     * <p>Not a static method with a name argument because the "already warned" flag has to belong to one
     * entry point: a shared flag would let the first failure anywhere silence every later one, which is
     * exactly the blindness that let a second, unguarded call site sit next to a guarded one.
     */
    public static final class Guard {

        private final Entry entry;
        private boolean warned;
        private int failures;

        Guard(Entry entry) {
            this.entry = entry;
        }

        /** Which entry point this is. */
        public Entry entry() {
            return entry;
        }

        /** How many times this entry point has failed; the unit test's seam. */
        public int failures() {
            return failures;
        }

        /** Whether the one warning has been written. */
        public boolean warned() {
            return warned;
        }

        /**
         * Runs one carrier action and turns any failure into the caller's fallback.
         *
         * <p>The exception is caught for a {@code RuntimeException} or a {@code LinkageError} and
         * nothing else: an {@code Error} that is not a linkage failure is the JVM saying something this
         * layer has no business swallowing.
         *
         * @param where the position the action was about, for the warning line
         * @param action what to run
         * @param fallback what to answer when it throws - {@code false} ("not handled") everywhere
         */
        public boolean call(@Nullable BlockPos where, Supplier<Boolean> action, boolean fallback) {
            try {
                Boolean handled = action.get();
                return handled != null && handled;
            } catch (RuntimeException | LinkageError failed) {
                fail(where, failed);
                return fallback;
            }
        }

        /**
         * The same, for an action that produces something rather than a yes/no.
         *
         * <p>{@code fallback} is called with the failure so the caller can decide what "degraded" means
         * for its own return type - a solve pass answers "nothing changed", a lookup answers
         * {@code null}.
         */
        public <T> T call(@Nullable BlockPos where, Supplier<T> action, Function<Throwable, T> fallback) {
            try {
                return action.get();
            } catch (RuntimeException | LinkageError failed) {
                fail(where, failed);
                return fallback.apply(failed);
            }
        }

        /**
         * Records one failure and writes the single warning.
         *
         * <p>Called by {@link #call} and available to a caller that has to catch around something
         * {@code call} cannot wrap - a lambda that captures a mutable local, for instance. It never
         * throws: a diagnostics line must not be the thing that fails the tick.
         */
        public void fail(@Nullable BlockPos where, Throwable failed) {
            failures++;
            CarrierGuards.record(entry, failures, String.valueOf(failed));
            if (warned) {
                return;
            }
            warned = true;
            try {
                RCConfig.LOGGER.warn("[redstonecircuit] {} failed at {} (reported once; the gesture is "
                                + "left to the game, nothing was cancelled): {}",
                        entry.label(), where == null ? "?" : where.toShortString(), failed.toString());
            } catch (RuntimeException | LinkageError ignored) {
                // A logger that cannot log must not turn a contained failure into an uncontained one.
            }
        }

        /** Forgets the warning flag and the count; the unit tests' reset, so one cannot leak into the next. */
        void reset() {
            warned = false;
            failures = 0;
        }
    }

    /**
     * One row of the failure ledger: which entry point failed, how often, and what it said.
     *
     * <p>Exists because the warning is written <b>once</b> per entry point for the whole process: a
     * player who triggers the same failure a hundred times produces one line, and the report is where
     * "and how often" can still be read an hour later.
     */
    private record Row(Entry entry, int failures, String firstFailure) {
    }

    /** At most {@link #MAX_LEDGER_ENTRIES} rows; a fixed-size map, written only on a failure. */
    private static final Map<Entry, Row> LEDGER = new LinkedHashMap<>();

    /** How many failures were on entries beyond the ledger's ceiling; pooled, never dropped silently. */
    private static int pooled;

    /** One guard per entry point. Created once, process-lifetime, and never replaced. */
    public static final Guard BLOCK_CLICK = new Guard(Entry.BLOCK_CLICK);
    public static final Guard CONTRAPTION_PACKET = new Guard(Entry.CONTRAPTION_PACKET);
    public static final Guard BREAK = new Guard(Entry.BREAK);
    public static final Guard SELF_HEAL = new Guard(Entry.SELF_HEAL);
    public static final Guard HOST_REMOVAL = new Guard(Entry.HOST_REMOVAL);
    public static final Guard INSERT = new Guard(Entry.INSERT);
    public static final Guard SWITCH = new Guard(Entry.SWITCH);
    public static final Guard POSE = new Guard(Entry.POSE);
    public static final Guard OTHER = new Guard(Entry.OTHER);

    private CarrierGuards() {
    }

    /** Every guard, so a report or a test can walk them without naming each one. */
    public static java.util.List<Guard> all() {
        return java.util.List.of(BLOCK_CLICK, CONTRAPTION_PACKET, BREAK, SELF_HEAL, HOST_REMOVAL,
                INSERT, SWITCH, POSE, OTHER);
    }

    /**
     * The ledger's report lines, for {@code /rc physics}.
     *
     * <p>Empty when nothing has failed, which is the ordinary state - and the whole point of the report
     * is that its being empty is a positive statement rather than silence.
     */
    public static java.util.List<String> report() {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (LEDGER.isEmpty() && pooled == 0) {
            return out;
        }
        out.add("      carrier guard ledger : " + LEDGER.size() + " entry point(s) have failed at least "
                + "once (each writes one warning and then stays quiet)");
        for (Row row : LEDGER.values()) {
            out.add("        " + row.entry().label() + ": " + row.failures() + " failure(s), first: "
                    + row.firstFailure());
        }
        if (pooled > 0) {
            out.add("        (" + pooled + " further failure(s) were on entry points beyond the first "
                    + MAX_LEDGER_ENTRIES + ")");
        }
        return out;
    }

    /** Forgets the ledger and every guard's warning flag; called when the server stops. */
    public static void clear() {
        LEDGER.clear();
        pooled = 0;
        for (Guard guard : all()) {
            guard.reset();
        }
    }

    /** Records one failure in the bounded ledger. */
    private static synchronized void record(Entry entry, int failures, String firstFailure) {
        Row existing = LEDGER.get(entry);
        if (existing != null) {
            LEDGER.put(entry, new Row(entry, failures, existing.firstFailure()));
            return;
        }
        if (LEDGER.size() >= MAX_LEDGER_ENTRIES) {
            pooled++;
            return;
        }
        LEDGER.put(entry, new Row(entry, failures, firstFailure));
    }
}
