package com.jiangyuefengyu.redstonecircuit.block;

import java.util.List;

import net.minecraft.core.Direction;

/**
 * The four directions that lie <b>inside a mounting's own plane</b>, clockwise seen from outside the plate.
 *
 * <p>One table for every 贴面 block that has a ring: the diodes' {@code FACING} walks it, and 红石粉（贴面）uses it as
 * the order of its four link properties. It lives apart from both so that neither owns it, and so that a test can ask
 * the question without loading a block class (whose property tables need a bootstrapped game).
 */
public final class SurfaceRing {

    private SurfaceRing() {
    }

    /** 平面内四向. A floor or ceiling mounting walks the horizon; a wall mounting walks up, across, down, back. */
    public static List<Direction> of(Direction mount) {
        if (mount.getAxis().isVertical()) {
            return List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST);
        }
        return List.of(Direction.UP, mount.getClockWise(), Direction.DOWN, mount.getCounterClockWise());
    }
}
