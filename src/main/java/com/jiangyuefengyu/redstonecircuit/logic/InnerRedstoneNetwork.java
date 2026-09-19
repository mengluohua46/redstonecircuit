package com.jiangyuefengyu.redstonecircuit.logic;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneNode;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore;
import com.jiangyuefengyu.redstonecircuit.data.Slot;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Drives power propagation for inner redstone dust.
 *
 * <p>When anything that could affect a dust network changes (a component is placed or removed, a
 * neighbouring block changes, or the world loads), the affected position is marked dirty. At the
 * end of the tick the dirty positions are resolved: the connected dust component around each one is
 * collected, then re-evaluated until its values stop changing.
 *
 * <p>Convergence follows the same reasoning as vanilla: a wire's target strength is a pure function
 * of its neighbours, and a pass can only ever raise a value towards its target. When the input
 * power drops the worklist still terminates, because each pass that lowers a value cannot raise any
 * other value above what that neighbour previously supplied.
 */
public final class InnerRedstoneNetwork {

    /** Safety valve: a component can never need more passes than this. */
    private static final int MAX_PASSES = 16 * 16;

    /** Per-level set of positions awaiting recomputation. */
    private static final java.util.Map<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>, Set<BlockPos>> DIRTY =
            new java.util.HashMap<>();

    /**
     * Re-entrancy guard mirroring vanilla's {@code RedStoneWireBlock#shouldSignal}.
     *
     * <p>While a wire evaluates its own target strength it must not report its current power to
     * neighbours, otherwise a wire would charge itself from its own value and the network could
     * never settle. Vanilla flips a field around its {@code getBestNeighborSignal} call; the
     * equivalent here is a level-scoped flag consulted by the signal query in stage 5.
     */
    private static final Set<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>> SUPPRESS_SIGNAL =
            new HashSet<>();

    private InnerRedstoneNetwork() {
    }

    // ------------------------------------------------------------ scheduling --

    /** Queues a position for recomputation; resolved once per tick by {@link #tick(ServerLevel)}. */
    public static void markDirty(ServerLevel level, BlockPos pos) {
        DIRTY.computeIfAbsent(level.dimension(), key -> new HashSet<>()).add(pos.immutable());
    }

    /** Queues a position and everything around it, for changes that affect neighbours. */
    public static void markDirtyWithNeighbours(ServerLevel level, BlockPos pos) {
        Set<BlockPos> set = DIRTY.computeIfAbsent(level.dimension(), key -> new HashSet<>());
        set.add(pos.immutable());
        for (Direction direction : Direction.values()) {
            set.add(pos.relative(direction).immutable());
        }
    }

    /** Resolves everything queued for this level. Called once per server tick. */
    public static void tick(ServerLevel level) {
        Set<BlockPos> dirty = DIRTY.get(level.dimension());
        if (dirty == null || dirty.isEmpty()) {
            return;
        }
        InnerRedstoneStore store = InnerRedstoneStore.get(level);
        if (store.isEmpty()) {
            dirty.clear();
            return;
        }

        List<BlockPos> snapshot = new ArrayList<>(dirty);
        dirty.clear();
        // Only dust positions that actually exist need solving.
        for (BlockPos pos : snapshot) {
            if (store.slotAt(pos) != null) {
                recompute(level, store, pos);
            }
        }
    }

    /** Drops queued work for a level that is going away. */
    public static void clear(ServerLevel level) {
        DIRTY.remove(level.dimension());
        SUPPRESS_SIGNAL.remove(level.dimension());
    }

    /** True while the given level is evaluating wire power and must not report it to neighbours. */
    public static boolean isSignalSuppressed(net.minecraft.world.level.Level level) {
        return SUPPRESS_SIGNAL.contains(level.dimension());
    }

    // ---------------------------------------------------------- propagation --

    /**
     * Re-evaluates the connected dust component containing {@code seed} until it settles.
     *
     * <p>The component is recomputed from scratch rather than only from {@code seed}, because a
     * removed power source has to propagate a <em>decrease</em> as well, and that requires
     * re-deriving the whole component.
     */
    public static void recompute(ServerLevel level, InnerRedstoneStore store, BlockPos seed) {
        List<BlockPos> component = collectComponent(level, store, seed);
        if (component.isEmpty()) {
            return;
        }

        // Suppress inner-redstone signal output for the WHOLE solve.
        //
        // While the solver is deciding what each component should hold, the values it reads are
        // intermediate: reporting them to the world would let a component charge itself from its own
        // half-computed value. The flag is therefore scoped to this method rather than to individual
        // signal queries, which is both easier to reason about and impossible to leak (it is cleared
        // in a finally block even if a solve blows up).
        boolean alreadySuppressed = !SUPPRESS_SIGNAL.add(level.dimension());
        try {
            relax(level, store, seed, component);
        } finally {
            if (!alreadySuppressed) {
                SUPPRESS_SIGNAL.remove(level.dimension());
            }
        }
    }

    /** The relaxation loop: repeated passes until the component's values stop changing. */
    private static void relax(ServerLevel level, InnerRedstoneStore store, BlockPos seed,
                              List<BlockPos> component) {
        SolverEnvironment env = new SolverEnvironment(level, store);

        // Relax the whole component until nothing changes.
        //
        // A single worklist drain is NOT enough: when a value drops, the neighbour that had been
        // feeding off it is re-queued and drops too, and if that neighbour was the original
        // node's supply the two can drain each other in a vertical pair. Repeating the pass until a
        // fixpoint is reached removes that ordering artefact. The value range is 0-15, so at most
        // MAX_POWER passes can change anything.
        for (int pass = 0; pass < PowerSolver.MAX_POWER; pass++) {
            boolean changed = false;

            Deque<BlockPos> queue = new ArrayDeque<>(component);
            int steps = 0;
            int stepLimit = MAX_PASSES * Math.max(1, component.size());

            while (!queue.isEmpty()) {
                if (++steps > stepLimit) {
                    if (RCConfig.debugLog()) {
                        RCConfig.LOGGER.warn(
                                "[redstonecircuit] inner redstone network at {} did not settle within one pass",
                                seed.toShortString());
                    }
                    break;
                }

                BlockPos pos = queue.poll();
                Slot slot = store.slotAt(pos);
                if (slot == null || slot.type != ComponentType.DUST) {
                    continue;
                }

                // A component's own power is whatever it receives from the vanilla world - a lever or
                // redstone torch pressed against the host block, a neighbouring torch, a lamp
                // already lit - falling back to propagated power for anything that is only a
                // conductor. Dust therefore behaves exactly like vanilla wire, while a torch or
                // lever inside a block actually produces power.
                //
                // `fixedSource` is separate: it keeps the power seeded by /rc place and by the
                // tests, which have no real block to read a signal from.
                int floor = slot.fixedSource ? slot.power : 0;

                env.setQueryPos(pos);
                int target = Math.max(floor,
                        Math.max(env.externalSignal(), PowerSolver.targetStrength(env, pos)));

                if (target != slot.power) {
                    slot.power = target;
                    store.markDirty();
                    changed = true;
                    for (BlockPos neighbour : neighbours(store, pos)) {
                        if (!queue.contains(neighbour)) {
                            queue.add(neighbour);
                        }
                    }
                }
            }

            if (!changed) {
                break;
            }
        }
    }

    /** Collects every dust position connected to {@code seed}, following vanilla's coupling rules. */
    private static List<BlockPos> collectComponent(ServerLevel level, InnerRedstoneStore store, BlockPos seed) {
        List<BlockPos> out = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        queue.add(seed.immutable());
        seen.add(seed.immutable());

        while (!queue.isEmpty()) {
            BlockPos pos = queue.poll();
            Slot slot = store.slotAt(pos);
            if (slot == null || slot.type != ComponentType.DUST) {
                continue;
            }
            out.add(pos);

            for (BlockPos neighbour : neighbours(store, pos)) {
                if (seen.add(neighbour)) {
                    queue.add(neighbour);
                }
            }
        }
        return out;
    }

    /**
     * The dust positions that couple with {@code pos}.
     *
     * <p>All six directions are considered, mirroring {@link PowerSolver#targetStrength}: inner
     * redstone sits inside its host block, so an adjacent block holding a component is directly
     * coupled. Vanilla's terrain-aware climb/step-down rules deliberately do not apply here - see
     * the note on {@code PowerSolver}.
     */
    private static List<BlockPos> neighbours(InnerRedstoneStore store, BlockPos pos) {
        List<BlockPos> out = new ArrayList<>(6);
        for (Direction direction : Direction.values()) {
            addIfDust(store, pos.relative(direction), out);
        }
        return out;
    }

    private static void addIfDust(InnerRedstoneStore store, BlockPos pos, List<BlockPos> out) {
        Slot slot = store.slotAt(pos);
        if (slot != null && slot.type == ComponentType.DUST) {
            out.add(pos.immutable());
        }
    }

    // ------------------------------------------------------------- env view --

    /** Adapts a level plus the store to the headless {@link PowerEnvironment} used by the solver. */
    private static final class SolverEnvironment implements PowerEnvironment {

        private final ServerLevel level;
        private final InnerRedstoneStore store;

        /** The position currently being solved, needed because {@link #bestNeighborSignal()} is a no-arg query. */
        private BlockPos queryPos = BlockPos.ZERO;

        SolverEnvironment(ServerLevel level, InnerRedstoneStore store) {
            this.level = level;
            this.store = store;
        }

        void setQueryPos(BlockPos pos) {
            this.queryPos = pos;
        }

        @Override
        public int bestNeighborSignal() {
            // Mirrors vanilla Level#getBestNeighborSignal: the strongest signal neighbours push in.
            // Inner-redstone output is already suppressed for the duration of the solve, so this sees
            // only real blocks.
            int best = 0;
            for (Direction direction : Direction.values()) {
                BlockPos neighbour = queryPos.relative(direction);
                BlockState state = level.getBlockState(neighbour);
                best = Math.max(best, state.getSignal(level, neighbour, direction.getOpposite()));
                if (best >= PowerSolver.MAX_POWER) {
                    return PowerSolver.MAX_POWER;
                }
            }
            return best;
        }

        @Override
        public WireNode dustAt(int x, int y, int z) {
            Slot slot = store.slotAt(new BlockPos(x, y, z));
            if (slot == null || slot.type != ComponentType.DUST) {
                return null;
            }
            return new WireNode() {
                @Override
                public int power() {
                    return slot.power;
                }

                @Override
                public void setPower(int power) {
                    slot.power = power;
                }
            };
        }

        @Override
        public boolean isRedstoneConductor(int x, int y, int z) {
            BlockPos pos = new BlockPos(x, y, z);
            return level.getBlockState(pos).isRedstoneConductor(level, pos);
        }
    }
}
