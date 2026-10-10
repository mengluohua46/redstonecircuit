package com.jiangyuefengyu.redstonecircuit.network;

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.RCRegistry;
import com.jiangyuefengyu.redstonecircuit.RedstoneCircuit;
import com.jiangyuefengyu.redstonecircuit.block.RedstoneStructureBlockEntity;
import com.jiangyuefengyu.redstonecircuit.structure.ActivationTable;
import com.jiangyuefengyu.redstonecircuit.structure.PreviewRegion;
import com.jiangyuefengyu.redstonecircuit.structure.RegionEditor;
import com.jiangyuefengyu.redstonecircuit.structure.StructureEdits;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

/**
 * <b>Every edit a screen can send, through the packet's own dispatch.</b>
 *
 * <p>The screen itself needs a window, a mouse and a font, so what is driven here is everything behind its buttons:
 * each test builds a {@code StructureEditPayload} the way the screen does, hands it to the same
 * {@code apply(level, player, edit)} the packet handler calls, and asserts on the controller - so the chain
 * "button → payload → verb → edit → controller state → border on the other side" is covered end to end. It covers the
 * region fields (whose relative reading is resolved on the client and arrives as a rectangle) and the tables (a face
 * entry, a panel entry, removing one, pressing one), and the refusals: a cell outside the region and a strength out of
 * range both have to come back refused with the controller untouched.
 */
public final class StructureEditPathGameTest implements FabricGameTest {

    private static final BlockPos CONTROLLER = new BlockPos(1, 2, 1);

    private static RedstoneStructureBlockEntity placeController(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(CONTROLLER);
        level.setBlock(pos, RCRegistry.REDSTONE_STRUCTURE_BLOCK.defaultBlockState(), 3);
        level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), 3);
        if (level.getBlockEntity(pos) instanceof RedstoneStructureBlockEntity controller) {
            return controller;
        }
        helper.fail("the placed controller must have a block entity");
        throw new IllegalStateException("unreachable");
    }

    /** A floor with a row of wire on it, for the two cells a capture test needs. */
    private static void floorAndWire(ServerLevel level, BlockPos first, int length) {
        for (int x = 0; x < length; x++) {
            level.setBlock(first.offset(x, -1, 0), Blocks.STONE.defaultBlockState(), 3);
            level.setBlock(first.offset(x, 0, 0), Blocks.REDSTONE_WIRE.defaultBlockState(), 3);
        }
    }

    private static StructureEdits.Result send(ServerLevel level, BlockPos controller,
                                              RCNetwork.StructureEditPayload edit) {
        return RCNetwork.StructureEditPayload.apply(level, null, edit);
    }

    @GameTest(template = "redstonecircuit:empty")
    public void theRegionTheScreenTypedIsTheRegionThatIsShownAndTaken(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        RedstoneStructureBlockEntity controller = placeController(helper);
        BlockPos pos = controller.getBlockPos();
        BlockPos first = helper.absolutePos(new BlockPos(3, 2, 1));
        floorAndWire(level, first, 2);

        // The screen's fields, relative to the controller - the reading vanilla's structure block starts in.
        RegionEditor fields = RegionEditor.relative(new Vec3i(2, 0, 0), new Vec3i(2, 1, 1));
        PreviewRegion wanted = fields.resolve(pos);
        helper.assertTrue(wanted.min().equals(first),
                "the relative reading must land on the wire row, got " + wanted.min().toShortString());

        helper.assertTrue(send(level, pos, RCNetwork.StructureEditPayload.region(pos, wanted.min(),
                wanted.size())).ok(), "the screen's region edit must be accepted");
        helper.assertTrue(controller.preview() != null && controller.preview().equals(wanted),
                "and the controller must now be showing exactly that rectangle, got " + controller.preview());
        helper.assertTrue(!controller.isPreviewArmed(),
                "an edit must put the confirmation back - see RedstoneStructureBlockEntity#setPreview");

        // What the border says is what a client draws, and it is the same rectangle.
        StructurePreviews.Entry border = StructurePreviews.snapshot(level).stream()
                .filter(entry -> entry.controller().equals(pos)).findFirst().orElse(null);
        helper.assertTrue(border != null && border.region().equals(wanted),
                "the published border must be the rectangle that was typed, got " + border);

        // And the two operations then take that rectangle, not another one.
        helper.assertTrue(send(level, pos, RCNetwork.StructureEditPayload.of(pos,
                RCNetwork.StructureEditPayload.Verb.PRESS)).ok(), "the screen's operation button confirms");
        helper.assertTrue(send(level, pos, RCNetwork.StructureEditPayload.of(pos,
                RCNetwork.StructureEditPayload.Verb.PRESS)).ok(), "and the second press does it");
        helper.assertTrue(level.getBlockState(first).isAir() && level.getBlockState(first.offset(1, 0, 0)).isAir(),
                "the typed rectangle is the one that was taken");
        helper.assertTrue(controller.pending() != null
                        && controller.pending().size().equals(new Vec3i(2, 1, 1)),
                "and the slot holds exactly its size");
        helper.succeed();
    }

    @GameTest(template = "redstonecircuit:empty")
    public void theScreensOtherEditsReachTheController(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        RedstoneStructureBlockEntity controller = placeController(helper);
        BlockPos pos = controller.getBlockPos();

        helper.assertTrue(send(level, pos, RCNetwork.StructureEditPayload.flag(pos,
                RCNetwork.StructureEditPayload.Verb.SET_MODE, true)).ok(), "the mode button");
        helper.assertTrue(controller.mode() == RedstoneStructureBlockEntity.Mode.RELEASE,
                "mode must be release, got " + controller.mode());

        helper.assertTrue(send(level, pos, RCNetwork.StructureEditPayload.flag(pos,
                RCNetwork.StructureEditPayload.Verb.SET_IO, true)).ok(), "the io button");
        helper.assertTrue(controller.ioMode() == com.jiangyuefengyu.redstonecircuit.structure.IoMode.MANUAL,
                "io mode must be manual, got " + controller.ioMode());

        helper.assertTrue(send(level, pos, RCNetwork.StructureEditPayload.text(pos,
                RCNetwork.StructureEditPayload.Verb.SET_NAME, "the door machine")).ok(), "the name box");
        helper.assertTrue(controller.structureName().equals("the door machine"),
                "the name must be the one that was typed, got " + controller.structureName());

        // A survival player asking for a copy gets a cut, exactly as the command gives them - not a refusal and
        // not a copy.
        helper.assertTrue(send(level, pos, RCNetwork.StructureEditPayload.flag(pos,
                RCNetwork.StructureEditPayload.Verb.SET_TIER, true)).ok(), "the tier button");
        helper.assertTrue(controller.tier() == RedstoneStructureBlockEntity.Tier.CUT,
                "copying without creative mode must fall back to a cut, got " + controller.tier());

        // Moving and resizing travel as <b>world</b> values, like the command's own verbs: the screen's relative
        // fields are resolved into a rectangle before they are sent (see SET_REGION above), so there is one reading
        // of "where" on the wire and it is the one the server can check against the world.
        helper.assertTrue(send(level, pos, RCNetwork.StructureEditPayload.numbers(pos,
                RCNetwork.StructureEditPayload.Verb.MOVE_PREVIEW, pos.getX(), pos.getY(), pos.getZ())).ok(),
                "the move box");
        helper.assertTrue(send(level, pos, RCNetwork.StructureEditPayload.numbers(pos,
                RCNetwork.StructureEditPayload.Verb.RESIZE_PREVIEW, 2, 1, 1)).ok(), "the size box");
        helper.assertTrue(controller.preview().equals(new PreviewRegion(pos, new Vec3i(2, 1, 1))),
                "moving and resizing must give one rectangle, got " + controller.preview());
        helper.assertTrue(send(level, pos, RCNetwork.StructureEditPayload.of(pos,
                RCNetwork.StructureEditPayload.Verb.CANCEL)).ok(), "the cancel button");
        helper.assertTrue(!controller.isPreviewing(), "cancel must take the preview down");
        helper.succeed();
    }

    @GameTest(template = "redstonecircuit:empty")
    public void aPanelEntryGoesOnAndComesOffAndCanBePressed(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        RedstoneStructureBlockEntity controller = placeController(helper);
        BlockPos pos = controller.getBlockPos();
        BlockPos first = helper.absolutePos(new BlockPos(3, 2, 1));
        floorAndWire(level, first, 2);
        helper.assertTrue(send(level, pos, RCNetwork.StructureEditPayload.region(pos, first,
                new Vec3i(2, 1, 1))).ok(), "a region first: an entry names a cell inside it");

        // A face entry and a panel entry, both typed as world coordinates on the screen.
        helper.assertTrue(send(level, pos, RCNetwork.StructureEditPayload.face(pos, Direction.EAST, first, 9))
                .ok(), "the face table's add");
        helper.assertTrue(ActivationTable.entries(controller.faces(), Direction.EAST).size() == 1,
                "the cell must land on the east face");
        helper.assertTrue(ActivationTable.entries(controller.faces(), Direction.EAST).get(0).localPos()
                .equals(BlockPos.ZERO), "and must be stored selection-local, which is where it is");

        helper.assertTrue(send(level, pos, RCNetwork.StructureEditPayload.manual(pos, "key.keyboard.g", first, 7,
                "the door")).ok(), "the panel's add");
        helper.assertTrue(controller.manual().size() == 1
                        && controller.manual().get(0).strength() == 7
                        && controller.manual().get(0).key().equals("key.keyboard.g")
                        && controller.manual().get(0).label().equals("the door"),
                "the panel must hold exactly what was typed, got " + controller.manual());

        // The screen's row button: the same press the key sends, on the same function, so the distance rule and
        // the cooldown are the ones a key meets. Nothing is pocketing here, so the press is refused with a
        // sentence rather than doing something - which is the honest answer for a plot-less structure.
        StructureEdits.Result pressed = send(level, pos, RCNetwork.StructureEditPayload.entry(pos,
                RCNetwork.StructureEditPayload.Verb.PRESS_MANUAL, 0));
        helper.assertTrue(!pressed.message().isEmpty(), "a press must always answer with a sentence");

        // Removing by index, then the clear button.
        helper.assertTrue(send(level, pos, RCNetwork.StructureEditPayload.entry(pos,
                RCNetwork.StructureEditPayload.Verb.REMOVE_MANUAL, 0)).ok(), "removing entry 0");
        helper.assertTrue(controller.manual().isEmpty(), "the panel must be empty again");
        helper.assertTrue(!send(level, pos, RCNetwork.StructureEditPayload.entry(pos,
                RCNetwork.StructureEditPayload.Verb.REMOVE_MANUAL, 0)).ok(),
                "removing an entry that is not there must be refused, not ignored");
        helper.assertTrue(send(level, pos, RCNetwork.StructureEditPayload.clearFace(pos, Direction.EAST)).ok(),
                "the face table's clear");
        helper.assertTrue(ActivationTable.entries(controller.faces(), Direction.EAST).isEmpty(),
                "and the face is empty");
        helper.succeed();
    }

    /**
     * <b>The output page's road</b>: the same three refusals and the same pair-of-identities rule the other two tables
     * have, driven the way the screen drives them. This test names its verbs one by one rather than walking
     * {@code Verb.values()}, so a new verb is <b>not</b> covered by "it is handled in the same switch": this is the page
     * that writes rows through the payload, and the command has its own road.
     */
    @GameTest(template = "redstonecircuit:empty")
    public void theOutputPageWritesRemovesAndRefusesRows(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        RedstoneStructureBlockEntity controller = placeController(helper);
        BlockPos pos = controller.getBlockPos();
        BlockPos first = helper.absolutePos(new BlockPos(3, 2, 1));
        floorAndWire(level, first, 2);
        helper.assertTrue(send(level, pos, RCNetwork.StructureEditPayload.region(pos, first,
                new Vec3i(2, 1, 2))).ok(), "a region to name cells inside");

        // The page's first row: the cell it watches, the face it drives, the strength that face answers with.
        helper.assertTrue(send(level, pos, RCNetwork.StructureEditPayload.output(pos, Direction.EAST, first, 9))
                .ok(), "the output page's add");
        helper.assertTrue(controller.output().size() == 1, "one row must be written");
        helper.assertTrue(controller.output().get(0).localPos().equals(BlockPos.ZERO),
                "and the cell must be stored selection-local, like the other two tables' cells");
        helper.assertTrue(controller.output().get(0).face() == Direction.EAST
                        && controller.output().get(0).strength() == 9,
                "with the face and the strength that were typed, got " + controller.output().get(0));

        // The same cell on the same face again is the same row, not a second one: (cell, face) is the identity.
        helper.assertTrue(send(level, pos, RCNetwork.StructureEditPayload.output(pos, Direction.EAST, first, 12))
                .ok(), "setting the same cell on the same face again");
        helper.assertTrue(controller.output().size() == 1 && controller.output().get(0).strength() == 12,
                "must replace the row rather than add one, got " + controller.output());

        // One cell may watch two faces, and a strength of 0 is a legal row that answers nothing.
        helper.assertTrue(send(level, pos, RCNetwork.StructureEditPayload.output(pos, Direction.UP, first, 5))
                .ok(), "the same cell on another face");
        helper.assertTrue(send(level, pos, RCNetwork.StructureEditPayload.output(pos, Direction.UP,
                first.offset(1, 0, 0), 0)).ok(), "and a row whose strength is 0");
        helper.assertTrue(controller.output().size() == 3,
                "three rows, in the order they were added, got " + controller.output());
        helper.assertTrue(controller.output().get(2).strength() == 0,
                "with the 0 kept as the row it was typed as");

        // The row's own button: the pair that identifies the row, removed.
        helper.assertTrue(send(level, pos, RCNetwork.StructureEditPayload.removeOutput(pos, Direction.EAST, first))
                .ok(), "the output page's per-row remove");
        helper.assertTrue(controller.output().size() == 2
                        && controller.output().stream().noneMatch(row -> row.face() == Direction.EAST),
                "the east row must be gone and the others untouched, got " + controller.output());
        helper.assertTrue(!send(level, pos, RCNetwork.StructureEditPayload.removeOutput(pos, Direction.EAST, first))
                .ok(), "removing a row that is not there must be refused, not ignored");
        helper.assertTrue(controller.output().size() == 2, "and nothing may be written for it");

        // And the three refusals the other two tables have: a cell outside the selection, a strength the game cannot
        // carry, and a face ordinal that only a hand-written packet could send.
        helper.assertTrue(!send(level, pos, RCNetwork.StructureEditPayload.output(pos, Direction.EAST,
                helper.absolutePos(new BlockPos(9, 2, 1)), 9)).ok(),
                "a cell outside the region must be refused");
        helper.assertTrue(!send(level, pos, RCNetwork.StructureEditPayload.output(pos, Direction.EAST, first, 99))
                .ok(), "a strength of 99 must be refused");
        helper.assertTrue(!send(level, pos, new RCNetwork.StructureEditPayload(pos,
                RCNetwork.StructureEditPayload.Verb.SET_OUTPUT, 99, first.getX(), first.getY(), first.getZ(), 9, 0,
                "", "")).ok(), "a face that does not exist must be refused");
        helper.assertTrue(controller.output().size() == 2, "and none of the refusals may write anything");
        helper.succeed();
    }

    @GameTest(template = "redstonecircuit:empty")
    public void impossibleEditsAreRefusedAndChangeNothing(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        RedstoneStructureBlockEntity controller = placeController(helper);
        BlockPos pos = controller.getBlockPos();
        BlockPos first = helper.absolutePos(new BlockPos(3, 2, 1));
        floorAndWire(level, first, 1);
        helper.assertTrue(send(level, pos, RCNetwork.StructureEditPayload.region(pos, first,
                new Vec3i(1, 1, 1))).ok(), "a one-cell region");

        // A cell outside the region has no meaning: an entry names a cell inside the selection.
        StructureEdits.Result outside = send(level, pos,
                RCNetwork.StructureEditPayload.manual(pos, "key.keyboard.g",
                        helper.absolutePos(new BlockPos(9, 2, 1)), 7, ""));
        helper.assertTrue(!outside.ok(), "a cell outside the region must be refused");
        helper.assertTrue(controller.manual().isEmpty(), "and nothing may be written");

        // A strength the game cannot carry.
        StructureEdits.Result tooStrong = send(level, pos,
                RCNetwork.StructureEditPayload.face(pos, Direction.EAST, first, 99));
        helper.assertTrue(!tooStrong.ok(), "a strength of 99 must be refused");
        helper.assertTrue(controller.faces().isEmpty(), "and nothing may be written for it either");

        // A face ordinal that does not exist, which is what a hand-written packet would send.
        StructureEdits.Result noFace = send(level, pos, new RCNetwork.StructureEditPayload(pos,
                RCNetwork.StructureEditPayload.Verb.SET_FACE, 99, 0, 0, 0, 9, 0, "", ""));
        helper.assertTrue(!noFace.ok(), "a face that does not exist must be refused");
        helper.assertTrue(controller.faces().isEmpty(), "and still nothing may be written");

        // A region further than the budget allows: refused at the edit, before anything is confirmed.
        StructureEdits.Result tooBig = send(level, pos, RCNetwork.StructureEditPayload.region(pos, first,
                new Vec3i(RCConfig.structureMaxSize() + 1, 1, 1)));
        helper.assertTrue(tooBig.ok(), "the region itself may be set - the budget is answered at the operation");
        helper.assertTrue(!send(level, pos, RCNetwork.StructureEditPayload.of(pos,
                RCNetwork.StructureEditPayload.Verb.PRESS)).ok(),
                "but an operation on a region that cannot be taken must be refused");
        helper.assertTrue(controller.isPreviewing(), "and the preview stays up so the player can fix it");
        helper.succeed();
    }

    @GameTest(template = "redstonecircuit:empty")
    public void aReleasePlacedByTheScreensFieldsIsTheRectangleThatIsWritten(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        RedstoneStructureBlockEntity controller = placeController(helper);
        BlockPos pos = controller.getBlockPos();
        BlockPos source = helper.absolutePos(new BlockPos(8, 2, 1));
        level.setBlock(source.below(), Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(source, Blocks.REDSTONE_LAMP.defaultBlockState(), 3);
        BlockPos target = helper.absolutePos(new BlockPos(3, 2, 1));
        level.setBlock(target.below(), Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(target, Blocks.STONE.defaultBlockState(), 3);

        com.jiangyuefengyu.redstonecircuit.structure.StructureCapture.Result captured =
                com.jiangyuefengyu.redstonecircuit.structure.StructureCapture.capture(level,
                        new com.jiangyuefengyu.redstonecircuit.structure.StructureCapture.Request(source, source,
                                com.jiangyuefengyu.redstonecircuit.structure.IoMode.ACTIVATION_FACES,
                                "screen release", true, false, null),
                        com.jiangyuefengyu.redstonecircuit.structure.StructureCapture.Limits
                                .of(com.jiangyuefengyu.redstonecircuit.structure.PayloadLimits.DEFAULT));
        helper.assertTrue(captured.allowed(), "the test must be able to capture its own lamp: "
                + captured.refusal());
        try {
            com.jiangyuefengyu.redstonecircuit.structure.StructureWorldStore
                    .of(level.getServer(), RCConfig.structurePayloadLimits())
                    .add(captured.captured().payload());
        } catch (java.io.IOException failed) {
            helper.fail("the test could not write its structure to this save: " + failed);
        }
        controller.setPending(com.jiangyuefengyu.redstonecircuit.structure.StructureProductData
                .of(captured.captured().payload(), RCConfig.structurePreviewBytes()));

        // The screen's release half: mode, then the target box, then the two operations.
        helper.assertTrue(send(level, pos, RCNetwork.StructureEditPayload.flag(pos,
                RCNetwork.StructureEditPayload.Verb.SET_MODE, true)).ok(), "the mode button");
        StructureEdits.Result placed = send(level, pos,
                RCNetwork.StructureEditPayload.numbers(pos, RCNetwork.StructureEditPayload.Verb.TARGET,
                        target.getX(), target.getY(), target.getZ()));
        helper.assertTrue(placed.ok(), "the target box: " + placed.message());
        helper.assertTrue(controller.preview().size().equals(controller.pending().size()),
                "a release's border must be the size of what would be written, got " + controller.preview());
        StructurePreviews.Entry border = StructurePreviews.snapshot(level).stream()
                .filter(entry -> entry.controller().equals(pos)).findFirst().orElse(null);
        helper.assertTrue(border != null && border.release() && border.overwrite() == 1,
                "the border must count the one block in the way, got " + border);

        helper.assertTrue(send(level, pos, RCNetwork.StructureEditPayload.of(pos,
                RCNetwork.StructureEditPayload.Verb.PRESS)).ok(), "the operation button confirms");
        helper.assertTrue(level.getBlockState(target).is(Blocks.STONE),
                "confirming must not write into the target");
        helper.assertTrue(send(level, pos, RCNetwork.StructureEditPayload.of(pos,
                RCNetwork.StructureEditPayload.Verb.PRESS)).ok(), "and the second press releases");
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(level.getBlockState(target).is(Blocks.REDSTONE_LAMP),
                        "the released block must arrive, and it is not there yet"))
                .thenSucceed();
    }
}
