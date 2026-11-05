package com.jiangyuefengyu.redstonecircuit.client;

import java.util.Set;

import net.minecraft.core.BlockPos;

import org.jetbrains.annotations.Nullable;

/**
 * The one decision both chunk meshers ask: leave this block out of the mesh?
 *
 * <p>The two hooks are handed the same host set in different coordinate conventions, and the convention cannot be
 * inferred from the set: Sodium's block renderer receives the world position of the block plus its position inside the
 * section, vanilla's receives the world position alone. Reading the pair the wrong way round invents blocks that are
 * not hosts, and a skipped block is a hole straight through the world - the plain block next to a machine that a
 * goggles-wearing player sees vanish under a shader pack.
 *
 * <p>Pure arithmetic over packed positions, so the rules are testable without a game.
 */
public final class HostCubeSkip {

    private HostCubeSkip() {
    }

    /**
     * Whether the block at {@code pos} must be left out of the chunk mesh.
     *
     * @param pos    the first position the hook was handed; every known hook passes world coordinates here
     * @param origin the hook's optional second position, {@code null} where there is none
     */
    public static boolean skips(Set<Long> hosts, BlockPos pos, @Nullable BlockPos origin) {
        if (hosts.contains(pos.asLong())) {
            return true;
        }
        // The pair means "section origin plus section-local position" only when both bounds hold: an origin is a
        // multiple of 16 on every axis and a local position lies in 0..15. Current Sodium passes its section-local
        // position second, so a section origin never arrives, and the only position that reaches the line below from
        // it is (0, 0, 0) - where adding it back yields pos itself, a repeat of the answer above and harmless.
        if (origin == null || !isSectionOrigin(origin) || !isSectionLocal(pos)) {
            return false;
        }
        return hosts.contains(origin.offset(pos).asLong());
    }

    private static boolean isSectionOrigin(BlockPos origin) {
        return (origin.getX() & 15) == 0 && (origin.getY() & 15) == 0 && (origin.getZ() & 15) == 0;
    }

    private static boolean isSectionLocal(BlockPos pos) {
        return inSection(pos.getX()) && inSection(pos.getY()) && inSection(pos.getZ());
    }

    private static boolean inSection(int value) {
        return value >= 0 && value < 16;
    }
}
