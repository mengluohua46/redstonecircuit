package com.jiangyuefengyu.redstonecircuit.blueprint;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.Nullable;

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneNode;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore;
import com.jiangyuefengyu.redstonecircuit.logic.ClockRule;
import com.jiangyuefengyu.redstonecircuit.logic.ExtenderRule;
import com.jiangyuefengyu.redstonecircuit.network.RCNetwork;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Puts a blueprint's inner redstone into the file Create wrote, on the side that owns the data.
 *
 * <p>The store only exists on a {@link ServerLevel}, so a client cannot write the components itself and the work has
 * to happen on the server - which is also the only side that can be trusted with it. Two paths reach a file, and both
 * end in {@link #attachToFile}: the server exporting a region itself ({@code SchematicExport#saveSchematic}, which is
 * what the quill triggers and what the instant-build packet re-runs), and the server receiving bytes a client wrote
 * ({@code ServerSchematicLoader#handleFinishedUpload}, after its stream is closed).
 *
 * <p>Everything here is additive and guarded: a refusal means the file is left exactly as Create wrote it, which is a
 * working blueprint with no components in it.
 */
public final class BlueprintExport {

    /** The longest schematic file name that is still a name; also the wire's limit for it. */
    public static final int MAX_NAME_LENGTH = 128;

    private static final String NBT_SUFFIX = ".nbt";

    /** The Create class that owns the upload directory, reached by name so this class names no Create type. */
    private static final String CREATE_PATHS = "com.simibubi.create.foundation.utility.CreatePaths";

    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    private static boolean uploadedDirectoryDone;
    @Nullable
    private static Path uploadedDirectory;

    private BlueprintExport() {
    }

    // -------------------------------------------------------------- exports --

    /**
     * Called with whatever {@code SchematicExport#saveSchematic} returned, on either side.
     *
     * <p>On a server the file was just written and is injected into place. On a client there is nothing to inject - the
     * client has no store - so the box is only remembered, and it is remembered through the file name the result
     * carries rather than the one that was asked for, because Create renames an export rather than overwriting one.
     */
    public static void afterExport(@Nullable Object result, @Nullable Level level, @Nullable BlockPos first,
                                   @Nullable BlockPos second) {
        if (result == null || level == null || first == null || second == null) {
            return;
        }
        if (level instanceof ServerLevel serverLevel) {
            if (accessor(result, "file") instanceof Path file) {
                attachToFile(file, serverLevel, first, second);
            }
            return;
        }
        if (accessor(result, "fileName") instanceof String fileName) {
            BlueprintLocalSaves.remember(fileName, level.dimension(), first, second);
        }
    }

    /** Client: the file about to be uploaded, and the box this client exported it from, if it did. */
    public static void announceUpload(@Nullable String schematic) {
        if (schematic == null) {
            return;
        }
        BlueprintLocalSaves.Save save = BlueprintLocalSaves.find(schematic);
        if (save == null) {
            return;
        }
        try {
            PacketDistributor.sendToServer(new RCNetwork.SchematicSelectionPayload(schematic, save.dimension(),
                    save.first(), save.second()));
        } catch (RuntimeException | LinkageError notConnected) {
            // Nothing was announced, which the server takes as "no components" - the upload itself is Create's and
            // must not be disturbed by an announcement that cannot be made.
            reportOnce("announce", "the blueprint selection could not be announced: " + notConnected);
        }
    }

    /**
     * Server: the uploaded file is complete, so the region its author announced is applied to it.
     *
     * <p>The announcement is consumed here whatever happens next: one announcement describes one upload.
     */
    public static void afterUpload(@Nullable ServerPlayer player, @Nullable String schematic) {
        if (player == null || schematic == null) {
            return;
        }
        BlueprintUploadRequests.Pending pending = BlueprintUploadRequests.take(player.getUUID(), schematic);
        if (pending == null) {
            return;
        }
        if (!(player.level() instanceof ServerLevel level) || !level.dimension().equals(pending.dimension())) {
            reportOnce("dimension", "a blueprint selection was announced in " + pending.dimension().location()
                    + " and is not usable from " + player.level().dimension().location());
            return;
        }
        Optional<String> refused = limits(player, pending.first(), pending.second());
        if (refused.isPresent()) {
            reportOnce("upload-refused", "a blueprint selection was refused: " + refused.get());
            return;
        }
        Path directory = uploadedDirectory();
        if (directory == null) {
            return;
        }
        Path file = directory.resolve(player.getGameProfile().getName()).normalize().resolve(schematic).normalize();
        // Create normalises both halves itself; this repeats the check because the two are what the path is built
        // from, and a name that escapes the player's own folder is the one thing an upload must never be able to do.
        if (!file.startsWith(directory) || !isPlainNbtName(schematic) || !Files.isRegularFile(file)) {
            return;
        }
        attachToFile(file, level, pending.first(), pending.second());
    }

    // ----------------------------------------------------------- the section --

    /**
     * Reads the file, adds this mod's section and writes it back, or leaves the file untouched.
     *
     * <p>A file that already carries a section is left alone: it was written by a build that had the data at hand
     * (a single-player export, an earlier upload of the same region), and re-deriving it from a second region is more
     * likely to be wrong than right.
     *
     * <p>The rewrite goes through a sibling file so a failure mid-write cannot leave a truncated blueprint behind.
     */
    public static boolean attachToFile(@Nullable Path file, @Nullable ServerLevel level, @Nullable BlockPos first,
                                       @Nullable BlockPos second) {
        if (file == null || level == null || first == null || second == null) {
            return false;
        }
        BoundingBox box = BoundingBox.fromCorners(first, second);
        Vec3i size = new Vec3i(box.getXSpan(), box.getYSpan(), box.getZSpan());
        Optional<String> refused = BlueprintLimits.DEFAULT.refuse(size);
        if (refused.isPresent()) {
            reportOnce("size", "a blueprint no longer carries components: " + refused.get());
            return false;
        }
        CompoundTag section = sectionFor(level, box, size);
        if (section == null) {
            return false;
        }
        Path temporary = file.resolveSibling(file.getFileName() + ".redstonecircuit");
        try {
            CompoundTag blueprint = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            if (blueprint.contains(BlueprintComponents.KEY)) {
                return false;
            }
            blueprint.put(BlueprintComponents.KEY, section);
            NbtIo.writeCompressed(blueprint, temporary);
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException | RuntimeException failed) {
            reportOnce("write", "a blueprint could not be given its components (" + file.getFileName() + "): "
                    + failed);
            deleteQuietly(temporary);
            return false;
        }
    }

    /**
     * Everything the store holds inside {@code box}, in the coordinates the file itself uses, or {@code null} when
     * there is nothing to add.
     *
     * <p>The walk is over the store's own keys rather than over the box's cells: a level's components are a few
     * thousand positions at most, a large box is millions of cells, and only the first number grows with what
     * actually exists.
     */
    @Nullable
    private static CompoundTag sectionFor(ServerLevel level, BoundingBox box, Vec3i size) {
        InnerRedstoneStore store = InnerRedstoneStore.get(level);
        if (store == null || store.isEmpty()) {
            return null;
        }
        BlockPos origin = new BlockPos(box.minX(), box.minY(), box.minZ());

        List<BlueprintComponents.Node> nodes = new ArrayList<>();
        for (long key : store.positionKeys()) {
            BlockPos pos = BlockPos.of(key);
            if (!box.isInside(pos)) {
                continue;
            }
            InnerRedstoneNode node = store.get(key);
            if (node != null && !node.isEmpty()) {
                nodes.add(new BlueprintComponents.Node(pos.subtract(origin), node));
            }
        }
        List<BlueprintComponents.Clock> clocks = new ArrayList<>();
        for (long key : store.clockKeys()) {
            clockEntry(store, box, origin, key, clocks);
        }
        List<BlueprintComponents.Extender> extenders = new ArrayList<>();
        for (long key : store.extenderKeys()) {
            extenderEntry(store, box, origin, key, extenders);
        }
        if (nodes.isEmpty() && clocks.isEmpty() && extenders.isEmpty()) {
            return null;
        }
        int entries = nodes.size() + clocks.size() + extenders.size();
        if (BlueprintLimits.DEFAULT.refusesEntries(entries)) {
            reportOnce("entries", "a blueprint would carry " + entries + " components, the limit is "
                    + BlueprintLimits.DEFAULT.maxEntries());
            return null;
        }
        return BlueprintComponents.write(size, nodes, clocks, extenders);
    }

    private static void clockEntry(InnerRedstoneStore store, BoundingBox box, BlockPos origin, long key,
                                   List<BlueprintComponents.Clock> out) {
        BlockPos pos = BlockPos.of(key);
        if (!box.isInside(pos)) {
            return;
        }
        ClockRule.Setting setting = store.clockAt(pos);
        if (setting != null) {
            out.add(new BlueprintComponents.Clock(pos.subtract(origin), setting));
        }
    }

    private static void extenderEntry(InnerRedstoneStore store, BoundingBox box, BlockPos origin, long key,
                                      List<BlueprintComponents.Extender> out) {
        BlockPos pos = BlockPos.of(key);
        if (!box.isInside(pos)) {
            return;
        }
        ExtenderRule.Setting setting = store.extenderAt(pos);
        if (setting != null) {
            out.add(new BlueprintComponents.Extender(pos.subtract(origin), setting));
        }
    }

    // ----------------------------------------------------------------- policy --

    /**
     * Everything that can be decided about a selection before a single position is read: the shape of the box and how
     * far its author is from it. An empty answer means acceptable.
     */
    public static Optional<String> limits(ServerPlayer player, BlockPos first, BlockPos second) {
        BoundingBox box = BoundingBox.fromCorners(first, second);
        Vec3i size = new Vec3i(box.getXSpan(), box.getYSpan(), box.getZSpan());
        Optional<String> refused = BlueprintLimits.DEFAULT.refuse(size);
        if (refused.isPresent()) {
            return refused;
        }
        return BlueprintLimits.DEFAULT.refuseDistance(player.blockPosition(),
                new BlockPos(box.minX(), box.minY(), box.minZ()), size);
    }

    /** True when a name is a bare {@code *.nbt} file name and cannot address anything outside its own folder. */
    public static boolean isPlainNbtName(@Nullable String name) {
        if (name == null || name.length() <= NBT_SUFFIX.length() || name.length() > MAX_NAME_LENGTH
                || !name.endsWith(NBT_SUFFIX) || name.contains("..")) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '/' || c == '\\' || c == ':' || c < ' ') {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- plumbing --

    /** Create's own upload directory, or {@code null} when it cannot be reached. Resolved at most once. */
    @Nullable
    private static Path uploadedDirectory() {
        if (uploadedDirectoryDone) {
            return uploadedDirectory;
        }
        uploadedDirectoryDone = true;
        try {
            Class<?> paths = Class.forName(CREATE_PATHS, false, BlueprintExport.class.getClassLoader());
            Field field = paths.getField("UPLOADED_SCHEMATICS_DIR");
            // Read as Object first: a Path is nothing to us until it is known to be one.
            Object value = field.get(null);
            uploadedDirectory = value instanceof Path path ? path : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            uploadedDirectory = null;
            reportOnce("paths", "the uploaded-blueprint directory could not be reached: " + unavailable);
        }
        return uploadedDirectory;
    }

    /**
     * One accessor of the record Create returns, or {@code null}.
     *
     * <p>By name rather than by type because the result record is Create's, and this mod's source set may not name a
     * Create type: a rename there has to mean "no components", not a failed class load.
     */
    @Nullable
    private static Object accessor(Object result, String name) {
        try {
            Method method = result.getClass().getMethod(name);
            return method.invoke(result);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            reportOnce("result-" + name, "a blueprint export could not be read back: " + unavailable);
            return null;
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException | RuntimeException ignored) {
            // A leftover sibling file is untidy; failing to remove it is not worth a second failure path.
        }
    }

    /** Reports a condition once per key: every one of these can happen per blueprint, not per tick. */
    private static void reportOnce(String key, String message) {
        if (REPORTED.add(key)) {
            RCConfig.LOGGER.warn("[redstonecircuit] blueprint components: {}", message);
        }
    }
}
