package com.jiangyuefengyu.redstonecircuit;

import java.util.List;
import java.util.Locale;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.ComparatorMode;
import com.jiangyuefengyu.redstonecircuit.data.ConnectionState;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneNode;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore;
import com.jiangyuefengyu.redstonecircuit.data.Slot;
import com.jiangyuefengyu.redstonecircuit.logic.InnerRedstoneNetwork;
import com.jiangyuefengyu.redstonecircuit.logic.PowerEnvironment;
import com.jiangyuefengyu.redstonecircuit.logic.PowerSolver;
import com.jiangyuefengyu.redstonecircuit.logic.SlotNode;
import com.jiangyuefengyu.redstonecircuit.network.HostSync;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import org.jetbrains.annotations.Nullable;

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
        root.then(positionSub("probe", RCCommand::probe));
        root.then(positionSub("clear", RCCommand::clear));
        root.then(positionSub("rules", RCCommand::rules));
        root.then(positionSub("solve", RCCommand::solve));
        root.then(positionSub("toggle", RCCommand::toggle));
        root.then(connectSub());
        root.then(setSub());
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

    /** {@code place <type> [power]}. */
    private static LiteralArgumentBuilder<CommandSourceStack> placeSub() {
        ArgumentBuilder<CommandSourceStack, ?> power = Commands.argument("power", IntegerArgumentType.integer(0, 15));
        power.executes(ctx -> place(ctx, IntegerArgumentType.getInteger(ctx, "power")));

        RequiredArgumentBuilder<CommandSourceStack, String> type =
                Commands.argument("type", StringArgumentType.word());
        type.suggests((ctx, builder) -> {
            for (ComponentType value : ComponentType.values()) {
                builder.suggest(value.name().toLowerCase(Locale.ROOT));
            }
            return builder.buildFuture();
        });
        type.executes(ctx -> place(ctx, 15));
        type.then(power);

        LiteralArgumentBuilder<CommandSourceStack> place = Commands.literal("place");
        place.then(type);
        return place;
    }

    /**
     * {@code set <facing|delay|mode> <value> [pos]} - the properties of the component at a position.
     *
     * <p>Stands in for the wrench while it does not exist yet: a repeater's input side and delay, and
     * a comparator's mode, all have to be settable to test them in game.
     */
    private static LiteralArgumentBuilder<CommandSourceStack> setSub() {
        LiteralArgumentBuilder<CommandSourceStack> set = Commands.literal("set");

        RequiredArgumentBuilder<CommandSourceStack, String> facing =
                Commands.argument("direction", StringArgumentType.word());
        facing.suggests((ctx, builder) -> {
            for (Direction direction : Direction.values()) {
                builder.suggest(direction.getName());
            }
            return builder.buildFuture();
        });
        facing.executes(ctx -> setProperty(ctx, ownPos(ctx), "facing",
                StringArgumentType.getString(ctx, "direction")));
        facing.then(Commands.argument("pos", BlockPosArgument.blockPos())
                .executes(ctx -> setProperty(ctx, BlockPosArgument.getBlockPos(ctx, "pos"), "facing",
                        StringArgumentType.getString(ctx, "direction"))));
        set.then(Commands.literal("facing").then(facing));

        RequiredArgumentBuilder<CommandSourceStack, Integer> delay =
                Commands.argument("ticks", IntegerArgumentType.integer(1, 4));
        delay.executes(ctx -> setProperty(ctx, ownPos(ctx), "delay",
                Integer.toString(IntegerArgumentType.getInteger(ctx, "ticks"))));
        delay.then(Commands.argument("pos", BlockPosArgument.blockPos())
                .executes(ctx -> setProperty(ctx, BlockPosArgument.getBlockPos(ctx, "pos"), "delay",
                        Integer.toString(IntegerArgumentType.getInteger(ctx, "ticks")))));
        set.then(Commands.literal("delay").then(delay));

        RequiredArgumentBuilder<CommandSourceStack, String> mode =
                Commands.argument("mode", StringArgumentType.word());
        mode.suggests((ctx, builder) -> {
            for (ComparatorMode value : ComparatorMode.values()) {
                builder.suggest(value.name().toLowerCase(Locale.ROOT));
            }
            return builder.buildFuture();
        });
        mode.executes(ctx -> setProperty(ctx, ownPos(ctx), "mode",
                StringArgumentType.getString(ctx, "mode")));
        mode.then(Commands.argument("pos", BlockPosArgument.blockPos())
                .executes(ctx -> setProperty(ctx, BlockPosArgument.getBlockPos(ctx, "pos"), "mode",
                        StringArgumentType.getString(ctx, "mode"))));
        set.then(Commands.literal("mode").then(mode));

        return set;
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

        // Show what the component does with each side, so a diode's dead ends and the wrench's
        // overrides are both visible without guessing.
        PowerEnvironment.Node view = SlotNode.of(slot);
        for (Direction direction : Direction.values()) {
            boolean reads = PowerSolver.readsFrom(view, direction);
            boolean emits = PowerSolver.emitsToward(view, direction);
            String state = (reads ? "in " : "   ") + (emits ? "out" : "   ");
            if (slot.isLocked(direction)) {
                state += slot.forcedOn.contains(direction) ? "  forced ON" : "  forced OFF";
            }
            feedback(ctx, Component.literal("    " + direction.getName() + ": " + state)
                    .withStyle(reads || emits ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY));
        }
        return 1;
    }

    private static int place(CommandContext<CommandSourceStack> ctx, int power) {
        String typeName = StringArgumentType.getString(ctx, "type");
        ComponentType type = ComponentType.byName(typeName, null);
        if (type == null) {
            error(ctx, "unknown component type '" + typeName + "'");
            return 0;
        }
        if (type == ComponentType.PRESSURE_PLATE) {
            error(ctx, "a pressure plate inside a block is not implemented yet");
            return 0;
        }

        BlockPos pos = ownPos(ctx);
        ServerLevel level = level(ctx);
        InnerRedstoneStore store = InnerRedstoneStore.get(level);

        Slot slot = new Slot(type);
        int value = Math.max(0, Math.min(PowerSolver.MAX_POWER, power));
        if (type == ComponentType.DUST) {
            slot.power = value;
            // Seeded by hand, so it is an input like a lever against the host block - not something
            // the solver is free to derive away on its next pass.
            slot.injectedPower = value;
        } else if (type.isManual()) {
            slot.powered = value > 0;
            slot.power = slot.powered ? PowerSolver.MAX_POWER : 0;
        } else {
            // A device's output follows its input, so it starts silent and the solver decides.
            slot.power = 0;
        }
        // Default the input side to where the operator is looking: "point the repeater away from me"
        // is the gesture that matches how a repeater is placed in vanilla.
        slot.facing = facingOf(ctx.getSource().getPlayer());

        InnerRedstoneNode node = store.getOrCreate(pos);
        node.setSlot(slot);
        store.markDirty();
        InnerRedstoneNetwork.markDirtyWithNeighbours(level, pos);
        InnerRedstoneNetwork.notifyOutputChanged(level, pos);
        HostSync.broadcastChange(level, pos, true);

        feedback(ctx, header("placed " + type + " (" + slot.describe() + ") at " + pos.toShortString()));
        return 1;
    }

    /** The horizontal direction the operator faces, or north when there is no player (command block). */
    private static Direction facingOf(@Nullable ServerPlayer player) {
        return player == null ? Direction.NORTH : player.getDirection();
    }

    /** {@code set facing|delay|mode}: changes one property of the component at a position. */
    private static int setProperty(CommandContext<CommandSourceStack> ctx, BlockPos pos,
                                   String property, String value) {
        ServerLevel level = level(ctx);
        InnerRedstoneStore store = InnerRedstoneStore.get(level);
        InnerRedstoneNode node = store.get(pos);
        if (node == null || node.isEmpty()) {
            error(ctx, "no inner redstone at " + pos.toShortString());
            return 0;
        }
        Slot slot = node.slot();

        switch (property) {
            case "facing" -> {
                Direction direction = faceByName(value);
                if (direction == null) {
                    error(ctx, "unknown direction '" + value + "'");
                    return 0;
                }
                slot.facing = direction;
            }
            case "delay" -> {
                if (slot.type != ComponentType.REPEATER) {
                    error(ctx, "delay only applies to a repeater (this block holds " + slot.type + ")");
                    return 0;
                }
                slot.delay = Math.max(1, Math.min(4, Integer.parseInt(value)));
            }
            case "mode" -> {
                if (slot.type != ComponentType.COMPARATOR) {
                    error(ctx, "mode only applies to a comparator (this block holds " + slot.type + ")");
                    return 0;
                }
                ComparatorMode mode = ComparatorMode.byName(value, null);
                if (mode == null) {
                    error(ctx, "unknown comparator mode '" + value + "'");
                    return 0;
                }
                slot.mode = mode;
            }
            default -> {
                error(ctx, "unknown property '" + property + "'");
                return 0;
            }
        }

        store.markDirty();
        InnerRedstoneNetwork.markDirtyWithNeighbours(level, pos);
        InnerRedstoneNetwork.settle(level);
        feedback(ctx, header("set " + property + " = " + value + " at " + pos.toShortString()
                + " -> " + slot.describe()));
        return 1;
    }

    /** {@code toggle [pos]}: works a lever or button stored inside a block, without a player click. */
    private static int toggle(CommandContext<CommandSourceStack> ctx, BlockPos pos) {
        ServerLevel level = level(ctx);
        InnerRedstoneStore store = InnerRedstoneStore.get(level);
        InnerRedstoneNode node = store.get(pos);
        if (node == null || node.isEmpty()) {
            error(ctx, "no inner redstone at " + pos.toShortString());
            return 0;
        }
        Slot slot = node.slot();
        if (!slot.type.isManual()) {
            error(ctx, slot.type + " cannot be toggled; only a lever or button can");
            return 0;
        }

        slot.powered = !slot.powered;
        slot.power = slot.powered ? PowerSolver.MAX_POWER : 0;
        if (slot.powered && slot.type == ComponentType.BUTTON) {
            InnerRedstoneNetwork.scheduleRelease(level, pos, 20);
        }
        store.markDirty();
        InnerRedstoneNetwork.markDirtyWithNeighbours(level, pos);
        InnerRedstoneNetwork.settle(level);

        feedback(ctx, header((slot.powered ? "engaged " : "released ") + slot.type
                + " at " + pos.toShortString()));
        return 1;
    }

    /** Forces an immediate recomputation of the local network, for debugging. */
    private static int solve(CommandContext<CommandSourceStack> ctx, BlockPos pos) {
        ServerLevel level = level(ctx);
        InnerRedstoneStore store = InnerRedstoneStore.get(level);
        // Go through the same queue-and-settle path the tick uses, so a command-driven solve sees
        // the identical result a player would see (including the feedback from notifying the world).
        InnerRedstoneNetwork.markDirtyWithNeighbours(level, pos);
        InnerRedstoneNetwork.settle(level);

        InnerRedstoneNode node = store.get(pos);
        if (node == null || node.isEmpty()) {
            error(ctx, "no inner redstone at " + pos.toShortString());
            return 0;
        }
        feedback(ctx, header("solved " + pos.toShortString() + " -> power " + node.power()));
        return node.power();
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

    /**
     * {@code probe [pos]}: where does this block's supply come from, side by side.
     *
     * <p>The one command that answers "something outside is keeping this block powered": it lists the
     * block on each side, what the vanilla world reports ({@code raw}) and what the solver actually
     * sees once our own output is suppressed ({@code solver}). A {@code raw} of 15 with a
     * {@code solver} of 0 means the value was our own signal being read back; a {@code solver} of 15
     * names a real block next door, and the line then says which one.
     */
    private static int probe(CommandContext<CommandSourceStack> ctx, BlockPos pos) {
        ServerLevel level = level(ctx);
        InnerRedstoneNode node = InnerRedstoneStore.get(level).get(pos);
        feedback(ctx, header("solver probe @ " + pos.toShortString()));
        feedback(ctx, Component.literal(node == null || node.isEmpty()
                        ? "  (no component here - probing what the world pushes in)"
                        : "  " + node.slot().describe())
                .withStyle(ChatFormatting.WHITE));

        for (InnerRedstoneNetwork.ProbeSide side : InnerRedstoneNetwork.probe(level, pos)) {
            String text = "  " + side.direction().getName() + ": " + side.block().getBlock()
                    + (side.holdsComponent() ? " [component]" : "")
                    + "  world=" + side.rawSignal() + " solver=" + side.solverSignal()
                    + " inner=" + side.innerSignal()
                    + (side.reads() ? " reads" : "") + (side.emits() ? " emits" : "");
            ChatFormatting colour = side.solverSignal() > 0 || side.innerSignal() > 0
                    ? ChatFormatting.GREEN
                    : ChatFormatting.DARK_GRAY;
            feedback(ctx, Component.literal(text).withStyle(colour));
        }
        return 1;
    }

    private static int clear(CommandContext<CommandSourceStack> ctx, BlockPos pos) {
        InnerRedstoneStore store = InnerRedstoneStore.get(level(ctx));
        InnerRedstoneNode removed = store.remove(pos);
        boolean had = removed != null && !removed.isEmpty();
        if (had) {
            HostSync.broadcastChange(level(ctx), pos, false);
        }
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
