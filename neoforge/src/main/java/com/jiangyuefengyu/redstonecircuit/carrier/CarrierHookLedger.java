package com.jiangyuefengyu.redstonecircuit.carrier;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>What the Create-side carrier-tool hook registration actually did, as lines for
 * {@code /rc physics}.</b>
 *
 * <h2>The question this exists for</h2>
 * "A right-click with the wrench does nothing on a physicalised block" has two completely different
 * causes on a Create contraption, and until now the mod could only report one of them:
 *
 * <ul>
 *   <li><b>the block type was never given the hook</b> - it has never held a component, so
 *       {@code CarrierToolHook#register} was never called for it, and Create's
 *       {@code Contraption.interactors} map holds nothing for it. Every click on it is Create's own
 *       business and nothing of ours ever runs;</li>
 *   <li><b>the block type was refused</b> - another mod had already registered a
 *       {@code MovingInteractionBehaviour} for it, and the first-wins rule (the same one
 *       {@code MovementHostHook} uses) deliberately left it alone. The Sable entry point is unaffected,
 *       so the symptom is "it works from a sub-level and not from a contraption".</li>
 * </ul>
 *
 * <p>The second one used to be reported exactly once, at registration time, in a log line that is easy to
 * miss; a report run an hour later could not see it at all. This ledger keeps the same facts - bounded,
 * insertion-ordered, and readable on demand - so {@code /rc physics} can print them beside the count.
 *
 * <h2>Why the count in that report is not this class's number</h2>
 * {@code CarrierToolHook#registeredCount()} answers with the number of block types <em>attempted</em>,
 * because that is what its dedupe set holds: a refused type is deliberately never retried. Keeping that
 * number unchanged means an operator can compare it against the log they already have, so this ledger
 * adds the breakdown ({@code hooked=}/{@code refused=}) instead of redefining it. The two numbers are
 * printed together and are only equal when nothing was refused.
 *
 * <h2>Bounds</h2>
 * Every list is capped at {@value #LIMIT} entries. A registration happens once per block type, so this
 * can never be a hot path, and a world with a hundred host block types cannot turn one command into
 * pages of output - it prints the first few and says how many more there were.
 */
public final class CarrierHookLedger {

    /** How many accepted and how many refused block types are remembered, each. */
    public static final int LIMIT = 8;

    /** The mod's own ledger. One instance, because there is one Create registry. */
    public static final CarrierHookLedger HOOKS = new CarrierHookLedger();

    private final List<String> hooked = new ArrayList<>();
    private final List<String> refused = new ArrayList<>();
    private int hookedTotal;
    private int refusedTotal;

    /**
     * Package-visible rather than private so the bounds and the text are pinned by offline unit tests
     * that can build a ledger of their own; production uses the {@link #HOOKS} instance and nothing else.
     */
    CarrierHookLedger() {
    }

    /**
     * Records that the tool hook was registered for one block type.
     *
     * @param block the block type as {@code namespace:path}, so the line names what an operator can point
     *     at rather than a class name
     */
    public void hook(String block) {
        hookedTotal++;
        add(hooked, block);
    }

    /**
     * Records that the hook was <b>not</b> registered, and why.
     *
     * @param block the block type as {@code namespace:path}
     * @param reason a few words naming the guard - normally "another MovingInteractionBehaviour is
     *     already registered for it"
     */
    public void refuse(String block, String reason) {
        refusedTotal++;
        add(refused, "not hooking " + block + " (" + reason + ')');
    }

    /** How many block types were accepted, whether or not they are still in the printed list. */
    public int hookedCount() {
        return hookedTotal;
    }

    /** How many block types were refused, whether or not they are still in the printed list. */
    public int refusedCount() {
        return refusedTotal;
    }

    /** The accepted block types, at most {@link #LIMIT}, oldest first. */
    public List<String> hookedBlocks() {
        return List.copyOf(hooked);
    }

    /** The refusal lines, at most {@link #LIMIT}, oldest first, each beginning {@code not hooking}. */
    public List<String> refusals() {
        return List.copyOf(refused);
    }

    /**
     * The report lines for {@code /rc physics}: the breakdown, then every remembered refusal.
     *
     * <p>Empty when nothing was ever refused, so the ordinary case costs no output.
     */
    public List<String> describe() {
        List<String> out = new ArrayList<>();
        out.add("  carried-tool hook ledger: hooked=" + hookedTotal + " refused=" + refusedTotal
                + " remembered=" + hooked.size() + "/" + refused.size()
                + "  (hooked + refused = the count above; equal only when nothing was refused)");
        if (!hooked.isEmpty()) {
            out.add("    hooked: " + String.join(", ", hooked)
                    + (hookedTotal > hooked.size() ? " ...+" + (hookedTotal - hooked.size()) : ""));
        }
        for (String refusal : refused) {
            out.add("    " + refusal);
        }
        if (refusedTotal > refused.size()) {
            out.add("    ...+" + (refusedTotal - refused.size()) + " more refusal(s)");
        }
        if (!refused.isEmpty()) {
            out.add("    a refused block type keeps its existing behaviour: a carried host of that type "
                    + "cannot be operated with the wrench or the rotation rod FROM A CREATE CONTRAPTION "
                    + "(the Sable sub-level entry point is unaffected)");
        }
        return out;
    }

    /** Forgets everything; the unit tests' own reset, so one test cannot leak into the next. */
    public void clear() {
        hooked.clear();
        refused.clear();
        hookedTotal = 0;
        refusedTotal = 0;
    }

    /** Adds one entry, evicting the oldest once the ceiling is reached. */
    private static void add(List<String> list, String entry) {
        list.add(entry);
        while (list.size() > LIMIT) {
            list.remove(0);
        }
    }
}
