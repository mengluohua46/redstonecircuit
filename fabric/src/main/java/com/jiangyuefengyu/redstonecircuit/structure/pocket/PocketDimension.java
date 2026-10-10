package com.jiangyuefengyu.redstonecircuit.structure.pocket;

import org.jetbrains.annotations.Nullable;

import com.jiangyuefengyu.redstonecircuit.RedstoneCircuit;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * The hidden dimension real captured blocks live in.
 *
 * <p>The blocks inside a structure keep being <b>ordinary level blocks</b>, so vanilla itself runs the redstone,
 * pistons, hoppers, observers and comparators, and none of it needs simulating; hence a real datapack dimension
 * (the two files under {@code data/redstonecircuit/dimension/}) rather than something conjured at runtime.
 *
 * <p>NeoForge-side glue: only what every other pocket class needs to agree on - the key, and where a plot sits. The
 * arithmetic lives in the loader-free classes beside it, which the fabric build mirrors verbatim.
 */
public final class PocketDimension {

    /** {@code redstonecircuit:structure_space} - see the two datapack files. */
    public static final ResourceKey<Level> STRUCTURE_SPACE =
            ResourceKey.create(Registries.DIMENSION, RedstoneCircuit.id("structure_space"));

    /**
     * The y a plot starts at. The dimension spans the ordinary {@code -64..319}, so a plot never needs a special
     * height: a plot's cell is 64 blocks tall and sits at y 0, leaving the whole build range below it free.
     */
    public static final int PLOT_BASE_Y = 0;

    /** The pocket level, or {@code null} when this server has no such dimension (a datapack problem). */
    @Nullable
    public static ServerLevel of(@Nullable MinecraftServer server) {
        return server == null ? null : server.getLevel(STRUCTURE_SPACE);
    }

    private PocketDimension() {
    }
}
