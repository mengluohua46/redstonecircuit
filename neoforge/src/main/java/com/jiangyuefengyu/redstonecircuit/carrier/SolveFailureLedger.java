package com.jiangyuefengyu.redstonecircuit.carrier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.jiangyuefengyu.redstonecircuit.RCConfig;

import org.jetbrains.annotations.Nullable;

/**
 * <b>The one fallback under the in-carrier solve: it counts every failure and says each kind once.</b>
 *
 * <h2>Why the old catch was wrong twice over</h2>
 * {@code CarrierNetworks#solveAll} wraps each structure's pass in a {@code try/catch} so that one bad
 * structure cannot take the server tick down. That part is right and stays. What it did with the
 * exception was not: it called {@code LOGGER.warn("carrier {} solve failed: {}", …)} on <b>every</b>
 * failure. The author's P1.24 log has <b>53 identical lines</b> from one emptied host on one moving
 * structure - one per tick for as long as the structure stayed in flight:
 *
 * <pre>
 *   [redstonecircuit] carrier s:aa684475 solve failed: java.lang.NullPointerException:
 *       Cannot read field "type" because "slot" is null
 * </pre>
 *
 * <p>Two failures of that design, and they pull in opposite directions:
 *
 * <ol>
 *   <li><b>It floods.</b> A defect that happens every tick for a minute is thousands of lines, and the
 *       log the author has to read becomes unreadable exactly when it matters.</li>
 *   <li><b>And a naive "warn once" would be worse.</b> A single process-lifetime flag on this catch
 *       would let the first {@code NullPointerException} silence a later, unrelated
 *       {@code IllegalStateException} - which is how a real defect hides behind a known one.</li>
 * </ol>
 *
 * <p>So the rule here is <b>once per exception KIND</b>, with the count kept for every kind and the
 * whole ledger bounded. "Kind" is the exception's own class: {@code NullPointerException} and
 * {@code IllegalStateException} are different defects and each gets its own line; a hundred
 * {@code NullPointerException}s get one line and a count.
 *
 * <h2>Bounded, and honest about its own ceiling</h2>
 * At most {@link #MAX_KINDS} kinds are remembered. The first kind that does not fit is still
 * <b>reported once</b> - otherwise the ceiling itself would become the blind spot it exists to prevent -
 * and the fact that kinds are being pooled is stated in the report rather than dropped. The table is
 * process-lifetime, never grows past the ceiling, and is cleared with the rest of the carrier state when
 * the server stops.
 *
 * <h2>Never itself a failure</h2>
 * Nothing here throws: a diagnostics line must not be able to turn a contained failure into an
 * uncontained one, so the log call and every lookup are wrapped.
 */
public final class SolveFailureLedger {

    /** How many distinct exception kinds are remembered by name before the rest are pooled. */
    public static final int MAX_KINDS = 8;

    /** One remembered kind: its name, how often it happened, and the first one seen. */
    private record KindRow(String typeName, int failures, String firstCarrier, String firstMessage) {
    }

    /** kind name -> what was seen, in first-seen order; never longer than {@link #MAX_KINDS}. */
    private static final Map<String, KindRow> KINDS = new LinkedHashMap<>();

    /** Every failure, including the kinds the ledger could not name. */
    private static int failures;

    /** How many kinds did not fit, and whether the one warning about that has been written. */
    private static int pooled;
    private static boolean pooledWarned;

    private SolveFailureLedger() {
    }

    /**
     * Records one solve failure: counts it, and warns the first time <b>this kind</b> is seen.
     *
     * <p>The message names the structure, the exception's class and its own message, and says outright
     * that repeats are counted rather than printed - so a reader who sees one line knows a second one is
     * not coming and does not have to guess whether the solve recovered.
     *
     * @param carrier the structure whose pass threw, or {@code null} when it is not known
     * @param failed what it threw; the class is the kind, the message is kept for the first of each
     */
    public static synchronized void fail(@Nullable CarrierId carrier, Throwable failed) {
        failures++;
        if (failed == null) {
            // A null throwable is not a thing Java can produce, but this method is on the path that must
            // never throw - so an impossible input is counted and dropped rather than dereferenced.
            return;
        }
        String kind = kindOf(failed);
        String where = carrier == null ? "?" : carrier.shortId();
        String message = messageOf(failed);

        KindRow existing = KINDS.get(kind);
        if (existing != null) {
            KINDS.put(kind, new KindRow(kind, existing.failures() + 1, existing.firstCarrier(),
                    existing.firstMessage()));
            return;
        }
        if (KINDS.size() >= MAX_KINDS) {
            pooled++;
            if (!pooledWarned) {
                pooledWarned = true;
                warn("carrier " + where + " solve failed with an exception kind the ledger is already "
                        + "full of (" + MAX_KINDS + " kind(s) named); this is the first of the rest and "
                        + "no further new kind will be printed. First one: " + kind + ": " + message
                        + " - repeats of every kind are counted, not printed (see /rc physics)");
            }
            return;
        }
        KINDS.put(kind, new KindRow(kind, 1, where, message));
        warn("carrier " + where + " solve failed (reported once for this kind of failure; repeats are "
                + "counted, not printed - see /rc physics): " + kind + ": " + message);
    }

    /** Every solve failure since the server started, of every kind, including the pooled ones. */
    public static synchronized int failureCount() {
        return failures;
    }

    /** How many distinct exception kinds have been remembered by name. */
    public static synchronized int distinctKinds() {
        return KINDS.size();
    }

    /** Whether this exception kind has already had its one warning. */
    public static synchronized boolean warnedFor(@Nullable Class<?> type) {
        return type != null && KINDS.containsKey(type.getName());
    }

    /**
     * The ledger, for {@code /rc physics}.
     *
     * <p>Empty when nothing has failed, which is the ordinary state - so the report's being empty is a
     * positive statement rather than silence. One line per kind: how often, on which structure first,
     * and what it said.
     */
    public static synchronized List<String> report() {
        List<String> out = new ArrayList<>();
        if (failures == 0) {
            return out;
        }
        out.add("      carrier solve ledger: " + failures + " failure(s) across " + KINDS.size()
                + " named kind(s)" + (pooled > 0 ? " plus " + pooled + " beyond the ledger" : "")
                + " (each kind warns once and then stays quiet)");
        for (KindRow row : KINDS.values()) {
            out.add("        " + row.typeName() + ": " + row.failures() + " failure(s), first on carrier "
                    + row.firstCarrier() + ": " + row.firstMessage());
        }
        if (pooled > 0) {
            out.add("        (" + pooled + " failure(s) were of kinds beyond the first " + MAX_KINDS
                    + "; they are counted here and were warned about once, when the first one appeared)");
        }
        return out;
    }

    /** Forgets everything; called when the server stops, so nothing survives a save. */
    public static synchronized void clear() {
        KINDS.clear();
        failures = 0;
        pooled = 0;
        pooledWarned = false;
    }

    /** The exception's own class name: two different defects must never share one line. */
    private static String kindOf(Throwable failed) {
        try {
            return failed.getClass().getName();
        } catch (RuntimeException | LinkageError ignored) {
            return "unknown";
        }
    }

    /** The exception's message, or a placeholder - never {@code null}, so the line always reads. */
    private static String messageOf(Throwable failed) {
        try {
            String message = failed.getMessage();
            return message == null ? failed.toString() : message;
        } catch (RuntimeException | LinkageError ignored) {
            return "unreadable";
        }
    }

    /** One warning, and a logger that cannot log must not turn this into a thrown failure. */
    private static void warn(String line) {
        try {
            RCConfig.LOGGER.warn("[redstonecircuit] {}", line);
        } catch (RuntimeException | LinkageError ignored) {
            // Contained on purpose: see the class comment.
        }
    }
}
