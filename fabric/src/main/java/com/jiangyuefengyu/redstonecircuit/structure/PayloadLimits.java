package com.jiangyuefengyu.redstonecircuit.structure;

import java.util.Optional;

import net.minecraft.core.Vec3i;

/**
 * The size limits a payload is checked against, as a plain value.
 *
 * <p>The loader-free layer cannot read the mod's configuration and the tests must not need a running game, so the
 * numbers travel as this record: the game side builds it from {@code RCConfig}, the defaults below are for tests.
 */
public record PayloadLimits(int maxAxis, int maxVolume, int maxManualEntries) {

    /** The ceiling: 48 per axis, 48³ cells, 32 manual entries. */
    public static final PayloadLimits DEFAULT = new PayloadLimits(48, 110592, 32);

    /** An empty reason means "acceptable". */
    public Optional<String> refuse(Vec3i size, int paletteSize, int cellCount, int manualCount) {
        Optional<String> tooBig = refuseSize(size);
        if (tooBig.isPresent()) {
            return tooBig;
        }
        if (paletteSize < 1) {
            return Optional.of("the palette is empty");
        }
        long volume = (long) size.getX() * size.getY() * size.getZ();
        if (cellCount != volume) {
            return Optional.of("the block indices describe " + cellCount + " cells, the size is " + volume);
        }
        if (manualCount > this.maxManualEntries) {
            return Optional.of("the manual panel has " + manualCount + " entries, the limit is "
                    + this.maxManualEntries);
        }
        return Optional.empty();
    }

    /**
     * Just the size rules: capture must answer "may this region be taken at all" before reading a single block.
     */
    public Optional<String> refuseSize(Vec3i size) {
        int longest = Math.max(size.getX(), Math.max(size.getY(), size.getZ()));
        if (size.getX() < 1 || size.getY() < 1 || size.getZ() < 1) {
            return Optional.of("a structure needs a size of at least 1 on every axis, got "
                    + size.getX() + "x" + size.getY() + "x" + size.getZ());
        }
        if (longest > this.maxAxis) {
            return Optional.of("the selection is " + longest + " blocks on its longest axis, the limit is "
                    + this.maxAxis);
        }
        long volume = (long) size.getX() * size.getY() * size.getZ();
        if (volume > this.maxVolume) {
            return Optional.of("the selection is " + volume + " blocks, the limit is " + this.maxVolume);
        }
        return Optional.empty();
    }
}
