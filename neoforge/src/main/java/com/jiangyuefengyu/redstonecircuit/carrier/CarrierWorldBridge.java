package com.jiangyuefengyu.redstonecircuit.carrier;

import org.jetbrains.annotations.Nullable;

/**
 * Everything a solve pass needs to open the bridge between one structure and the world it is flying
 * through, or {@code null} for "closed".
 *
 * <h2>Two answers, because P4 has two directions</h2>
 * <ul>
 *   <li>{@link Poses} - "where is this structure now". Asked once per carrier per pass, and it is the
 *       same pose the renderer and the landing arithmetic use, so a component cannot be drawn
 *       somewhere it is not read from.</li>
 *   <li>{@link CarrierWorldSignals} - "what is the world signalling at this position". Asked per local
 *       side of every component that takes part in a solve.</li>
 * </ul>
 *
 * <p>A {@code null} bridge - {@code carrierFreezeWorldIo = true}, or a build without the compat half,
 * or a structure whose pose cannot be read - is not an error: it is exactly the P1/P2 state, in which
 * the inside of a machine and the world outside it cannot see each other, and every query in
 * {@link CarrierSpace} answers zero.
 *
 * @param poses where one carrier is right now
 * @param signals what the world pushes into a world position
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
