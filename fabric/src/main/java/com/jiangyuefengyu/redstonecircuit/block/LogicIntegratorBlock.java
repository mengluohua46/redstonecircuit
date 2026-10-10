package com.jiangyuefengyu.redstonecircuit.block;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.jiangyuefengyu.redstonecircuit.carrier.CarrierStore;
import com.jiangyuefengyu.redstonecircuit.carrier.ProductFrame;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore;
import com.jiangyuefengyu.redstonecircuit.data.Slot;
import com.jiangyuefengyu.redstonecircuit.logic.InnerRedstoneNetwork;
import com.jiangyuefengyu.redstonecircuit.logic.BlockAnswerCache;
import com.jiangyuefengyu.redstonecircuit.logic.LogicGate;
import com.jiangyuefengyu.redstonecircuit.logic.PowerSolver;
import com.jiangyuefengyu.redstonecircuit.network.RCNetwork;
import com.jiangyuefengyu.redstonecircuit.logic.NoWeakPowerConduction;
import com.mojang.serialization.MapCodec;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DaylightDetectorBlock;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.ObserverBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.RedstoneTorchBlock;
import net.minecraft.world.level.block.RedstoneWallTorchBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import org.jetbrains.annotations.Nullable;

/**
 * 逻辑整合器: a full cube whose front face is the output and whose other five faces are inputs.
 *
 * <p>档位、X、Y 进方块状态，因为凡是能搬动方块的手段都会带走方块状态；正面当下给出的答案不进状态，缓存在
 * 方块旁边（{@link BlockAnswerCache}），由邻居变化驱动重算。
 */
public class LogicIntegratorBlock extends Block implements NoWeakPowerConduction {

    public static final MapCodec<LogicIntegratorBlock> CODEC = simpleCodec(LogicIntegratorBlock::new);

    /** 档位: which of the seven gates this block is set to. */
    public static final EnumProperty<LogicGate.Mode> MODE = EnumProperty.create("mode", LogicGate.Mode.class);

    /** X: the strength the input is compared against, or {@link LogicGate#MAX} + 1 while it is blank. */
    public static final IntegerProperty X = IntegerProperty.create("x", 0, LogicGate.MAX + 1);

    /** Y: the strength the gate answers with, or blank. 同或/异或 answer with X and never read this. */
    public static final IntegerProperty Y = IntegerProperty.create("y", 0, LogicGate.MAX + 1);

    /** 正面: the output face. The other five faces are the input faces. All six, like an observer's. */
    public static final DirectionProperty FACING = BlockStateProperties.FACING;

    /** How far a player may be from the block (measured where it is drawn) and still edit it. */
    private static final double RANGE = 8.0;

    /** The fewest ticks between two answers to the same player's screen; see {@link #mayAnswer}. */
    private static final int ANSWER_INTERVAL_TICKS = 2;

    /** How many players' last-answered ticks are remembered before the oldest are dropped. */
    private static final int ANSWER_MEMORY = 64;

    /** The tick each player's screen was last answered on, so a refresh button is not a packet flood. */
    private static final Map<UUID, Long> LAST_ANSWER = new HashMap<>();

    public LogicIntegratorBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(MODE, LogicGate.Mode.AND)
                .setValue(X, LogicGate.toState(LogicGate.UNSET))
                .setValue(Y, LogicGate.toState(LogicGate.UNSET)));
    }

    @Override
    protected MapCodec<LogicIntegratorBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, MODE, X, Y);
    }

    /**
     * The front of a placed block points back at whoever placed it - the piston's own rule
     * ({@code getNearestLookingDirection().getOpposite()}), so putting one on the ground looking down gives an output
     * face pointing up at the player rather than one buried in the floor.
     */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getNearestLookingDirection().getOpposite());
    }

    /** A gate is a source: consumers have to ask it, and {@code getSignal} is where they ask. */
    @Override
    protected boolean isSignalSource(BlockState state) {
        return true;
    }

    /** 只答正面（普通弱信号），其余五面 0。`side` 是询问的行进方向，正在答的那一面是 `side.getOpposite()`。 */
    @Override
    protected int getSignal(BlockState state, net.minecraft.world.level.BlockGetter level, BlockPos pos,
                            Direction side) {
        if (side.getOpposite() != state.getValue(FACING)) {
            return 0;
        }
        return answerAt(level, pos, state);
    }

    /**
     * The answer, from the cache beside the block - see {@link BlockAnswerCache} for why it is not a block state
     * property: the settings have to stay in the state, because they are what a piston push carries.
     *
     * <p>One map read on the server; a read that finds nothing computes once and stores, which is the first query after
     * a world load. On the client there is no cache: its copy of the neighbourhood is whatever the last packet said, so
     * a client-side cache could only disagree with the server that computed it.
     */
    public static int answerAt(net.minecraft.world.level.BlockGetter level, BlockPos pos, BlockState state) {
        if (!(level instanceof Level realLevel)) {
            return 0;
        }
        if (realLevel instanceof ServerLevel serverLevel) {
            int cached = BlockAnswerCache.get(serverLevel, pos);
            if (cached != BlockAnswerCache.ABSENT) {
                return cached;
            }
        }
        int computed = answerOf(state, sight(realLevel, pos, state.getValue(FACING))).power();
        if (realLevel instanceof ServerLevel serverLevel) {
            BlockAnswerCache.put(serverLevel, pos, computed);
        }
        return computed;
    }



    /** A neighbour moved: the inputs may have moved with it, so the answer is derived again. */
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

    /**
     * 点击进入控制界面 - one gesture, and it is the screen, opened by the server because the server is the side that has
     * to agree the click happened. The payload carries the block's coordinate and nothing else, and the client reads the
     * settings out of the state it was sent: a carried block is clicked at its plot coordinate, and the screen echoes
     * back exactly what it was given.
     */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hit) {
        if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)) {
            // The client's prediction: the hand has to swing, and the client must not act.
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        sendView(serverPlayer, serverLevel, pos, true);
        return InteractionResult.sidedSuccess(true);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip,
                                TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        // One line by default, the how-to behind [Shift], like Create's wrench.
        com.jiangyuefengyu.redstonecircuit.RcTooltips.append(tooltip, flag,
                "block.redstonecircuit.logic_integrator.tip",
                com.jiangyuefengyu.redstonecircuit.RcTooltips.detail("block.redstonecircuit.logic_integrator.tip.detail"));
    }

    // ------------------------------------------------------------------ reading --

    /**
     * The blocks that can put a signal on a face but do not say so.
     *
     * <p>The emitters vanilla never calls a signal source but which can still drive a face while they are quiet: a
     * target, a sculk sensor, a tripwire hook, a detector rail, a lightning rod, a redstone block. Every other
     * source is judged by {@code isSignalSource} <em>and</em> by whether it drives <em>this</em> face - see
     * {@link #emitterAt}.
     *
     * <p>A {@code Set<Block>} rather than a datapack tag, because that is what this mod's other rule lists are; a modded
     * wire that declares itself needs nothing here, and a modded emitter that does not can be added in one line.
     */
    private static final Set<Block> CAN_EMIT = Set.of(
            Blocks.REDSTONE_BLOCK,
            Blocks.TARGET,
            Blocks.SCULK_SENSOR,
            Blocks.CALIBRATED_SCULK_SENSOR,
            Blocks.TRIPWIRE_HOOK,
            Blocks.DETECTOR_RAIL,
            Blocks.LIGHTNING_ROD);

    /**
     * Everything the screen shows, read at one position in one go: {@code x} is the player's X or
     * {@link LogicGate#UNSET}; {@code readings} is indexed by {@link Direction#ordinal()}, with 0 for the front face
     * because that is the output; {@code inputs} is one bit per face for those that <b>count</b>, a face with nothing on
     * it and no signal from it being no input at all.
     */
    public record Readout(LogicGate.Mode mode, int x, int y, Direction facing, int[] readings, int inputs,
                          LogicGate.Answer answer) {
    }

    /**
     * What the block sees, in one pass over the five input faces: each one's strength, and which of them count.
     *
     * <p>A face counts when it <b>carries a signal</b> or has something <b>attached</b> to it: a wire that is off is
     * still wired, and its 0 is then a strength the gate compares like any other. The two are read together because a
     * face already carrying something needs no second look at its neighbour, and asking the level about the same
     * neighbour twice would be the cost of every query this block makes.
     */
    public record Sight(int[] readings, int inputs) {
    }

    /** The state and the world around it, as the screen shows it. */
    public static Readout readout(Level level, BlockPos pos, BlockState state) {
        Direction front = state.getValue(FACING);
        Sight sight = sight(level, pos, front);
        return new Readout(state.getValue(MODE), LogicGate.fromState(state.getValue(X)),
                LogicGate.fromState(state.getValue(Y)), front, sight.readings(), sight.inputs(),
                answerOf(state, sight));
    }

    /** One pass over the five input faces; the front face's entry in {@code readings} stays 0. */
    public static Sight sight(Level level, BlockPos pos, Direction front) {
        int[] readings = new int[Direction.values().length];
        int inputs = 0;
        ProductFrame frame = frameOf(level, pos);
        for (Direction side : Direction.values()) {
            if (side == front) {
                continue;
            }
            int reading = readingAt(level, pos, side, frame);
            readings[side.ordinal()] = reading;
            if (reading > 0 || attached(level, pos, side, frame)) {
                inputs |= 1 << side.ordinal();
            }
        }
        return new Sight(readings, inputs);
    }

    /**
     * Whether something that can signal is wired to this face - even while it is giving nothing.
     *
     * <p>Asked only for a face that reads 0, and answered by {@link #emitterAt}, which asks whether that neighbour
     * can ever drive <em>this</em> face - a wire that is off is still a wire, while a repeater pointing the other way
     * is not wired to it at all.
     *
     * <p>Both frames are asked, exactly as the reading is: either one attached is enough, because one neighbour can only
     * be in one of them. Air is never attached, so a gate with nothing around it is still 不工作 rather than counting
     * five zeros as five inputs.
     */
    private static boolean attached(Level level, BlockPos pos, Direction side, ProductFrame frame) {
        BlockPos neighbour = pos.relative(side);
        if (emitterAt(level, neighbour, side.getOpposite())) {
            return true;
        }
        if (!frame.carried()) {
            return false;
        }
        BlockPos drawn = frame.worldPosOf(neighbour);
        return !drawn.equals(neighbour) && emitterAt(level, drawn, frame.worldFace(side).getOpposite());
    }

    /**
     * Whether the block at {@code at} can drive the face {@code towardsUs} - both in the <b>same frame</b>, the
     * direction running from that block towards this gate.
     *
     * <p>Only neighbours that can really put a signal on that face count as inputs: a diode or an observer answers
     * on the one face it points at, a torch never drives the block it hangs on, dust never drives the block above it,
     * a device of ours answers on its output face alone, and a component stored inside a neighbour emits only where
     * its own rule - the wrench's overrides included - lets it.
     *
     * <p>{@code side} in vanilla's own methods is the way a query <em>travelled</em>, from the asker towards the
     * block, so "the face it points at" is {@code towardsUs.getOpposite()}.
     */
    private static boolean emitterAt(Level level, BlockPos at, Direction towardsUs) {
        if (level == null || !level.isLoaded(at)) {
            return false;
        }
        BlockState state = level.getBlockState(at);
        if (state.isAir()) {
            return false;
        }
        Block block = state.getBlock();
        // Ours: the gate answers on its FACING, the plates (控制器 / 变压器 / 时钟源) and 传输门 on the back of
        // theirs. The other five faces drive nothing, so two devices touching input-to-input are not wired.
        if (block instanceof LogicIntegratorBlock) {
            return state.getValue(FACING) == towardsUs;
        }
        if (block instanceof RcPlateBlock) {
            return state.getValue(RcPlateBlock.FACING).getOpposite() == towardsUs;
        }
        if (block instanceof TransmissionGateBlock) {
            return state.getValue(TransmissionGateBlock.FACING).getOpposite() == towardsUs;
        }
        // Vanilla's directional emitters: a diode and an observer drive the face they point at, and nothing else.
        if (block instanceof DiodeBlock) {
            return state.getValue(DiodeBlock.FACING) == towardsUs.getOpposite();
        }
        if (block instanceof ObserverBlock) {
            return state.getValue(ObserverBlock.FACING) == towardsUs.getOpposite();
        }
        // A wall torch never drives the block it hangs on; a standing torch never drives the block under it.
        if (block instanceof RedstoneWallTorchBlock) {
            return state.getValue(RedstoneWallTorchBlock.FACING).getOpposite() != towardsUs;
        }
        if (block instanceof RedstoneTorchBlock) {
            return towardsUs != Direction.DOWN;
        }
        // Dust - ours included - drives every face except the one above it.
        if (block instanceof RedStoneWireBlock) {
            return towardsUs != Direction.UP;
        }
        if (state.isSignalSource() || CAN_EMIT.contains(block) || block instanceof DaylightDetectorBlock) {
            return true;
        }
        // A lever put inside a block writes no block state, so only the stores can see it - and it is wired only
        // where it emits: a side the wrench cut, or a diode stored facing elsewhere, is no input at all.
        Slot slot = slotAt(level, at);
        return slot != null && PowerSolver.emitsToward(slot, towardsUs);
    }

    /**
     * The component stored inside the block at this position, in either of the two stores, or {@code null}.
     *
     * <p>Two stores, for the same reason {@code InnerRedstoneTickHandler#holdsComponent} asks both: a block standing in
     * the world keeps its component in the level's own store, and one riding a structure keeps it in the carrier's
     * index. Neither of them changes a block state, so neither vanilla's question nor {@link #CAN_EMIT} can see them.
     */
    @Nullable
    private static Slot slotAt(Level level, BlockPos at) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return null;
        }
        Slot slot = InnerRedstoneStore.get(serverLevel).slotAt(at);
        return slot != null ? slot : CarrierStore.slotAt(serverLevel, at);
    }

    /** What the gate answers for one sighting - {@link LogicGate}'s rule, over the faces that count. */
    public static LogicGate.Answer answerOf(BlockState state, Sight sight) {
        int front = state.getValue(FACING).ordinal();
        int[] arrived = new int[sight.readings().length];
        int counting = 0;
        for (Direction side : Direction.values()) {
            if (side.ordinal() != front && (sight.inputs() & (1 << side.ordinal())) != 0) {
                arrived[counting++] = sight.readings()[side.ordinal()];
            }
        }
        return LogicGate.evaluate(state.getValue(MODE), LogicGate.fromState(state.getValue(X)),
                LogicGate.fromState(state.getValue(Y)), Arrays.copyOf(arrived, counting));
    }

    /** The frame this block is read in: where it is drawn, or where it stands. */
    private static ProductFrame frameOf(Level level, BlockPos pos) {
        return level instanceof ServerLevel serverLevel ? ProductFrame.carriedAt(serverLevel, pos)
                : ProductFrame.inPlace();
    }

    /**
     * One face's reading, in both frames.
     *
     * <p>{@link ProductFrame#signalInto} is the mod's one answer to "what is this neighbour giving this block": the
     * world position the neighbour is drawn at <em>and</em>, for a carrier whose storage is the level, the local
     * coordinate itself, taking the larger. A block on the ground degenerates to the ordinary weak-and-strong read, so
     * the two frames cannot drift apart.
     */
    private static int readingAt(Level level, BlockPos pos, Direction side, ProductFrame frame) {
        BlockPos neighbour = pos.relative(side);
        if (level instanceof ServerLevel serverLevel) {
            return PowerSolver.clamp(frame.signalInto(serverLevel, neighbour, side));
        }
        return PowerSolver.clamp(Math.max(level.getSignal(neighbour, side), level.getDirectSignal(neighbour, side)));
    }

    // ----------------------------------------------------------------- answering --

    /**
     * Derives the answer again and publishes it if it moved.
     *
     * <p>Server side only, and the comparison is the whole point of caching: a gate whose answer did not move has
     * nothing to say to anybody, and a gate that notified six neighbours on every neighbour change would let two of
     * them facing each other ring for ever.
     */
    public static void announce(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof LogicIntegratorBlock)) {
            return;
        }
        int wanted = answerOf(state, sight(level, pos, state.getValue(FACING))).power();
        if (BlockAnswerCache.get(level, pos) == wanted) {
            return;
        }
        BlockAnswerCache.put(level, pos, wanted);
        notifyInFront(level, pos, state);
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
     * Wakes whatever is in front of the output face - locally <b>and</b> where the block is drawn.
     *
     * <p>Vanilla's diode shape, because a diode drives one face: a neighbour change for the block itself (dust, a
     * repeater, a piston) plus {@code updateNeighborsAtExceptFromFacing} for its neighbours, which is also the event the
     * mod's own solver listens to, so a component inside a host block in front of the gate hears about it with no extra
     * call.
     *
     * <p>For a block riding a structure the coordinate in front is inside the carrier's storage and the position the
     * player can see is somewhere else entirely - air - so a machine whose gate changed while it stands still would
     * leave the lamp beside it dark until something unrelated poked it. Both notifications are sent at the drawn
     * position, because the two halves listen to different ones.
     */
    private static void notifyInFront(ServerLevel level, BlockPos pos, BlockState state) {
        Direction front = state.getValue(FACING);
        BlockPos target = pos.relative(front);
        level.neighborChanged(target, state.getBlock(), pos);
        level.updateNeighborsAtExceptFromFacing(target, state.getBlock(), front.getOpposite());

        ProductFrame frame = ProductFrame.carriedAt(level, pos);
        if (!frame.carried()) {
            return;
        }
        BlockPos drawn = frame.worldPosOf(target);
        if (drawn.equals(target)) {
            return;
        }
        level.updateNeighborsAt(drawn, state.getBlock());
        InnerRedstoneNetwork.notifyOutputChanged(level, drawn);
        InnerRedstoneNetwork.notifyOutputChangedNow(level, drawn);
    }

    /**
     * The carried half's poll: a physicalised gate, asked again by the walk that already visits it.
     *
     * <p>Everything that happens <em>inside</em> the structure reaches a plot cell through the level's own
     * notifications, but nothing reaches it from outside: a lever in the world notifies the world's six neighbours -
     * air, where the machine is drawn - and the plot cell hears nothing at all. The world read itself is live, so the
     * inputs were never the problem; the <b>cached answer</b> is, and this is what re-derives it.
     *
     * <p>Called from the one walk that already visits every signal source a carrier draws, so there is no ledger and no
     * timer here - and {@link #announce} writes nothing at all when the answer has not moved, so a parked machine with
     * nothing changing costs six neighbour reads per visit. The state is handed in because the walk has just read it.
     */
    public static void refreshCarried(ServerLevel level, BlockPos localPos, BlockState state) {
        if (!(state.getBlock() instanceof LogicIntegratorBlock)) {
            return;
        }
        announce(level, localPos);
    }

    /**
     * The screen's changes, and the server's answer to them.
     *
     * <p>One entry point for both verbs, because both end the same way: the server reads the block and sends the screen
     * what it now sees. A refusal - too far, nothing there, a strength that is not 0-15 - answers with the unchanged
     * view rather than with silence, so a screen can never show a value the block does not have.
     */
    public static void applyEdit(ServerPlayer player, RCNetwork.LogicEditPayload payload) {
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        BlockPos pos = payload.pos();
        if (!level.isLoaded(pos)) {
            return;
        }
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof LogicIntegratorBlock)) {
            return;
        }
        // Measured where the block is DRAWN, or a screen for a physicalised block could never be used: the plot
        // coordinate is most of a world away from the player looking at it.
        BlockPos drawn = ProductFrame.carriedAt(level, pos).worldPosOf(pos);
        if (player.getEyePosition().distanceToSqr(Vec3.atCenterOf(drawn)) > RANGE * RANGE) {
            return;
        }
        if (!mayAnswer(level, player)) {
            return;
        }
        if (payload.verb() == RCNetwork.LogicEditPayload.Verb.SET) {
            LogicGate.Mode[] modes = LogicGate.Mode.values();
            LogicGate.Mode mode = payload.mode() >= 0 && payload.mode() < modes.length ? modes[payload.mode()]
                    : state.getValue(MODE);
            applySettings(level, pos, mode, payload.x(), payload.y());
        }
        sendView(player, level, pos, false);
    }

    /**
     * The settings themselves: validated, written, and announced.
     *
     * <p>The write is {@code UPDATE_CLIENTS} and nothing more - the answer the neighbours read lives beside the block,
     * and {@link #announce} notifies the front face by hand if, and only if, that number moved.
     */
    public static boolean applySettings(ServerLevel level, BlockPos pos, @Nullable LogicGate.Mode mode, int x, int y) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof LogicIntegratorBlock)) {
            return false;
        }
        if (mode == null || !LogicGate.isSettable(x) || !LogicGate.isSettable(y)) {
            return false;
        }
        BlockState wanted = state.setValue(MODE, mode)
                .setValue(X, LogicGate.toState(x))
                .setValue(Y, LogicGate.toState(y));
        if (!wanted.equals(state)) {
            level.setBlock(pos, wanted, Block.UPDATE_CLIENTS);
        }
        announce(level, pos);
        return true;
    }

    /** Sends one player the block's current settings; {@code open} also asks the client to open the screen. */
    public static void sendView(ServerPlayer player, ServerLevel level, BlockPos pos, boolean open) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof LogicIntegratorBlock)) {
            return;
        }
        ServerPlayNetworking.send(player, RCNetwork.LogicViewPayload.of(pos, open, readout(level, pos, state)));
    }

    /**
     * Whether this player's screen may be answered again yet.
     *
     * <p>The screen refreshes itself on a timer and a client is not trusted to send at that rate: two ticks apart is
     * well under what a screen asks for and well over what a hand-written flood would like. The ledger is a player's
     * own last tick, dropped once it is old enough that nobody could still be refreshing.
     */
    private static boolean mayAnswer(ServerLevel level, ServerPlayer player) {
        long now = level.getGameTime();
        Long last = LAST_ANSWER.get(player.getUUID());
        if (last != null && now - last < ANSWER_INTERVAL_TICKS) {
            return false;
        }
        if (LAST_ANSWER.size() >= ANSWER_MEMORY) {
            LAST_ANSWER.entrySet().removeIf(entry -> now - entry.getValue() > 200L);
            if (LAST_ANSWER.size() >= ANSWER_MEMORY) {
                LAST_ANSWER.clear();
            }
        }
        LAST_ANSWER.put(player.getUUID(), now);
        return true;
    }
}
