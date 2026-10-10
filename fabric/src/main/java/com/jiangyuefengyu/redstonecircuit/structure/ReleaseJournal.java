package com.jiangyuefengyu.redstonecircuit.structure;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.jiangyuefengyu.redstonecircuit.RCConfig;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

/**
 * "A release was in progress here": one small NBT file per release in progress - {structure id, name, dimension,
 * origin} - written <b>before the first block</b> and deleted when the job finishes.
 *
 * <p>Writing a structure is deterministic, so an interrupted release is repaired by running it again from the start;
 * rolling back would mean capturing the target first, which the block blacklist can refuse. A release that fits in one
 * tick never writes one.
 */
public final class ReleaseJournal {

    /** Under the world directory, beside {@code structures}. */
    public static final String DIRECTORY = "releases";

    private static final String SUFFIX = ".nbt";

    /** One unfinished release. */
    public record Entry(UUID structureId, String name, ResourceKey<Level> dimension, BlockPos origin) {
    }

    /** Where a save's journals live. */
    public static Path directory(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT)
                .resolve(StructureWorldStore.DIRECTORY).resolve(DIRECTORY);
    }

    public static Path fileOf(Path directory, UUID structureId) {
        return directory.resolve(structureId.toString().toLowerCase(Locale.ROOT) + SUFFIX);
    }

    /** Notes that a release is starting. Called before its first block is written. */
    public static void writeTo(Path directory, Entry entry) {
        try {
            Files.createDirectories(directory);
            NbtIo.writeCompressed(save(entry), fileOf(directory, entry.structureId()));
        } catch (IOException failed) {
            // Not fatal, but not silent either: without the journal this release is the one that can be left
            // half-written.
            RCConfig.LOGGER.warn("[redstonecircuit] could not write the release journal for {} at {}; a crash "
                    + "during this release would leave it half-written", entry.name(),
                    entry.origin().toShortString(), failed);
        }
    }

    /** Forgets a release: it finished, so there is nothing to resume. */
    public static void removeFrom(Path directory, UUID structureId) {
        try {
            Files.deleteIfExists(fileOf(directory, structureId));
        } catch (IOException failed) {
            RCConfig.LOGGER.warn("[redstonecircuit] could not remove the release journal for {}; it will be "
                    + "resumed (again) at the next start, which writes the same blocks", structureId, failed);
        }
    }

    /** Every unfinished release in a directory, in no particular order. */
    public static List<Entry> readFrom(Path directory) {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        List<Entry> entries = new ArrayList<>();
        try (var files = Files.list(directory)) {
            for (Path file : files.filter(ReleaseJournal::isJournal).toList()) {
                Entry entry = readOne(file);
                if (entry != null) {
                    entries.add(entry);
                }
            }
        } catch (IOException failed) {
            RCConfig.LOGGER.warn("[redstonecircuit] could not read the release journals in {}", directory, failed);
        }
        return entries;
    }

    private static boolean isJournal(Path file) {
        return Files.isRegularFile(file) && file.getFileName().toString().endsWith(SUFFIX);
    }

    @Nullable
    private static Entry readOne(Path file) {
        try {
            return load(NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()));
        } catch (IOException | RuntimeException unreadable) {
            RCConfig.LOGGER.warn("[redstonecircuit] could not read the release journal {}; leaving it alone",
                    file, unreadable);
            return null;
        }
    }

    static CompoundTag save(Entry entry) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("structure", entry.structureId());
        tag.putString("name", entry.name());
        tag.putString("dimension", entry.dimension().location().toString());
        BlockPos.CODEC.encodeStart(NbtOps.INSTANCE, entry.origin()).result()
                .ifPresent(encoded -> tag.put("origin", encoded));
        return tag;
    }

    /** A tag that names no structure, dimension or position is not a journal - null rather than a guess. */
    @Nullable
    static Entry load(CompoundTag tag) {
        UUID id = tag.hasUUID("structure") ? tag.getUUID("structure") : null;
        // An empty string is not a dimension: ResourceLocation's lenient parse would accept "" as a namespace-less
        // path, and a journal pointing at nowhere is worse than one that is left alone.
        String dimensionText = tag.getString("dimension");
        ResourceLocation dimension = dimensionText.isEmpty() ? null : ResourceLocation.tryParse(dimensionText);
        BlockPos origin = tag.contains("origin")
                ? BlockPos.CODEC.parse(NbtOps.INSTANCE, tag.get("origin")).result().orElse(null)
                : null;
        if (id == null || dimension == null || origin == null) {
            return null;
        }
        return new Entry(id, tag.getString("name"),
                ResourceKey.create(Registries.DIMENSION, dimension), origin);
    }

    public static void write(MinecraftServer server, Entry entry) {
        writeTo(directory(server), entry);
    }

    public static void remove(MinecraftServer server, UUID structureId) {
        removeFrom(directory(server), structureId);
    }

    /**
     * Resumes every unfinished release this save knows about, and says so in the log. Called once, when the server has
     * started. A journal whose structure file is gone is <b>forgotten</b> rather than retried forever: there is nothing
     * left to write, and a journal that can never be satisfied would be a warning on every start.
     */
    public static int recover(MinecraftServer server) {
        int resumed = 0;
        for (Entry entry : readFrom(directory(server))) {
            ServerLevel level = server.getLevel(entry.dimension());
            if (level == null) {
                RCConfig.LOGGER.warn("[redstonecircuit] a release of {} at {} was interrupted, but this server has "
                        + "no level {}; leaving the journal", entry.name(), entry.origin().toShortString(),
                        entry.dimension().location());
                continue;
            }
            StructurePayload payload = StructureWorldStore.of(server, RCConfig.structurePayloadLimits())
                    .loadOrNull(entry.structureId());
            if (payload == null) {
                RCConfig.LOGGER.warn("[redstonecircuit] a release of {} at {} was interrupted, and its structure "
                        + "file ({}) is gone; forgetting it", entry.name(), entry.origin().toShortString(),
                        entry.structureId());
                remove(server, entry.structureId());
                continue;
            }
            StructureRelease.Result result = StructureRelease.start(level, payload, entry.origin(), true,
                    RCConfig.structureReleaseOptions());
            if (!result.started()) {
                RCConfig.LOGGER.warn("[redstonecircuit] could not resume the interrupted release of {} at {}: {}",
                        entry.name(), entry.origin().toShortString(), result.refusal());
                continue;
            }
            resumed++;
            RCConfig.LOGGER.info("[redstonecircuit] resuming the release of {} at {} that a previous run left "
                            + "unfinished ({} block(s))", entry.name(), entry.origin().toShortString(),
                    payload.header().size().getX() * payload.header().size().getY()
                            * payload.header().size().getZ());
        }
        return resumed;
    }

    private ReleaseJournal() {
    }
}
