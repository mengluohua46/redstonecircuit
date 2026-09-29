package com.jiangyuefengyu.redstonecircuit.compat.physics;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import org.jetbrains.annotations.Nullable;

/**
 * A deliberately tiny accessor into Sable's <em>companion</em> API, in <b>our own</b> package.
 *
 * <h2>Why this class exists at all</h2>
 * The question P1 and P2 both hinge on is "given a world {@link BlockPos}, which structure is it part
 * of, and what is that structure's local-to-world transform?". {@code dev.ryanhcode.sable.api} does
 * <b>not</b> answer it: {@code SubLevelContainer} can enumerate and spatially query sub-levels, but
 * there is no position-to-structure lookup anywhere in that package. The lookup does exist, on
 * {@code dev.ryanhcode.sable.companion.SableCompanion}, and Sable hands it out through one field:
 *
 * <pre>
 * public final class dev.ryanhcode.sable.Sable {
 *     public static final dev.ryanhcode.sable.ActiveSableCompanion HELPER;
 * }
 * </pre>
 *
 * <p>Two things about that are worth writing down, because both cost time to discover:
 *
 * <ol>
 *   <li>The field's declared type is Sable's <em>implementation</em> class
 *       ({@code ActiveSableCompanion}) while the contract is the interface {@code SableCompanion}.
 *       Only the interface is reachable as a compile-time type from here, so the accessor below
 *       names the interface and takes the implementation only through the assignment.</li>
 *   <li>The declared return type of every {@code getContaining} overload on the interface is
 *       {@code SubLevelAccess}, but the concrete override returns {@code SubLevel} /
 *       {@code ClientSubLevel} - {@code ActiveSableCompanion} redeclares them with covariant return
 *       types. Compiling against the interface therefore yields {@code SubLevelAccess}, and anything
 *       needing {@code logicalPose()} has that too ({@code SubLevelAccess} declares it), but anything
 *       needing {@code getPlot()} or the physics fields will have to cast to {@code SubLevel} at the
 *       call site. {@code SubLevelAccess} is the more stable of the two, so this class exposes that
 *       and leaves the cast to whoever genuinely needs it.</li>
 * </ol>
 *
 * <h2>Why it lives in the compat source set rather than the main one</h2>
 * It names Sable's own classes, so it cannot be compiled - let alone loaded - without Sable's nested
 * companion jar on the classpath. It therefore belongs to {@code src/compat/java}, the source set
 * that is only compiled when those jars are present.
 *
 * <h2>Why it is in {@code com.jiangyuefengyu.redstonecircuit.compat.physics} and nowhere else</h2>
 *
 * <p><b>Post-mortem of a crash.</b> This class used to be declared in
 * {@code dev.ryanhcode.sable.companion} - Sable's own package - on the theory that it saved an import
 * alias for the same-named interface. That was wrong and it is the reason the mod failed to start
 * with Sable installed:
 *
 * <pre>
 * java.lang.module.ResolutionException: Modules redstonecircuit and sablecompanion
 *     export package dev.ryanhcode.sable.companion to module lanserverproperties
 * </pre>
 *
 * <p>Under JPMS two modules may not export the same package, so putting <em>any</em> of our classes
 * into a package another module already owns breaks the game before a single line of our code runs.
 * It also bought nothing: every member this class touches - {@code Sable.HELPER},
 * {@code SableCompanion.getContaining}, {@code SableCompanion.getAllIntersecting},
 * {@code SubLevelAccess} - is {@code public} (verified with {@code javap}), so no package-private
 * access was ever required. The move out is a pure rename plus three imports.
 *
 * <p><b>Rule for the future:</b> our class files only ever live under
 * {@code com.jiangyuefengyu.redstonecircuit}. If a package-private member of another mod is ever
 * genuinely needed, the answer is a Mixin {@code @Accessor}/{@code @Invoker} with a string
 * {@code targets=} (plus a gatekeeper {@code IMixinConfigPlugin} so it is skipped when that mod is
 * absent), never a class of ours dropped into somebody else's package. The build now enforces this:
 * see the {@code verifyNoSplitPackages} task in {@code build.gradle}.
 *
 * <p>It is reached solely from {@link SableReport}, which itself is only loaded after
 * {@code ModList} has confirmed Sable is installed - with Sable absent, Sable's classes are not on
 * the classpath and this class is never loaded, so it cannot fail to resolve.
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
     * The companion singleton.
     *
     * <p>{@code Sable.HELPER} is initialised by Sable's own static initialiser, which cannot have run
     * before {@code ModList} reports Sable as loaded, so a null here would mean the gate in
     * {@code PhysicsCompat} is wrong - fail loudly rather than silently doing nothing.
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
