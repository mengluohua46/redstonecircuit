package com.jiangyuefengyu.redstonecircuit.compat.physics;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierStore;
import com.jiangyuefengyu.redstonecircuit.carrier.SubLevelSpace;
import com.jiangyuefengyu.redstonecircuit.carrier.WorldComponentAdoption;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneNode;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore;

import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import org.jetbrains.annotations.Nullable;

/**
 * The adoption sweep: the Sable half of the repair, and the only place that turns "a component is in the world store
 * inside this sub-level's plot" into candidates.
 *
 * <p>A claim is a <b>move</b>: the pose connects the two frames, and {@code blockHasLeftTheWorld} proves the block
 * really went. An adoption is not a move at all - the coordinate is <em>already</em> the structure's own storage
 * coordinate (a sub-level's blocks are the level's blocks at their plot coordinates, see
 * {@code CarrierStore.CarrierSource#plotOwnerAt}) - so "the block has left the world" is meaningless: the plot cell
 * <b>is</b> the block. Keeping the two in one method would mean one predicate with two meanings.
 *
 * <p>The candidate rule is therefore the plot's own bounding box: at most {@link #MAX_NODES_PER_PASS} world-store
 * entries per structure per attempt, {@link #ADOPT_INTERVAL} ticks apart and staggered by UUID, at most
 * {@link #MAX_PASSES} attempts in total - after which nothing is examined, so a world with nothing to adopt stops
 * costing anything. The decision ({@code WorldComponentAdoption#plan}) is idempotent, and its claim removes the world
 * entry.
 *
 * <p>Nothing here runs unless Sable is installed: the class is reached only from {@link SableCarrierClaim#sweep}, which
 * is installed after a {@code ModList} check.
 */
final class SableComponentAdoption {

    /**
     * How many level ticks between two adoption attempts on one structure: twenty, one second. The state being repaired
     * is a <b>saved</b> state - an entry written before the structure was saved, or while this build was not managing
     * the coordinate - so a second's latency costs the player nothing, while a per-tick scan of the world store would be
     * a per-tick cost on every structure for ever.
     */
    static final int ADOPT_INTERVAL = 20;

    /**
     * How many times one structure is examined before it is left alone: three hundred attempts at twenty ticks each is
     * a hundred seconds of coverage from the moment the structure is first seen, which covers a save being loaded, a
     * player walking up to a machine whose chunk has just loaded, and a component placed by anything else that reaches
     * the world store. After that the structure is dropped from the sweep, so the steady-state cost of this feature in a
     * world with a machine in it is <b>zero</b>.
     */
    static final int MAX_PASSES = 300;

    /**
     * The most world-store entries one structure may examine in one attempt: sixty-four, a hard ceiling on the one loop
     * in this class. The loop is one containment comparison per entry against numbers already read - no block read, no
     * chunk load, no pose arithmetic - so the ceiling is about honesty rather than about cost: "at most this much work
     * per structure per second" is a claim that can be checked by reading one line.
     */
    static final int MAX_NODES_PER_PASS = 64;

    private SableComponentAdoption() {
    }

    /**
     * One attempt at one structure, called from the claim sweep. {@code present} is already-known liveness - the caller
     * has the {@code SubLevel} in front of it - and the answer is how many components were adopted.
     */
    static int adopt(ServerLevel level, SubLevel subLevel, SubLevelSpace space, boolean present) {
        if (level == null || subLevel == null || space == null || !present
                || !CarrierStore.isActive() || !RCConfig.adoptWorldComponents()) {
            return 0;
        }
        try {
            return sweep(level, subLevel, space);
        } catch (RuntimeException | LinkageError failed) {
            // An adoption failing must never take a level tick down, nor leave the two stores worse than it found
            // them: the world entry is only removed by `claim`, which either completed or did not.
            RCConfig.LOGGER.warn("[redstonecircuit] adoption sweep failed for sub-level {}: {}",
                    space.uuid(), failed.toString());
            return 0;
        }
    }

    /** The sweep itself, wrapped by {@link #adopt} for the failure path. */
    private static int sweep(ServerLevel level, SubLevel subLevel, SubLevelSpace space) {
        InnerRedstoneStore store = InnerRedstoneStore.get(level);
        if (store == null || store.isEmpty()) {
            return 0;
        }
        List<BlockPos> candidates = candidates(level, subLevel, store);
        if (candidates.isEmpty()) {
            return 0;
        }
        int adopted = 0;
        for (BlockPos local : candidates) {
            // WHERE THE COMPONENT CAME FROM: the world position the structure's own pose names for this coordinate
            // right now. There is no "position it was moved from" to recover for a component already inside the
            // structure's own storage, and inventing one - the plot coordinate, say - would put the release fallback
            // two million blocks from anywhere a player can go. The rule and its three edge cases are written out at
            // `WorldComponentAdoption#originFor` and `CarrierStore#adoptWorldComponent`.
            BlockPos origin = WorldComponentAdoption.originFor(local, space::worldFor);
            if (origin == null) {
                // No readable pose, or one that does not answer with finite numbers: this code cannot say
                // where the block is, so it does not record a position for it. Nothing is adopted on a
                // guess; the next attempt will try again.
                continue;
            }
            WorldComponentAdoption.Plan plan = CarrierStore.adoptWorldComponent(level, space.carrier(),
                    local, origin, "in a sub-level's own plot storage with no carrier record");
            if (plan != null && plan.adopts()) {
                adopted++;
            }
        }
        return adopted;
    }

    /**
     * The world-store entries that stand at one of this structure's own plot coordinates. Bounded by the store, not by
     * the plot: the plot's own bounding box is a block range in the level's coordinates, so the test is one containment
     * comparison per entry - no pose, no block read, no chunk load.
     */
    private static List<BlockPos> candidates(ServerLevel level, SubLevel subLevel,
                                             InnerRedstoneStore store) {
        List<BlockPos> out = new ArrayList<>();
        LevelPlot plot = subLevel.getPlot();
        BoundingBox3ic bounds = plot == null ? null : plot.getBoundingBox();
        if (bounds == null) {
            return out;
        }
        for (BlockPos pos : store.positions()) {
            if (out.size() >= MAX_NODES_PER_PASS) {
                break;
            }
            if (pos == null || !bounds.contains(pos.getX(), pos.getY(), pos.getZ())) {
                continue;
            }
            InnerRedstoneNode node = store.get(pos);
            if (node == null || node.isEmpty()) {
                continue;
            }
            out.add(pos.immutable());
        }
        return out;
    }

    /** The UUID of a structure, or {@code null} when it cannot be read; for the sweep's bookkeeping. */
    @Nullable
    static UUID uuidOf(@Nullable SubLevel subLevel) {
        return subLevel == null ? null : subLevel.getUniqueId();
    }
}
