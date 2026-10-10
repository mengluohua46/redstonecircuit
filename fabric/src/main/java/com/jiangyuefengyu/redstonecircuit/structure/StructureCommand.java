package com.jiangyuefengyu.redstonecircuit.structure;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;

import org.jetbrains.annotations.Nullable;

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.RCRegistry;
import com.jiangyuefengyu.redstonecircuit.block.RedstoneStructureBlockEntity;
import com.jiangyuefengyu.redstonecircuit.block.StructureBlockEntity;
import com.jiangyuefengyu.redstonecircuit.network.StructurePreviews;
import com.jiangyuefengyu.redstonecircuit.structure.pocket.PocketMaterializer;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

/**
 * {@code /rc structure} - the controller's operations, and the file side of a structure.
 *
 * <p>Two ways in, the block's own right-click and this command, and both go through {@code StructurePreviews.press},
 * so the command cannot skip the confirmation: {@code /rc structure press} twice is what a capture is. Everything
 * else edits the region ({@code select}, {@code preview move|size}, {@code target}) or reports a controller fact
 * ({@code mode}, {@code tier}, {@code face}, {@code manual}, {@code info}), and every edit returns the preview to
 * the unconfirmed state, so the confirmed state is never reached from a rectangle nobody has looked at since it
 * changed. It works through the controller's own block entity, because the region is that entity's state in the
 * real flow; the file verbs ({@code list}, {@code give}, {@code export}, {@code import}) sit outside that flow.
 */
public final class StructureCommand {

    public static void attach(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("structure")
                .then(Commands.literal("select")
                        .then(Commands.argument("controller", BlockPosArgument.blockPos())
                                .then(Commands.argument("from", BlockPosArgument.blockPos())
                                        .then(Commands.argument("to", BlockPosArgument.blockPos())
                                                .executes(StructureCommand::select)))))
                .then(Commands.literal("tier")
                        .then(Commands.argument("controller", BlockPosArgument.blockPos())
                                .then(Commands.literal("cut").executes(ctx -> tier(ctx, false)))
                                .then(Commands.literal("copy").executes(ctx -> tier(ctx, true)))))
                .then(Commands.literal("face")
                        .then(Commands.argument("controller", BlockPosArgument.blockPos())
                                .then(Commands.literal("list").executes(StructureCommand::listFaces))
                                .then(Commands.literal("clear")
                                        .then(Commands.argument("face", StringArgumentType.word())
                                                .suggests(StructureCommand::suggestFaces)
                                                .executes(StructureCommand::clearFace)))
                                .then(Commands.literal("set")
                                        .then(Commands.argument("face", StringArgumentType.word())
                                                .suggests(StructureCommand::suggestFaces)
                                                .then(Commands.argument("cell", BlockPosArgument.blockPos())
                                                        .then(Commands.argument("strength",
                                                                        IntegerArgumentType.integer(
                                                                                ActivationTable.MIN_STRENGTH,
                                                                                ActivationTable.MAX_STRENGTH))
                                                                .executes(StructureCommand::setFaceEntry)))))))
                .then(Commands.literal("output")
                        .then(Commands.argument("controller", BlockPosArgument.blockPos())
                                .then(Commands.literal("list").executes(StructureCommand::listOutput))
                                .then(Commands.literal("clear").executes(StructureCommand::clearOutput))
                                .then(Commands.literal("remove")
                                        .then(Commands.argument("cell", BlockPosArgument.blockPos())
                                                .then(Commands.argument("face", StringArgumentType.word())
                                                        .suggests(StructureCommand::suggestFaces)
                                                        .executes(StructureCommand::removeOutput))))
                                .then(Commands.literal("add")
                                        .then(Commands.argument("cell", BlockPosArgument.blockPos())
                                                .then(Commands.argument("face", StringArgumentType.word())
                                                        .suggests(StructureCommand::suggestFaces)
                                                        .then(Commands.argument("strength",
                                                                        IntegerArgumentType.integer(
                                                                                ActivationTable.MIN_STRENGTH,
                                                                                ActivationTable.MAX_STRENGTH))
                                                                .executes(StructureCommand::addOutput)))))))
                .then(Commands.literal("manual")
                        .then(Commands.literal("press")
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .then(Commands.argument("entry", IntegerArgumentType.integer(0, 128))
                                                .executes(StructureCommand::pressManual))))
                        .then(Commands.argument("controller", BlockPosArgument.blockPos())
                                .then(Commands.literal("list").executes(StructureCommand::listManual))
                                .then(Commands.literal("clear").executes(StructureCommand::clearManual))
                                .then(Commands.literal("remove")
                                        .then(Commands.argument("entry", IntegerArgumentType.integer(0, 512))
                                                .executes(StructureCommand::removeManual)))
                                .then(Commands.literal("add")
                                        .then(Commands.argument("key", StringArgumentType.word())
                                                .then(Commands.argument("cell", BlockPosArgument.blockPos())
                                                        .then(Commands.argument("strength",
                                                                        IntegerArgumentType.integer(
                                                                                ActivationTable.MIN_STRENGTH,
                                                                                ActivationTable.MAX_STRENGTH))
                                                                .executes(ctx -> addManual(ctx, ""))
                                                                .then(Commands.argument("label",
                                                                                StringArgumentType.greedyString())
                                                                        .executes(ctx -> addManual(ctx,
                                                                                StringArgumentType.getString(
                                                                                        ctx, "label"))))))))))
                .then(Commands.literal("info")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(StructureCommand::info)))
                .then(Commands.literal("mode")
                        .then(Commands.argument("controller", BlockPosArgument.blockPos())
                                .then(Commands.literal("capture").executes(ctx -> mode(ctx, false)))
                                .then(Commands.literal("release").executes(ctx -> mode(ctx, true)))))
                .then(Commands.literal("io")
                        .then(Commands.argument("controller", BlockPosArgument.blockPos())
                                .then(Commands.literal("faces").executes(ctx -> io(ctx, false)))
                                .then(Commands.literal("manual").executes(ctx -> io(ctx, true)))))
                .then(Commands.literal("name")
                        .then(Commands.argument("controller", BlockPosArgument.blockPos())
                                .then(Commands.argument("name", StringArgumentType.greedyString())
                                        .executes(StructureCommand::name))))
                .then(Commands.literal("press")
                        .then(Commands.argument("controller", BlockPosArgument.blockPos())
                                .executes(StructureCommand::press)))
                .then(Commands.literal("target")
                        .then(Commands.argument("controller", BlockPosArgument.blockPos())
                                .then(Commands.argument("target", BlockPosArgument.blockPos())
                                        .executes(StructureCommand::target))))
                .then(Commands.literal("preview")
                        .then(Commands.argument("controller", BlockPosArgument.blockPos())
                                .then(Commands.literal("end").executes(StructureCommand::previewEnd))
                                .then(Commands.literal("info").executes(StructureCommand::previewInfo))
                                .then(Commands.literal("move")
                                        .then(Commands.argument("min", BlockPosArgument.blockPos())
                                                .executes(StructureCommand::previewMove)))
                                .then(Commands.literal("size")
                                        .then(Commands.argument("x", IntegerArgumentType.integer(1, 512))
                                                .then(Commands.argument("y",
                                                                IntegerArgumentType.integer(1, 512))
                                                        .then(Commands.argument("z",
                                                                        IntegerArgumentType.integer(1, 512))
                                                                .executes(StructureCommand::previewSize)))))))
                .then(Commands.literal("materialize")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(StructureCommand::materialize)))
                .then(Commands.literal("jobs").executes(StructureCommand::jobs))
                .then(Commands.literal("list").executes(StructureCommand::list))
                .then(Commands.literal("give")
                        .then(Commands.argument("id", StringArgumentType.string())
                                .executes(StructureCommand::give)))
                .then(Commands.literal("export")
                        .then(Commands.argument("id", StringArgumentType.string())
                                .executes(StructureCommand::export)))
                .then(Commands.literal("import")
                        .then(Commands.argument("file", StringArgumentType.string())
                                .executes(StructureCommand::importStructure))));
    }

    private static void say(CommandSourceStack source, String text, ChatFormatting colour) {
        source.sendSuccess(() -> Component.literal(text).withStyle(colour), false);
    }

    private static StructureWorldStore store(CommandSourceStack source) {
        return StructureWorldStore.of(source.getServer(), RCConfig.structurePayloadLimits());
    }

    /** The controller block entity at a position, or null (with a message already sent). */
    private static RedstoneStructureBlockEntity controller(CommandSourceStack source, BlockPos pos) {
        if (!(source.getLevel().getBlockEntity(pos) instanceof RedstoneStructureBlockEntity found)) {
            say(source, "no redstone structure block at " + pos.toShortString(), ChatFormatting.RED);
            return null;
        }
        return found;
    }

    /**
     * Prints what an edit did and returns the brigadier result code. Every editing verb below is one call into
     * {@link StructureEdits} plus this, so the rules live in one place the screen's packets reach too.
     */
    private static int report(CommandSourceStack source, StructureEdits.Result result) {
        say(source, result.message(), result.ok() ? ChatFormatting.AQUA : ChatFormatting.RED);
        return result.ok() ? 1 : 0;
    }

    private static int select(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        return report(source, StructureEdits.selectCorners(source.getLevel(),
                BlockPosArgument.getLoadedBlockPos(context, "controller"),
                BlockPosArgument.getLoadedBlockPos(context, "from"),
                BlockPosArgument.getLoadedBlockPos(context, "to")));
    }

    /**
     * One operation, with a command instead of a click: show the region, confirm it, or do it. The same entry
     * point the block's own right-click uses, so a rule that holds for a player holds for a command block.
     */
    private static int press(CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        if (!(source.getLevel() instanceof ServerLevel level)) {
            say(source, "that is not a server level", ChatFormatting.RED);
            return 0;
        }
        StructurePreviews.Result result = StructurePreviews.press(level, source.getPlayer(),
                BlockPosArgument.getLoadedBlockPos(context, "controller"));
        say(source, result.message(), !result.ok() ? ChatFormatting.RED
                : result.step() == StructurePreviews.Step.APPLIED ? ChatFormatting.GREEN
                        : ChatFormatting.AQUA);
        for (String detail : result.details()) {
            say(source, "  " + detail, ChatFormatting.GRAY);
        }
        return result.ok() ? 1 : 0;
    }

    /**
     * Which of the two jobs this controller has. Switching it ends any preview: a border left standing while the
     * controller has been switched would lie about what the next operation does.
     */
    private static int mode(CommandContext<CommandSourceStack> context, boolean release)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        return report(source, StructureEdits.setMode(source.getLevel(),
                BlockPosArgument.getLoadedBlockPos(context, "controller"), release));
    }

    /** Which of the two input modes the structure reads (mode A's faces or mode B's keys). */
    private static int io(CommandContext<CommandSourceStack> context, boolean manual)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        return report(source, StructureEdits.setIoMode(source.getLevel(),
                BlockPosArgument.getLoadedBlockPos(context, "controller"), manual));
    }

    /** What the structure is called - the name a product carries and a screen would show. */
    private static int name(CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        return report(source, StructureEdits.setName(source.getLevel(),
                BlockPosArgument.getLoadedBlockPos(context, "controller"),
                StringArgumentType.getString(context, "name")));
    }

    /**
     * Where a release would land: the product's own size, with its first cell at {@code target}. An edit like
     * any other, so it puts the preview back into the unconfirmed state.
     */
    private static int target(CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        BlockPos pos = BlockPosArgument.getLoadedBlockPos(context, "controller");
        StructureEdits.Result result = StructureEdits.target(source.getLevel(), pos,
                BlockPosArgument.getLoadedBlockPos(context, "target"));
        report(source, result);
        if (result.ok() && controller(source, pos) != null && controller(source, pos).isPreviewing()) {
            PreviewRegion region = controller(source, pos).preview();
            say(source, "  " + StructureRelease.occupied(source.getLevel(), region.min(), region.size())
                    + " block(s) are in the way and would be overwritten", ChatFormatting.GRAY);
        }
        return result.ok() ? 1 : 0;
    }

    /** Ends a preview without anything happening. */
    private static int previewEnd(CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        return report(source, StructureEdits.cancel(source.getLevel(),
                BlockPosArgument.getLoadedBlockPos(context, "controller")));
    }

    /** What is being shown, and whether the next operation would do it. */
    private static int previewInfo(CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        BlockPos pos = BlockPosArgument.getLoadedBlockPos(context, "controller");
        RedstoneStructureBlockEntity found = controller(source, pos);
        if (found == null) {
            return 0;
        }
        if (!found.isPreviewing()) {
            say(source, "this controller is previewing nothing", ChatFormatting.AQUA);
            return 0;
        }
        PreviewRegion region = found.preview();
        say(source, "previewing " + region.describe() + " (" + region.volume() + " block(s)), mode "
                + found.mode() + (found.isPreviewArmed() ? ", CONFIRMED - the next operation does it"
                        : ", nothing has happened to the world yet"), ChatFormatting.AQUA);
        java.util.Optional<String> refusal = region.refusal(RCConfig.structurePayloadLimits(),
                source.getLevel().getMinBuildHeight(), source.getLevel().getMaxBuildHeight());
        if (refusal.isPresent()) {
            say(source, "  this region cannot be used: " + refusal.get(), ChatFormatting.RED);
        }
        return 1;
    }

    /** Moves the preview's first cell. An edit, so the confirmation starts over. */
    private static int previewMove(CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        return report(source, StructureEdits.movePreview(source.getLevel(),
                BlockPosArgument.getLoadedBlockPos(context, "controller"),
                BlockPosArgument.getLoadedBlockPos(context, "min")));
    }

    /** Resizes the preview, keeping its first cell. An edit, so the confirmation starts over. */
    private static int previewSize(CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        return report(source, StructureEdits.resizePreview(source.getLevel(),
                BlockPosArgument.getLoadedBlockPos(context, "controller"),
                new Vec3i(IntegerArgumentType.getInteger(context, "x"),
                        IntegerArgumentType.getInteger(context, "y"),
                        IntegerArgumentType.getInteger(context, "z"))));
    }

    private static int tier(CommandContext<CommandSourceStack> context, boolean copy)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        return report(source, StructureEdits.setTier(source.getLevel(),
                BlockPosArgument.getLoadedBlockPos(context, "controller"), copy, source.getPlayer()));
    }

    /**
     * The controller's six activation tables, as they stand: mode A fills a table per face, and a signal on that
     * face pushes these cells. From the console they are filled in here; the cells are world coordinates inside
     * the current selection, converted to the selection-local coordinates the table and the payload store.
     */
    private static int listFaces(CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        BlockPos pos = BlockPosArgument.getLoadedBlockPos(context, "controller");
        RedstoneStructureBlockEntity found = controller(source, pos);
        if (found == null) {
            return 0;
        }
        say(source, "io mode " + found.ioMode() + "; a face with no entries does nothing when it is "
                + "energized", ChatFormatting.AQUA);
        int total = 0;
        for (Direction face : Direction.values()) {
            List<ActivationTable.FaceEntry> entries = ActivationTable.entries(found.faces(), face);
            total += entries.size();
            if (entries.isEmpty()) {
                continue;
            }
            StringBuilder line = new StringBuilder("  " + face.getName() + " -> ");
            for (ActivationTable.FaceEntry entry : entries) {
                line.append(entry.localPos().toShortString()).append('@').append(entry.strength()).append(' ');
            }
            say(source, line.toString(), ChatFormatting.GRAY);
        }
        say(source, total + " cell(s) in the table, in selection-local coordinates", ChatFormatting.GRAY);
        return total;
    }

    /** The six face names, as suggestions - the argument is a word because vanilla has no face argument. */
    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions>
            suggestFaces(CommandContext<CommandSourceStack> context,
                         com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        for (Direction face : Direction.values()) {
            builder.suggest(face.getName());
        }
        return builder.buildFuture();
    }

    /** A face by name, or null with the reason already sent. */
    @Nullable
    private static Direction face(CommandSourceStack source, String name) {
        for (Direction direction : Direction.values()) {
            if (direction.getName().equalsIgnoreCase(name)) {
                return direction;
            }
        }
        say(source, "'" + name + "' is not a face: use north, east, south, west, up or down",
                ChatFormatting.RED);
        return null;
    }

    private static int clearFace(CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        Direction face = face(source, StringArgumentType.getString(context, "face"));
        if (face == null) {
            return 0;
        }
        return report(source, StructureEdits.clearFace(source.getLevel(),
                BlockPosArgument.getLoadedBlockPos(context, "controller"), face));
    }

    private static int setFaceEntry(CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        Direction face = face(source, StringArgumentType.getString(context, "face"));
        if (face == null) {
            return 0;
        }
        return report(source, StructureEdits.setFaceEntry(source.getLevel(),
                BlockPosArgument.getLoadedBlockPos(context, "controller"), face,
                BlockPosArgument.getLoadedBlockPos(context, "cell"),
                IntegerArgumentType.getInteger(context, "strength")));
    }

    private static int info(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        BlockPos pos = BlockPosArgument.getLoadedBlockPos(context, "pos");
        if (source.getLevel().getBlockEntity(pos) instanceof RedstoneStructureBlockEntity found) {
            say(source, "controller at " + pos.toShortString() + ": mode " + found.mode() + ", tier "
                    + found.tier() + ", io " + found.ioMode() + ", previewing "
                    + (found.isPreviewing()
                            ? found.preview().describe() + (found.isPreviewArmed() ? " CONFIRMED" : "")
                            : "nothing"),
                    ChatFormatting.AQUA);
            StructureProductData pending = found.pending();
            say(source, pending == null ? "  holding nothing"
                            : "  holding " + pending.name() + " (" + pending.id() + ")",
                    ChatFormatting.GRAY);
            if (pending != null) {
                say(source, "  preview: " + StructureActions.describe(pending), ChatFormatting.GRAY);
            }
            say(source, "  face table: " + countFaceEntries(found.faces()) + " cell(s), manual panel "
                    + found.manual().size() + " entr(ies), output " + found.output().size() + " row(s)",
                    ChatFormatting.GRAY);
            return 1;
        }
        if (source.getLevel().getBlockEntity(pos) instanceof StructureBlockEntity product) {
            say(source, "structure at " + pos.toShortString() + ": " + (product.isMissing()
                    ? "MISSING - no id, or its file is not in this save (the placeholder case)"
                    : product.structureName() + " (" + product.structureId() + ") "
                            + product.size().getX() + "x" + product.size().getY() + "x"
                            + product.size().getZ()), ChatFormatting.AQUA);
            say(source, "  preview: " + StructureActions.describe(product.asProduct()), ChatFormatting.GRAY);
            say(source, "  face table: " + countFaceEntries(product.faces()) + " cell(s), output "
                    + product.output().size() + " row(s); plot " + product.plotIndex(), ChatFormatting.GRAY);
            return 1;
        }
        say(source, "nothing of ours at " + pos.toShortString(), ChatFormatting.RED);
        return 0;
    }

    /**
     * Mode B's panel as the client will see it: one line per entry, with the index a press names - the index is
     * what {@code /rc structure manual press} takes and what the client sends.
     */
    private static int listManual(CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        BlockPos pos = BlockPosArgument.getLoadedBlockPos(context, "controller");
        RedstoneStructureBlockEntity found = controller(source, pos);
        if (found == null) {
            return 0;
        }
        say(source, "io mode " + found.ioMode() + "; keys do something only in manual mode ("
                + IoMode.MANUAL.getSerializedName() + ")", ChatFormatting.AQUA);
        List<ActivationTable.ManualEntry> manual = found.manual();
        for (int entry = 0; entry < manual.size(); entry++) {
            ActivationTable.ManualEntry one = manual.get(entry);
            say(source, "  [" + entry + "] " + one.key() + " -> " + one.localPos().toShortString() + "@"
                    + one.strength() + (one.label().isEmpty() ? "" : " (" + one.label() + ")"),
                    ChatFormatting.GRAY);
        }
        say(source, manual.size() + " entr(ies), in selection-local coordinates; the limit is "
                + RCConfig.structureManualEntryLimit(), ChatFormatting.GRAY);
        return manual.size();
    }

    private static int clearManual(CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        BlockPos pos = BlockPosArgument.getLoadedBlockPos(context, "controller");
        RedstoneStructureBlockEntity found = controller(source, pos);
        if (found == null) {
            return 0;
        }
        return report(source, StructureEdits.clearManual(source.getLevel(),
                BlockPosArgument.getLoadedBlockPos(context, "controller")));
    }

    /**
     * Adds or replaces one panel entry: the cell is given in world coordinates inside the current selection and
     * stored selection-local, exactly like a face table entry.
     */
    private static int addManual(CommandContext<CommandSourceStack> context, String label)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        return report(source, StructureEdits.setManualEntry(source.getLevel(),
                BlockPosArgument.getLoadedBlockPos(context, "controller"),
                StringArgumentType.getString(context, "key"),
                BlockPosArgument.getLoadedBlockPos(context, "cell"),
                IntegerArgumentType.getInteger(context, "strength"), label));
    }

    /** Removes one panel entry by the index {@code manual list} prints. */
    private static int removeManual(CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        return report(source, StructureEdits.clearManualEntry(source.getLevel(),
                BlockPosArgument.getLoadedBlockPos(context, "controller"),
                IntegerArgumentType.getInteger(context, "entry")));
    }

    /**
     * The output table, as it stands. Every row is printed with both coordinates, because the list is what a row
     * is copied out of to remove it and both roads in name a world cell, not the stored selection-local one.
     */
    private static int listOutput(CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        BlockPos pos = BlockPosArgument.getLoadedBlockPos(context, "controller");
        RedstoneStructureBlockEntity found = controller(source, pos);
        if (found == null) {
            return 0;
        }
        say(source, "output: while a watched cell carries a signal (any non-zero counts), its face answers "
                + "with the row's strength; nothing latches and nothing fires once", ChatFormatting.AQUA);
        List<ActivationTable.OutputEntry> output = found.output();
        BlockPos min = found.isPreviewing() ? found.preview().min() : null;
        for (int row = 0; row < output.size(); row++) {
            ActivationTable.OutputEntry one = output.get(row);
            String where = min == null
                    ? one.localPos().toShortString() + " (local; no region is selected)"
                    : min.offset(one.localPos()).toShortString() + " (local "
                            + one.localPos().toShortString() + ")";
            say(source, "  [" + row + "] " + one.face().getName() + " <- " + where + " answers "
                    + one.strength(), ChatFormatting.GRAY);
        }
        say(source, output.size() + " row(s); several rows may watch one face, and the stronger one wins",
                ChatFormatting.GRAY);
        return output.size();
    }

    private static int clearOutput(CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        return report(source, StructureEdits.clearOutput(source.getLevel(),
                BlockPosArgument.getLoadedBlockPos(context, "controller")));
    }

    /** Adds or replaces one output row: a world cell, a face and the strength that face then answers with. */
    private static int addOutput(CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        Direction face = face(source, StringArgumentType.getString(context, "face"));
        if (face == null) {
            return 0;
        }
        return report(source, StructureEdits.setOutputEntry(source.getLevel(),
                BlockPosArgument.getLoadedBlockPos(context, "controller"),
                BlockPosArgument.getLoadedBlockPos(context, "cell"), face,
                IntegerArgumentType.getInteger(context, "strength")));
    }

    private static int removeOutput(CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        Direction face = face(source, StringArgumentType.getString(context, "face"));
        if (face == null) {
            return 0;
        }
        return report(source, StructureEdits.clearOutputEntry(source.getLevel(),
                BlockPosArgument.getLoadedBlockPos(context, "controller"),
                BlockPosArgument.getLoadedBlockPos(context, "cell"), face));
    }

    /**
     * Presses one entry without a client - what the screen's own button does, and how a structure's keys are
     * checked from the console. It goes through the same function the packet handler does, so the distance rule
     * and the cooldown are the ones a player meets; a console has no eyes, so the press is measured from the block
     * itself.
     */
    private static int pressManual(CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        return report(source, StructureEdits.pressManual(source.getLevel(), source.getPlayer(),
                BlockPosArgument.getLoadedBlockPos(context, "pos"),
                IntegerArgumentType.getInteger(context, "entry")));
    }

    /** How many cells a six-face table names - the number a capture reports on its diagnostics line. */
    private static int countFaceEntries(Map<Direction, List<ActivationTable.FaceEntry>> faces) {
        int total = 0;
        for (Direction face : Direction.values()) {
            total += ActivationTable.entries(faces, face).size();
        }
        return total;
    }

    private static int list(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        StructureWorldStore store = store(source);
        List<PayloadHeader> headers = new ArrayList<>(store.headers());
        headers.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        say(source, headers.size() + " structure(s) in " + store.structuresDirectory(), ChatFormatting.AQUA);
        for (PayloadHeader header : headers) {
            say(source, "  " + header.name() + "  " + header.size().getX() + "x" + header.size().getY()
                    + "x" + header.size().getZ() + "  " + header.uuid(), ChatFormatting.GRAY);
        }
        int unreadable = store.unreadable();
        if (unreadable > 0) {
            say(source, "  " + unreadable + " file(s) could not be read and are not listed",
                    ChatFormatting.YELLOW);
        }
        return headers.size();
    }

    private static UUID id(CommandSourceStack source, String argument) {
        try {
            return UUID.fromString(argument);
        } catch (IllegalArgumentException notAnId) {
            say(source, "'" + argument + "' is not a structure id", ChatFormatting.RED);
            return null;
        }
    }

    private static int give(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        UUID id = id(source, StringArgumentType.getString(context, "id"));
        if (id == null) {
            return 0;
        }
        StructurePayload payload = store(source).loadOrNull(id);
        if (payload == null) {
            say(source, "no readable structure " + id + " in this save - that is the missing-placeholder "
                    + "case, not an empty structure", ChatFormatting.RED);
            return 0;
        }
        if (source.getPlayer() == null) {
            say(source, "a player has to run this: the item goes into their inventory", ChatFormatting.RED);
            return 0;
        }
        ItemStack stack = RCRegistry.structureStack(
                StructureProductData.of(payload, RCConfig.structurePreviewBytes()));
        if (!source.getPlayer().addItem(stack)) {
            source.getPlayer().drop(stack, false);
        }
        say(source, "gave " + payload.header().name(), ChatFormatting.GREEN);
        return 1;
    }

    private static int export(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        UUID id = id(source, StringArgumentType.getString(context, "id"));
        if (id == null) {
            return 0;
        }
        try {
            Path written = store(source).exportTo(id);
            say(source, "exported to " + written, ChatFormatting.GREEN);
            return 1;
        } catch (IOException failed) {
            say(source, "export failed: " + failed, ChatFormatting.RED);
            return 0;
        }
    }

    /**
     * Imports a file from the export directory. The name only, never a path: a path argument could be pointed at
     * anything the server process can read.
     */
    private static int importStructure(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String file = StringArgumentType.getString(context, "file");
        if (file.contains("/") || file.contains("\\") || file.contains("..")) {
            say(source, "give a file name, not a path", ChatFormatting.RED);
            return 0;
        }
        StructureWorldStore store = store(source);
        Path source_file = store.exportsDirectory().resolve(file.endsWith(".nbt") ? file : file + ".nbt");
        try {
            UUID imported = store.importFrom(source_file);
            StructurePayload payload = store.loadOrNull(imported);
            say(source, "imported as " + imported + (payload == null ? "" : " (" + payload.header().name()
                    + ")"), ChatFormatting.GREEN);
            return 1;
        } catch (IOException failed) {
            say(source, "import failed: " + failed, ChatFormatting.RED);
            return 0;
        }
    }

    /** What is being written right now, and how far it has got. */
    private static int jobs(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        List<StructureRelease.Job> jobs = StructureRelease.jobs();
        if (jobs.isEmpty()) {
            say(source, "no release is running", ChatFormatting.GRAY);
            return 0;
        }
        for (StructureRelease.Job job : jobs) {
            say(source, "  " + job.name() + " at " + job.origin().toShortString() + ": " + job.placed()
                    + "/" + job.total() + " block(s)", ChatFormatting.GRAY);
        }
        return jobs.size();
    }

    /**
     * Puts a placed product's structure into a pocket plot, if it does not have one yet: the item path does this
     * on placement, and this covers the other ways a product appears (a {@code setblock}, a structure file, a
     * structure imported after its block was placed) and repairs one whose pocket was lost.
     */
    private static int materialize(CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        BlockPos pos = BlockPosArgument.getLoadedBlockPos(context, "pos");
        if (!(source.getLevel().getBlockEntity(pos)
                instanceof com.jiangyuefengyu.redstonecircuit.block.StructureBlockEntity product)) {
            say(source, "no structure block at " + pos.toShortString(), ChatFormatting.RED);
            return 0;
        }
        if (product.isMissing()) {
            say(source, "this structure block holds no structure id (the placeholder case)", ChatFormatting.RED);
            return 0;
        }
        if (product.plotIndex() >= 0) {
            say(source, "it is already in pocket plot " + product.plotIndex(), ChatFormatting.YELLOW);
            return 0;
        }
        StructureWorldStore store = store(source);
        StructurePayload payload = store.loadOrNull(product.structureId());
        if (payload == null) {
            say(source, "MISSING: no readable structure " + product.structureId() + " in this save",
                    ChatFormatting.RED);
            return 0;
        }
        PocketMaterializer.Result result = PocketMaterializer.materialize(source.getLevel(), payload,
                RCConfig.structureCaptureContextShell(), source.getLevel().dimension(), pos);
        if (!result.materialized()) {
            say(source, "could not give it a pocket: " + result.refusal(), ChatFormatting.RED);
            return 0;
        }
        product.setPlotIndex(result.plot());
        say(source, "structure " + product.structureName() + " is now running in pocket plot "
                + result.plot() + " (" + result.blocks() + " block(s))", ChatFormatting.GREEN);
        return 1;
    }

    private StructureCommand() {
    }
}
