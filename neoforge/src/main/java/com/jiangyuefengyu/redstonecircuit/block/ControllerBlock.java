package com.jiangyuefengyu.redstonecircuit.block;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.RcTooltips;
import com.jiangyuefengyu.redstonecircuit.carrier.CarriedBlockRemoval;
import com.jiangyuefengyu.redstonecircuit.carrier.CarriedHost;
import com.jiangyuefengyu.redstonecircuit.carrier.CarriedHostIndex;
import com.jiangyuefengyu.redstonecircuit.carrier.CarriedHosts;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierId;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierStore;
import com.jiangyuefengyu.redstonecircuit.carrier.ProductFrame;
import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore;
import com.jiangyuefengyu.redstonecircuit.data.Slot;
import com.jiangyuefengyu.redstonecircuit.logic.BlockAnswerCache;
import com.jiangyuefengyu.redstonecircuit.logic.ControllerRule;
import com.jiangyuefengyu.redstonecircuit.logic.DeviceKind;
import com.jiangyuefengyu.redstonecircuit.logic.InnerRedstoneNetwork;
import com.jiangyuefengyu.redstonecircuit.logic.PowerSolver;
import com.jiangyuefengyu.redstonecircuit.network.HostSync;
import com.jiangyuefengyu.redstonecircuit.network.RCNetwork;
import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.SignalGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 控制器: one comparison, four settings, and an output strength of its own choosing. The comparison is
 * {@link ControllerRule} - a table of numbers with no level in it - and this class is the other half: a
 * plate with 正面 to read and 背面 to answer on, a state to carry the four settings, and a screen to set them.
 *
 * <p>The default (大于 + 等于 + 档位 1 + 输出 15, "any real signal answers 15") is the setting that works out of
 * the box: 档位 0 with 等于 would be satisfied by nothing at all. The settings live in the block state because
 * they then travel wherever the block travels - capture and release, a Sable plot, a piston, {@code /clone}.
 */
public class ControllerBlock extends RcPlateBlock {

    public static final MapCodec<ControllerBlock> CODEC = simpleCodec(ControllerBlock::new);

    /** 主档位: 大于 or 小于. */
    public static final EnumProperty<ControllerRule.Main> MAIN =
            EnumProperty.create("main", ControllerRule.Main.class);

    /** 副档位: 等于 / 不等于 / 只等于 / 只不等于. */
    public static final EnumProperty<ControllerRule.Sub> SUB =
            EnumProperty.create("sub", ControllerRule.Sub.class);

    /** 输入档位: the strength the input is compared against, 0-15. */
    public static final IntegerProperty X = IntegerProperty.create("x", 0, ControllerRule.MAX);

    /** 输出强度: what 背面 answers while the condition holds, 1-15. */
    public static final IntegerProperty Y =
            IntegerProperty.create("y", ControllerRule.MIN_OUTPUT, ControllerRule.MAX);

    /** 上面那颗显示条: lit while the device is answering, which is what 控制器上面--亮 is drawn for. */
    public static final BooleanProperty LIT = BlockStateProperties.LIT;

    public ControllerBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(MAIN, ControllerRule.Main.GREATER)
                .setValue(SUB, ControllerRule.Sub.EQUAL)
                .setValue(X, 1)
                .setValue(Y, ControllerRule.MAX)
                .setValue(LIT, false));
    }

    @Override
    protected MapCodec<ControllerBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(MAIN, SUB, X, Y, LIT);
    }

    /** A controller is a source: consumers have to ask it, and {@link #getSignal} is where they ask. */
    @Override
    protected boolean isSignalSource(BlockState state) {
        return true;
    }

    /**
     * 背面 only, as the other plates have it: 正面 and the two sides answer 0. {@code side} is vanilla's
     * backwards parameter - it names the way the query travelled, from the asker towards this block - so the
     * answering face is {@code side.getOpposite()}, and 背面 is {@code FACING.getOpposite()}.
     */
    @Override
    protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction side) {
        if (side.getOpposite() != state.getValue(FACING).getOpposite()) {
            return 0;
        }
        return answerAt(level, pos, state);
    }

    /**
     * The answer, from the cache beside the block (see {@link BlockAnswerCache} for why it is not a state
     * property). One map read on the server, computing and storing on a miss; on the client there is no cache,
     * because a client's copy is whatever the last packet said and a cache could only disagree with the server.
     */
    public static int answerAt(BlockGetter level, BlockPos pos, BlockState state) {
        if (!(level instanceof Level realLevel)) {
            return 0;
        }
        if (realLevel instanceof ServerLevel serverLevel) {
            int cached = BlockAnswerCache.get(serverLevel, pos);
            if (cached != BlockAnswerCache.ABSENT) {
                return cached;
            }
        }
        int computed = computeAnswer(realLevel, pos, state);
        if (realLevel instanceof ServerLevel serverLevel) {
            BlockAnswerCache.put(serverLevel, pos, computed);
        }
        return computed;
    }

    /** Reads 正面 and runs the rule - the one place the answer is derived. */
    private static int computeAnswer(Level level, BlockPos pos, BlockState state) {
        return ControllerRule.answer(state.getValue(MAIN), state.getValue(SUB), reading(level, pos, state),
                state.getValue(X), state.getValue(Y));
    }

    /**
     * A device is not a block of stone: it does not carry power that is not its own. Were this true, a wire at
     * the front would charge the plate and the plate would re-emit that charge on <b>all six faces</b>,
     * including the back, where it would be read back as an input one step later.
     */
    @Override
    public boolean shouldCheckWeakPower(BlockState state, SignalGetter level, BlockPos pos, Direction side) {
        return false;
    }

    /** A neighbour moved: the input may have moved with it, so the answer is derived again. */
    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighbour,
                                   BlockPos fromPos, boolean movedByPiston) {
        super.neighborChanged(state, level, pos, neighbour, fromPos, movedByPiston);
        if (level instanceof ServerLevel serverLevel) {
            announce(serverLevel, pos);
        }
    }

    /** Placed (or put back by a piston): announced at once rather than one neighbour change later. */
    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState,
                           boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (level instanceof ServerLevel serverLevel) {
            announce(serverLevel, pos);
        }
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip,
                                TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        RcTooltips.append(tooltip, flag, "block.redstonecircuit.controller.tip",
                RcTooltips.detail("block.redstonecircuit.controller.tip.detail"));
    }

    // ------------------------------------------------------------------ reading --

    /**
     * Everything the screen shows, read at one position in one go: the four settings and the facing, the
     * strength standing at 正面 right now (0-15), and what 背面 is answering.
     */
    public record Readout(ControllerRule.Main main, ControllerRule.Sub sub, int x, int y, Direction facing,
                          int reading, int power) {
    }

    /** The state and the world around it, as the screen shows it. */
    public static Readout readout(Level level, BlockPos pos, BlockState state) {
        return new Readout(state.getValue(MAIN), state.getValue(SUB), state.getValue(X), state.getValue(Y),
                state.getValue(FACING), reading(level, pos, state), answerAt(level, pos, state));
    }

    /** The strength arriving at 正面, read in both frames - see {@link RcPlateBlock#readingInto}. */
    public static int reading(Level level, BlockPos pos, BlockState state) {
        return readingInto(level, pos, state.getValue(FACING));
    }

    // ----------------------------------------------------------------- answering --

    /**
     * Derives the answer again and keeps it where the queries will find it - the cache, not the state. Server
     * side only: an answer that did not move has nothing to say to anybody. What <b>is</b> written to the state
     * is {@link #LIT}, the display's own fact, so the one place that knows the answer is the one that sets it.
     */
    public static void announce(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof ControllerBlock)) {
            return;
        }
        int input = reading(level, pos, state);
        int wanted = ControllerRule.answer(state.getValue(MAIN), state.getValue(SUB), input,
                state.getValue(X), state.getValue(Y));
        int before = BlockAnswerCache.get(level, pos);
        BlockAnswerCache.put(level, pos, wanted);
        BlockState announced = state;
        if (state.getValue(LIT) != (wanted > 0)) {
            announced = state.setValue(LIT, wanted > 0);
            level.setBlock(pos, announced, Block.UPDATE_CLIENTS);
        }
        if (before == wanted) {
            return;
        }
        notifyOutput(level, pos, announced, input, wanted);
    }

    /** A device block is gone: its answer goes with it (see {@link BlockAnswerCache}). */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState,
                            boolean movedByPiston) {
        super.onRemove(state, level, pos, newState, movedByPiston);
        if (level instanceof ServerLevel serverLevel && !state.is(newState.getBlock())) {
            BlockAnswerCache.forget(serverLevel, pos);
        }
    }

    /** Wakes whatever stands at 背面 (see {@link RcPlateBlock#wakeOutput}); it only names the device in the log line. */
    private static void notifyOutput(ServerLevel level, BlockPos pos, BlockState state, int input, int power) {
        wakeOutput(level, pos, state, input, power, "controller");
    }

    /**
     * A physicalised controller, asked again by the walk that already visits it. Nothing reaches a plot cell
     * from outside the structure - a lever in the world wakes the world's six neighbours, where the machine is
     * only drawn - and the world read is live, so the <b>cached answer</b> is what goes stale and this re-derives it.
     */
    public static void refreshCarried(ServerLevel level, BlockPos localPos, BlockState state) {
        if (!(state.getBlock() instanceof ControllerBlock)) {
            return;
        }
        announce(level, localPos);
    }

    // ------------------------------------------------------------------- screen --

    /** The settings themselves: validated, written, and announced. */
    public static boolean applySettings(ServerLevel level, BlockPos pos, ControllerRule.Main main,
                                        ControllerRule.Sub sub, int x, int y) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof ControllerBlock)) {
            return false;
        }
        if (main == null || sub == null || !ControllerRule.isThreshold(x) || !ControllerRule.isOutput(y)) {
            return false;
        }
        BlockState wanted = state.setValue(MAIN, main).setValue(SUB, sub).setValue(X, x).setValue(Y, y);
        if (!wanted.equals(state)) {
            level.setBlock(pos, wanted, Block.UPDATE_CLIENTS);
        }
        announce(level, pos);
        return true;
    }

    @Override
    public void sendView(ServerPlayer player, ServerLevel level, BlockPos pos, boolean open) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof ControllerBlock)) {
            return;
        }
        Readout readout = readout(level, pos, state);
        PacketDistributor.sendToPlayer(player, new RCNetwork.DeviceViewPayload(pos, open,
                DeviceKind.CONTROLLER.ordinal(), readout.main().ordinal(), readout.sub().ordinal(),
                readout.x(), readout.y(), readout.power(), readout.reading()));
    }

    /**
     * The screen's changes, and the server's answer to them: one entry point for both verbs, because both end
     * the same way - the server reads the block and sends the screen what it now sees. A refusal answers with
     * the unchanged view, so a screen can never show a value the block does not have.
     */
    @Override
    public void applyEdit(ServerPlayer player, RCNetwork.DeviceEditPayload payload) {
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        BlockPos pos = payload.pos();
        if (!level.isLoaded(pos)) {
            return;
        }
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof ControllerBlock)) {
            return;
        }
        // The distance is measured where the block is DRAWN, or a physicalised device's screen could never be used.
        BlockPos drawn = ProductFrame.carriedAt(level, pos).worldPosOf(pos);
        if (player.getEyePosition().distanceToSqr(Vec3.atCenterOf(drawn)) > RANGE * RANGE) {
            return;
        }
        if (!mayAnswer(level, player)) {
            return;
        }
        if (payload.verb() == RCNetwork.DeviceEditPayload.Verb.SET) {
            ControllerRule.Main[] mains = ControllerRule.Main.values();
            ControllerRule.Sub[] subs = ControllerRule.Sub.values();
            ControllerRule.Main main = payload.a() >= 0 && payload.a() < mains.length ? mains[payload.a()]
                    : state.getValue(MAIN);
            ControllerRule.Sub sub = payload.b() >= 0 && payload.b() < subs.length ? subs[payload.b()]
                    : state.getValue(SUB);
            applySettings(level, pos, main, sub, payload.c(), payload.d());
        }
        sendView(player, level, pos, false);
    }

    // ------------------------------------------------------------- 可放入方块内 --

    /**
     * The same screen, for a controller stored inside a host block. The address is the coordinate the click
     * arrived at - the host block's own position in the world and its <b>plot</b> coordinate inside a
     * structure, the convention {@link RcPlateBlock} uses for a physicalised plate. A controller riding a moved
     * structure is not served here: the click on such a host is left to the class that owns the carrier record.
     */
    public static void sendHostView(ServerPlayer player, ServerLevel level, BlockPos host, boolean open) {
        Slot slot = hostSlot(level, host);
        if (slot == null) {
            // A diagnostic rather than silence: "点不开" cannot be told apart from "the click never reached this
            // method", and the resolver's own answer is printed beside the two stores so the halves can be compared.
            if (RCConfig.debugLog()) {
                CarriedBlockRemoval.Hit hit = CarrierStore.hitAt(level, host);
                RCConfig.LOGGER.info("[redstonecircuit] controller screen at {}: world store={} carried index={}"
                                + " resolver={}", host.toShortString(),
                        InnerRedstoneStore.get(level).slotAt(host) != null,
                        CarrierStore.slotAt(level, host) != null,
                        hit == null ? "-" : hit.carrier().shortId() + " local=" + hit.localPos().toShortString());
            }
            return;
        }
        ControllerRule.Setting setting = slot.controller;
        PacketDistributor.sendToPlayer(player, new RCNetwork.DeviceViewPayload(host, open,
                DeviceKind.CONTROLLER.ordinal(), setting.main().ordinal(), setting.sub().ordinal(),
                setting.threshold(), setting.output(), slot.power, hostReading(level, host, slot)));
    }

    /** The controller stored inside the block at {@code host}, or {@code null}; see {@link RcPlateBlock#hostSlot}. */
    @Nullable
    private static Slot hostSlot(ServerLevel level, BlockPos host) {
        return hostSlot(level, host, ComponentType.CONTROLLER);
    }

    /** The stored controller's changes: validated, written, re-solved, and told to the client. */
    public static void applyHostEdit(ServerPlayer player, RCNetwork.DeviceEditPayload payload) {
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        BlockPos host = payload.pos();
        if (!level.isLoaded(host)) {
            return;
        }
        Slot slot = hostSlot(level, host);
        if (slot == null) {
            return;
        }
        BlockPos drawn = ProductFrame.carriedAt(level, host).worldPosOf(host);
        if (player.getEyePosition().distanceToSqr(Vec3.atCenterOf(drawn)) > RANGE * RANGE) {
            return;
        }
        if (!mayAnswer(level, player)) {
            return;
        }
        if (payload.verb() == RCNetwork.DeviceEditPayload.Verb.SET) {
            ControllerRule.Main[] mains = ControllerRule.Main.values();
            ControllerRule.Sub[] subs = ControllerRule.Sub.values();
            ControllerRule.Main main = payload.a() >= 0 && payload.a() < mains.length ? mains[payload.a()]
                    : slot.controller.main();
            ControllerRule.Sub sub = payload.b() >= 0 && payload.b() < subs.length ? subs[payload.b()]
                    : slot.controller.sub();
            applyHostSettings(level, host, slot, new ControllerRule.Setting(main, sub, payload.c(), payload.d()));
        }
        sendHostView(player, level, host, false);
    }

    /**
     * Writes the four settings onto the stored component and re-derives its answer. A component lives in a
     * server-side store and the client draws a <b>copy</b> of it, so the change has to be broadcast; the two
     * notifications after it make the answer move in the same tick.
     */
    public static boolean applyHostSettings(ServerLevel level, BlockPos host, Slot slot,
                                            ControllerRule.Setting setting) {
        if (slot == null || slot.type != ComponentType.CONTROLLER || setting == null) {
            return false;
        }
        slot.controller = setting.clamped();
        // The shared sequence: a component's change has to reach its store, the carrier's copy of it and the clients.
        announceHostSettings(level, host, slot);
        return true;
    }
}
