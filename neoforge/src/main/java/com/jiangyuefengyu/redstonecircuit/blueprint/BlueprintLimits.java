package com.jiangyuefengyu.redstonecircuit.blueprint;

import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;

/**
 * What a blueprint's inner-redstone section is allowed to cost.
 *
 * <p>Every number here is a refusal, and a refusal means "this blueprint carries no components" rather than "this
 * blueprint does not work": the file is Create's, and the components are an addition to it.
 *
 * <p>The selection arrives from the client, so the shape of it is checked before a single position is looked up:
 * {@code maxAxis}/{@code maxVolume} because a nonsense box would make the walk meaningless, {@code maxDistance}
 * because the components of a region on the far side of the world are not the sender's to export, and
 * {@code maxEntries} because the section is written into a file whose size Create's own limits also bound.
 */
public record BlueprintLimits(int maxAxis, int maxVolume, int maxEntries, int maxDistance) {

    /** 512 per axis, 2,000,000 cells, 16384 components, and the selection has to be within 256 blocks of its author. */
    public static final BlueprintLimits DEFAULT = new BlueprintLimits(512, 2_000_000, 16384, 256);

    /** An empty answer means "acceptable". */
    public Optional<String> refuse(Vec3i size) {
        int longest = Math.max(size.getX(), Math.max(size.getY(), size.getZ()));
        if (size.getX() < 1 || size.getY() < 1 || size.getZ() < 1) {
            return Optional.of("a blueprint needs a size of at least 1 on every axis, got "
                    + size.getX() + "x" + size.getY() + "x" + size.getZ());
        }
        long volume = (long) size.getX() * size.getY() * size.getZ();
        if (longest > this.maxAxis || volume > this.maxVolume) {
            return Optional.of("the selection is " + size.getX() + "x" + size.getY() + "x" + size.getZ()
                    + " (" + volume + " blocks), the limit is " + this.maxAxis + " per axis and "
                    + this.maxVolume + " blocks");
        }
        return Optional.empty();
    }

    /**
     * Whether a player standing at {@code playerPos} may export the box.
     *
     * <p>The distance is taken to the <em>nearest cell</em> of the box rather than to its corners: a large machine
     * a player built and is standing inside has corners hundreds of blocks away, and refusing that would refuse the
     * ordinary case while letting a distant box through as soon as it happened to have a corner nearby.
     */
    public Optional<String> refuseDistance(BlockPos playerPos, BlockPos min, Vec3i size) {
        int dx = outside(playerPos.getX(), min.getX(), size.getX());
        int dy = outside(playerPos.getY(), min.getY(), size.getY());
        int dz = outside(playerPos.getZ(), min.getZ(), size.getZ());
        long squared = (long) dx * dx + (long) dy * dy + (long) dz * dz;
        if (squared > (long) this.maxDistance * this.maxDistance) {
            return Optional.of("the selection is not within " + this.maxDistance + " blocks of its author ("
                    + dx + "/" + dy + "/" + dz + ")");
        }
        return Optional.empty();
    }

    /** How far one coordinate sits outside {@code [start, start + span)}; zero when it is inside. */
    private static int outside(int value, int start, int span) {
        if (value < start) {
            return start - value;
        }
        int end = start + span - 1;
        return value > end ? value - end : 0;
    }

    /** True when a section this size is more components than one blueprint may carry. */
    public boolean refusesEntries(int entries) {
        return entries > this.maxEntries;
    }
}
