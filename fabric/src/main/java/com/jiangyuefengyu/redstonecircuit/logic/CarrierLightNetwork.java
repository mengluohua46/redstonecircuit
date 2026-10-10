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
 * The solve loop of {@link InnerRedstoneNetwork}, with everything that made it about a {@code ServerLevel}
 * taken out: the same algorithm over local coordinates and plain data, so it can be pinned by unit tests.
 *
 * <p>Notifications are absent here - {@link InnerRedstoneNetwork} follows every change with a neighbour
 * update, but there is no world to wake from here, and the layer that owns the world position announces it.
 *
 * <p>A repeater inside a structure still takes its delay, driven by a tick counter the caller advances.
 */
public final class CarrierLightNetwork {

    /**
     * Ceiling on the fixpoint passes one component may need in a single solve: a wire's value only rises and
     * is capped at fifteen, so the loop terminates, but a pathological wiring order must not stall a tick.
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

    /** The caller's per-tick budget, so a structure far larger than expected cannot stall a tick. */
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
     * Settles the network containing each seed, and everything already due, at clock value {@code now}; a
     * seed whose component was removed in the meantime is skipped. Returns how many positions changed value,
     * or {@code -1} when the effect budget ran out before the whole list could be visited.
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
                // A seed that holds nothing: one lookup, no component, no work - the common case for a
                // machine that is mostly stone.
                continue;
            }
            // The component is sized before anything is touched, and the budget is checked against that
            // size: relaxing resets every wire in the component to zero, so a pass that ran out of budget
            // half way would leave half-solved values behind - worse than leaving it until the next tick.
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
     * Applies every driven component whose scheduled re-evaluation has come due. The output is decided now
     * rather than remembered from when it was scheduled, which is what lets a short pulse disappear.
     */
    private void applyDueFlips(PowerEnvironment env, long now) {
        List<BlockPos> due = pending.takeDue(now);
        for (BlockPos pos : due) {
            PowerEnvironment.Node node = env.nodeAt(pos.getX(), pos.getY(), pos.getZ());
            if (node == null) {
                // Taken out of the structure while its re-evaluation was waiting; nothing to do.
                continue;
            }
            if (!node.type().hasInputSide()) {
                continue;
            }
            // A bridged environment reads the world at a position relative to this component, so it is told
            // which one that is before it is asked. A no-op for every other environment.
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
            // The same predicate the world's scheduler uses, so a controller riding a machine is re-derived
            // exactly like one standing in the world.
            if (node == null || !node.type().hasInputSide()) {
                continue;
            }
            env.setQueryPosition(pos);
            int desired = PowerSolver.desiredOutput(env, pos, node);
            if (desired == node.power()) {
                continue;
            }
            int delay = node.type().delayTicks(delayOf(env, pos));
            if (delay <= 0) {
                // A driven component with no delay would be a contradiction; treat it as immediate so a
                // future type cannot silently stop working.
                node.setPower(desired);
                changed.add(pos.immutable());
                continue;
            }
            pending.schedule(pos, now + delay);
        }
    }

    /**
     * The power a wire draws from outside its own network: what the world pushes into the host block, read
     * through the same rules a host block on the ground uses. An injected value is an input either way.
     */
    private static int supplyOf(PowerEnvironment env, PowerEnvironment.Node node) {
        int supply = Math.max(env.externalSignal(), injectedPowerOf(node));
        return PowerSolver.clamp(supply);
    }

    /**
     * The injected supply of a node, or a value that cannot win. The node's own slot is asked rather than
     * looked up again, which keeps the loop at one lookup per position.
     */
    private static int injectedPowerOf(PowerEnvironment.Node node) {
        if (node instanceof CarrierNode carrierNode) {
            Slot slot = carrierNode.slot();
            return slot != null && slot.hasInjectedPower() ? slot.injectedPower : -1;
        }
        return -1;
    }

    /**
     * The repeater delay of a node, or 1 when it cannot be read: the delay is a property of the stored slot,
     * not of the solver's view of a component.
     */
    private static int delayOf(PowerEnvironment env, BlockPos pos) {
        PowerEnvironment.Node node = env.nodeAt(pos.getX(), pos.getY(), pos.getZ());
        if (node instanceof CarrierNode carrierNode && carrierNode.slot() != null) {
            return carrierNode.slot().delay;
        }
        return 1;
    }

    /**
     * A solver node that can also name the slot behind it: the injected supply and a repeater's delay would
     * otherwise need a second lookup in the carrier index for every component, on every solve.
     */
    public interface CarrierNode extends PowerEnvironment.Node {

        /** The stored component behind this node, or {@code null} if it has just been removed. */
        @Nullable
        Slot slot();
    }

    // ------------------------------------------------------------ the network --

    /**
     * Collects every component reachable from {@code seed}, in the coordinates the environment speaks: two
     * neighbours belong together when <em>either</em> of them reaches the other. Public because the
     * diagnostics count components too, and a second implementation of this walk would drift apart.
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
        // A component with no neighbours is a network of one - a lone repeater with no dust anywhere - and
        // it still has to follow its input.
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
     * Whether a component participates in a network at all. A manual source is never driven and never solved -
     * it is set by the player or an entity, and the solver only reads it - but it is still worth solving so
     * that its neighbours see it.
     */
    public static boolean isLogic(ComponentType type) {
        // 时钟源's answer comes from the world clock rather than from an input, and 信号延长器's hold runs out on that
        // same clock: both still have to be solved, and looked at every pass, because time is what changes them.
        return type.isWire() || type.hasInputSide() || type.isTimeDriven();
    }
}
