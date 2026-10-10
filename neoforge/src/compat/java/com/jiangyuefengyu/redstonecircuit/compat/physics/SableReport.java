package com.jiangyuefengyu.redstonecircuit.compat.physics;

import java.util.ArrayList;
import java.util.List;

import com.jiangyuefengyu.redstonecircuit.RCConfig;

import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import org.joml.Vector3d;

/**
 * The Sable half of the {@code /rc physics} diagnostics: everything that report prints that Sable can
 * answer. It is <b>read-only</b> - every method is a query or a string builder - and safe with Sable absent,
 * because it is reached only through {@code PhysicsCompat}, which resolves it by name after a real
 * {@code ModList} check.
 *
 * <p>Three questions it settles: what {@code getContaining} really means (it looks up which sub-level plot
 * occupies a world chunk, with no vertical component, so an ordinary block in a covered chunk comes back
 * non-null - {@link #containing} prints the raw answer and {@link #looksLikeGround} says whether the block
 * there is still an ordinary one), which local coordinate space a sub-level's own blocks live in
 * ({@link #roundTrip}), and whether {@code getAllSubLevels} is the right enumeration
 * ({@link #describeSubLevels}).
 */
final class SableReport {

    /** How many characters of a sub-level's pose go on one line before it stops being readable. */
    private static final int POSE_TEXT_LIMIT = 240;

    private SableReport() {
    }

    // --------------------------------------------------------------- queries --

    /**
     * The raw {@code getContaining} answer, exactly as Sable returns it.
     *
     * <p>Not wrapped in anything: if this comes back non-null for an ordinary block on the ground, that is
     * the finding, and it must be visible in the log unedited.
     */
    static SubLevelAccess containing(Level level, BlockPos worldPos) {
        return SableCompanionAccess.getContaining(level, worldPos);
    }

    /**
     * Whether a position Sable says belongs to a sub-level is nonetheless still an ordinary block in
     * the world.
     *
     * <p>This is the practical form of that warning: a moved block leaves air behind, so
     * {@code worldState.isAir() && containing != null} means the position really is inside a moving
     * structure, while {@code !isAir() && containing != null} means {@code getContaining} is reporting the
     * plot grid rather than the block, and is not a usable predicate.
     */
    static boolean looksLikeGround(Level level, BlockPos worldPos, SubLevelAccess containing) {
        return containing != null && !level.getBlockState(worldPos).isAir();
    }

    /**
     * Every sub-level whose world-space bounds intersect a one-block box around {@code worldPos}, and
     * which actually contains the point.
     *
     * <p>Two different questions are answered here: {@code getAllIntersecting} is Sable's own spatial query,
     * and {@code boundingBox().contains(x+0.5, y+0.5, z+0.5)} is the cheap, understandable version of the
     * same thing. Their disagreeing is itself information.
     */
    static List<SubLevel> intersecting(Level level, BlockPos worldPos) {
        List<SubLevel> found = new ArrayList<>();
        for (SubLevelAccess access : SableCompanionAccess.getAllIntersecting(level, boxAround(worldPos))) {
            if (access instanceof SubLevel subLevel && contains(subLevel, worldPos)) {
                found.add(subLevel);
            }
        }
        return found;
    }

    // -------------------------------------------------------------- reporting --

    /** {@code containing(...)} plus the two readings that decide what it means. */
    static List<String> describeContaining(Level level, BlockPos worldPos) {
        List<String> out = new ArrayList<>();
        SubLevelAccess containing = containing(level, worldPos);
        BlockState worldState = level.getBlockState(worldPos);

        if (containing == null) {
            out.add("getContaining -> null   (query = which chunk does a sub-level plot own; null means none does)");
        } else {
            out.add("getContaining -> " + nameOf(containing) + "  uuid=" + containing.getUniqueId());
            out.add("  contains(target)= " + contains(containing, worldPos)
                    + "   (false means the chunk is covered but this point is not inside the structure)");
            boolean ground = looksLikeGround(level, worldPos, containing);
            out.add("  VERDICT: " + (ground
                    ? "GROUND BLOCK STILL IN THE WORLD -> getContaining returned non-null for a block that is "
                            + "NOT in a moving structure. It is a plot-grid query, NOT an 'am I inside a "
                            + "structure' predicate."
                    : "the block here is air -> this position really is inside a moving structure; "
                            + "getContaining is usable HERE."));
        }
        out.add("worldState = " + worldState
                + (worldState.isAir() ? "   (air: the block has been taken)" : "   (still a real block)"));
        return out;
    }

    /**
     * Enumerates every sub-level of the level, with the three things the report needs per sub-level and a
     * world -&gt; local -&gt; world round trip through the pose.
     */
    static List<String> describeSubLevels(ServerLevel level, BlockPos worldPos) {
        List<String> out = new ArrayList<>();
        List<? extends SubLevel> all = subLevels(level);
        out.add("allSubLevels = " + all.size() + " in " + level.dimension().location());

        if (all.isEmpty()) {
            out.add("  (none loaded - assemble something first, or nothing in this world uses a sub-level)");
        }
        int index = 0;
        for (SubLevel subLevel : all) {
            index++;
            LevelPlot plot = subLevel.getPlot();
            BoundingBox3ic localBounds = plot == null ? null : plot.getBoundingBox();
            BoundingBox3dc worldBounds = subLevel.boundingBox();

            out.add("  [" + index + "] uuid=" + subLevel.getUniqueId() + " name=" + subLevel.getName()
                    + " removed=" + subLevel.isRemoved());
            out.add("      plotPos=" + (plot == null ? "?" : plot.plotPos)
                    + " centerBlock=" + (plot == null ? "?" : plot.getCenterBlock())
                    + " localBounds=" + (localBounds == null ? "?" : bounds(localBounds)));
            out.add("      worldBounds=" + (worldBounds == null ? "?" : bounds(worldBounds))
                    + " containsTarget=" + contains(subLevel, worldPos));
            out.add("      logicalPose=" + pose(subLevel.logicalPose()));
            out.add("      contraptions=" + contraptionCount(plot));
            out.add("      roundTrip: " + roundTrip(subLevel));
            out.add("      blocksAtTargetChunk=" + blocksAt(level, subLevel, worldPos));
        }
        return out;
    }

    /**
     * The world -&gt; local -&gt; world round trip, which is the whole coordinate contract the mod rests on.
     *
     * <p>{@code plot.getBoundingBox()} is in the same space as the positions
     * {@code EmbeddedPlotLevelAccessor} reads ({@code level.getBlockState(local.offset(center))}), so
     * transforming a point of that box by {@code logicalPose()} must land on the block the world actually
     * has, and transforming it back must return the original. Both directions are printed, including the
     * angle between the two quaternions, because a mismatched convention shows up there and nowhere else.
     */
    static String roundTrip(SubLevel subLevel) {
        LevelPlot plot = subLevel.getPlot();
        Pose3dc pose = subLevel.logicalPose();
        if (plot == null || pose == null) {
            return "unavailable (plot=" + (plot != null) + ", pose=" + (pose != null) + ")";
        }

        // A point that is genuinely inside the sub-level's local bounds.
        BoundingBox3ic local = plot.getBoundingBox();
        Vector3d localPoint = new Vector3d((local.minX() + local.maxX()) / 2.0 + 0.5,
                (local.minY() + local.maxY()) / 2.0 + 0.5,
                (local.minZ() + local.maxZ()) / 2.0 + 0.5);

        Vector3d world = pose.transformPosition(localPoint, new Vector3d());
        Vector3d back = pose.transformPositionInverse(world, new Vector3d());

        double positionError = localPoint.distance(back);
        double angleDegrees = quaternionAngleDegrees(pose);
        return "local " + vector(localPoint) + " -> world " + vector(world)
                + " -> local " + vector(back)
                + "  |err|=" + fmt(positionError)
                + (positionError < 1.0E-6 ? " PERFECT" : " !! round trip is not the identity")
                + ", rotation = " + fmt(angleDegrees) + "deg"
                + (angleDegrees < 0.01 ? " (no rotation right now)"
                        : " (non-zero: the pose really is carrying a rotation)");
    }

    /**
     * Reads the sub-level's own blocks at the target position, through the level accessor a carried read
     * would use, so the two coordinate spaces can be compared against the world's own answer.
     */
    static String blocksAt(ServerLevel level, SubLevel subLevel, BlockPos worldPos) {
        LevelPlot plot = subLevel.getPlot();
        if (plot == null) {
            return "no plot";
        }
        try {
            BlockState viaPlot = plot.getEmbeddedLevelAccessor().getBlockState(worldPos);
            BlockState inWorld = level.getBlockState(worldPos);
            return "viaPlotAccessor(worldPos)=" + viaPlot + " vs world=" + inWorld;
        } catch (RuntimeException failed) {
            return "failed: " + failed;
        }
    }

    /** The whole store, as "how many" plus the keys the report lists. */
    static List<String> describeStore(ServerLevel level) {
        List<String> out = new ArrayList<>();
        var store = com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore.get(level);
        if (store == null) {
            out.add("store: unavailable for this level");
            return out;
        }
        List<BlockPos> positions = store.positions();
        out.add("store entries=" + positions.size());
        if (positions.isEmpty()) {
            return out;
        }
        // Position order is map order, which is stable enough to read but not meaningful - the point
        // is the list of world keys, not the order.
        int limit = Math.min(positions.size(), 24);
        StringBuilder keys = new StringBuilder("store keys=[");
        for (int i = 0; i < limit; i++) {
            if (i > 0) {
                keys.append(", ");
            }
            keys.append(positions.get(i).toShortString());
        }
        keys.append(positions.size() > limit ? ", ... +" + (positions.size() - limit) + " more]" : "]");
        out.add(keys.toString());
        out.add("  (these are WORLD BlockPos keys; P1 replaces them with structure id + local coordinates)");
        return out;
    }

    /** One line: how many sub-levels exist and whether the target position is inside any of them. */
    static String summary(ServerLevel level, BlockPos worldPos) {
        List<SubLevel> hits = intersecting(level, worldPos);
        return "target " + worldPos.toShortString() + ": " + subLevels(level).size() + " sub-level(s), "
                + hits.size() + " containing this point";
    }

    // --------------------------------------------------------------- helpers --

    /** {@code SubLevelContainer.getAllSubLevels()} - the enumeration the accounting would be driven from. */
    static List<? extends SubLevel> subLevels(Level level) {
        var container = dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(level);
        return container == null ? List.of() : container.getAllSubLevels();
    }

    /** How many Create contraptions (if any) are attached to a sub-level's plot. */
    static int contraptionCount(LevelPlot plot) {
        if (plot instanceof dev.ryanhcode.sable.sublevel.plot.ServerLevelPlot serverPlot) {
            return serverPlot.getContraptions().size();
        }
        return 0;
    }

    /** The human-readable name of a sub-level, tolerating a null name. */
    static String nameOf(SubLevelAccess access) {
        String name = access.getName();
        return name == null || name.isEmpty() ? "(unnamed)" : name;
    }

    private static boolean contains(SubLevelAccess access, BlockPos pos) {
        BoundingBox3dc box = access.boundingBox();
        return box != null && box.contains(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
    }

    private static BoundingBox3dc boxAround(BlockPos pos) {
        return new dev.ryanhcode.sable.companion.math.BoundingBox3d(
                pos.getX(), pos.getY(), pos.getZ(),
                pos.getX() + 1.0, pos.getY() + 1.0, pos.getZ() + 1.0);
    }

    private static String bounds(BoundingBox3ic box) {
        return "[" + box.minX() + "," + box.minY() + "," + box.minZ()
                + " .. " + box.maxX() + "," + box.maxY() + "," + box.maxZ() + "]";
    }

    private static String bounds(BoundingBox3dc box) {
        return "[" + fmt(box.minX()) + "," + fmt(box.minY()) + "," + fmt(box.minZ())
                + " .. " + fmt(box.maxX()) + "," + fmt(box.maxY()) + "," + fmt(box.maxZ()) + "]";
    }

    private static String pose(Pose3dc pose) {
        if (pose == null) {
            return "null";
        }
        String text = "position=" + vector(pose.position())
                + " rotationPoint=" + vector(pose.rotationPoint())
                + " scale=" + vector(pose.scale())
                + " orientation=" + pose.orientation();
        return text.length() <= POSE_TEXT_LIMIT ? text : text.substring(0, POSE_TEXT_LIMIT) + "...";
    }

    private static String vector(org.joml.Vector3dc vector) {
        if (vector == null) {
            return "null";
        }
        return "(" + fmt(vector.x()) + ", " + fmt(vector.y()) + ", " + fmt(vector.z()) + ")";
    }

    /**
     * How far the pose's orientation is from the identity, in degrees.
     *
     * <p>Not a measurement of the block's own rotation - it is the sub-level's orientation, which is
     * what tells whether "local +X" still means "world +X".
     */
    private static double quaternionAngleDegrees(Pose3dc pose) {
        org.joml.Quaterniondc orientation = pose.orientation();
        if (orientation == null) {
            return 0.0;
        }
        // angle = 2 * acos(|w|), the standard quaternion-to-angle formula, clamped for safety.
        double w = Math.min(1.0, Math.abs(orientation.w()));
        return Math.toDegrees(2.0 * Math.acos(w));
    }

    private static String fmt(double value) {
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }

    /** Logs one report to the game log as well, so a crash-free run still leaves a file behind. */
    static void log(String line) {
        RCConfig.LOGGER.info("[redstonecircuit] physics {}", line);
    }
}
