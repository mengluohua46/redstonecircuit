package com.jiangyuefengyu.redstonecircuit.structure;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.RCRegistry;
import com.jiangyuefengyu.redstonecircuit.block.RedstoneStructureBlockEntity;
import com.jiangyuefengyu.redstonecircuit.network.StructureManualTrigger;
import com.jiangyuefengyu.redstonecircuit.network.StructurePreviews;
import com.jiangyuefengyu.redstonecircuit.structure.StructureProductData;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Every edit a controller can be given, in one place - the screen's road and the command's road.
 *
 * <p>The edits live here as functions that take a level position and plain numbers, so the two callers are thin:
 * the command prints what these return, and the packet handler does the same and stays silent. They return a
 * {@link Result} rather than throwing or logging, because "refused, and here is the sentence" is the normal
 * answer to a half-typed text field. The two-stage gate and the capture/release themselves are
 * {@link StructurePreviews} and {@link StructureActions}: an edit only ever describes, the presses operate.
 */
public final class StructureEdits {

    /** What an edit did, in the words a command output or a log line wants. */
    public record Result(boolean ok, String message) {

        public static Result refused(String reason) {
            return new Result(false, reason);
        }

        public static Result done(String message) {
            return new Result(true, message);
        }
    }

    /** The controller at a position, or a refusal - the first line of every edit. */
    @Nullable
    public static Result require(ServerLevel level, BlockPos pos, Call call) {
        RedstoneStructureBlockEntity controller = StructureActions.controllerAt(level, pos);
        if (controller == null) {
            return Result.refused("no redstone structure block at " + pos.toShortString());
        }
        return call.on(controller);
    }

    /** One controller-editing step, so {@link #require} can hand it the block entity it found. */
    public interface Call {
        Result on(RedstoneStructureBlockEntity controller);
    }

    // ------------------------------------------------------------------ shape --

    public static Result setMode(ServerLevel level, BlockPos pos, boolean release) {
        return require(level, pos, controller -> {
            controller.setMode(release ? RedstoneStructureBlockEntity.Mode.RELEASE
                    : RedstoneStructureBlockEntity.Mode.CAPTURE);
            return Result.done("mode is now " + (release
                    ? "release (what the controller holds is written into the world)"
                    : "capture (the region is taken and held)"));
        });
    }

    /**
     * The cutting tier: copying is creative-only, so a request for a copy that is not allowed falls back to a cut
     * rather than being refused - the player asked for the capture.
     */
    public static Result setTier(ServerLevel level, BlockPos pos, boolean copy,
                                 @Nullable ServerPlayer player) {
        boolean allowed = copy && RCConfig.structureAllowCopy() && player != null && player.isCreative();
        return require(level, pos, controller -> {
            controller.setTier(allowed ? RedstoneStructureBlockEntity.Tier.COPY
                    : RedstoneStructureBlockEntity.Tier.CUT);
            if (copy && !allowed) {
                return Result.done("tier is cut: copying needs creative mode and structureAllowCopy");
            }
            return Result.done("tier is now " + (allowed ? "copy (the region stays)"
                    : "cut (the region goes away)"));
        });
    }

    public static Result setIoMode(ServerLevel level, BlockPos pos, boolean manual) {
        return require(level, pos, controller -> {
            controller.setIoMode(manual ? IoMode.MANUAL : IoMode.ACTIVATION_FACES);
            return Result.done("io mode is now " + (manual
                    ? "manual (the panel's keys push into the plot)"
                    : "activation faces (a signal on a face pushes into the plot)"));
        });
    }

    /** The structure's name. Empty means the default, which is what the controller does with a blank name. */
    public static Result setName(ServerLevel level, BlockPos pos, String name) {
        if (name.length() > PayloadHeader.MAX_NAME_LENGTH) {
            return Result.refused("a name longer than " + PayloadHeader.MAX_NAME_LENGTH
                    + " characters is more than a description can carry");
        }
        return require(level, pos, controller -> {
            controller.setName(name);
            return Result.done("name is now " + controller.structureName());
        });
    }

    // ----------------------------------------------------------------- region --

    /**
     * The region, from the screen's relative/absolute offset fields. An edit, so it puts the confirmation back: a
     * player must look at this rectangle before it happens.
     */
    public static Result setRegion(ServerLevel level, BlockPos pos, RegionEditor fields) {
        return require(level, pos, controller -> {
            controller.setPreview(fields.resolve(controller.getBlockPos()));
            return Result.done("preview " + controller.preview().describe()
                    + " (" + (fields.relative() ? "relative to the controller" : "absolute")
                    + "); nothing has happened to the world yet");
        });
    }

    /** The region's first cell, size unchanged - the screen's "move" and the command's {@code preview move}. */
    public static Result movePreview(ServerLevel level, BlockPos pos, BlockPos min) {
        return require(level, pos, controller -> {
            PreviewRegion moved = controller.isPreviewing() ? controller.preview().movedTo(min)
                    : PreviewRegion.one(min);
            controller.setPreview(moved);
            return Result.done("preview moved to " + moved.describe()
                    + "; the confirmation starts over - operate again to go on");
        });
    }

    /** The region's size, first cell unchanged. */
    public static Result resizePreview(ServerLevel level, BlockPos pos, Vec3i size) {
        return require(level, pos, controller -> {
            PreviewRegion resized = controller.isPreviewing() ? controller.preview().resized(size)
                    : new PreviewRegion(controller.getBlockPos(), size);
            controller.setPreview(resized);
            return Result.done("preview resized to " + resized.describe()
                    + "; the confirmation starts over - operate again to go on");
        });
    }

    /**
     * Two corners, in either order - the command's {@code select}, and what a screen's corner picks would do.
     */
    public static Result selectCorners(ServerLevel level, BlockPos pos, BlockPos from, BlockPos to) {
        PreviewRegion region = PreviewRegion.of(from, to);
        return require(level, pos, controller -> {
            controller.setPreview(region);
            return Result.done("selection " + region.describe() + " (" + region.volume()
                    + " blocks); this is the preview state - nothing has happened to the world yet");
        });
    }

    /**
     * Where a release would land: the product's own size, with its first cell at {@code target} - the offset from
     * the controller that a release preview is tuned with.
     */
    public static Result target(ServerLevel level, BlockPos pos, BlockPos target) {
        return require(level, pos, controller -> {
            StructureProductData pending = controller.pending();
            if (pending == null) {
                return Result.refused("this controller is holding nothing: capture something first");
            }
            PreviewRegion region = new PreviewRegion(target, pending.size());
            controller.setPreview(region);
            return Result.done("a release would fill " + region.describe() + " (" + region.volume()
                    + " block(s), " + pending.name() + "); "
                    + StructureRelease.occupied(level, region.min(), region.size())
                    + " block(s) are in the way and would be overwritten");
        });
    }

    /** Ends a preview without anything happening. */
    public static Result cancel(ServerLevel level, BlockPos pos) {
        StructurePreviews.Result result = StructurePreviews.cancel(level, pos);
        return new Result(result.ok(), result.message());
    }

    /** One operation, on the same road a right-click takes. */
    public static Result press(ServerLevel level, @Nullable ServerPlayer player, BlockPos pos) {
        StructurePreviews.Result result = StructurePreviews.press(level, player, pos);
        return new Result(result.ok(), result.message());
    }

    /**
     * Hands the structure the block is holding to the player and empties the slot - the direction a structure
     * item puts in, taken out. It is the same stack the block would drop, so taking it out and breaking the block
     * are the same thing with different timing.
     */
    public static Result takeOut(ServerLevel level, @Nullable ServerPlayer player, BlockPos pos) {
        if (player == null) {
            return Result.refused("that needs a player to hand the structure to");
        }
        return require(level, pos, controller -> {
            StructureProductData pending = controller.pending();
            if (pending == null) {
                return Result.refused("this block is not holding a structure");
            }
            ItemStack stack = RCRegistry.structureStack(pending);
            if (!player.getInventory().add(stack)) {
                player.drop(stack, false);
            }
            controller.setPending(null);
            return Result.done("took " + pending.name() + " out of the block as a structure item");
        });
    }

    // ------------------------------------------------------------------ tables --

    /**
     * One cell of one face table. The cell arrives in <b>world</b> coordinates and is stored selection-local,
     * because a player points at a block rather than at an index.
     */
    public static Result setFaceEntry(ServerLevel level, BlockPos pos, Direction face, BlockPos cell,
                                      int strength) {
        if (face == null) {
            return Result.refused("that is not a face");
        }
        if (strength < ActivationTable.MIN_STRENGTH || strength > ActivationTable.MAX_STRENGTH) {
            return Result.refused("a strength must be between " + ActivationTable.MIN_STRENGTH + " and "
                    + ActivationTable.MAX_STRENGTH + ", got " + strength);
        }
        return require(level, pos, controller -> {
            Result inside = requireCellInside(controller, cell);
            if (inside != null) {
                return inside;
            }
            BlockPos local = cell.subtract(controller.preview().min());
            controller.setFaceEntry(face, local, strength);
            return Result.done("face " + face.getName() + " now pushes " + strength + " into "
                    + cell.toShortString() + " (local " + local.toShortString() + ") while it is energized");
        });
    }

    public static Result clearFace(ServerLevel level, BlockPos pos, Direction face) {
        if (face == null) {
            return Result.refused("that is not a face");
        }
        return require(level, pos, controller -> {
            controller.clearFace(face);
            return Result.done("face " + face.getName() + " is now empty: a signal on it does nothing");
        });
    }

    /**
     * Removes <b>one cell</b> of one face table. The entry is identified by (face, cell) rather than by its index,
     * which other edits may have reordered.
     */
    public static Result clearFaceEntry(ServerLevel level, BlockPos pos, Direction face, BlockPos cell) {
        if (face == null) {
            return Result.refused("that is not a face");
        }
        return require(level, pos, controller -> {
            Result inside = requireCellInside(controller, cell);
            if (inside != null) {
                return inside;
            }
            BlockPos local = cell.subtract(controller.preview().min());
            boolean present = ActivationTable.entries(controller.faces(), face).stream()
                    .anyMatch(entry -> entry.localPos().equals(local));
            if (!present) {
                return Result.refused("face " + face.getName() + " has no entry for " + cell.toShortString());
            }
            controller.clearFaceEntry(face, local);
            return Result.done("face " + face.getName() + " no longer pushes into " + cell.toShortString());
        });
    }

    /**
     * One entry of the manual panel. Its identity is <b>the key and the cell</b> together: one key may drive two
     * cells and one cell may be driven by two keys, so neither alone can be the identity.
     */
    public static Result setManualEntry(ServerLevel level, BlockPos pos, String key, BlockPos cell, int strength,
                                        String label) {
        if (key.isEmpty()) {
            return Result.refused("a button needs a name: it is what the panel shows and what identifies it");
        }
        if (key.length() > ActivationTable.MAX_KEY_LENGTH || label.length() > ActivationTable.MAX_LABEL_LENGTH) {
            return Result.refused("a name or label longer than " + ActivationTable.MAX_KEY_LENGTH + " characters "
                    + "is not something a button can show");
        }
        if (strength < ActivationTable.MIN_STRENGTH || strength > ActivationTable.MAX_STRENGTH) {
            return Result.refused("a strength must be between " + ActivationTable.MIN_STRENGTH + " and "
                    + ActivationTable.MAX_STRENGTH + ", got " + strength);
        }
        return require(level, pos, controller -> {
            Result inside = requireCellInside(controller, cell);
            if (inside != null) {
                return inside;
            }
            BlockPos local = cell.subtract(controller.preview().min());
            boolean replacing = controller.manual().stream().anyMatch(entry -> entry.key().equals(key)
                    && entry.localPos().equals(local));
            if (!replacing && controller.manual().size() >= RCConfig.structureManualEntryLimit()) {
                return Result.refused("the panel is full: " + controller.manual().size() + " entr(ies), the limit "
                        + "is " + RCConfig.structureManualEntryLimit() + " (structureManualEntryLimit)");
            }
            controller.setManualEntry(key, local, strength, label);
            return Result.done((replacing ? "replaced" : "added") + " entry " + key + " -> "
                    + cell.toShortString() + " (local " + local.toShortString() + ") pushing " + strength);
        });
    }

    /** Removes one panel entry by the index a client presses. */
    public static Result clearManualEntry(ServerLevel level, BlockPos pos, int index) {
        return require(level, pos, controller -> {
            List<ActivationTable.ManualEntry> manual = controller.manual();
            if (index < 0 || index >= manual.size()) {
                return Result.refused("this panel has " + manual.size() + " entr(ies), so there is no entry "
                        + index);
            }
            ActivationTable.ManualEntry removed = manual.get(index);
            controller.clearManualEntry(removed.key(), removed.localPos());
            return Result.done("removed entry " + index + " (" + removed.key() + " -> "
                    + removed.localPos().toShortString() + ")");
        });
    }

    /**
     * One panel entry replaced whole, by the index a client presses - the row's "update" button.
     *
     * <p>An entry's identity is its {@code (key, cell)} pair, so a rename is a <b>different</b> entry: a remove
     * followed by an add is two edits, and between them the row is gone - a refused second half would lose it. This is
     * one edit, and it is by index because that is what the grid of buttons shows, so the row keeps its place.
     */
    public static Result replaceManualEntry(ServerLevel level, BlockPos pos, int index, String name, BlockPos cell,
                                            int strength) {
        if (name.isEmpty()) {
            return Result.refused("a button needs a name: it is what the panel shows and what identifies it");
        }
        if (name.length() > ActivationTable.MAX_KEY_LENGTH) {
            return Result.refused("a name longer than " + ActivationTable.MAX_KEY_LENGTH + " characters is not "
                    + "something a button can show");
        }
        if (strength < ActivationTable.MIN_STRENGTH || strength > ActivationTable.MAX_STRENGTH) {
            return Result.refused("a strength must be between " + ActivationTable.MIN_STRENGTH + " and "
                    + ActivationTable.MAX_STRENGTH + ", got " + strength);
        }
        return require(level, pos, controller -> {
            Result inside = requireCellInside(controller, cell);
            if (inside != null) {
                return inside;
            }
            List<ActivationTable.ManualEntry> manual = controller.manual();
            BlockPos local = cell.subtract(controller.preview().min());
            ActivationTable.ManualEntry replacement = new ActivationTable.ManualEntry(name, local, strength, name);
            if (!controller.replaceManual(index, replacement)) {
                return Result.refused("this panel has " + manual.size() + " entr(ies), so there is no entry "
                        + index);
            }
            return Result.done("replaced entry " + index + ": " + manual.get(index).key() + " -> " + name
                    + " (local " + local.toShortString() + ") pushing " + strength);
        });
    }

    public static Result clearManual(ServerLevel level, BlockPos pos) {
        return require(level, pos, controller -> {
            controller.clearManual();
            return Result.done("the panel is empty: this structure has no keys");
        });
    }

    /**
     * One row of the output table: the cell arrives in <b>world</b> coordinates and is stored selection-local,
     * like the other tables' cells. A row means "while that cell carries a signal, this face answers with this
     * strength", and several rows on one face add up.
     */
    public static Result setOutputEntry(ServerLevel level, BlockPos pos, BlockPos cell, Direction face,
                                        int strength) {
        if (face == null) {
            return Result.refused("an output row has to name the face it answers on");
        }
        if (strength < ActivationTable.MIN_STRENGTH || strength > ActivationTable.MAX_STRENGTH) {
            return Result.refused("a strength must be between " + ActivationTable.MIN_STRENGTH + " and "
                    + ActivationTable.MAX_STRENGTH + ", got " + strength);
        }
        return require(level, pos, controller -> {
            Result inside = requireCellInside(controller, cell);
            if (inside != null) {
                return inside;
            }
            BlockPos local = cell.subtract(controller.preview().min());
            boolean replacing = controller.output().stream().anyMatch(entry -> entry.localPos().equals(local)
                    && entry.face() == face);
            controller.setOutputEntry(local, face, strength);
            return Result.done((replacing ? "replaced" : "added") + " output row: while "
                    + cell.toShortString() + " (local " + local.toShortString() + ") carries a signal, "
                    + face.getName() + " answers " + strength);
        });
    }

    /** Removes one output row by the pair that identifies it: the cell and the face. */
    public static Result clearOutputEntry(ServerLevel level, BlockPos pos, BlockPos cell, Direction face) {
        if (face == null) {
            return Result.refused("an output row has to name the face it answers on");
        }
        return require(level, pos, controller -> {
            Result inside = requireCellInside(controller, cell);
            if (inside != null) {
                return inside;
            }
            BlockPos local = cell.subtract(controller.preview().min());
            boolean present = controller.output().stream().anyMatch(entry -> entry.localPos().equals(local)
                    && entry.face() == face);
            if (!present) {
                return Result.refused("no output row watches " + cell.toShortString() + " on "
                        + face.getName());
            }
            controller.clearOutputEntry(local, face);
            return Result.done("removed the output row for " + cell.toShortString() + " on " + face.getName());
        });
    }

    /** Empties the output table: no face answers with anything. */
    public static Result clearOutput(ServerLevel level, BlockPos pos) {
        return require(level, pos, controller -> {
            controller.clearOutput();
            return Result.done("the output table is empty: this structure answers with nothing");
        });
    }

    /**
     * Presses one entry without a keyboard. It goes through {@link StructureManualTrigger}, the function the key
     * packet uses, so the distance rule and the cooldown are the ones a player meets.
     */
    public static Result pressManual(ServerLevel level, @Nullable ServerPlayer player, BlockPos pos, int entry) {
        java.util.UUID id = player == null ? NIL_PLAYER : player.getUUID();
        net.minecraft.world.phys.Vec3 eye = player == null ? net.minecraft.world.phys.Vec3.atCenterOf(pos)
                : player.getEyePosition();
        StructureManualTrigger.Result result = StructureManualTrigger.press(level, id, eye, pos, entry);
        if (!result.accepted()) {
            return Result.refused("refused: " + result.refusal());
        }
        return Result.done("pressed entry " + entry + " of the structure at " + pos.toShortString() + "; "
                + result.cells() + " pulse(s) are running");
    }

    /** The identity a press made from the console or a screen-less caller is rate limited under. */
    public static final java.util.UUID NIL_PLAYER = new java.util.UUID(0L, 0L);

    /**
     * A cell has to be inside the region being previewed: the region is what makes an entry meaningful, since a
     * local coordinate means nothing without a selection.
     */
    @Nullable
    private static Result requireCellInside(RedstoneStructureBlockEntity controller, BlockPos cell) {
        if (!controller.isPreviewing()) {
            return Result.refused("no region selected: a cell is named inside the region, so pick one first "
                    + "(/rc structure select, or the screen)");
        }
        StructureCapture.Box box = controller.preview().box();
        if (!box.contains(cell)) {
            return Result.refused(cell.toShortString() + " is outside the selection "
                    + box.min().toShortString() + ".." + box.max().toShortString());
        }
        return null;
    }

    private StructureEdits() {
    }
}
