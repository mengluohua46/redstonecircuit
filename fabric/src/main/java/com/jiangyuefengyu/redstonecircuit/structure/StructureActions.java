package com.jiangyuefengyu.redstonecircuit.structure;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.block.RedstoneStructureBlockEntity;
import com.jiangyuefengyu.redstonecircuit.compat.physics.PhysicsCompat;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * The two things a controller can actually do - take a region, or put a structure back - as functions with no
 * opinion about who asked.
 *
 * <p>The two-stage gate is not here: whether this is the operation that happens or only the one being shown is the
 * controller's own preview state ({@code network.StructurePreviews}). The two-stage press and {@code /rc structure}
 * must reach the same checks, so a rule cannot hold on one road and not the other.
 */
public final class StructureActions {

    /** What happened, in the words a player, a command block or a log line want. */
    public record Result(boolean ok, String headline, List<String> details) {

        static Result refused(String reason) {
            return new Result(false, reason, List.of());
        }

        static Result done(String headline, List<String> details) {
            return new Result(true, headline, List.copyOf(details));
        }
    }

    /** The controller at a position, or null - the lookup both actions start with. */
    @Nullable
    public static RedstoneStructureBlockEntity controllerAt(ServerLevel level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof RedstoneStructureBlockEntity found ? found : null;
    }

    /**
     * Captures the region the controller is showing. A copy request from a non-creative source falls back to a cut
     * rather than being refused, and the controller's manual panel travels with the capture as well as its faces.
     */
    public static Result capture(ServerLevel level, @Nullable ServerPlayer player, BlockPos pos) {
        RedstoneStructureBlockEntity controller = controllerAt(level, pos);
        if (controller == null) {
            return Result.refused("no redstone structure block at " + pos.toShortString());
        }
        if (!controller.isPreviewing()) {
            return Result.refused("no region selected: pick two corners first (/rc structure select ...)");
        }
        PreviewRegion region = controller.preview();
        Optional<String> refusal = region.refusal(RCConfig.structurePayloadLimits(),
                level.getMinBuildHeight(), level.getMaxBuildHeight());
        if (refusal.isPresent()) {
            return Result.refused("refused: " + refusal.get());
        }
        // The controller is the block holding the result: a cut that took it would leave the structure in the save
        // file with no block anywhere that knows its id. Refused at the operation, not at the edit.
        if (region.box().contains(pos)) {
            return Result.refused("refused: the region contains this controller's own block ("
                    + pos.toShortString() + "). A cut would take the controller with it, and the structure would be "
                    + "left with no block that knows its id - move the region off this block and capture again");
        }
        // A host block that a carrier is carrying must not go into a payload with the carrier relation on it, so
        // the capture is refused rather than landing it: landing would branch inside the physics compat layer.
        if (PhysicsCompat.active()) {
            java.util.function.Predicate<BlockPos> carried =
                    candidate -> com.jiangyuefengyu.redstonecircuit.carrier.CarrierStore.isCarried(level, candidate);
            BlockPos firstCarried = firstCarried(region, carried);
            if (firstCarried != null) {
                return Result.refused("refused: the block at " + firstCarried.toShortString() + " is being carried "
                        + "by a moving structure, and a carried block must not travel into a payload with that "
                        + "relation on it - land it first (or leave it out of the region) and capture again");
            }
        }
        boolean copy = controller.tier() == RedstoneStructureBlockEntity.Tier.COPY
                && RCConfig.structureAllowCopy()
                && player != null
                && player.isCreative();
        StructureCapture.Request request = new StructureCapture.Request(region.min(), region.max(),
                controller.ioMode(), controller.structureName(), !copy,
                RCConfig.structureCaptureContextShell(), pos, controller.faces(), controller.manual(),
                controller.output());

        StructureCapture.Result result = StructureCapture.capture(level, request,
                RCConfig.structureCaptureLimits());
        if (!result.allowed()) {
            return Result.refused("refused: " + result.refusal());
        }
        StructureCapture.Captured captured = result.captured();
        try {
            store(level).add(captured.payload());
        } catch (IOException failed) {
            RCConfig.LOGGER.error("[redstonecircuit] structure capture could not be stored", failed);
            return Result.refused("the capture could not be written to this save: " + failed);
        }

        StructureProductData product = StructureProductData.of(captured.payload(),
                RCConfig.structurePreviewBytes(), RCConfig.structurePreviewCells());
        controller.setPending(product);
        // The take is over: the region has left the world (a cut) or been duplicated (a copy), and a successful
        // operation is one of the ways a preview ends.
        controller.endPreview();
        return Result.done((copy ? "copied " : "cut ") + captured.volume() + " block(s) into "
                + product.name() + " (" + product.id() + ")", List.of(
                        "palette " + captured.paletteSize() + ", block entities " + captured.blockEntities()
                                + ", our components " + captured.slotEntries() + ", shell "
                                + captured.shellBlocks() + ", nesting " + captured.nestingDepth(),
                        "preview: " + describe(product)));
    }

    /**
     * Releases what the controller is holding, with the region's first cell at {@code target}. {@code confirmed} is
     * always true because the overwrite guard's question has already been asked and answered by the preview stage,
     * which reports the number while the border is up. A structure whose file is not in this save is refused rather
     * than released as a hole.
     */
    public static Result release(ServerLevel level, @Nullable ServerPlayer player, BlockPos pos,
                                 BlockPos target) {
        RedstoneStructureBlockEntity controller = controllerAt(level, pos);
        if (controller == null) {
            return Result.refused("no redstone structure block at " + pos.toShortString());
        }
        StructureProductData pending = controller.pending();
        if (pending == null) {
            return Result.refused("this controller is holding nothing: capture something first "
                    + "(/rc structure capture)");
        }
        StructurePayload payload = store(level).loadOrNull(pending.id());
        if (payload == null) {
            return Result.refused("MISSING: no readable structure " + pending.id() + " in this save. It came "
                    + "from another save (or its file is gone), so it cannot be released - import the file or "
                    + "capture it again");
        }

        StructureRelease.Result result = StructureRelease.start(level, payload, target, true,
                RCConfig.structureReleaseOptions());
        if (!result.started()) {
            return Result.refused("not yet: " + result.refusal());
        }
        // 生效即消耗: the design says the product in the slot is gone once a release goes ahead.
        controller.setPending(null);
        controller.endPreview();
        StructureRelease.Job job = result.job();
        int ticks = (job.total() + RCConfig.structureReleaseOptions().blocksPerTick() - 1)
                / RCConfig.structureReleaseOptions().blocksPerTick();
        return Result.done("releasing " + job.name() + ": " + job.total() + " block(s) into "
                + job.origin().toShortString() + ", spread over about " + ticks + " tick(s)", List.of());
    }

    /** What a client would be able to draw for this description, or the placeholder case. */
    public static String describe(StructureProductData data) {
        return data.preview() == null
                ? "none (this description cannot draw itself - the missing-placeholder case)"
                : data.preview().describe();
    }

    /**
     * The first cell of {@code region} that {@code carried} says is being carried, or null. A predicate, so the rule
     * - a region that holds a carried block may not be captured - stays decidable in a unit test with no physics mod
     * installed; the real caller passes {@code candidate -> CarrierStore.isCarried(level, candidate)}.
     */
    @Nullable
    static BlockPos firstCarried(PreviewRegion region, java.util.function.Predicate<BlockPos> carried) {
        for (int y = 0; y < region.size().getY(); y++) {
            for (int z = 0; z < region.size().getZ(); z++) {
                for (int x = 0; x < region.size().getX(); x++) {
                    BlockPos cell = region.min().offset(x, y, z);
                    if (carried.test(cell)) {
                        return cell;
                    }
                }
            }
        }
        return null;
    }

    /** This save's structure files, as the actions read them. */
    private static StructureWorldStore store(ServerLevel level) {
        return StructureWorldStore.of(level.getServer(), RCConfig.structurePayloadLimits());
    }

    private StructureActions() {
    }
}
