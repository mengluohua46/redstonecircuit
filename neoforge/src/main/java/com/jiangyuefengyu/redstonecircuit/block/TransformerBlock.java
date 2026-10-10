package com.jiangyuefengyu.redstonecircuit.block;

import java.util.List;

import com.jiangyuefengyu.redstonecircuit.InnerRedstoneInteraction;
import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.RcTooltips;
import com.jiangyuefengyu.redstonecircuit.carrier.CarriedBlockRemoval;
import com.jiangyuefengyu.redstonecircuit.carrier.CarriedHostIndex;
import com.jiangyuefengyu.redstonecircuit.carrier.CarriedHosts;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierId;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierStore;
import com.jiangyuefengyu.redstonecircuit.carrier.ProductFrame;
import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore;
import com.jiangyuefengyu.redstonecircuit.data.Slot;
import com.jiangyuefengyu.redstonecircuit.logic.BlockAnswerCache;
import com.jiangyuefengyu.redstonecircuit.logic.DeviceKind;
import com.jiangyuefengyu.redstonecircuit.logic.InnerRedstoneNetwork;
import com.jiangyuefengyu.redstonecircuit.logic.TransformerRule;
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
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 红石变压器: whatever arrives at 正面, 背面 answers the strength the player set. The rule itself is
 * {@link TransformerRule}; everything here is the other half - a plate with a front to read, a back to
 * answer on, a state for the one setting, and a screen to set it.
 *
 * <p>The setting and {@link #LIT} live in the block state, because anything that can move a block carries
 * block state with it. The <b>answer</b> does not: it is cached beside the block, in {@link BlockAnswerCache}.
 */
public class TransformerBlock extends RcPlateBlock {

    public static final MapCodec<TransformerBlock> CODEC = simpleCodec(TransformerBlock::new);

    /** 输出强度: the strength 背面 answers with while anything reaches 正面. */
    public static final IntegerProperty SETTING =
            IntegerProperty.create("setting", TransformerRule.MIN, TransformerRule.MAX);

    /** The display bar: lit while the device is answering, which is what 红石变压器-*-亮 are drawn for. */
    public static final BooleanProperty LIT = BlockStateProperties.LIT;

    public TransformerBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(SETTING, TransformerRule.DEFAULT)
                .setValue(LIT, false));
    }

    @Override
    protected MapCodec<TransformerBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(SETTING, LIT);
    }

    /** A transformer is a source: consumers have to ask it, and {@link #getSignal} is where they ask. */
    @Override
    protected boolean isSignalSource(BlockState state) {
        return true;
    }

    /**
     * The 背面 only, exactly as 控制器 and 传输门 have it: the front and the two sides answer 0. {@code side} is
     * vanilla's backwards parameter - it names the way the query travelled, from the asker towards this block.
     */
    @Override
    protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction side) {
        if (side.getOpposite() != outputFace(state)) {
            return 0;
        }
        return answerAt(level, pos, state);
    }

    /** The answer, from the cache beside the block; see {@link BlockAnswerCache} and {@code ControllerBlock#answerAt}. */
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

    /** Reads 正面 and runs the transformer rule - the one place the answer is derived. */
    private static int computeAnswer(Level level, BlockPos pos, BlockState state) {
        return TransformerRule.answer(reading(level, pos, state), state.getValue(SETTING));
    }

    /**
     * <b>A device is not a block of stone - it does not carry power that is not its own.</b> With the default,
     * vanilla's signal lookup would add the direct signal to it: a wire standing at the front charges the plate,
     * which then re-emits that charge out of its back and reads it back as an input.
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
        RcTooltips.append(tooltip, flag, "block.redstonecircuit.transformer.tip",
                RcTooltips.detail("block.redstonecircuit.transformer.tip.detail"));
    }

    // ------------------------------------------------------------------ reading --

    /**
     * Everything the screen shows, read at one position in one go: the setting (1-15), the strength arriving at
     * 正面 right now (0-15), and the strength 背面 is answering with - 0 when nothing reaches the front.
     */
    public record Readout(int setting, int reading, int power) {
    }

    /** The state and the world around it, as the screen shows it. */
    public static Readout readout(Level level, BlockPos pos, BlockState state) {
        return new Readout(state.getValue(SETTING), reading(level, pos, state), answerAt(level, pos, state));
    }

    /** The strength arriving at 正面, read in both frames - see {@link RcPlateBlock#readingInto}. */
    public static int reading(Level level, BlockPos pos, BlockState state) {
        return readingInto(level, pos, state.getValue(FACING));
    }

    // ----------------------------------------------------------------- answering --

    /**
     * Derives the answer again and keeps it where the queries will find it: the cache, not the state. Server
     * side only, and the comparison is why the answer is a cache - an unchanged answer has nothing to say.
     */
    public static void announce(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof TransformerBlock)) {
            return;
        }
        int input = reading(level, pos, state);
        int wanted = TransformerRule.answer(input, state.getValue(SETTING));
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
        wakeOutput(level, pos, announced, input, wanted, "transformer");
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

    /**
     * The carried half's poll: a physicalised transformer, asked again by the walk that already visits it. A
     * plate inside a sub-level is a real block at its plot coordinate, so the cached answer is what goes stale.
     */
    public static void refreshCarried(ServerLevel level, BlockPos localPos, BlockState state) {
        if (!(state.getBlock() instanceof TransformerBlock)) {
            return;
        }
        announce(level, localPos);
    }

    // ------------------------------------------------------------------- screen --

    /** The setting itself: validated, written, and announced. */
    public static boolean applySettings(ServerLevel level, BlockPos pos, int setting) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof TransformerBlock) || !TransformerRule.isOutput(setting)) {
            return false;
        }
        BlockState wanted = state.setValue(SETTING, setting);
        if (!wanted.equals(state)) {
            level.setBlock(pos, wanted, Block.UPDATE_CLIENTS);
        }
        announce(level, pos);
        return true;
    }

    @Override
    public void sendView(ServerPlayer player, ServerLevel level, BlockPos pos, boolean open) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof TransformerBlock)) {
            return;
        }
        Readout readout = readout(level, pos, state);
        PacketDistributor.sendToPlayer(player, new RCNetwork.DeviceViewPayload(pos, open,
                DeviceKind.TRANSFORMER.ordinal(), readout.setting(), 0, 0, 0, readout.power(),
                readout.reading()));
    }

    /**
     * The screen's changes, and the server's answer to them. One entry point for both verbs, because both end
     * the same way: the server reads the block and sends the screen what it now sees, so a screen can never
     * show a value the block does not have.
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
        if (!(state.getBlock() instanceof TransformerBlock)) {
            return;
        }
        // The distance is measured where the block is DRAWN, or a screen for a physicalised device could never
        // be used - the plot coordinate is most of a world away from the player looking at it.
        if (tooFar(player, level, pos)) {
            return;
        }
        if (!mayAnswer(level, player)) {
            return;
        }
        if (payload.verb() == RCNetwork.DeviceEditPayload.Verb.SET) {
            applySettings(level, pos, payload.a());
        }
        sendView(player, level, pos, false);
    }

    /** Where a device is drawn, and whether the player is close enough to it to be editing it. */
    private static boolean tooFar(ServerPlayer player, ServerLevel level, BlockPos pos) {
        BlockPos drawn = ProductFrame.carriedAt(level, pos).worldPosOf(pos);
        return player.getEyePosition().distanceToSqr(Vec3.atCenterOf(drawn)) > RANGE * RANGE;
    }

    // ------------------------------------------------------- 可放入方块内 --

    /**
     * The same screen, for a transformer stored inside a host block. The reading is the number the last solve
     * read rather than a fresh query - the level has no coordinate the machine's input is at.
     *
     * <p>The address is the coordinate the click arrived at, which is both the host block's own position in the
     * world and its <b>plot</b> coordinate inside a structure, so nothing here needs to know which it is.
     */
    public static void sendHostView(ServerPlayer player, ServerLevel level, BlockPos host, boolean open) {
        Slot slot = hostSlot(level, host, ComponentType.TRANSFORMER);
        if (slot == null) {
            // "点不开" cannot be told apart from "the click never arrived" without a line naming which store
            // was asked, and the resolver's own answer printed beside them.
            if (RCConfig.debugLog()) {
                CarriedBlockRemoval.Hit hit = CarrierStore.hitAt(level, host);
                RCConfig.LOGGER.info("[redstonecircuit] transformer screen at {}: world store={} carried index={}"
                                + " resolver={}", host.toShortString(),
                        InnerRedstoneStore.get(level).slotAt(host) != null,
                        CarrierStore.slotAt(level, host) != null,
                        hit == null ? "-" : hit.carrier().shortId() + " local=" + hit.localPos().toShortString());
            }
            return;
        }
        PacketDistributor.sendToPlayer(player, new RCNetwork.DeviceViewPayload(host, open,
                DeviceKind.TRANSFORMER.ordinal(), slot.transformer, 0, 0, 0, slot.power,
                hostReading(level, host, slot)));
    }

    /** The stored transformer's changes: validated, written, re-solved, and told to the client. */
    public static void applyHostEdit(ServerPlayer player, RCNetwork.DeviceEditPayload payload) {
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        BlockPos host = payload.pos();
        if (!level.isLoaded(host)) {
            return;
        }
        Slot slot = hostSlot(level, host, ComponentType.TRANSFORMER);
        if (slot == null || tooFar(player, level, host)) {
            return;
        }
        if (!mayAnswer(level, player)) {
            return;
        }
        if (payload.verb() == RCNetwork.DeviceEditPayload.Verb.SET) {
            applyHostSettings(level, host, slot, payload.a());
        }
        sendHostView(player, level, host, false);
    }

    /**
     * Writes the setting onto the stored component and re-derives its answer. A component lives in a
     * server-side store and the client draws a <b>copy</b> of it, so a change that is not broadcast is a change
     * the player cannot see; the two notifications after it move the answer in the same tick.
     */
    public static boolean applyHostSettings(ServerLevel level, BlockPos host, Slot slot, int setting) {
        if (slot == null || slot.type != ComponentType.TRANSFORMER || !TransformerRule.isOutput(setting)) {
            return false;
        }
        slot.transformer = setting;
        // The store write, the carrier's copy and the two notifications are one shared method now, where the
        // reason for each of the five steps is written down.
        announceHostSettings(level, host, slot);
        return true;
    }
}
