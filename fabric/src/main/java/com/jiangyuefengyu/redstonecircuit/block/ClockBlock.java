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
import com.jiangyuefengyu.redstonecircuit.logic.ClockRule;
import com.jiangyuefengyu.redstonecircuit.logic.DeviceKind;
import com.jiangyuefengyu.redstonecircuit.network.RCNetwork;
import com.jiangyuefengyu.redstonecircuit.logic.NoWeakPowerConduction;
import com.mojang.serialization.MapCodec;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.Vec3;

/**
 * 时钟源: the one device that answers to nothing but the world clock. 正面 is where the player stood and
 * 背面 is where it emits; nothing reaches it, and {@link ClockRule} is a function of the period, the
 * strength and the world's own tick. The answer lives in {@link #LIT}, and 秒/tick/强度 live in the level's
 * {@code InnerRedstoneStore} - in block state they would be millions of states, and beside the block they
 * travel with a captured structure. It is driven by the level's own tick pass and the plot-cell walk, never
 * by a scheduled tick of its own: a block does not tick itself in a game test, and a plot's chunks do not tick.
 */
public class ClockBlock extends RcPlateBlock implements NoWeakPowerConduction {

    public static final MapCodec<ClockBlock> CODEC = simpleCodec(ClockBlock::new);

    /**
     * 正在输出 - true for the two ticks of every period in which 背面 answers the set strength. This one
     * boolean is the whole of the answer; no answer cache is kept beside this block.
     */
    public static final BooleanProperty LIT = BlockStateProperties.LIT;

    public ClockBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(LIT, false));
    }

    @Override
    protected MapCodec<ClockBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(LIT);
    }

    /** A clock is a source: consumers have to ask it, and {@link #getSignal} is where they ask. */
    @Override
    protected boolean isSignalSource(BlockState state) {
        return true;
    }

    /**
     * 背面 only, exactly as 控制器 and 红石变压器 have it: 正面 and the two sides answer 0. The number is the
     * last announced answer rather than a fresh reading of the clock, and the strength comes from the
     * settings beside the block.
     */
    @Override
    protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction side) {
        if (side.getOpposite() != outputFace(state)) {
            return 0;
        }
        return state.getValue(LIT) ? settingAt(level, pos).strength() : 0;
    }



    /**
     * This clock's three numbers, from the store beside it, or the default when there is nothing stored.
     * Server side only: a client has no store, so a client asking this gets the default.
     */
    public static ClockRule.Setting settingAt(BlockGetter level, BlockPos pos) {
        if (level instanceof ServerLevel serverLevel) {
            InnerRedstoneStore store = InnerRedstoneStore.get(serverLevel);
            ClockRule.Setting setting = store == null ? null : store.clockAt(pos);
            if (setting != null) {
                return setting;
            }
        }
        return ClockRule.Setting.DEFAULT;
    }

    // ------------------------------------------------------------------ reading --

    /**
     * Everything the screen shows, read at one position in one go: the three numbers the player set, what
     * 背面 answers right now (0 between pulses), and how many ticks until the output next changes.
     */
    public record Readout(ClockRule.Setting setting, int power, int ticksToEdge) {
    }

    /** The state, the settings and the world clock, as the screen shows them. */
    public static Readout readout(ServerLevel level, BlockPos pos, BlockState state) {
        ClockRule.Setting setting = settingAt(level, pos);
        boolean on = state.getValue(LIT);
        return new Readout(setting, on ? setting.strength() : 0,
                ClockRule.ticksToEdge(level.getGameTime(), setting.period()));
    }

    // ---------------------------------------------------------------- answering --

    /**
     * Derives the answer again and tells the world if it moved - one edge at a time. Called from its own
     * scheduled tick, its placement, an edit of its settings and the plot-cell walk; the comparison is
     * against {@link #LIT}, which <em>is</em> the last answer, so an unchanged plot cell writes nothing.
     */
    public static void announce(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof ClockBlock)) {
            return;
        }
        ClockRule.Setting setting = settingAt(level, pos);
        boolean on = ClockRule.isOn(level.getGameTime(), setting.period());
        if (state.getValue(LIT) == on) {
            return;
        }
        state = state.setValue(LIT, on);
        level.setBlock(pos, state, Block.UPDATE_CLIENTS);
        wakeOutput(level, pos, state, 0, on ? setting.strength() : 0, "clock");
    }

    /**
     * Asks every clock standing in this level what it is answering now - the one driver this device has.
     * Called once per level tick, before the solver's own pass; the positions come from the settings table
     * itself, which is exactly the set of clocks this level knows about. An unloaded position is skipped.
     */
    public static void tickStanding(ServerLevel level) {
        InnerRedstoneStore store = InnerRedstoneStore.get(level);
        if (store == null || store.clockCount() == 0) {
            return;
        }
        for (long key : store.clockKeys()) {
            BlockPos pos = BlockPos.of(key);
            if (level.isLoaded(pos) && level.getBlockState(pos).getBlock() instanceof ClockBlock) {
                announce(level, pos);
            }
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

    /**
     * Gives a clock that has no settings the default ones. A capture carries a clock's settings only when
     * there is an entry, so a clock placed by hand must have one; a clock arriving from a payload is given
     * its real ones a moment later.
     */
    private static void keepASetting(ServerLevel level, BlockPos pos) {
        InnerRedstoneStore store = InnerRedstoneStore.get(level);
        if (store.clockAt(pos) == null) {
            store.putClock(pos, ClockRule.Setting.DEFAULT);
        }
    }

    /** A clock is gone: its settings go with it. */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState,
                            boolean movedByPiston) {
        super.onRemove(state, level, pos, newState, movedByPiston);
        if (level instanceof ServerLevel serverLevel && !state.is(newState.getBlock())) {
            InnerRedstoneStore.get(serverLevel).forgetClock(pos);
        }
    }

    /**
     * A physicalised clock, asked again by the walk that already visits every plot cell - called from the
     * product driver, which is bounded and cursored. A plot's chunks are not the level's ticking chunks, so
     * this walk is what turns a riding clock's edges over; it schedules nothing.
     */
    public static void refreshCarried(ServerLevel level, BlockPos localPos, BlockState state) {
        if (!(state.getBlock() instanceof ClockBlock)) {
            return;
        }
        announce(level, localPos);
    }

    // ------------------------------------------------------------------- screen --

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip,
                                TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        RcTooltips.append(tooltip, flag, "block.redstonecircuit.clock.tip",
                RcTooltips.detail("block.redstonecircuit.clock.tip.detail"));
    }

    /**
     * Writes the three numbers beside the block and answers with the new state of the world; a new period
     * re-phases the clock against the world clock rather than restarting a countdown, so the answer is
     * derived again immediately. Returns whether the stored setting changed.
     */
    public static boolean applySettings(ServerLevel level, BlockPos pos, ClockRule.Setting setting) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof ClockBlock) || setting == null) {
            return false;
        }
        // Every setting that reaches the store goes through the range check first, as it does in 红石变压器.
        ClockRule.Setting wanted = ClockRule.Setting.of(setting.seconds(), setting.ticks(), setting.strength());
        InnerRedstoneStore store = InnerRedstoneStore.get(level);
        ClockRule.Setting before = store.clockAt(pos);
        store.putClock(pos, wanted);
        announce(level, pos);
        return !wanted.equals(before);
    }

    @Override
    public void sendView(ServerPlayer player, ServerLevel level, BlockPos pos, boolean open) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof ClockBlock)) {
            return;
        }
        Readout readout = readout(level, pos, state);
        ServerPlayNetworking.send(player, new RCNetwork.DeviceViewPayload(pos, open,
                DeviceKind.CLOCK.ordinal(), readout.setting().seconds(), readout.setting().ticks(),
                readout.setting().strength(), 0, readout.power(), readout.ticksToEdge()));
    }

    /**
     * The screen's changes, and the server's answer to them: every number is checked before anything is
     * written, and a refusal answers with the unchanged view. A number out of range is refused rather than
     * clamped, because 0 秒 0 tick is a real setting (the off switch).
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
        if (!(state.getBlock() instanceof ClockBlock)) {
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
            ClockRule.Setting wanted = wanted(payload.a(), payload.b(), payload.c());
            if (wanted != null) {
                applySettings(level, pos, wanted);
            }
        }
        sendView(player, level, pos, false);
    }

    /** The three numbers a payload carries, or {@code null} when any of them is not a setting this device has. */
    @Nullable
    public static ClockRule.Setting wanted(int seconds, int ticks, int strength) {
        if (!ClockRule.isSeconds(seconds) || !ClockRule.isTicks(ticks) || !ClockRule.isStrength(strength)) {
            return null;
        }
        return new ClockRule.Setting(seconds, ticks, strength);
    }

    /** Where a device is drawn, and whether the player is close enough to it to be editing it. */
    private static boolean tooFar(ServerPlayer player, ServerLevel level, BlockPos pos) {
        BlockPos drawn = ProductFrame.carriedAt(level, pos).worldPosOf(pos);
        return player.getEyePosition().distanceToSqr(Vec3.atCenterOf(drawn)) > RANGE * RANGE;
    }

    // ------------------------------------------------------------- 可放入方块内 --

    /**
     * The same screen, for a clock stored inside a host block. The address is the coordinate the click
     * arrived at - the host's world position and its <b>plot</b> coordinate inside a structure - and the
     * lookup is {@link RcPlateBlock#hostSlot}. 时钟源 has no input face, so what the screen shows beside
     * the settings is the component's own output ({@code slot.power}), the number the solver last left there.
     */
    public static void sendHostView(ServerPlayer player, ServerLevel level, BlockPos host, boolean open) {
        Slot slot = hostSlot(level, host, ComponentType.CLOCK);
        if (slot == null) {
            // A diagnostic rather than silence: "点不开" cannot be told apart from "the click never arrived".
            if (RCConfig.debugLog()) {
                CarriedBlockRemoval.Hit hit = CarrierStore.hitAt(level, host);
                RCConfig.LOGGER.info("[redstonecircuit] clock screen at {}: world store={} carried index={}"
                                + " resolver={}", host.toShortString(),
                        InnerRedstoneStore.get(level).slotAt(host) != null,
                        CarrierStore.slotAt(level, host) != null,
                        hit == null ? "-" : hit.carrier().shortId() + " local=" + hit.localPos().toShortString());
            }
            return;
        }
        ClockRule.Setting setting = slot.clock;
        ServerPlayNetworking.send(player, new RCNetwork.DeviceViewPayload(host, open,
                DeviceKind.CLOCK.ordinal(), setting.seconds(), setting.ticks(), setting.strength(), 0,
                slot.power, ClockRule.ticksToEdge(level.getGameTime(), setting.period())));
    }

    /** The stored clock's changes: checked, written, re-solved, and told to the client. */
    public static void applyHostEdit(ServerPlayer player, RCNetwork.DeviceEditPayload payload) {
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        BlockPos host = payload.pos();
        if (!level.isLoaded(host)) {
            return;
        }
        Slot slot = hostSlot(level, host, ComponentType.CLOCK);
        if (slot == null || tooFar(player, level, host)) {
            return;
        }
        if (!mayAnswer(level, player)) {
            return;
        }
        if (payload.verb() == RCNetwork.DeviceEditPayload.Verb.SET) {
            ClockRule.Setting wanted = wanted(payload.a(), payload.b(), payload.c());
            if (wanted != null) {
                applyHostSettings(level, host, slot, wanted);
            }
        }
        sendHostView(player, level, host, false);
    }

    /**
     * Writes the three numbers onto the stored component and lets the network know. The answer is not
     * written here - it is a function of the world clock, and the next pass of the tick that owns it
     * derives it - so what this must do is make that pass happen now.
     */
    public static boolean applyHostSettings(ServerLevel level, BlockPos host, Slot slot,
                                            ClockRule.Setting setting) {
        if (slot == null || slot.type != ComponentType.CLOCK || setting == null) {
            return false;
        }
        ClockRule.Setting before = slot.clock;
        ClockRule.Setting wanted = ClockRule.Setting.of(setting.seconds(), setting.ticks(), setting.strength());
        slot.clock = wanted;
        announceHostSettings(level, host, slot);
        return !wanted.equals(before);
    }
}
