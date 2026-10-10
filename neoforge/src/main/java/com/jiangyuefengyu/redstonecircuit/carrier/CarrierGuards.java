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
 * <p>六个入口点都在别人的调用栈上（NeoForge 事件、Create 交互包、{@code BlockEvent.BreakEvent}、tick 扫描、
 * host 移除通知、结构内求解），异常从这里抛出去就是服务器崩溃，所以守卫是这一层的属性而不是调用点的:
 * 每个入口一个 {@link Guard}，各写一次警告。返回 {@code false} 意为"这次手势不归这一层"，失败必须给出它。
 */
public final class CarrierGuards {

    /** How many distinct entry points the failure ledger remembers before the rest are pooled. */
    public static final int MAX_LEDGER_ENTRIES = 12;

    /** Who a guard is protecting, and what the log should say when it fires - named, so "which hook threw"
     * can be answered rather than naming a position and no hook at all. */
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

    /** One entry point's safety net: it answers "unhandled" and warns once. The "already warned" flag belongs
     * to one entry point - a shared flag would let the first failure anywhere silence every later one. */
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

        /** Runs one carrier action and turns any failure into the caller's fallback, which is {@code false}
         * everywhere. Only a {@code RuntimeException} or a {@code LinkageError} is caught. */
        public boolean call(@Nullable BlockPos where, Supplier<Boolean> action, boolean fallback) {
            try {
                Boolean handled = action.get();
                return handled != null && handled;
            } catch (RuntimeException | LinkageError failed) {
                fail(where, failed);
                return fallback;
            }
        }

        /** The same, for an action that produces something rather than a yes/no: {@code fallback} is called with
         * the failure, so "degraded" means whatever the caller's own return type needs. */
        public <T> T call(@Nullable BlockPos where, Supplier<T> action, Function<Throwable, T> fallback) {
            try {
                return action.get();
            } catch (RuntimeException | LinkageError failed) {
                fail(where, failed);
                return fallback.apply(failed);
            }
        }

        /** Records one failure and writes the single warning. It never throws: a diagnostics line must not be
         * the thing that fails the tick. */
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

    /** One row of the failure ledger: which entry point failed, how often, and what it said. The warning is
     * written once per entry point, so the ledger is where "and how often" can still be read later. */
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

    /** The ledger's report lines, for {@code /rc physics}. Empty when nothing has failed, which is the
     * ordinary state and a positive statement rather than silence. */
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
