package com.jiangyuefengyu.redstonecircuit.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.Nullable;

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.RedstoneCircuit;
import com.jiangyuefengyu.redstonecircuit.block.RedstoneStructureBlockEntity;
import com.jiangyuefengyu.redstonecircuit.structure.PreviewRegion;
import com.jiangyuefengyu.redstonecircuit.structure.StructureActions;
import com.jiangyuefengyu.redstonecircuit.structure.StructureRelease;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The two-stage operation of §5.6, on the server: what one operation on a controller means, and who is told about the
 * border.
 *
 * <p>The first operation shows a region and changes nothing; the second one does it. What tells them apart is the
 * controller's own {@code previewArmed} flag - not a countdown, not a screen - so a player who closes the game between
 * the two comes back to a preview that still needs confirming, and re-logging has not banked the second operation.
 * Editing the region disarms it again (see {@code RedstoneStructureBlockEntity#setPreview}), because a player must have
 * looked at <b>this</b> rectangle before it happens.
 *
 * <p>The registry is only a set of positions, and the broadcast snapshot is built by <b>reading the block entities
 * back</b> rather than by remembering their contents: a set of positions cannot disagree with the block it points at,
 * and an entry whose block is gone, unloaded or no longer previewing is dropped when a snapshot is built.
 */
@EventBusSubscriber(modid = RedstoneCircuit.MODID)
public final class StructurePreviews {

    /** What one operation did. */
    public enum Step {
        /** The first operation: a region is up and nothing has happened to the world. */
        SHOWN,
        /** The confirmation: one more operation and it happens. */
        ARMED,
        /** It happened. */
        APPLIED,
        /** The preview was taken down without anything happening. */
        ENDED,
        /** Nothing was done, and the sentence says why. */
        REFUSED
    }

    /**
     * One border, as a client is told about it.
     *
     * <p>Position, size, mode and state, plus the one number a player needs before confirming a release (how much of
     * the world is in the way). No structure data, ever.
     */
    public record Entry(BlockPos controller, boolean release, PreviewRegion region, boolean armed,
                        int overwrite) {
    }

    /** The outcome of one operation, in words a command, an action bar or a log line can all use. */
    public record Result(Step step, boolean ok, String message, List<String> details) {

        static Result of(Step step, String message, String... details) {
            return new Result(step, true, message, List.of(details));
        }

        static Result refused(Step step, String reason) {
            return new Result(step, false, reason, List.of());
        }
    }

    /**
     * The positions that have a border up, per dimension.
     *
     * <p>A set of positions rather than a map of borders: see the class comment. A dimension with no previews
     * is removed, and {@link #snapshot} prunes anything whose controller has stopped previewing.
     */
    private static final Map<ResourceKey<Level>, Set<BlockPos>> ACTIVE = new ConcurrentHashMap<>();

    /**
     * One operation on a controller - the whole of §5.6's state machine, in one place.
     *
     * <p>Three callers, one path: the block's own right-click, the {@code /rc structure} commands while there is no
     * screen, and the self-test on a real server. {@code player} is who operated, or null for a command source that is
     * not a player.
     */
    public static Result press(ServerLevel level, @Nullable ServerPlayer player, BlockPos pos) {
        RedstoneStructureBlockEntity controller = StructureActions.controllerAt(level, pos);
        if (controller == null) {
            return Result.refused(Step.REFUSED, "no redstone structure block at " + pos.toShortString());
        }
        if (!controller.isPreviewing()) {
            // §5.6's first operation. With no region there is nothing to show, so the block itself is the
            // starting region - which is also where vanilla's own structure block starts a selection. A
            // release starts from the size of what would be written, because a release's rectangle is decided
            // by the payload: a border of any other size would be a border about a different operation.
            PreviewRegion start = controller.mode() == RedstoneStructureBlockEntity.Mode.RELEASE
                    && controller.pending() != null
                            ? new PreviewRegion(pos, controller.pending().size())
                            : PreviewRegion.one(pos);
            controller.setPreview(start);
            return Result.of(Step.SHOWN, "previewing " + controller.preview().describe()
                    + " (" + (controller.mode() == RedstoneStructureBlockEntity.Mode.RELEASE
                            ? "release" : "capture")
                    + "): nothing has happened to the world yet - operate again to confirm");
        }
        PreviewRegion region = controller.preview();
        Optional<String> unusable = region.refusal(RCConfig.structurePayloadLimits(),
                level.getMinBuildHeight(), level.getMaxBuildHeight());
        if (unusable.isPresent()) {
            return Result.refused(Step.REFUSED, "this region cannot be used: " + unusable.get());
        }
        if (controller.mode() == RedstoneStructureBlockEntity.Mode.RELEASE
                && controller.pending() != null
                && !region.size().equals(controller.pending().size())) {
            // What gets written is the payload's size, so a border of another size is a border about a
            // different rectangle than the operation would use - the one thing a preview must never be.
            return Result.refused(Step.REFUSED, "this border is " + region.describe() + " but a release writes "
                    + controller.pending().size().getX() + "x" + controller.pending().size().getY() + "x"
                    + controller.pending().size().getZ() + ": place it with /rc structure target so that what is "
                    + "shown and what is written are the same rectangle");
        }
        if (!controller.isPreviewArmed()) {
            controller.armPreview();
            return Result.of(Step.ARMED, confirming(level, controller, region));
        }
        // The second operation. Both roads end the preview on success (see StructureActions) and keep it up
        // on a refusal, because a refusal is exactly the case where the player still needs to see the border.
        boolean release = controller.mode() == RedstoneStructureBlockEntity.Mode.RELEASE;
        StructureActions.Result done = release
                ? StructureActions.release(level, player, pos, region.min())
                : StructureActions.capture(level, player, pos);
        if (!done.ok()) {
            return Result.refused(Step.REFUSED, done.headline());
        }
        if (RCConfig.debugLog()) {
            RCConfig.LOGGER.info("[redstonecircuit] structure {} at {} applied: {}",
                    release ? "release" : "capture", pos.toShortString(), done.headline());
        }
        return new Result(Step.APPLIED, true, done.headline(), done.details());
    }

    /** Takes a preview down without anything happening. One of §5.6's three ways for a preview to end. */
    public static Result cancel(ServerLevel level, BlockPos pos) {
        RedstoneStructureBlockEntity controller = StructureActions.controllerAt(level, pos);
        if (controller == null) {
            return Result.refused(Step.REFUSED, "no redstone structure block at " + pos.toShortString());
        }
        if (!controller.isPreviewing()) {
            return Result.refused(Step.REFUSED, "that controller is not previewing anything");
        }
        controller.endPreview();
        return Result.of(Step.ENDED, "preview ended; nothing happened to the world");
    }

    /** The sentence a player sees in the "one more operation" state. */
    private static String confirming(ServerLevel level, RedstoneStructureBlockEntity controller,
                                     PreviewRegion region) {
        if (controller.mode() != RedstoneStructureBlockEntity.Mode.RELEASE) {
            return "confirmed " + region.describe() + " (" + controller.tier() + "): operate once more and "
                    + "the region is taken";
        }
        int occupied = StructureRelease.occupied(level, region.min(), region.size());
        return "confirmed " + region.describe() + ": operate once more and "
                + (occupied == 0 ? "the structure is written into an empty target"
                        : occupied + " block(s) in the target are overwritten");
    }

    /** A controller has something to show: remember the position and tell the dimension. */
    public static void publish(@Nullable Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel server)) {
            return;
        }
        ACTIVE.computeIfAbsent(server.dimension(), key -> ConcurrentHashMap.newKeySet()).add(pos);
        broadcast(server);
    }

    /** A controller has nothing to show any more: forget it and tell the dimension. */
    public static void withdraw(@Nullable Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel server)) {
            return;
        }
        Set<BlockPos> positions = ACTIVE.get(server.dimension());
        if (positions == null || !positions.remove(pos)) {
            return;
        }
        broadcast(server);
    }

    /**
     * Every border a player in this dimension should see, read back out of the loaded controllers.
     *
     * <p>Reading them back is what makes a stale entry impossible, and the {@code isLoaded} test first is what
     * makes it cheap: a position whose chunk is not loaded is skipped <b>without</b> asking the level for it -
     * {@code getBlockEntity} would load the chunk, and a snapshot must never generate terrain.
     */
    public static List<Entry> snapshot(ServerLevel level) {
        Set<BlockPos> positions = ACTIVE.get(level.dimension());
        if (positions == null || positions.isEmpty()) {
            return List.of();
        }
        List<Entry> entries = new ArrayList<>(positions.size());
        for (BlockPos pos : positions) {
            if (!level.isLoaded(pos)) {
                // Not an error and not a removal: §5.6 keeps the preview across a chunk unload, and the border
                // is back the moment the chunk is.
                continue;
            }
            if (!(level.getBlockEntity(pos) instanceof RedstoneStructureBlockEntity controller)
                    || !controller.isPreviewing()) {
                continue;
            }
            entries.add(entryFor(level, pos, controller));
        }
        positions.retainAll(entries.stream().map(Entry::controller).toList());
        entries.sort((a, b) -> a.controller().compareTo(b.controller()));
        return List.copyOf(entries);
    }

    private static Entry entryFor(ServerLevel level, BlockPos pos, RedstoneStructureBlockEntity controller) {
        PreviewRegion region = controller.preview();
        boolean release = controller.mode() == RedstoneStructureBlockEntity.Mode.RELEASE;
        // Counted here rather than remembered: what is in the way is a fact about the world, and a remembered
        // count would be wrong the moment a player placed a block inside the target.
        int overwrite = release ? StructureRelease.occupied(level, region.min(), region.size()) : 0;
        return new Entry(pos, release, region, controller.isPreviewArmed(), overwrite);
    }

    /** Tells everyone in a dimension what the borders are. */
    public static void broadcast(ServerLevel level) {
        List<Entry> entries = snapshot(level);
        RCNetwork.PreviewSnapshotPayload payload =
                new RCNetwork.PreviewSnapshotPayload(level.dimension(), entries);
        for (ServerPlayer player : level.players()) {
            PacketDistributor.sendToPlayer(player, payload);
        }
    }

    /**
     * Tells one player what the borders are where they are.
     *
     * <p>This is the half of the preview's lifetime that a broadcast cannot cover: a player who joins, or who walks
     * into a dimension that already has previews up, has never seen a broadcast for them.
     */
    public static void sendSnapshotTo(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        PacketDistributor.sendToPlayer(player,
                new RCNetwork.PreviewSnapshotPayload(level.dimension(), snapshot(level)));
    }

    @SubscribeEvent
    public static void onJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            sendSnapshotTo(player);
        }
    }

    @SubscribeEvent
    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            sendSnapshotTo(player);
        }
    }

    /** For the tests: forget every border, as a server restart does. */
    public static void forgetAll() {
        ACTIVE.clear();
    }

    /** How many positions a dimension is remembering - for the diagnostics and the tests. */
    public static int published(ServerLevel level) {
        Set<BlockPos> positions = ACTIVE.get(level.dimension());
        return positions == null ? 0 : positions.size();
    }

    private StructurePreviews() {
    }
}
