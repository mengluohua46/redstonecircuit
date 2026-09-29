package com.jiangyuefengyu.redstonecircuit.carrier;

import net.minecraft.world.level.BlockGetter;

import org.jetbrains.annotations.Nullable;

/**
 * The one question the carrier layer cannot answer on its own: "give me this structure's blocks in
 * its own local coordinates".
 *
 * <h2>Why this is an interface here and an implementation in the compat half</h2>
 * Everything about <em>which</em> host is inside <em>which</em> structure is our own bookkeeping and
 * lives in the main source set. Reading the structure's blocks is not: for a Create contraption it is
 * {@code Contraption#getBlocks()}, exposed as {@code ContraptionWorld}; for a Sable sub-level it is
 * the plot's {@code EmbeddedPlotLevelAccessor}. Neither type may be named outside
 * {@code src/compat/java} - that is the rule P0 established and {@code verifyNoSplitPackages}
 * enforces - so only this one-method interface is here, and the compat half provides the value.
 *
 * <p>{@link CarrierStore.CarrierSource} - the P1 interface next door - answers the other two
 * questions ("where is this structure" and "which structure owns this world position"). They are kept
 * apart because they are asked at different moments: a pose is asked once per tick per carrier, while
 * this is asked once per solve.
 *
 * <h2>Answering {@code null} is the ordinary case</h2>
 * A structure that has just come apart, a world that was reloaded while a machine was in the air, or
 * a jar built without the compat half at all, all answer {@code null} for every carrier. Nothing
 * downstream distinguishes those: a structure with no block view is still solved - the index is what
 * the solve reads - it simply cannot answer the one conductor query that nothing in the solve loop
 * asks anyway. The orphan sweep the P1 layer already has is what puts a genuinely vanished
 * structure's components back.
 */
@FunctionalInterface
public interface CarrierAccess {

    /**
     * A {@link BlockGetter} over one structure's local coordinates, or {@code null} when the
     * structure is not there (or this build cannot read it).
     *
     * <p>The returned view must answer in the <b>same</b> coordinate space as the local coordinates
     * the carried index is keyed by; both halves of P1's diagnostics checked that contract
     * ({@code ContraptionWorld#getBlockState} looks the position up in {@code Contraption#getBlocks},
     * and {@code EmbeddedPlotLevelAccessor} offsets by the plot's centre, which is the space
     * {@code LevelPlot#getBoundingBox} describes).
     */
    @Nullable
    BlockGetter blockViewOf(CarrierId carrier);
}
