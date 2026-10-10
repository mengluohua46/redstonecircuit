package com.jiangyuefengyu.redstonecircuit.carrier;

import org.jetbrains.annotations.Nullable;

/**
 * Everything a solve pass needs to open the bridge between one structure and the world it is flying through, or
 * {@code null} for "closed".
 * <p>{@link Poses} answers "where is this structure now", once per carrier per pass, the same pose the renderer and
 * the landing arithmetic use; {@link CarrierWorldSignals} answers "what is the world signalling here", per local side
 * of every component taking part in a solve.
 * <p>A {@code null} bridge - {@code carrierFreezeWorldIo = true}, a build without the compat half, or a pose that
 * cannot be read - is not an error: the inside of a machine and the world outside it simply cannot see each other.
 */
public record CarrierWorldBridge(Poses poses, CarrierWorldSignals signals) {

    /** "Where is this structure right now", without handing the caller a level. */
    @FunctionalInterface
    public interface Poses {

        /** The carrier's pose, or {@code null} when it is not there any more. */
        @Nullable
        CarrierPose poseOf(CarrierId carrier);
    }
}
