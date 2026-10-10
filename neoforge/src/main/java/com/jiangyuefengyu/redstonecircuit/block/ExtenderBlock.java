package com.jiangyuefengyu.redstonecircuit.block;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.RcTooltips;
import com.jiangyuefengyu.redstonecircuit.carrier.CarriedBlockRemoval;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierStore;
import com.jiangyuefengyu.redstonecircuit.carrier.ProductFrame;
import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore;
import com.jiangyuefengyu.redstonecircuit.data.Slot;
import com.jiangyuefengyu.redstonecircuit.logic.BlockAnswerCache;
import com.jiangyuefengyu.redstonecircuit.logic.DeviceKind;
import com.jiangyuefengyu.redstonecircuit.logic.ExtenderDeadlines;
import com.jiangyuefengyu.redstonecircuit.logic.ExtenderRule;
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
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 信号延长器: 正面读一个面, 背面答出同一个强度, 并在输入走了之后继续答设定时长.
 *
 * <p>The rule is {@link ExtenderRule} and the two halves of the device are split the way the code needs them: while
 * something arrives at 正面 the answer is combinational ({@link #announce} derives it, and a neighbour change re-derives
 * it immediately), and the hold is advanced by the level's own tick ({@link #tickStanding}) because only time ends it.
 *
 * <p>The settings (秒 and 刻) live in the level's {@link InnerRedstoneStore} - in block state they would be 576,160
 * states - and the hold's deadline beside the block in {@link ExtenderDeadlines}; the answer lives in
 * {@link BlockAnswerCache}, and {@link #LIT} in the state because the model has to pick a texture. Heartbeat and
 * physicalised half both go through {@link #announce}, so there is one place where an answer is derived.
 */
public class ExtenderBlock extends RcPlateBlock {

    public static final MapCodec<ExtenderBlock> CODEC = simpleCodec(ExtenderBlock::new);

    /** 正在输出 - true while 背面 answers anything at all: the display runs and the dot is lit. */
    public static final BooleanProperty LIT = BlockStateProperties.LIT;

    public ExtenderBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(LIT, false));
    }

    @Override
    protected MapCodec<ExtenderBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(LIT);
    }

    /** An extender is a source: consumers have to ask it, and {@link #getSignal} is where they ask. */
    @Override
    protected boolean isSignalSource(BlockState state) {
        return true;
    }

    /** 背面 only, as 控制器, 红石变压器 and 时钟源 have it: 正面 and the two sides answer 0. */
    @Override
    protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction side) {
        if (side.getOpposite() != outputFace(state)) {
            return 0;
        }
        return answerAt(level, pos, state);
    }

    /** A device does not carry power that is not its own, for the reason 控制器 and 红石变压器 give. */
    @Override
    public boolean shouldCheckWeakPower(BlockState state, SignalGetter level, BlockPos pos, Direction side) {
        return false;
    }

    /** A neighbour moved: the input may have moved with it, so the answer is derived again - in the same tick. */
    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighbour,
                                   BlockPos fromPos, boolean movedByPiston) {
        super.neighborChanged(state, level, pos, neighbour, fromPos, movedByPiston);
        if (level instanceof ServerLevel serverLevel) {
            announce(serverLevel, pos);
        }
    }

    /** Placed - by a player, by a piston, by a structure: it gets a setting and its first answer. */
    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState,
                           boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (level instanceof ServerLevel serverLevel) {
            keepASetting(serverLevel, pos);
            announce(serverLevel, pos);
        }
    }

    /** An extender is gone: its settings, its hold and its answer go with it. */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState,
                            boolean movedByPiston) {
        super.onRemove(state, level, pos, newState, movedByPiston);
        if (level instanceof ServerLevel serverLevel && !state.is(newState.getBlock())) {
            InnerRedstoneStore.get(serverLevel).forgetExtender(pos);
            ExtenderDeadlines.forget(serverLevel, pos);
            BlockAnswerCache.forget(serverLevel, pos);
        }
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip,
                                TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        RcTooltips.append(tooltip, flag, "block.redstonecircuit.extender.tip",
                RcTooltips.detail("block.redstonecircuit.extender.tip.detail"));
    }

    /**
     * This extender's two numbers, from the store beside it, or the default when there is nothing stored. Server side
     * only: a client has no store, so a client asking this gets the default.
     */
    public static ExtenderRule.Setting settingAt(BlockGetter level, BlockPos pos) {
        if (level instanceof ServerLevel serverLevel) {
            InnerRedstoneStore store = InnerRedstoneStore.get(serverLevel);
            ExtenderRule.Setting setting = store == null ? null : store.extenderAt(pos);
            if (setting != null) {
                return setting;
            }
        }
        return ExtenderRule.Setting.DEFAULT;
    }

    // ------------------------------------------------------------------ reading --

    /**
     * Everything the screen shows, read at one position in one go: the two numbers the player set, what is arriving at
     * 正面 right now, what 背面 is answering, and how much of the hold is left.
     */
    public record Readout(ExtenderRule.Setting setting, int reading, int power, int ticksLeft) {
    }

    /** The state, the settings and the world, as the screen shows them. */
    public static Readout readout(Level level, BlockPos pos, BlockState state) {
        int ticksLeft = level instanceof ServerLevel serverLevel
                ? ExtenderRule.ticksLeft(level.getGameTime(), ExtenderDeadlines.get(serverLevel, pos))
                : 0;
        return new Readout(settingAt(level, pos), reading(level, pos, state), answerAt(level, pos, state),
                ticksLeft);
    }

    /** The strength arriving at 正面, read in both frames - see {@link RcPlateBlock#readingInto}. */
    public static int reading(Level level, BlockPos pos, BlockState state) {
        return readingInto(level, pos, state.getValue(FACING));
    }

    // ----------------------------------------------------------------- answering --

    /**
     * The answer, from the cache beside the block; see {@link BlockAnswerCache} and {@code TransformerBlock#answerAt}.
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
        return computeAnswer(realLevel, pos, state);
    }

    /**
     * Derives the answer from the front, the store and the hold.
     *
     * <p>Asked on demand for a block nobody has announced yet - and on a client, where there is no store and no
     * deadline. A client therefore answers the input while it is live and nothing during a hold: what a hold is holding
     * is the answer, and the answer is deliberately not in the block state. The server is the one that decides.
     */
    private static int computeAnswer(Level level, BlockPos pos, BlockState state) {
        int input = reading(level, pos, state);
        if (input > 0) {
            return input;
        }
        if (!(level instanceof ServerLevel serverLevel)) {
            return 0;
        }
        long now = level.getGameTime();
        int held = Math.max(0, BlockAnswerCache.get(serverLevel, pos));
        return ExtenderRule.answer(now, 0, held, ExtenderDeadlines.get(serverLevel, pos));
    }

    /**
     * Derives the answer again and keeps it where the queries will find it: the cache, the hold's deadline beside the
     * block, and {@link #LIT} in the state (the model needs it). Called from a neighbour change, its placement, an edit
     * of its settings, its own tick and the plot-cell walk.
     *
     * <p>While something arrives at 正面 the deadline is pushed to {@code now + 设定时长} again on every pass, which is
     * the whole of "输入期间一直输出, 停下之后再延长设定时长": a hold is not a countdown but a moment, so a missed tick
     * cannot stretch it either.
     */
    public static void announce(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof ExtenderBlock)) {
            return;
        }
        long now = level.getGameTime();
        int input = reading(level, pos, state);
        ExtenderRule.Setting setting = settingAt(level, pos);
        long deadline = ExtenderRule.deadline(now, input, setting.holdTicks(), ExtenderDeadlines.get(level, pos));
        ExtenderDeadlines.put(level, pos, deadline);
        // The strength a hold keeps is the answer the device was already giving, which is why the cache is read
        // before it is written. Nothing answered yet (ABSENT) means there is nothing to keep.
        int before = BlockAnswerCache.get(level, pos);
        int wanted = ExtenderRule.answer(now, input, Math.max(0, before), deadline);
        BlockAnswerCache.put(level, pos, wanted);
        BlockState announced = state;
        if (state.getValue(LIT) != (wanted > 0)) {
            announced = state.setValue(LIT, wanted > 0);
            level.setBlock(pos, announced, Block.UPDATE_CLIENTS);
        }
        if (before == wanted) {
            return;
        }
        wakeOutput(level, pos, announced, input, wanted, "extender");
    }

    /**
     * Asks every extender standing in this level what it is answering now - the one driver a hold has. Called once per
     * level tick, before the solver's own pass; the positions come from the settings table itself, which is exactly the
     * set of extenders this level knows about. An unloaded position is skipped.
     */
    public static void tickStanding(ServerLevel level) {
        InnerRedstoneStore store = InnerRedstoneStore.get(level);
        if (store == null || store.extenderKeys().isEmpty()) {
            return;
        }
        for (long key : store.extenderKeys()) {
            BlockPos pos = BlockPos.of(key);
            if (level.isLoaded(pos) && level.getBlockState(pos).getBlock() instanceof ExtenderBlock) {
                announce(level, pos);
            }
        }
    }

    /**
     * Gives an extender that has no settings the default ones. A capture carries an extender's settings only when there
     * is an entry, so one placed by hand must have an entry; one arriving from a payload is given its real settings a
     * moment later.
     */
    private static void keepASetting(ServerLevel level, BlockPos pos) {
        InnerRedstoneStore store = InnerRedstoneStore.get(level);
        if (store.extenderAt(pos) == null) {
            store.putExtender(pos, ExtenderRule.Setting.DEFAULT);
        }
    }

    /**
     * A physicalised extender, asked again by the walk that already visits every plot cell - called from the product
     * driver, which is bounded and cursored. A plot's chunks are not the level's ticking chunks, so this walk is what
     * turns a riding extender's edges over; it schedules nothing.
     */
    public static void refreshCarried(ServerLevel level, BlockPos localPos, BlockState state) {
        if (!(state.getBlock() instanceof ExtenderBlock)) {
            return;
        }
        announce(level, localPos);
    }

    // ------------------------------------------------------------------- screen --

    /**
     * Writes the two numbers beside the block and answers with the new state of the world. A setting changed while a
     * hold is running does not move that hold's deadline: the hold ends when the input that started it said it would.
     * Returns whether the stored setting changed.
     */
    public static boolean applySettings(ServerLevel level, BlockPos pos, ExtenderRule.Setting setting) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof ExtenderBlock) || setting == null) {
            return false;
        }
        // Every setting that reaches the store goes through the range check first, as it does in the other devices.
        ExtenderRule.Setting wanted = ExtenderRule.Setting.of(setting.seconds(), setting.ticks());
        InnerRedstoneStore store = InnerRedstoneStore.get(level);
        ExtenderRule.Setting before = store.extenderAt(pos);
        store.putExtender(pos, wanted);
        announce(level, pos);
        return !wanted.equals(before);
    }

    @Override
    public void sendView(ServerPlayer player, ServerLevel level, BlockPos pos, boolean open) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof ExtenderBlock)) {
            return;
        }
        Readout readout = readout(level, pos, state);
        PacketDistributor.sendToPlayer(player, new RCNetwork.DeviceViewPayload(pos, open,
                DeviceKind.EXTENDER.ordinal(), readout.setting().seconds(), readout.setting().ticks(),
                readout.reading(), 0, readout.power(), readout.ticksLeft()));
    }

    /**
     * The screen's changes, and the server's answer to them: every number is checked before anything is written, and a
     * refusal answers with the unchanged view. A number out of range is refused rather than clamped, because 0 秒 0 刻
     * is a real setting (the one that does not extend anything).
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
        if (!(state.getBlock() instanceof ExtenderBlock)) {
            return;
        }
        // The distance is measured where the block is DRAWN, or a physicalised device's screen could never be used.
        if (tooFar(player, level, pos)) {
            return;
        }
        if (!mayAnswer(level, player)) {
            return;
        }
        if (payload.verb() == RCNetwork.DeviceEditPayload.Verb.SET) {
            ExtenderRule.Setting wanted = wanted(payload.a(), payload.b());
            if (wanted != null) {
                applySettings(level, pos, wanted);
            }
        }
        sendView(player, level, pos, false);
    }

    /** The two numbers a payload carries, or {@code null} when either is not a setting this device has. */
    @Nullable
    public static ExtenderRule.Setting wanted(int seconds, int ticks) {
        if (!ExtenderRule.isSeconds(seconds) || !ExtenderRule.isTicks(ticks)) {
            return null;
        }
        return new ExtenderRule.Setting(seconds, ticks);
    }

    /** Where a device is drawn, and whether the player is close enough to it to be editing it. */
    private static boolean tooFar(ServerPlayer player, ServerLevel level, BlockPos pos) {
        BlockPos drawn = ProductFrame.carriedAt(level, pos).worldPosOf(pos);
        return player.getEyePosition().distanceToSqr(Vec3.atCenterOf(drawn)) > RANGE * RANGE;
    }

    // ------------------------------------------------------------- 可放入方块内 --

    /**
     * The same screen, for an extender stored inside a host block. The address is the coordinate the click arrived at -
     * the host's world position and its <b>plot</b> coordinate inside a structure - and the lookup is
     * {@link RcPlateBlock#hostSlot}. What the screen shows beside the settings is the component's own output
     * ({@code slot.power}) and what is left of its hold.
     */
    public static void sendHostView(ServerPlayer player, ServerLevel level, BlockPos host, boolean open) {
        Slot slot = hostSlot(level, host, ComponentType.EXTENDER);
        if (slot == null) {
            // A diagnostic rather than silence: "点不开" cannot be told apart from "the click never arrived".
            if (RCConfig.debugLog()) {
                CarriedBlockRemoval.Hit hit = CarrierStore.hitAt(level, host);
                RCConfig.LOGGER.info("[redstonecircuit] extender screen at {}: world store={} carried index={}"
                                + " resolver={}", host.toShortString(),
                        InnerRedstoneStore.get(level).slotAt(host) != null,
                        CarrierStore.slotAt(level, host) != null,
                        hit == null ? "-" : hit.carrier().shortId() + " local=" + hit.localPos().toShortString());
            }
            return;
        }
        PacketDistributor.sendToPlayer(player, new RCNetwork.DeviceViewPayload(host, open,
                DeviceKind.EXTENDER.ordinal(), slot.extender.seconds(), slot.extender.ticks(),
                hostReading(level, host, slot), 0, slot.power,
                ExtenderRule.ticksLeft(level.getGameTime(), slot.extenderDeadline)));
    }

    /** The stored extender's changes: checked, written, re-solved, and told to the client. */
    public static void applyHostEdit(ServerPlayer player, RCNetwork.DeviceEditPayload payload) {
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        BlockPos host = payload.pos();
        if (!level.isLoaded(host)) {
            return;
        }
        Slot slot = hostSlot(level, host, ComponentType.EXTENDER);
        if (slot == null || tooFar(player, level, host)) {
            return;
        }
        if (!mayAnswer(level, player)) {
            return;
        }
        if (payload.verb() == RCNetwork.DeviceEditPayload.Verb.SET) {
            ExtenderRule.Setting wanted = wanted(payload.a(), payload.b());
            if (wanted != null) {
                applyHostSettings(level, host, slot, wanted);
            }
        }
        sendHostView(player, level, host, false);
    }

    /**
     * Writes the two numbers onto the stored component and lets the network know. The hold is not touched: it belongs to
     * whichever pass owns the world the component lives in.
     */
    public static boolean applyHostSettings(ServerLevel level, BlockPos host, Slot slot,
                                            ExtenderRule.Setting setting) {
        if (slot == null || slot.type != ComponentType.EXTENDER || setting == null) {
            return false;
        }
        ExtenderRule.Setting before = slot.extender;
        ExtenderRule.Setting wanted = ExtenderRule.Setting.of(setting.seconds(), setting.ticks());
        slot.extender = wanted;
        announceHostSettings(level, host, slot);
        return !wanted.equals(before);
    }
}
