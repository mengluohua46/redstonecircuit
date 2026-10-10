package com.jiangyuefengyu.redstonecircuit.compat.physics;

import java.lang.reflect.Field;
import java.util.Iterator;
import java.util.Map;

import com.jiangyuefengyu.redstonecircuit.RCConfig;

import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.sublevel.storage.HoldingSubLevel;
import dev.ryanhcode.sable.sublevel.storage.holding.SubLevelHoldingChunk;
import dev.ryanhcode.sable.sublevel.storage.holding.SubLevelHoldingChunkMap;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

/**
 * The repair for a saved sub-level Sable can no longer read.
 *
 * <p>Sable files a structure that is not loaded as a <b>pointer</b> in a per-chunk holding record, with the
 * structure's data in a separate file. When the data behind a pointer is gone - a structure Sable did not make is
 * one Sable cannot read back - the world reports {@code Failed to load sub_level at global->[chunkPos=…]} on every
 * load, and nothing in the game removes it.
 *
 * <p>Removing it through {@code SubLevelStorage#attemptRemoveHoldingChunk} is not enough: that call only drops it
 * from the <b>file</b> ({@code SubLevelRegionFile#tryRemove}), while {@code SubLevelHoldingChunkMap} keeps what it
 * read in memory ({@code loadedHoldingChunks}, {@code allHoldingSubLevels}) and writes it back at shutdown. There
 * is no API for one cached holding chunk, so both fields are reached <b>by type rather than by name</b> and every
 * step is guarded; a Sable that moved them means "nothing forgotten", which the command reports. A live structure,
 * a plot or a world block is never touched.
 */
final class SableStorageRepair {

    private SableStorageRepair() {
    }

    /** What one repair did: the pointers found on disk and how many in-memory records were forgotten. */
    record Outcome(boolean hadRecord, int pointers, int forgotten) {

        String describe(ChunkPos chunk) {
            if (!hadRecord) {
                return "chunk " + chunk + " has no saved sub-level record";
            }
            return "chunk " + chunk + ": dropped " + pointers + " saved sub-level record(s) from disk and forgot "
                    + forgotten + " from Sable's memory"
                    + (forgotten < pointers
                            ? " - if the report comes back, Sable's bookkeeping moved and this tool needs updating"
                            : "");
        }
    }

    static Outcome purgeHoldingChunk(ServerLevel level, ChunkPos chunk) {
        ServerSubLevelContainer container = ServerSubLevelContainer.getContainer(level);
        if (container == null) {
            return new Outcome(false, 0, 0);
        }
        SubLevelHoldingChunkMap map = container.getHoldingChunkMap();
        if (map == null) {
            return new Outcome(false, 0, 0);
        }

        // The pointer count is what the player is shown, and the number the in-memory half has to match.
        SubLevelHoldingChunk filed = map.getStorage().attemptLoadHoldingChunk(chunk);
        int pointers = filed == null ? 0 : filed.getSubLevelPointers().size();

        map.getStorage().attemptRemoveHoldingChunk(chunk);
        int forgotten = forgetCached(map, chunk);

        Outcome outcome = new Outcome(filed != null, pointers, forgotten);
        RCConfig.LOGGER.info("[redstonecircuit] stored sub-level repair: {}", outcome.describe(chunk));
        return outcome;
    }

    /**
     * Drops what Sable is holding in memory for one chunk: the cached holding chunk and the per-uuid record behind it.
     *
     * <p>Returns how many in-memory records were forgotten; 0 while the file's record existed means this Sable
     * version keeps them somewhere else, which the command reports rather than hides.
     */
    private static int forgetCached(SubLevelHoldingChunkMap map, ChunkPos chunk) {
        int forgotten = 0;
        long packed = chunk.toLong();
        for (Field field : SubLevelHoldingChunkMap.class.getDeclaredFields()) {
            if (!Map.class.isAssignableFrom(field.getType())) {
                continue;
            }
            try {
                field.setAccessible(true);
                if (!(field.get(map) instanceof Map<?, ?> entries) || entries.isEmpty()) {
                    continue;
                }
                Object sample = entries.values().iterator().next();
                if (sample instanceof SubLevelHoldingChunk) {
                    // loadedHoldingChunks: one entry per chunk, keyed by the packed chunk position.
                    if (entries.keySet().remove(Long.valueOf(packed))) {
                        forgotten++;
                    }
                } else if (sample instanceof HoldingSubLevel) {
                    // allHoldingSubLevels: one entry per saved structure, keyed by its uuid, filed under the chunk its pointer names.
                    Iterator<? extends Map.Entry<?, ?>> it = entries.entrySet().iterator();
                    while (it.hasNext()) {
                        Object value = it.next().getValue();
                        if (value instanceof HoldingSubLevel holding && chunk.equals(holding.pointer().chunkPos())) {
                            it.remove();
                            forgotten++;
                        }
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError failed) {
                RCConfig.LOGGER.warn("[redstonecircuit] stored sub-level repair: could not read {} of Sable's holding "
                        + "map ({}), so its in-memory copy of this chunk may stay and write the record back",
                        field.getName(), failed.toString());
            }
        }
        return forgotten;
    }
}
