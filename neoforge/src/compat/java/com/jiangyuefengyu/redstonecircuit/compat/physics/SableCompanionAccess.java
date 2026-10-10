package com.jiangyuefengyu.redstonecircuit.compat.physics;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import org.jetbrains.annotations.Nullable;

/**
 * A tiny accessor into Sable's <em>companion</em> API, in <b>our own</b> package.
 *
 * <p>{@code dev.ryanhcode.sable.api} cannot answer "given a world {@link BlockPos}, which structure is it part of":
 * {@code SubLevelContainer} enumerates and spatially queries sub-levels, but position-to-structure lookup exists only
 * on {@code SableCompanion}, which Sable hands out as {@code Sable.HELPER}. That field is declared as Sable's
 * implementation class while the contract is the interface, so the interface is what this accessor names; its
 * {@code getContaining} overloads are declared as {@code SubLevelAccess} even where the concrete override returns
 * {@code SubLevel}, so anything needing {@code getPlot()} casts at the call site.
 *
 * <p>It names Sable's own classes, so it belongs to the compat source set, and it must stay under
 * {@code com.jiangyuefengyu.redstonecircuit}: under JPMS two modules may not export the same package, so a class of
 * ours dropped into Sable's package stops the game before any of our code runs. The build's
 * {@code verifyNoSplitPackages} task enforces that. It is reached solely from {@link SableReport}, which is loaded
 * only once {@code ModList} has confirmed Sable is installed.
 */
final class SableCompanionAccess {

    private SableCompanionAccess() {
    }

    /** The structure a world position belongs to, or {@code null} when it is ordinary world space. */
    @Nullable
    static SubLevelAccess getContaining(Level level, BlockPos worldPos) {
        return companion().getContaining(level, worldPos);
    }

    /** Every structure whose bounds intersect the given world-space box. */
    static Iterable<? extends SubLevelAccess> getAllIntersecting(Level level, BoundingBox3dc box) {
        return companion().getAllIntersecting(level, box);
    }

    /**
     * The companion singleton. {@code Sable.HELPER} is initialised by Sable's own static initialiser, which cannot
     * have run before {@code ModList} reports Sable as loaded, so null here would mean the gate in
     * {@code PhysicsCompat} is wrong.
     */
    private static SableCompanion companion() {
        SableCompanion companion = Sable.HELPER;
        if (companion == null) {
            throw new IllegalStateException(
                    "Sable is loaded but its companion API is unavailable; the physics-compat gate is wrong");
        }
        return companion;
    }
}
