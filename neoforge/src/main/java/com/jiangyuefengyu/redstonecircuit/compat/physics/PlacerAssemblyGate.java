package com.jiangyuefengyu.redstonecircuit.compat.physics;

/**
 * When 方块放置器 may turn a block into a structure of its own, and when it must stop doing that.
 *
 * <p>Sable gives a sub-level a physics body <b>when it allocates it</b>, before a block has been moved into its
 * plot, and weighs that body afterwards from the plot's contents. A structure whose plot stays empty therefore
 * keeps a body with no mass and no centre of mass for its whole life, which is not a state the physics step can be
 * asked to survive. Two rules follow, and both are facts about <b>this server run</b> rather than about a structure,
 * which is why they live here as run-wide state instead of in a config file:
 *
 * <ul>
 *   <li>{@link #claim} - one assembly per server tick. The rest of a tick's placements wait for a later tick
 *       instead of assembling in the same pass, so the number of new bodies a single pass produces stays at one.</li>
 *   <li>{@link #switchOff} - once a structure comes back without a body, no more structures are assembled for the
 *       rest of the run. The machine is not stopped by that: it keeps placing its block, which simply stays an
 *       ordinary block in the world.</li>
 * </ul>
 *
 * <p>Neither rule names a Sable type or a level, so both are checkable without either.
 */
public final class PlacerAssemblyGate {

    /** The server tick that has already spent its one assembly; {@link Long#MIN_VALUE} is "none yet". */
    private static long claimedTick = Long.MIN_VALUE;

    /** Why assembly was switched off, or {@code null} while it is still allowed. */
    private static String switchedOff;

    private PlacerAssemblyGate() {
    }

    /**
     * Answers whether one carried placement may run now, and takes this tick's assembly when it may.
     *
     * @param serverTick the server's own tick counter, which is one number for every level ticked in it - "one per
     *     tick" has to mean one per server tick, not one per level
     * @return true when this placement may place and assemble; false when another placement already did in this
     *     tick, in which case this one has to come back on a later tick rather than place without its structure
     */
    public static synchronized boolean claim(long serverTick) {
        if (switchedOff != null) {
            // Nothing will be assembled any more, so there is nothing left to ration: holding a placement back would
            // delay a block for an assembly that is never going to happen.
            return true;
        }
        if (serverTick == claimedTick) {
            return false;
        }
        claimedTick = serverTick;
        return true;
    }

    /** Whether more structures may be assembled during this server run. */
    public static synchronized boolean assemblyAllowed() {
        return switchedOff == null;
    }

    /** Why assembly was switched off, or {@code null} while it is still allowed. */
    public static synchronized String switchedOffReason() {
        return switchedOff;
    }

    /**
     * Switches assembly off for the rest of this server run.
     *
     * @return true when this call is what switched it off, so that the caller writes the one line: the first
     *     structure with no body is the one that says why
     */
    public static synchronized boolean switchOff(String reason) {
        if (switchedOff != null) {
            return false;
        }
        switchedOff = reason == null ? "(no reason recorded)" : reason;
        return true;
    }

    /**
     * What is wrong with the body of a structure Sable has just assembled, or {@code null} when nothing is.
     *
     * <p>Every fact that is wrong is named, not just the first, because one line in a log is the only chance to say
     * why the machine stopped making structures.
     *
     * @param mass the body's mass, from Sable's own mass tracker
     * @param centreOfMassPresent whether that tracker holds a centre of mass. Sable uploads a structure's mass only
     *     through it, so a structure without one has never had a mass at all
     * @param plotEmpty whether the plot's bounding box is the empty one - the cheap early signal, since that box is
     *     built from the non-air blocks that were carried in
     * @param poseReadable whether the sub-level's pose could be read
     */
    public static String bodyProblem(double mass, boolean centreOfMassPresent, boolean plotEmpty,
                                     boolean poseReadable) {
        StringBuilder problem = new StringBuilder();
        add(problem, !Double.isFinite(mass) || mass <= 0.0, "mass=" + mass);
        add(problem, !centreOfMassPresent, "centreOfMass=absent");
        add(problem, plotEmpty, "plotBounds=empty");
        add(problem, !poseReadable, "pose=unreadable");
        return problem.isEmpty() ? null : problem.toString();
    }

    private static void add(StringBuilder problem, boolean wrong, String fact) {
        if (!wrong) {
            return;
        }
        if (!problem.isEmpty()) {
            problem.append(' ');
        }
        problem.append(fact);
    }

    /** Forgets both rules, as a freshly started server would. Every test starts from here. */
    public static synchronized void forgetForTests() {
        claimedTick = Long.MIN_VALUE;
        switchedOff = null;
    }
}
