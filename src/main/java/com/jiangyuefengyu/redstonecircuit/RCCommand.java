package com.jiangyuefengyu.redstonecircuit;

import java.util.List;
import java.util.Locale;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneNode;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore;
import com.jiangyuefengyu.redstonecircuit.data.Slot;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

/**
 * {@code /rc} debug command (permission level 2).
 *
 * <pre>
 *   /rc dump  [pos]                        show what is stored in a block
 *   /rc place &lt;face&gt; &lt;type&gt; [yaw] [power]   store a component directly (bypasses the held item)
 *   /rc clear [pos]                        remove everything stored in a block
 *   /rc list                               list every block holding inner redstone
 *   /rc rules [pos]                        explain whether a block may host redstone
 * </pre>
 *
 * <p>Subcommands are built as separate variables on purpose: Brigadier chains get very deeply
 * nested and are then easy to get wrong.
 */
public final class RCCommand {

    private RCCommand() {
    }

    /** A subcommand handler. */
    private interface Sub {
        int run(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException;
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("rc")
                .requires(source -> source.hasPermission(2));

        root.then(positionSub("dump", RCCommand::dump));
        root.then(positionSub("clear", RCCommand::clear));
        root.then(positionSub("rules", RCCommand::rules));
        root.then(Commands.literal("list").executes(RCCommand::list));
        root.then(placeSub());

        dispatcher.register(root);
    }

    /** {@code <literal> [pos]} - uses the executing player's block when no position is given. */
    private static LiteralArgumentBuilder<CommandSourceStack> positionSub(String name, PositionSub handler) {
        LiteralArgumentBuilder<CommandSourceStack> literal = Commands.literal(name);
        literal.executes(ctx -> handler.run(ctx, ownPos(ctx)));
        literal.then(Commands.argument("pos", BlockPosArgument.blockPos())
                .executes(ctx -> handler.run(ctx, BlockPosArgument.getBlockPos(ctx, "pos"))));
        return literal;
    }

    private interface PositionSub {
        int run(CommandContext<CommandSourceStack> ctx, BlockPos pos) throws CommandSyntaxException;
    }

    /** {@code place <face> <type> [yaw] [power]}. */
    private static LiteralArgumentBuilder<CommandSourceStack> placeSub() {
        // Inner-most: power
        ArgumentBuilder<CommandSourceStack, ?> power = Commands.argument("power", IntegerArgumentType.integer(0, 15));
        power.executes(ctx -> place(ctx, FloatArgumentType.getFloat(ctx, "yaw"),
                IntegerArgumentType.getInteger(ctx, "power")));

        // yaw
        ArgumentBuilder<CommandSourceStack, ?> yaw = Commands.argument("yaw", FloatArgumentType.floatArg());
        yaw.executes(ctx -> place(ctx, FloatArgumentType.getFloat(ctx, "yaw"), 15));
        yaw.then(power);

        // type
        RequiredArgumentBuilder<CommandSourceStack, String> type =
                Commands.argument("type", StringArgumentType.word());
        type.suggests((ctx, builder) -> {
            for (ComponentType value : ComponentType.values()) {
                builder.suggest(value.name().toLowerCase(Locale.ROOT));
            }
            return builder.buildFuture();
        });
        type.executes(ctx -> place(ctx, 0.0F, 15));
        type.then(yaw);

        // face
        RequiredArgumentBuilder<CommandSourceStack, String> face =
                Commands.argument("face", StringArgumentType.word());
        face.suggests((ctx, builder) -> {
            for (Direction direction : Direction.values()) {
                builder.suggest(direction.getName());
            }
            return builder.buildFuture();
        });
        face.then(type);

        LiteralArgumentBuilder<CommandSourceStack> place = Commands.literal("place");
        place.then(face);
        return place;
    }

    // --------------------------------------------------------------- helpers --

    private static BlockPos ownPos(CommandContext<CommandSourceStack> ctx) {
        return BlockPos.containing(ctx.getSource().getPosition());
    }

    private static ServerLevel level(CommandContext<CommandSourceStack> ctx) {
        return ctx.getSource().getLevel();
    }

    private static void feedback(CommandContext<CommandSourceStack> ctx, Component message) {
        ctx.getSource().sendSuccess(() -> message, false);
    }

    private static void error(CommandContext<CommandSourceStack> ctx, String message) {
        ctx.getSource().sendFailure(Component.literal(message));
    }

    private static Component header(String text) {
        return Component.literal(text).withStyle(ChatFormatting.AQUA);
    }

    private static Direction faceByName(String name) {
        for (Direction direction : Direction.values()) {
            if (direction.getName().equalsIgnoreCase(name)) {
                return direction;
            }
        }
        return null;
    }

    // ------------------------------------------------------------ subcommands --

    private static int dump(CommandContext<CommandSourceStack> ctx, BlockPos pos) {
        InnerRedstoneStore store = InnerRedstoneStore.get(level(ctx));
        InnerRedstoneNode node = store.get(pos);

        feedback(ctx, header("redstonecircuit @ " + pos.toShortString()));
        if (node == null || node.isEmpty()) {
            feedback(ctx, Component.literal("  (nothing stored)").withStyle(ChatFormatting.GRAY));
            return 0;
        }
        for (Direction face : Direction.values()) {
            Slot slot = node.get(face);
            if (slot != null) {
                feedback(ctx, Component.literal("  " + face.getName() + ": " + slot.describe())
                        .withStyle(ChatFormatting.WHITE));
            }
        }
        feedback(ctx, Component.literal("  total: " + node.size() + " component(s)")
                .withStyle(ChatFormatting.DARK_GRAY));
        return node.size();
    }

    private static int place(CommandContext<CommandSourceStack> ctx, float yaw, int power) {
        String faceName = StringArgumentType.getString(ctx, "face");
        String typeName = StringArgumentType.getString(ctx, "type");

        Direction face = faceByName(faceName);
        if (face == null) {
            error(ctx, "unknown face '" + faceName + "' (use up/down/north/south/east/west)");
            return 0;
        }
        ComponentType type = ComponentType.byName(typeName, null);
        if (type == null) {
            error(ctx, "unknown component type '" + typeName + "'");
            return 0;
        }

        BlockPos pos = ownPos(ctx);
        InnerRedstoneStore store = InnerRedstoneStore.get(level(ctx));

        InnerRedstoneNode node = store.getOrCreate(pos);
        Slot slot = node.put(face, type);
        slot.facing = Direction.fromYRot(yaw);
        slot.power = Math.max(0, Math.min(15, power));
        store.markDirty();

        feedback(ctx, header("placed " + type + " on " + face.getName() + " at " + pos.toShortString()));
        return 1;
    }

    private static int clear(CommandContext<CommandSourceStack> ctx, BlockPos pos) {
        InnerRedstoneStore store = InnerRedstoneStore.get(level(ctx));
        InnerRedstoneNode removed = store.remove(pos);
        int count = removed == null ? 0 : removed.size();
        feedback(ctx, header("cleared " + count + " component(s) at " + pos.toShortString()));
        return count;
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        InnerRedstoneStore store = InnerRedstoneStore.get(level(ctx));
        List<BlockPos> positions = store.positions();

        feedback(ctx, header("redstonecircuit store: " + positions.size() + " block(s) in "
                + level(ctx).dimension().location()));
        if (positions.isEmpty()) {
            feedback(ctx, Component.literal("  (empty)").withStyle(ChatFormatting.GRAY));
            return 0;
        }

        int limit = Math.min(positions.size(), 50);
        for (int i = 0; i < limit; i++) {
            BlockPos pos = positions.get(i);
            InnerRedstoneNode node = store.get(pos);
            feedback(ctx, Component.literal("  " + pos.toShortString() + "  ("
                    + (node == null ? 0 : node.size()) + ")").withStyle(ChatFormatting.WHITE));
        }
        if (positions.size() > limit) {
            feedback(ctx, Component.literal("  ... and " + (positions.size() - limit) + " more")
                    .withStyle(ChatFormatting.GRAY));
        }
        return positions.size();
    }

    private static int rules(CommandContext<CommandSourceStack> ctx, BlockPos pos) {
        ServerLevel level = level(ctx);
        var state = level.getBlockState(pos);

        boolean solidRender = state.isSolidRender(level, pos);
        boolean fullCube = state.isCollisionShapeFullBlock(level, pos);
        boolean allowed = HostRules.isValidHost(state);

        feedback(ctx, header("host rules @ " + pos.toShortString()));
        feedback(ctx, Component.literal("  block           : " + state.getBlock())
                .withStyle(ChatFormatting.WHITE));
        feedback(ctx, Component.literal("  isSolidRender   : " + solidRender)
                .withStyle(solidRender ? ChatFormatting.GREEN : ChatFormatting.RED));
        feedback(ctx, Component.literal("  fullCubeShape   : " + fullCube)
                .withStyle(fullCube ? ChatFormatting.GREEN : ChatFormatting.RED));
        feedback(ctx, Component.literal("  mayHostRedstone : " + allowed)
                .withStyle(allowed ? ChatFormatting.GREEN : ChatFormatting.RED));
        return allowed ? 1 : 0;
    }
}
