package com.jiangyuefengyu.redstonecircuit.client;

import java.util.LinkedHashMap;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.core.BlockPos;

/**
 * What the player typed into the screen, remembered per controller.
 *
 * <p>The boxes are the player's, as in vanilla's structure block: an uncommitted row (a bound key, a typed cell, its
 * strength and label) stays as typed until the player reads the block's values back (读回当前区域 / 当前) or commits it,
 * so this memory is never refreshed from the server. Most recent {@link #LIMIT} controllers, cleared on leaving the
 * world - client-side memory must not grow with the world.
 */
@Environment(EnvType.CLIENT)
final class StructureScreenMemory {

    /** How many controllers are remembered at once: a player edits one or two, and this cannot grow. */
    static final int LIMIT = 24;

    private static final Map<Long, Fields> FIELDS = new LinkedHashMap<>(16, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Fields> eldest) {
            return size() > LIMIT;
        }
    };

    /** Everything the screen can be holding that the block does not already know. */
    record Fields(String name, String offsetX, String offsetY, String offsetZ, String sizeX, String sizeY,
                  String sizeZ, String key, String cellX, String cellY, String cellZ, String strength, String label,
                  boolean relative, int faceOrdinal, int page, int scroll) {
    }

    @Nullable
    static Fields get(BlockPos controller) {
        return FIELDS.get(controller.asLong());
    }

    static void put(BlockPos controller, Fields fields) {
        FIELDS.put(controller.asLong(), fields);
    }

    static void forget(BlockPos controller) {
        FIELDS.remove(controller.asLong());
    }

    static void clear() {
        FIELDS.clear();
    }

    private StructureScreenMemory() {
    }
}
