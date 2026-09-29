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
 * <b>The adoption sweep: the Sable half of P1.30's repair, and the only place that turns "a component is
 * in the world store inside this sub-level's plot" into candidates.</b>
 *
 * <h2>Why this is not part of {@link SableCarrierClaim}</h2>
 * The two sweeps look similar and answer opposite questions about the same pair of stores:
 *
 * <pre>
 *   claim    a component is in the world store at a WORLD position inside the structure's world bounds
 *            -&gt; invert the pose to find its local coordinate -&gt; file it there
 *   adopt    a component is in the world store at a LEVEL position that is one of the structure's own
 *            plot cells (i.e. already at its local coordinate)          -&gt; file it there
 * </pre>
 *
 * <p>A claim is a <b>move</b> of a component the structure has just taken out of the world; the pose is
 * what connects the two frames, and {@code blockHasLeftTheWorld} is what proves the block really went.
 * An adoption is not a move at all: the coordinate is <em>already</em> the structure's own storage
 * coordinate (see {@code CarrierStore.CarrierSource#plotOwnerAt} and
 * {@code CarrierStructureAccess#subLevelPlot} - a sub-level's blocks are the level's blocks at their plot
 * coordinates), and "the block has left the world" is meaningless because the plot cell <b>is</b> the
 * block. Keeping the two in one method would mean one predicate with two meanings, which is how the two
 * truths this class exists to remove would come back.
 *
 * <p>The candidate rule is therefore the plot's own bounding box, not the world bounds, and that is the
 * whole of the narrowing: a world position in the main world can never satisfy it unless a structure's
 * plot region really covers that number.
 *
 * <h2>Bounded, throttled and idempotent</h2>
 * <ul>
 *   <li><b>bounded</b>: at most {@link #MAX_NODES_PER_PASS} world-store entries are examined per structure
 *       per attempt, so a machine whose plot contains a thousand entries is finished over the following
 *       attempts rather than in one tick;</li>
 *   <li><b>throttled</b>: {@link #ADOPT_INTERVAL} ticks between attempts on one structure, staggered by
 *       its UUID so a world with many structures does not examine them all on the same tick, and at most
 *       {@link #MAX_PASSES} attempts in total - after which the structure is left alone, so a world with
 *       nothing to adopt stops costing anything;</li>
 *   <li><b>idempotent</b>: the decision is {@code WorldComponentAdoption#plan}, which answers
 *       {@code already-carried} whenever the index already holds a live record at the coordinate, and the
 *       claim itself removes the world entry - so a coordinate is adopted once and then cannot even be a
 *       candidate again.</li>
 * </ul>
 *
 * <h2>Nothing here runs unless Sable is installed</h2>
 * Every Sable type this class names lives in the compat source set, and the class is only reached from
 * {@link SableCarrierClaim#sweep}, which is only installed after a {@code ModList} check.
 */
final class SableComponentAdoption {

    /**
     * How many level ticks between two adoption attempts on one structure.
     *
     * <p>Twenty - one second. The state being repaired is a <b>saved</b> state: an entry written before
     * the structure was saved, or written while this build was not managing the coordinate. Nothing about
     * it is urgent, and nothing about it changes on the tick scale, so a second's latency costs the player
     * nothing while a per-tick scan of the world store would be a per-tick cost on every structure in the
     * world for ever.
     */
    static final int ADOPT_INTERVAL = 20;

    /**
     * How many times one structure is examined before it is left alone.
     *
     * <p>Three hundred attempts at twenty ticks each is a hundred seconds of coverage from the moment the
     * structure is first seen, which covers a save being loaded, a player walking up to a machine whose
     * chunk has just loaded, and a component placed by anything else that reaches the world store. After
     * that the structure is dropped from the sweep, so the steady-state cost of this feature in a world
     * with a machine in it is <b>zero</b>.
     */
    static final int MAX_PASSES = 300;

    /**
     * The most world-store entries one structure may examine in one attempt.
     *
     * <p>Sixty-four, as a hard ceiling on the one loop in this class. The loop is one containment
     * comparison per entry against numbers already read (no block read, no chunk load, no pose
     * arithmetic), so the ceiling is about honesty rather than about cost: "at most this much work per
     * structure per second" is a claim that can be checked by reading one line.
     */
    static final int MAX_NODES_PER_PASS = 64;

    private SableComponentAdoption() {
    }

    /**
     * One attempt at one structure, called from the claim sweep.
     *
     * @param present already-known liveness - the caller has the {@code SubLevel} in front of it, so this
     *     is {@code true} by construction and is passed rather than asked again
     * @return how many components were adopted
     */
    static int adopt(ServerLevel level, SubLevel subLevel, SubLevelSpace space, boolean present) {
        if (level == null || subLevel == null || space == null || !present
                || !CarrierStore.isActive() || !RCConfig.adoptWorldComponents()) {
            return 0;
        }
        try {
            return sweep(level, subLevel, space);
        } catch (RuntimeException | LinkageError failed) {
            // An adoption failing must never take a level tick down, and it must never leave the two
            // stores in a state that is worse than the one it found: the world entry is only removed by
            // `claim`, which either completed or did not.
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
            // WHERE THE COMPONENT CAME FROM, as this round defines it: the world position the structure's
            // own pose names for this coordinate right now. There is no "position it was moved from" to
            // recover for a component that is already inside the structure's own storage, and inventing
            // one - the plot coordinate, say - would put the release fallback two million blocks from
            // anywhere a player can go. The rule, its three edge cases and its reasons are written out at
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
     * The world-store entries that stand at one of this structure's own plot coordinates.
     *
     * <p>Bounded by the store, not by the plot: see the class doc, and {@link #MAX_NODES_PER_PASS}. The
     * plot's own bounding box is a block range in the level's coordinates, so the test is one containment
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
