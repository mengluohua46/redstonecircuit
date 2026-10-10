package com.jiangyuefengyu.redstonecircuit.structure;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Where a save keeps its structures: {@code <world>/redstonecircuit/structures/<uuid>.nbt}.
 *
 * <p>整份结构存进存档里的文件，物品和方块只带 id，所以 id 来自别的存档时找不到文件；此时 {@link #load} 抛
 * {@code NoSuchFileException} 而不是返回 null 或空结构，好让"缺失占位"看得见。
 *
 * <p>目录就是事实: 索引只是每会话从目录读出并维护的内存缓存；写入是先写临时文件再原子替换，中断只会留下旧文件或
 * 新文件。不依赖 loader，只需要一个 {@link Path}。
 */
public final class StructureWorldStore {

    /** Under the world directory, so a save carries its structures and a copy of the save carries them too. */
    public static final String DIRECTORY = "redstonecircuit";
    public static final String STRUCTURES = "structures";
    /** Where {@code /rc structure export} puts files a player asked for. */
    public static final String EXPORT = "export";

    private static final String SUFFIX = ".nbt";

    private final Path root;
    private final Path structures;
    private final Path exports;
    private final PayloadLimits limits;
    private final Map<UUID, PayloadHeader> known = new HashMap<>();
    private boolean scanned;
    private int unreadable;

    public StructureWorldStore(Path worldRoot, PayloadLimits limits) {
        this.root = worldRoot;
        this.structures = worldRoot.resolve(DIRECTORY).resolve(STRUCTURES);
        this.exports = worldRoot.resolve(DIRECTORY).resolve(EXPORT);
        this.limits = limits;
    }

    /** The store of the save this server is running. */
    public static StructureWorldStore of(MinecraftServer server, PayloadLimits limits) {
        return new StructureWorldStore(server.getWorldPath(LevelResource.ROOT), limits);
    }

    public Path structuresDirectory() {
        return this.structures;
    }

    public Path exportsDirectory() {
        return this.exports;
    }

    public Path fileOf(UUID id) {
        return this.structures.resolve(id.toString().toLowerCase(Locale.ROOT) + SUFFIX);
    }

    /** Stores a payload under the id in its own header. */
    public void add(StructurePayload payload) throws IOException {
        UUID id = payload.header().uuid();
        Files.createDirectories(this.structures);
        Path target = fileOf(id);
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        payload.writeTo(temporary);
        moveIntoPlace(temporary, target);
        // Only maintained once the directory has been read: before that the cache is empty and the next scan
        // finds this file anyway, and adding it to an unbuilt cache would hide every other structure.
        if (this.scanned) {
            this.known.put(id, payload.header());
        }
    }

    private static void moveIntoPlace(Path temporary, Path target) throws IOException {
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            // Not every filesystem can move atomically; the temporary file is still complete, so a plain
            // replace is the next best thing and is never a partial file either.
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Reads a structure. A missing file is a missing file; a broken one is a format problem. */
    public StructurePayload load(UUID id) throws IOException {
        return StructurePayload.readFrom(fileOf(id), this.limits);
    }

    /** Reads a structure, or {@code null} when this save has no file for that id. */
    @Nullable
    public StructurePayload loadOrNull(UUID id) {
        try {
            return load(id);
        } catch (IOException missingOrBroken) {
            return null;
        }
    }

    public boolean contains(UUID id) {
        return Files.isRegularFile(fileOf(id));
    }

    public boolean delete(UUID id) throws IOException {
        this.known.remove(id);
        return Files.deleteIfExists(fileOf(id));
    }

    /** What this save holds, by id - the id, name and size of every readable structure. The directory is read once
     * per session and the map kept up to date after that; a file that cannot be read is skipped and counted
     * ({@link #unreadable()}) rather than failing the whole listing, because one corrupt structure must not hide the
     * other twenty. */
    public Collection<PayloadHeader> headers() {
        scan();
        return List.copyOf(this.known.values());
    }

    /** How many files in the directory could not be read as structures (corrupt, or from another mod). */
    public int unreadable() {
        scan();
        return this.unreadable;
    }

    private void scan() {
        if (this.scanned) {
            return;
        }
        this.scanned = true;
        this.known.clear();
        this.unreadable = 0;
        if (!Files.isDirectory(this.structures)) {
            return;
        }
        List<Path> files = new ArrayList<>();
        try (var entries = Files.list(this.structures)) {
            entries.filter(path -> path.getFileName().toString().endsWith(SUFFIX)).forEach(files::add);
        } catch (IOException unreadableDirectory) {
            this.unreadable++;
            return;
        }
        for (Path file : files) {
            String name = file.getFileName().toString();
            UUID id;
            try {
                id = UUID.fromString(name.substring(0, name.length() - SUFFIX.length()));
            } catch (IllegalArgumentException notOurs) {
                this.unreadable++;
                continue;
            }
            try {
                this.known.put(id, StructurePayload.readFrom(file, this.limits).header());
            } catch (IOException broken) {
                this.unreadable++;
            }
        }
    }

    /** Copies a structure out to {@code <world>/redstonecircuit/export/}, so a build can move between saves when the
     * item only carries an id. A name that is already taken gets a numbered one and both are kept: an export must
     * never silently overwrite an earlier one. */
    public Path exportTo(UUID id) throws IOException {
        StructurePayload payload = load(id);
        Files.createDirectories(this.exports);
        Path target = this.exports.resolve(fileNameFor(payload.header().name(), id));
        int suffix = 2;
        while (Files.exists(target)) {
            target = this.exports.resolve(fileNameFor(payload.header().name(), id, suffix++));
        }
        payload.writeTo(target);
        return target;
    }

    /** Reads a structure file from anywhere and registers it as a <b>new</b> id, because the file may come from a save
     * where the id in it is already in use for something else, and keeping both is what the design asks for. */
    public UUID importFrom(Path source) throws IOException {
        StructurePayload payload = StructurePayload.readFrom(source, this.limits);
        UUID fresh = UUID.randomUUID();
        PayloadHeader header = new PayloadHeader(payload.header().formatVersion(), fresh,
                payload.header().name(), payload.header().size(), payload.header().relativeOrigin(),
                payload.header().nestingDepth(), payload.header().ioMode(), payload.header().byCut());
        StructurePayload renamed = new StructurePayload(header, payload.faces(), payload.manual(),
                payload.palette(), payload.indices(), payload.contextShell(), payload.slotData(),
                payload.settingData(), payload.blockEntities(), 0);
        add(renamed.withHash(renamed.computeHash()));
        return fresh;
    }

    private static String fileNameFor(String name, UUID id) {
        return fileNameFor(name, id, 0);
    }

    private static String fileNameFor(String name, UUID id, int suffix) {
        StringBuilder safe = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            // Letters and digits of any script are kept (the design's names are bilingual), everything
            // that could mean something to a filesystem is not.
            safe.append(Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.' ? c : '_');
        }
        String cleaned = safe.toString().trim();
        if (cleaned.isEmpty() || cleaned.equals(".") || cleaned.equals("..")) {
            cleaned = id.toString();
        }
        return suffix == 0 ? cleaned + SUFFIX : cleaned + " (" + suffix + ")" + SUFFIX;
    }

    /** The world directory this store belongs to. */
    public Path root() {
        return this.root;
    }
}
