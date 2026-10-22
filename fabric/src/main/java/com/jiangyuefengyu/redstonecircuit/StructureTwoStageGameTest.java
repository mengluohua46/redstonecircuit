package com.jiangyuefengyu.redstonecircuit;

import org.jetbrains.annotations.Nullable;

import com.jiangyuefengyu.redstonecircuit.block.RedstoneStructureBlockEntity;
import com.jiangyuefengyu.redstonecircuit.network.StructurePreviews;
import com.jiangyuefengyu.redstonecircuit.structure.ActivationTable;
import com.jiangyuefengyu.redstonecircuit.structure.IoMode;
import com.jiangyuefengyu.redstonecircuit.structure.PayloadLimits;
import com.jiangyuefengyu.redstonecircuit.structure.PreviewRegion;
import com.jiangyuefengyu.redstonecircuit.structure.StructureCapture;
import com.jiangyuefengyu.redstonecircuit.structure.StructurePayload;
import com.jiangyuefengyu.redstonecircuit.structure.StructureProductData;
import com.jiangyuefengyu.redstonecircuit.structure.StructureWorldStore;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

/**
 * The two-stage operation: a region is shown, then it is confirmed, then it happens.
 *
 * <p>The tests assert on the controller's <b>state</b> at each step - a border that is up, a border that is confirmed,
 * a region that is gone - rather than on "the nth call", because that is what a player experiences. A take of the
 * controller's own cell is refused, since the cut would carry the controller off with it, and every assertion names
 * its own controller: a border list is per dimension and these batches run in parallel in one level.
 */
public final class StructureTwoStageGameTest implements FabricGameTest {

    /** The controller's own block entity at a position, or a failure. */
    private static RedstoneStructureBlockEntity controllerAt(GameTestHelper helper, BlockPos pos) {
        if (helper.getLevel().getBlockEntity(pos) instanceof RedstoneStructureBlockEntity controller) {
            return controller;
        }
        helper.fail("no controller block entity at " + pos.toShortString());
        throw new IllegalStateException("unreachable");
    }

    /** This controller's border, as a client would be told about it, or null. */
    @Nullable
    private static StructurePreviews.Entry borderOf(ServerLevel level, BlockPos controller) {
        for (StructurePreviews.Entry entry : StructurePreviews.snapshot(level)) {
            if (entry.controller().equals(controller)) {
                return entry;
            }
        }
        return null;
    }

    /** A controller at {@code pos} showing {@code region}, in the given mode - the "shown" state. */
    private static RedstoneStructureBlockEntity preparedController(GameTestHelper helper, BlockPos pos,
                                                                   PreviewRegion region,
                                                                   RedstoneStructureBlockEntity.Mode mode) {
        ServerLevel level = helper.getLevel();
        level.setBlock(pos, RCRegistry.REDSTONE_STRUCTURE_BLOCK.defaultBlockState(), 3);
        RedstoneStructureBlockEntity controller = controllerAt(helper, pos);
        controller.setMode(mode);
        controller.setTier(RedstoneStructureBlockEntity.Tier.CUT);
        controller.setPreview(region);
        return controller;
    }

    /** A floor of stone under a row of blocks, so a wire in the region has support and does not drop. */
    private static void floorAndWire(ServerLevel level, BlockPos first, int length) {
        for (int x = 0; x < length; x++) {
            level.setBlock(first.offset(x, -1, 0), Blocks.STONE.defaultBlockState(), 3);
            level.setBlock(first.offset(x, 0, 0), Blocks.REDSTONE_WIRE.defaultBlockState(), 3);
        }
    }

    /** The confirmation and then the operation itself, asserting each step. */
    private static void confirmThenApply(GameTestHelper helper, ServerLevel level, BlockPos controllerPos,
                                         String what) {
        StructurePreviews.Result armed = StructurePreviews.press(level, null, controllerPos);
        helper.assertTrue(armed.step() == StructurePreviews.Step.ARMED,
                "confirming " + what + " must not do it yet, got " + armed.step() + ": " + armed.message());
        StructurePreviews.Entry border = borderOf(level, controllerPos);
        helper.assertTrue(border != null && border.armed(),
                "and the border a client is drawing must say so: " + border);
        StructurePreviews.Result done = StructurePreviews.press(level, null, controllerPos);
        helper.assertTrue(done.step() == StructurePreviews.Step.APPLIED,
                "the confirmed operation must happen, got " + done.step() + ": " + done.message());
    }

    @GameTest(template = "redstonecircuit:empty")
    public void theFirstOperationPutsUpABorderAndNothingElse(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos controllerPos = helper.absolutePos(new BlockPos(1, 2, 1));
        BlockPos first = helper.absolutePos(new BlockPos(3, 2, 1));
        floorAndWire(level, first, 3);

        // A controller with no region at all, as a player's first click finds it.
        level.setBlock(controllerPos, RCRegistry.REDSTONE_STRUCTURE_BLOCK.defaultBlockState(), 3);
        RedstoneStructureBlockEntity controller = controllerAt(helper, controllerPos);
        controller.setMode(RedstoneStructureBlockEntity.Mode.CAPTURE);
        controller.setTier(RedstoneStructureBlockEntity.Tier.CUT);
        helper.assertTrue(!controller.isPreviewing(), "a fresh controller is showing nothing");

        StructurePreviews.Result shown = StructurePreviews.press(level, null, controllerPos);
        helper.assertTrue(shown.step() == StructurePreviews.Step.SHOWN,
                "the first operation must only show a region, got " + shown.step() + ": " + shown.message());
        helper.assertTrue(controller.isPreviewing() && !controller.isPreviewArmed(),
                "the first operation leaves a border that is not confirmed");
        helper.assertTrue(controller.preview().size().equals(new Vec3i(1, 1, 1)),
                "with no region given, it starts at the block itself, as vanilla's structure block does");
        StructurePreviews.Entry border = borderOf(level, controllerPos);
        helper.assertTrue(border != null && !border.armed(),
                "and it must publish that border for clients to draw");
        for (int x = 0; x < 3; x++) {
            helper.assertTrue(level.getBlockState(first.offset(x, 0, 0)).is(Blocks.REDSTONE_WIRE),
                    "a border that is only being shown must not touch the world, and cell " + x + " is gone");
        }

        // The edit a player makes while the border is up: the region moves to the wire, still unconfirmed.
        PreviewRegion region = PreviewRegion.of(first, first.offset(2, 0, 0));
        controller.setPreview(region);
        helper.assertTrue(!controller.isPreviewArmed(), "an edit must put the confirmation back");
        helper.assertTrue(borderOf(level, controllerPos).region().equals(region),
                "and the border a client draws must be the edited one");

        confirmThenApply(helper, level, controllerPos, "the region");
        for (int x = 0; x < 3; x++) {
            helper.assertTrue(level.getBlockState(first.offset(x, 0, 0)).isAir(),
                    "the cut takes the whole confirmed region, and cell " + x + " is still there");
        }
        helper.assertTrue(controller.pending() != null && controller.pending().size().equals(new Vec3i(3, 1, 1)),
                "the take fills the slot with exactly the region that was taken");
        helper.assertTrue(!controller.isPreviewing() && borderOf(level, controllerPos) == null,
                "and a successful operation ends the preview, border and all");
        helper.succeed();
    }

    @GameTest(template = "redstonecircuit:empty")
    public void theRegionThatWasShownIsTheRegionThatIsTaken(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos controllerPos = helper.absolutePos(new BlockPos(1, 2, 1));
        BlockPos first = helper.absolutePos(new BlockPos(3, 2, 1));
        floorAndWire(level, first, 3);
        RedstoneStructureBlockEntity controller = preparedController(helper, controllerPos,
                PreviewRegion.of(first, first.offset(2, 0, 0)), RedstoneStructureBlockEntity.Mode.CAPTURE);

        // Confirm the three-cell region, then edit it down to one cell: the confirmation is gone with it.
        helper.assertTrue(StructurePreviews.press(level, null, controllerPos).step()
                == StructurePreviews.Step.ARMED, "confirming the three-cell region");
        helper.assertTrue(controller.isPreviewArmed(), "and the controller agrees it is confirmed");
        controller.setPreview(PreviewRegion.one(first));
        helper.assertTrue(!controller.isPreviewArmed(), "editing the region must take the confirmation back");
        StructurePreviews.Entry border = borderOf(level, controllerPos);
        helper.assertTrue(border != null && !border.armed(),
                "and the border must stop reading as confirmed too, got " + border);
        for (int x = 0; x < 3; x++) {
            helper.assertTrue(level.getBlockState(first.offset(x, 0, 0)).is(Blocks.REDSTONE_WIRE),
                    "an edit is not an operation: it must not touch the world");
        }

        confirmThenApply(helper, level, controllerPos, "the edited region");
        helper.assertTrue(level.getBlockState(first).isAir(), "the edited region is what was taken");
        helper.assertTrue(level.getBlockState(first.offset(1, 0, 0)).is(Blocks.REDSTONE_WIRE)
                        && level.getBlockState(first.offset(2, 0, 0)).is(Blocks.REDSTONE_WIRE),
                "and the cells the edit removed from the region are still there - the operation must use the "
                        + "rectangle that was shown, not an earlier one");
        helper.assertTrue(controller.pending().size().equals(new Vec3i(1, 1, 1)),
                "and the slot describes one cell, got " + controller.pending().size());
        helper.succeed();
    }

    @GameTest(template = "redstonecircuit:empty")
    public void aRegionThatCanNeverBeTakenSaysSoWhileItIsOnlyBeingShown(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos controllerPos = helper.absolutePos(new BlockPos(1, 2, 1));
        BlockPos first = helper.absolutePos(new BlockPos(3, 2, 1));
        floorAndWire(level, first, 1);
        RedstoneStructureBlockEntity controller = preparedController(helper, controllerPos,
                new PreviewRegion(first, new Vec3i(49, 1, 1)), RedstoneStructureBlockEntity.Mode.CAPTURE);

        StructurePreviews.Result refused = StructurePreviews.press(level, null, controllerPos);
        helper.assertTrue(refused.step() == StructurePreviews.Step.REFUSED,
                "a 49-block region must be refused while it is only being previewed, so that a player is told "
                        + "before they commit to it, got " + refused.step() + ": " + refused.message());
        helper.assertTrue(level.getBlockState(first).is(Blocks.REDSTONE_WIRE), "and nothing happened");
        helper.assertTrue(controller.isPreviewing(), "the border stays up: the player's next move is to edit it");

        // Editing it into a region that fits is enough to go on - no restart, no re-selection.
        controller.setPreview(PreviewRegion.one(first));
        confirmThenApply(helper, level, controllerPos, "the edited, legal region");
        helper.assertTrue(controller.pending() != null, "and it is taken");
        helper.succeed();
    }

    @GameTest(template = "redstonecircuit:empty")
    public void aRegionHoldingTheControllerItselfMayBeShownButNotTaken(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos controllerPos = helper.absolutePos(new BlockPos(1, 2, 1));
        BlockPos first = helper.absolutePos(new BlockPos(3, 2, 1));
        floorAndWire(level, first, 1);

        // A fresh controller starts on the block itself (1×1×1, as vanilla's structure block does), and that may be
        // shown but never taken: a cut would carry the controller off, leaving no block that knows the structure's id.
        RedstoneStructureBlockEntity controller = preparedController(helper, controllerPos,
                PreviewRegion.one(controllerPos), RedstoneStructureBlockEntity.Mode.CAPTURE);
        helper.assertTrue(StructurePreviews.press(level, null, controllerPos).step()
                == StructurePreviews.Step.ARMED, "the cell over the controller may still be confirmed and shown");
        StructurePreviews.Result taken = StructurePreviews.press(level, null, controllerPos);
        helper.assertTrue(taken.step() == StructurePreviews.Step.REFUSED,
                "but taking it must be refused, got " + taken.step() + ": " + taken.message());
        helper.assertTrue(taken.message().contains(controllerPos.toShortString()),
                "and the refusal must name the block it is about, got " + taken.message());
        helper.assertTrue(level.getBlockState(controllerPos).is(RCRegistry.REDSTONE_STRUCTURE_BLOCK),
                "the controller must still be there");
        helper.assertTrue(controller.pending() == null, "and nothing may have been put in its slot");
        helper.assertTrue(controller.isPreviewing(),
                "the preview stays up, because the player's next move is to move the region off this block");
        helper.assertTrue(level.getBlockState(first).is(Blocks.REDSTONE_WIRE), "and no other block was touched");

        // Moving the region off the controller is all it takes - no restart, no re-selection.
        controller.setPreview(PreviewRegion.of(first, first));
        confirmThenApply(helper, level, controllerPos, "the region beside the controller");
        helper.assertTrue(controller.pending() != null, "and now it is taken");
        helper.assertTrue(level.getBlockState(controllerPos).is(RCRegistry.REDSTONE_STRUCTURE_BLOCK),
                "with the controller still standing");
        helper.succeed();
    }

    @GameTest(template = "redstonecircuit:empty")
    public void aCaptureCarriesTheControllersPanelAndFaceTable(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos controllerPos = helper.absolutePos(new BlockPos(1, 2, 1));
        BlockPos cell = helper.absolutePos(new BlockPos(3, 2, 1));
        floorAndWire(level, cell, 1);
        RedstoneStructureBlockEntity controller = preparedController(helper, controllerPos,
                PreviewRegion.one(cell), RedstoneStructureBlockEntity.Mode.CAPTURE);
        controller.setIoMode(IoMode.MANUAL);
        controller.setFaceEntry(Direction.EAST, BlockPos.ZERO, 9);
        controller.setManualEntry("key.keyboard.g", BlockPos.ZERO, 7, "the panel's own label");

        confirmThenApply(helper, level, controllerPos, "the region");
        StructureProductData product = controller.pending();
        helper.assertTrue(product != null, "the operation fills the slot");
        // The panel and the face table travel with a capture: a request that leaves the panel empty arrives with no keys.
        helper.assertTrue(product.manual().size() == 1,
                "the panel must travel with a capture, got " + product.manual().size() + " entr(ies)");
        helper.assertTrue(product.manual().get(0).strength() == 7
                        && product.manual().get(0).key().equals("key.keyboard.g"),
                "and it must be the panel that was set up, got " + product.manual().get(0));
        helper.assertTrue(ActivationTable.entries(product.faces(), Direction.EAST).size() == 1,
                "and so must the face table");
        helper.succeed();
    }

    @GameTest(template = "redstonecircuit:empty")
    public void aReleasePreviewCountsWhatIsInTheWayAndOnlyTheSecondOperationWrites(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos controllerPos = helper.absolutePos(new BlockPos(1, 2, 1));
        BlockPos source = helper.absolutePos(new BlockPos(8, 2, 1));
        level.setBlock(source.below(), Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(source, Blocks.REDSTONE_LAMP.defaultBlockState(), 3);

        // What the controller would be holding: a one-cell capture of the lamp, written to this save the way a
        // real capture writes it, so the release reads it back exactly as it would in a game.
        StructureCapture.Result captured = StructureCapture.capture(level,
                new StructureCapture.Request(source, source, IoMode.ACTIVATION_FACES, "release preview", true,
                        false, null),
                StructureCapture.Limits.of(PayloadLimits.DEFAULT));
        helper.assertTrue(captured.allowed(),
                "the test must be able to capture its own lamp: " + captured.refusal());
        StructurePayload payload = captured.captured().payload();
        try {
            StructureWorldStore.of(level.getServer(), RCConfig.structurePayloadLimits()).add(payload);
        } catch (java.io.IOException failed) {
            helper.fail("the test could not write its structure to this save: " + failed);
        }

        BlockPos target = helper.absolutePos(new BlockPos(3, 2, 1));
        level.setBlock(target.below(), Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(target, Blocks.STONE.defaultBlockState(), 3);

        // A release with no region yet: the first operation must start from the size of what would be written,
        // because a release's rectangle is the payload's, and a border of another size would be about a
        // different operation.
        BlockPos releasePos = helper.absolutePos(new BlockPos(1, 4, 1));
        level.setBlock(releasePos, RCRegistry.REDSTONE_STRUCTURE_BLOCK.defaultBlockState(), 3);
        RedstoneStructureBlockEntity controller = controllerAt(helper, releasePos);
        controller.setMode(RedstoneStructureBlockEntity.Mode.RELEASE);
        controller.setPending(StructureProductData.of(payload, RCConfig.structurePreviewBytes(),
                RCConfig.structurePreviewCells()));
        StructurePreviews.Result shown = StructurePreviews.press(level, null, releasePos);
        helper.assertTrue(shown.step() == StructurePreviews.Step.SHOWN,
                "the first operation shows, got " + shown.step() + ": " + shown.message());
        helper.assertTrue(controller.isPreviewing()
                        && controller.preview().size().equals(controller.pending().size()),
                "and a release's border is the size of what would be written");

        // Placed at the target - the offset the player adjusted - which is an edit, so the confirmation starts over.
        controller.setPreview(new PreviewRegion(target, controller.pending().size()));
        StructurePreviews.Entry border = borderOf(level, releasePos);
        helper.assertTrue(border != null && border.release(), "the border must read as a release");
        helper.assertTrue(border.overwrite() == 1,
                "and must count the one block in the way before anything happens, got " + border.overwrite());
        helper.assertTrue(level.getBlockState(target).is(Blocks.STONE), "showing changes nothing");

        StructurePreviews.Result armed = StructurePreviews.press(level, null, releasePos);
        helper.assertTrue(armed.step() == StructurePreviews.Step.ARMED,
                "confirming the release must not do it yet, got " + armed.step() + ": " + armed.message());
        helper.assertTrue(level.getBlockState(target).is(Blocks.STONE),
                "confirming a release must not write into the target either");
        helper.assertTrue(StructurePreviews.press(level, null, releasePos).step()
                == StructurePreviews.Step.APPLIED, "and the confirmed operation releases");
        helper.assertTrue(controller.pending() == null, "and 生效即消耗: the confirmed operation consumes the slot");
        helper.assertTrue(!controller.isPreviewing() && borderOf(level, releasePos) == null,
                "and the preview ends with it");
        // The write itself is spread over level ticks, so it is waited for rather than counted.
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(level.getBlockState(target).is(Blocks.REDSTONE_LAMP),
                        "the released block must arrive, and it is not there yet"))
                .thenSucceed();
    }

    @GameTest(template = "redstonecircuit:empty")
    public void breakingOrSwitchingEndsAPreviewAndNothingElseDoes(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos first = helper.absolutePos(new BlockPos(3, 2, 1));
        floorAndWire(level, first, 2);
        PreviewRegion region = PreviewRegion.of(first, first.offset(1, 0, 0));

        // Breaking the controller: the border goes with it.
        BlockPos brokenPos = helper.absolutePos(new BlockPos(1, 2, 1));
        preparedController(helper, brokenPos, region, RedstoneStructureBlockEntity.Mode.CAPTURE);
        helper.assertTrue(borderOf(level, brokenPos) != null, "the border is up before the break");
        level.destroyBlock(brokenPos, false);
        helper.assertTrue(borderOf(level, brokenPos) == null,
                "breaking the controller must end its preview - a border for a block that is gone is a lie");
        helper.assertTrue(level.getBlockState(first).is(Blocks.REDSTONE_WIRE),
                "and it must not take the region with it");

        // Switching the mode: the third way for a preview to end.
        BlockPos switchedPos = helper.absolutePos(new BlockPos(1, 4, 1));
        RedstoneStructureBlockEntity switched = preparedController(helper, switchedPos, region,
                RedstoneStructureBlockEntity.Mode.CAPTURE);
        helper.assertTrue(switched.isPreviewing(), "the border is up before the switch");
        switched.setMode(RedstoneStructureBlockEntity.Mode.RELEASE);
        helper.assertTrue(!switched.isPreviewing(),
                "switching the mode must end the preview: the border would otherwise be about the wrong job");
        helper.assertTrue(borderOf(level, switchedPos) == null, "and the border must go with it");
        helper.assertTrue(level.getBlockState(first).is(Blocks.REDSTONE_WIRE), "still nothing happened");

        // The two things that must NOT end it: a save and a load. The region is in the block entity, so it survives both.
        BlockPos reloadedPos = helper.absolutePos(new BlockPos(1, 6, 1));
        RedstoneStructureBlockEntity reloaded = preparedController(helper, reloadedPos, region,
                RedstoneStructureBlockEntity.Mode.CAPTURE);
        StructurePreviews.press(level, null, reloadedPos);        net.minecraft.nbt.CompoundTag tag = reloaded.saveWithFullMetadata(level.registryAccess());
        RedstoneStructureBlockEntity copy = new RedstoneStructureBlockEntity(reloadedPos,
                reloaded.getBlockState());
        copy.loadWithComponents(tag, level.registryAccess());
        helper.assertTrue(copy.isPreviewing() && copy.preview().equals(region),
                "a preview must survive a save and a load - closing the game is not one of the ways it ends");
        helper.assertTrue(!copy.isPreviewArmed(),
                "and it comes back unconfirmed: one operation before a restart and one after it must not add "
                        + "up to an operation the player only asked for once");
        helper.succeed();
    }
}
