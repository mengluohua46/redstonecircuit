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
import com.jiangyuefengyu.redstonecircuit.data.ConnectionState;
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
 *   /rc dump  [pos]                            show what is stored in a block
 *   /rc place &lt;type&gt; [yaw] [power]              store a component (bypasses the held item)
 *   /rc connect &lt;dir&gt; &lt;auto|on|off&gt;            set a per-direction connection override
 *   /rc disconnect                             clear all connection overrides
 *   /rc clear [pos]                            remove the component stored in a block
 *   /rc list                                   list every block holding inner redstone
 *   /rc rules [pos]                            explain whether a block may host redstone
 * </pre>
 */
public final class RCCommand {

    private RCCommand() {
    }

    /** A subcommand handler taking an explicit position. */
    private interface PositionSub {
        int run(CommandContext<CommandSourceStack> ctx, BlockPos pos) throws CommandSyntaxException;
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("rc")
                .requires(source -> source.hasPermission(2));

        root.then(positionSub("dump", RCCommand::dump));
        root.then(positionSub("clear", RCCommand::clear));
        root.then(positionSub("rules", RCCommand::rules));
        root.then(connectSub());
        root.then(positionSub("disconnect", RCCommand::disconnect));
        root.then(Commands.literal("list").executes(RCCommand::list));
        root.then(placeSub());

        dispatcher.register(root);
    }

    /**
     * {@code <literal> [pos]} - uses the executing player's own block when no position is given.
     * Built step by step so the Brigadier chain stays readable.
     */
    private static LiteralArgumentBuilder<CommandSourceStack> positionSub(String name, PositionSub handler) {
        LiteralArgumentBuilder<CommandSourceStack> literal = Commands.literal(name);
        literal.executes(ctx -> handler.run(ctx, ownPos(ctx)));
        literal.then(Commands.argument("pos", BlockPosArgument.blockPos())
                .executes(ctx -> handler.run(ctx, BlockPosArgument.getBlockPos(ctx, "pos"))));
        return literal;
    }

    /** {@code connect <dir> <auto|on|off> [pos]}. */
    private static LiteralArgumentBuilder<CommandSourceStack> connectSub() {
        RequiredArgumentBuilder<CommandSourceStack, String> state =
                Commands.argument("state", StringArgumentType.word());
        state.suggests((ctx, builder) -> {
            for (ConnectionState value : ConnectionState.values()) {
                builder.suggest(value.name().toLowerCase(Locale.ROOT));
            }
            return builder.buildFuture();
        });
        state.executes(ctx -> connect(ctx, ownPos(ctx),
                StringArgumentType.getString(ctx, "dir"),
                StringArgumentType.getString(ctx, "state")));
        state.then(Commands.argument("pos", BlockPosArgument.blockPos())
                .executes(ctx -> connect(ctx, BlockPosArgument.getBlockPos(ctx, "pos"),
                        StringArgumentType.getString(ctx, "dir"),
                        StringArgumentType.getString(ctx, "state"))));

        RequiredArgumentBuilder<CommandSourceStack, String> dir =
                Commands.argument("dir", StringArgumentType.word());
        dir.suggests((ctx, builder) -> {
            for (Direction direction : Direction.values()) {
                builder.suggest(direction.getName());
            }
            return builder.buildFuture();
        });
        dir.then(state);

        LiteralArgumentBuilder<CommandSourceStack> literal = Commands.literal("connect");
        literal.then(dir);
        return literal;
    }

    /** {@code place <type> [yaw] [power]}. */
    private static LiteralArgumentBuilder<CommandSourceStack> placeSub() {
        // power
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

        LiteralArgumentBuilder<CommandSourceStack> place = Commands.literal("place");
        place.then(type);
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
            feedback(ctx, Component.literal("  (empty)").withStyle(ChatFormatting.GRAY));
            return 0;
        }
        Slot slot = node.slot();
        feedback(ctx, Component.literal("  " + slot.describe()).withStyle(ChatFormatting.WHITE));

        // Show the resolved connection state per direction so the wrench logic is testable
        // before the wrench item exists.
        for (Direction direction : Direction.values()) {
            boolean connected = slot.isConnected(direction);
            String state = slot.isLocked(direction)
                    ? (connected ? "forced ON" : "forced OFF")
                    : (connected ? "auto (on)" : "auto");
            feedback(ctx, Component.literal("    " + direction.getName() + ": " + state)
                    .withStyle(connected ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY));
        }
        return 1;
    }

    private static int place(CommandContext<CommandSourceStack> ctx, float yaw, int power) {
        String typeName = StringArgumentType.getString(ctx, "type");
        ComponentType type = ComponentType.byName(typeName, null);
        if (type == null) {
            error(ctx, "unknown component type '" + typeName + "'");
            return 0;
        }

        BlockPos pos = ownPos(ctx);
        InnerRedstoneStore store = InnerRedstoneStore.get(level(ctx));

        Slot slot = new Slot(type);
        slot.facing = Direction.fromYRot(yaw);
        slot.power = Math.max(0, Math.min(15, power));

        InnerRedstoneNode node = store.getOrCreate(pos);
        node.setSlot(slot);
        store.markDirty();

        feedback(ctx, header("placed " + type + " at " + pos.toShortString()));
        return 1;
    }

    private static int connect(CommandContext<CommandSourceStack> ctx, BlockPos pos,
                              String directionName, String stateName) {
        Direction direction = faceByName(directionName);
        if (direction == null) {
            error(ctx, "unknown direction '" + directionName + "' (use up/down/north/south/east/west)");
            return 0;
        }
        ConnectionState state = ConnectionState.byName(stateName, null);
        if (state == null) {
            error(ctx, "unknown state '" + stateName + "' (use auto/on/off)");
            return 0;
        }

        InnerRedstoneStore store = InnerRedstoneStore.get(level(ctx));
        InnerRedstoneNode node = store.get(pos);
        if (node == null || node.isEmpty()) {
            error(ctx, "no inner redstone at " + pos.toShortString());
            return 0;
        }
        node.slot().setConnection(direction, state);
        store.markDirty();

        feedback(ctx, header("connection " + direction.getName() + " -> " + state
                + " at " + pos.toShortString()));
        return 1;
    }

    private static int disconnect(CommandContext<CommandSourceStack> ctx, BlockPos pos) {
        InnerRedstoneStore store = InnerRedstoneStore.get(level(ctx));
        InnerRedstoneNode node = store.get(pos);
        if (node == null || node.isEmpty()) {
            error(ctx, "no inner redstone at " + pos.toShortString());
            return 0;
        }
        node.slot().clearConnections();
        store.markDirty();

        feedback(ctx, header("all connection overrides cleared at " + pos.toShortString()));
        return 1;
    }

    private static int clear(CommandContext<CommandSourceStack> ctx, BlockPos pos) {
        InnerRedstoneStore store = InnerRedstoneStore.get(level(ctx));
        InnerRedstoneNode removed = store.remove(pos);
        boolean had = removed != null && !removed.isEmpty();
        feedback(ctx, header((had ? "cleared " + removed.type() : "nothing to clear")
                + " at " + pos.toShortString()));
        return had ? 1 : 0;
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
            String type = node == null || node.isEmpty() ? "-" : node.type().name();
            feedback(ctx, Component.literal("  " + pos.toShortString() + "  " + type)
                    .withStyle(ChatFormatting.WHITE));
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
