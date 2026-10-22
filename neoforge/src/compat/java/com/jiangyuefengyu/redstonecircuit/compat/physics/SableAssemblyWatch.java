package com.jiangyuefengyu.redstonecircuit.compat.physics;

import java.util.List;

import com.jiangyuefengyu.redstonecircuit.RCConfig;

import dev.ryanhcode.sable.api.physics.mass.MassData;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;

import net.minecraft.server.level.ServerLevel;

/**
 * Reads back the body of the structure 方块放置器 has just assembled, and stops the machine making more if that body
 * is not one the physics step can use.
 *
 * <p>The read happens on a later tick than the assembly, on purpose. Sable creates a sub-level's physics body when
 * it <b>allocates</b> it - before a block has been moved into the plot - and fills the plot, its bounding box and the
 * mass tracker afterwards, so a structure that is perfectly sound answers "empty" if it is asked in the call that
 * created it. The carrier walk is where both have settled, and that is where {@code SableProductDriver} asks this
 * class, once per level tick.
 *
 * <p>The body is judged on its own mass data - the mass, and the centre of mass Sable uploads it through - with the
 * plot's bounding box as the cheap early signal and the sub-level's pose as the second. Anything wrong is one line
 * naming the sub-level, the world cell the machine was pointing at and every fact that was wrong; and then no more
 * structures are assembled for the rest of the run. A structure with no body is not a wasted block but a massless
 * body in a physics step that cannot be asked to survive one, whereas a machine that stops assembling is a machine
 * whose blocks simply stay ordinary blocks in the world.
 */
final class SableAssemblyWatch {

    private SableAssemblyWatch() {
    }

    /**
     * Judges every structure an earlier assembly left waiting, against the sub-levels the given container holds.
     *
     * <p>The caller is the carrier walk, so the whole pass is one that already enumerates the structure's level.
     */
    static void resolvePending(SubLevelContainer container, ServerLevel level) {
        if (container == null || level == null) {
            return;
        }
        List<? extends SubLevel> all = container.getAllSubLevels();
        long now = level.getGameTime();
        for (PlacerAssemblyWatch.Pending entry : PlacerAssemblyWatch.pending()) {
            if (!entry.dimension().equals(level.dimension()) || entry.tick() >= now) {
                // Another level's structure, which this walk cannot see; or one assembled in this very tick, whose
                // plot and mass are the numbers that are still being written.
                continue;
            }
            SubLevel found = null;
            for (SubLevel candidate : all) {
                if (candidate != null && entry.uuid().equals(candidate.getUniqueId())) {
                    found = candidate;
                    break;
                }
            }
            // Taken off the list before it is judged, so a verdict that throws is not a verdict that repeats for ever.
            PlacerAssemblyWatch.resolve(entry.uuid());
            judge(entry, found);
        }
    }

    /**
     * The same judgement, asked from the placement path: the previous structure is judged before the next one is
     * made, so a machine cannot assemble twice into a state that has already been shown to be unusable.
     */
    static void resolvePending(ServerLevel level) {
        if (level == null) {
            return;
        }
        resolvePending(SubLevelContainer.getContainer(level), level);
    }

    private static void judge(PlacerAssemblyWatch.Pending entry, SubLevel found) {
        if (found == null) {
            if (PlacerAssemblyGate.switchOff(
                    "sub-level " + entry.uuid() + " was gone before its body could be read back")) {
                RCConfig.LOGGER.warn("[redstonecircuit] block placer: the structure assembled from the cell at world {} "
                        + "(sub-level {}) was gone from the container before its body could be read back, which is what a "
                        + "structure with nothing in its plot does; no further structure will be assembled for the rest "
                        + "of this server run, and the machine keeps placing its blocks into the world",
                        entry.worldTarget().toShortString(), entry.uuid());
            }
            return;
        }

        LevelPlot plot = found.getPlot();
        BoundingBox3ic box = plot == null ? null : plot.getBoundingBox();
        boolean plotEmpty = box == null || box.maxX() < box.minX() || box.maxY() < box.minY()
                || box.maxZ() < box.minZ();
        // The same conversion the live carrier path uses, so "the pose cannot be read" means one thing in both places.
        boolean poseReadable = SableCarrierClaim.poseOf(found) != null;

        double mass = Double.NaN;
        boolean centreOfMass = false;
        if (found instanceof ServerSubLevel server) {
            MassData tracker = server.getMassTracker();
            if (tracker != null) {
                mass = tracker.getMass();
                centreOfMass = tracker.getCenterOfMass() != null;
            }
        }

        String problem = PlacerAssemblyGate.bodyProblem(mass, centreOfMass, plotEmpty, poseReadable);
        if (problem == null) {
            if (RCConfig.debugLog()) {
                RCConfig.LOGGER.info("[redstonecircuit] block placer: the structure assembled from the cell at world {} "
                        + "(sub-level {}) has a body of its own (mass {})", entry.worldTarget().toShortString(),
                        entry.uuid(), mass);
            }
            return;
        }

        if (!PlacerAssemblyGate.switchOff(problem)) {
            return;
        }
        RCConfig.LOGGER.warn("[redstonecircuit] block placer: the structure assembled from the cell at world {} "
                + "(sub-level {}) has no usable body: {} - a structure whose plot holds no block is given a physics "
                + "body when it is allocated and never a mass afterwards, which leaves a body with no centre of mass "
                + "in the physics step; no further structure will be assembled for the rest of this server run, and "
                + "the machine keeps placing its blocks into the world", entry.worldTarget().toShortString(),
                entry.uuid(), problem);
    }
}
