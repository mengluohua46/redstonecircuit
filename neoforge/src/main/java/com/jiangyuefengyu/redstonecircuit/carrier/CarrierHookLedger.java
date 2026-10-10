package com.jiangyuefengyu.redstonecircuit.carrier;

import java.util.ArrayList;
import java.util.List;

/**
 * What the Create-side carrier-tool hook registration actually did, as lines for {@code /rc physics}.
 *
 * <p>"A right-click with the wrench does nothing on a physicalised block" has two causes on a contraption: the
 * block type was never given the hook - it never held a component - or the type was refused, because another
 * mod had already registered a {@code MovingInteractionBehaviour} for it and the first-wins rule left it alone.
 * The Sable entry point is unaffected.
 *
 * <p>{@code CarrierToolHook#registeredCount()} counts every attempt; this adds the breakdown beside it. Every
 * list is capped at {@value #LIMIT} entries.
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
     * Package-visible rather than private so the bounds and the text can be pinned by offline unit tests;
     * production uses the {@link #HOOKS} instance and nothing else.
     */
    CarrierHookLedger() {
    }

    /** Records that the tool hook was registered for {@code block} ({@code namespace:path}). */
    public void hook(String block) {
        hookedTotal++;
        add(hooked, block);
    }

    /**
     * Records that the hook was not registered, and why: {@code reason} is a few words naming the guard,
     * normally "another MovingInteractionBehaviour is already registered for it".
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
     * The report lines for {@code /rc physics}: the breakdown, then every remembered refusal. Empty when
     * nothing was ever refused, so the ordinary case costs no output.
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

    /** Forgets everything. */
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
