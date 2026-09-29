package com.jiangyuefengyu.redstonecircuit.logic;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.Slot;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import org.jetbrains.annotations.Nullable;

/**
 * The solve loop of {@link InnerRedstoneNetwork}, with everything that made it about a
 * {@code ServerLevel} taken out.
 *
 * <h2>Why this class exists at all</h2>
 * P2 has to recompute the redstone inside a moving structure, and the structure's blocks do not live
 * at world positions: their coordinates are local to the contraption or to the sub-level. The
 * algorithm, though, does not care. {@link PowerSolver} already answers every question through the
 * narrow {@link PowerEnvironment} interface, and the three-phase loop around it - grow the wire to
 * its fixpoint, schedule the driven components a few ticks ahead, report what changed - only ever
 * touches positions, slots and an environment.
 *
 * <p>So this is that loop, written once, over plain data:
 *
 * <pre>
 *   InnerRedstoneNetwork   world positions + a ServerLevel + world notifications  (unchanged by P2)
 *   CarrierLightNetwork    local positions + any PowerEnvironment                (this class)
 * </pre>
 *
 * <p>Everything that has to happen <em>because</em> the answer changed - telling the client, waking
 * the world, marking the save dirty - stays with the caller, which is exactly why this half can be
 * pinned down by unit tests with no game running.
 *
 * <h2>Notifications are deliberately absent</h2>
 * {@link InnerRedstoneNetwork} follows every change with {@code updateNeighborsAt}, because a stored
 * value is not a block state and nothing else would wake the lamps and pistons around a host block.
 * There is no world to wake <em>from here</em>: a value computed inside a structure is announced by
 * the layer that owns the world position - P4's projection pass in {@code CarrierStore} - and this
 * class stays a pure solve over coordinates, which is what lets it be unit-tested with no game
 * running. Whether an environment can read the world at all is
 * {@link PowerEnvironment#externalSignal}'s business: a frozen one answers 0 on every side (P1/P2),
 * a bridged one reads the place the structure currently occupies (P4).
 *
 * <h2>Delays work exactly as they do in the world</h2>
 * A repeater inside a structure still takes its delay, and it still settles on a fixed schedule. The
 * pending queue is the same {@link PendingFlips}, driven by a tick counter the caller advances once
 * per solve pass rather than by {@code Level#getGameTime} - a moving structure's time base is
 * whichever callback is driving it, and the rule that matters ("do not reschedule while a tick is
 * already pending") is a property of the queue, not of the clock.
 */
public final class CarrierLightNetwork {

    /**
     * Ceiling on the fixpoint passes one component may need in a single solve.
     *
     * <p>The same guard, and the same reasoning, as {@code InnerRedstoneNetwork}: a wire's value only
     * rises and is capped at fifteen, so the loop always terminates; this bound exists so a
     * pathological wiring order cannot stall a tick.
     */
    private static final int MAX_FIXPOINT_PASSES = 512;

    /** Positions whose value changed in the last solve. */
    private final List<BlockPos> changed = new ArrayList<>();

    /** Driven components waiting for their output to follow their input. */
    private final PendingFlips pending = new PendingFlips();

    /** How many solves this network has run; the number the diagnostics print. */
    private int solveCount;

    /** How many components (wire runs and driven components) it has settled. */
    private int componentCount;

    /** The value the caller's clock had on the last solve. */
    private long lastTick = Long.MIN_VALUE;

    private final int maxComponentsPerSolve;

    /**
     * @param maxComponentsPerSolve how many components one solve may touch; the caller's per-tick
     *     budget, so a structure far larger than expected cannot stall a tick
     */
    public CarrierLightNetwork(int maxComponentsPerSolve) {
        this.maxComponentsPerSolve = Math.max(1, maxComponentsPerSolve);
    }

    // ------------------------------------------------------------- reporting --

    /** The positions whose value changed on the last {@link #solve} call. */
    public List<BlockPos> changed() {
        return List.copyOf(changed);
    }

    /** How many solves this network has run since it was created. */
    public int solveCount() {
        return solveCount;
    }

    /** How many components the last solve settled. */
    public int componentCount() {
        return componentCount;
    }

    /** The clock value the last solve ran at, or {@link Long#MIN_VALUE} before the first one. */
    public long lastTick() {
        return lastTick;
    }

    /** How many driven components are waiting for their delayed re-evaluation. */
    public int pendingCount() {
        return pending.size();
    }

    // ---------------------------------------------------------------- solving --

    /**
     * Settles the network containing {@code seed}, and everything already due, at clock value
     * {@code now}.
     *
     * @param seeds what to settle - the hosts the structure is carrying; a seed whose component was
     *     removed in the meantime is skipped, and one already settled by an earlier seed costs a hash
     *     lookup
     * @return how many positions changed value, or {@code -1} when the effect budget ran out before
     *     the whole list could be visited (the caller should say so rather than pretend it settled)
     */
    public int solve(PowerEnvironment env, List<BlockPos> seeds, long now) {
        lastTick = now;
        solveCount++;
        changed.clear();
        componentCount = 0;

        applyDueFlips(env, now);

        Set<BlockPos> reached = new HashSet<>();
        int budget = maxComponentsPerSolve;
        for (BlockPos seed : seeds) {
            if (reached.contains(seed)) {
                continue;
            }
            if (env.nodeAt(seed.getX(), seed.getY(), seed.getZ()) == null) {
                // A seed that holds nothing at all: one lookup, no component, no work. This is the
                // common case for a machine that is mostly stone.
                continue;
            }
            // The component is sized before anything is touched, and the budget is checked against
            // that size. Relaxing resets every wire in the component to zero on the way to its
            // fixpoint, so a pass that ran out of budget half way would leave a component holding
            // half-solved values - which is worse than leaving it alone until the next tick, and is
            // exactly what "effect budget exhausted" is reported as.
            List<BlockPos> component = collectComponent(env, seed);
            if (budget < component.size()) {
                return -1;
            }
            budget -= component.size();
            componentCount++;
            reached.addAll(component);
            relax(env, component, now);
        }
        return changed.size();
    }

    /**
     * Applies every driven component whose scheduled re-evaluation has come due.
     *
     * <p>The output is decided <em>now</em> rather than remembered from when the re-evaluation was
     * scheduled, exactly as in the world network: that is what makes a pulse shorter than the delay
     * disappear instead of being stretched by it.
     */
    private void applyDueFlips(PowerEnvironment env, long now) {
        List<BlockPos> due = pending.takeDue(now);
        for (BlockPos pos : due) {
            PowerEnvironment.Node node = env.nodeAt(pos.getX(), pos.getY(), pos.getZ());
            if (node == null) {
                // Taken out of the structure while its re-evaluation was waiting; nothing to do.
                continue;
            }
            if (!node.type().isDriven()) {
                continue;
            }
            // P4: a bridged environment reads the world at a position relative to this component, so
            // it is told which one that is before it is asked. A no-op for every other environment.
            env.setQueryPosition(pos);
            int desired = PowerSolver.desiredOutput(env, pos, node);
            if (desired == node.power()) {
                continue;
            }
            node.setPower(desired);
            changed.add(pos.immutable());
        }
    }

    /** Re-derives one component's wire values and schedules whatever is stale. */
    private void relax(PowerEnvironment env, List<BlockPos> component, long now) {
        // The wire solve starts from zero, so "did anything change?" has to be answered against
        // where the component was before. Driven components are NOT reset: their output is held
        // until their own scheduled tick replaces it.
        Map<BlockPos, Integer> previous = new HashMap<>(component.size() * 2);
        for (BlockPos pos : component) {
            PowerEnvironment.Node node = env.nodeAt(pos.getX(), pos.getY(), pos.getZ());
            previous.put(pos, node == null ? 0 : node.power());
            if (node != null && node.type().isWire()) {
                node.setPower(0);
            }
        }

        int maxPasses = 2 + Math.min(component.size(), MAX_FIXPOINT_PASSES);
        int pass = 0;
        boolean grew = true;
        while (grew) {
            if (pass++ > maxPasses) {
                break;
            }
            grew = false;
            for (BlockPos pos : component) {
                PowerEnvironment.Node node = env.nodeAt(pos.getX(), pos.getY(), pos.getZ());
                if (node == null || !node.type().isWire()) {
                    continue;
                }
                env.setQueryPosition(pos);
                int target = PowerSolver.wireTarget(env, pos, supplyOf(env, node), node);
                if (target > node.power()) {
                    node.setPower(target);
                    grew = true;
                }
            }
        }

        for (BlockPos pos : component) {
            PowerEnvironment.Node node = env.nodeAt(pos.getX(), pos.getY(), pos.getZ());
            if (node == null) {
                continue;
            }
            int before = previous.get(pos);
            if (before != node.power()) {
                changed.add(pos.immutable());
            }
        }

        for (BlockPos pos : component) {
            PowerEnvironment.Node node = env.nodeAt(pos.getX(), pos.getY(), pos.getZ());
            if (node == null || !node.type().isDriven()) {
                continue;
            }
            env.setQueryPosition(pos);
            int desired = PowerSolver.desiredOutput(env, pos, node);
            if (desired == node.power()) {
                continue;
            }
            int delay = node.type().delayTicks(delayOf(env, pos));
            if (delay <= 0) {
                // A driven component with no delay would be a contradiction; treat it as immediate so
                // a future type cannot silently stop working.
                node.setPower(desired);
                changed.add(pos.immutable());
                continue;
            }
            pending.schedule(pos, now + delay);
        }
    }

    /**
     * The power a wire draws from outside its own network.
     *
     * <p>{@link PowerEnvironment#externalSignal()} is what the structure's outside pushes into the
     * host. Inside a frozen structure that is nothing at all, because P1 closed that boundary; with
     * P4's bridge open it is the world signal at the place the host currently occupies, read through
     * the same rules a host block standing on the ground uses. An injected value (the debug command, a
     * game test) is an input either way and is still honoured.
     */
    private static int supplyOf(PowerEnvironment env, PowerEnvironment.Node node) {
        int supply = Math.max(env.externalSignal(), injectedPowerOf(node));
        return PowerSolver.clamp(supply);
    }

    /**
     * The injected supply of a node, or a value that cannot win.
     *
     * <p>{@link PowerEnvironment.Node} deliberately has no {@code injectedPower}: it is a debug seed
     * rather than part of the solver's contract, and the world implementation reaches it through the
     * {@link Slot} instead. The carrier hosts are slots too, so the same route is open here - but
     * finding the slot from a node would mean a second lookup per hop. Instead the nodes the carrier
     * layer hands in expose their slot directly, and this asks for it.
     */
    private static int injectedPowerOf(PowerEnvironment.Node node) {
        if (node instanceof CarrierNode carrierNode) {
            Slot slot = carrierNode.slot();
            return slot != null && slot.hasInjectedPower() ? slot.injectedPower : -1;
        }
        return -1;
    }

    /**
     * The repeater delay of a node, or 1 when it cannot be read.
     *
     * <p>Same route, and same reasoning, as {@link #injectedPowerOf}: the delay is a property of the
     * stored slot, not of the solver's view of a component.
     */
    private static int delayOf(PowerEnvironment env, BlockPos pos) {
        PowerEnvironment.Node node = env.nodeAt(pos.getX(), pos.getY(), pos.getZ());
        if (node instanceof CarrierNode carrierNode && carrierNode.slot() != null) {
            return carrierNode.slot().delay;
        }
        return 1;
    }

    /**
     * A solver node that can also name the slot behind it.
     *
     * <p>The two things the solve loop needs from the slot rather than from the node - the injected
     * debug supply and a repeater's delay - would otherwise need a second lookup in the carrier
     * index for every component, on every solve. Letting the node hand the slot over keeps the loop
     * at one lookup per position.
     */
    public interface CarrierNode extends PowerEnvironment.Node {

        /** The stored component behind this node, or {@code null} if it has just been removed. */
        @Nullable
        Slot slot();
    }

    // ------------------------------------------------------------ the network --

    /**
     * Collects every component reachable from {@code seed}, in the coordinates the environment
     * speaks.
     *
     * <p>The same rule as {@code InnerRedstoneNetwork#collectComponent}: two neighbours belong to the
     * same network when <em>either</em> of them reaches the other, so a lever feeding a repeater that
     * feeds dust is one component while two repeaters sitting back-to-back are two.
     *
     * <p>Public because the diagnostics count components too, and a second implementation of this
     * walk is how the two would drift apart.
     */
    public static List<BlockPos> collectComponent(PowerEnvironment env, BlockPos seed) {
        List<BlockPos> out = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        queue.add(seed.immutable());
        seen.add(seed.immutable());

        PowerEnvironment.Node root = env.nodeAt(seed.getX(), seed.getY(), seed.getZ());
        if (root == null) {
            return out;
        }
        // A component with no neighbours is a network of one - a lone repeater with no dust anywhere
        // is exactly that, and it still has to follow its input. Recorded here rather than inside the
        // walk so "the seed exists" is answered once.
        out.add(seed.immutable());

        while (!queue.isEmpty()) {
            BlockPos pos = queue.poll();
            PowerEnvironment.Node node = env.nodeAt(pos.getX(), pos.getY(), pos.getZ());
            if (node == null) {
                continue;
            }

            for (Direction direction : Direction.values()) {
                BlockPos neighbour = pos.relative(direction);
                PowerEnvironment.Node other = env.nodeAt(neighbour.getX(), neighbour.getY(),
                        neighbour.getZ());
                if (other == null || !connected(node, other, direction)) {
                    continue;
                }
                if (seen.add(neighbour.immutable())) {
                    out.add(neighbour.immutable());
                    queue.add(neighbour.immutable());
                }
            }
        }
        return out;
    }

    /** True when the two components talk across the side between them, in either direction. */
    private static boolean connected(PowerEnvironment.Node self, PowerEnvironment.Node other,
                                     Direction direction) {
        return PowerSolver.readsFrom(self, direction)
                        && PowerSolver.emitsToward(other, direction.getOpposite())
                || PowerSolver.readsFrom(other, direction.getOpposite())
                        && PowerSolver.emitsToward(self, direction);
    }

    /**
     * Whether a component participates in a network at all.
     *
     * <p>A manual source (a lever, a button, a plate) is never driven and never solved: it is set by
     * the player or by an entity, and the solver only ever reads it. It is still worth solving so
     * that its neighbours see it, which is why this is not used to filter seeds - it exists so the
     * diagnostics can say how many of a structure's hosts are actual logic.
     */
    public static boolean isLogic(ComponentType type) {
        return type.isWire() || type.isDriven();
    }
}
