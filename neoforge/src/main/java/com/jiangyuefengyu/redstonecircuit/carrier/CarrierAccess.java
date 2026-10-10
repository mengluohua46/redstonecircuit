package com.jiangyuefengyu.redstonecircuit.carrier;

import net.minecraft.world.level.BlockGetter;

import org.jetbrains.annotations.Nullable;

/**
 * The one question the carrier layer cannot answer on its own: "give me this structure's blocks in its own local
 * coordinates".
 * <p>Which host is inside which structure is our own bookkeeping; reading a structure's blocks is not - that needs
 * Create's or Sable's types, which may not be named outside {@code src/compat/java}, so only this one-method interface
 * is here and the compat half provides the value. {@link CarrierStore.CarrierSource} answers the other two questions
 * ("where is this structure", "which structure owns this world position"), asked once per tick per carrier rather
 * than once per solve.
 * <p>{@code null} is the ordinary case, and nothing downstream distinguishes it: a structure with no block view is
 * still solved.
 */
@FunctionalInterface
public interface CarrierAccess {

    /**
     * A {@link BlockGetter} over one structure's local coordinates, or {@code null} when the structure is not there
     * (or this build cannot read it). The view must answer in the same coordinate space the carried index is keyed by.
     */
    @Nullable
    BlockGetter blockViewOf(CarrierId carrier);
}
