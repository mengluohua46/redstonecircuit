package com.jiangyuefengyu.redstonecircuit.block;

import com.jiangyuefengyu.redstonecircuit.RcTooltips;

import java.util.List;
import java.util.function.Consumer;

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.carrier.ProductFrame;
import com.jiangyuefengyu.redstonecircuit.logic.InnerRedstoneNetwork;
import com.jiangyuefengyu.redstonecircuit.logic.PowerSolver;
import com.jiangyuefengyu.redstonecircuit.logic.TransmissionRule;
import com.mojang.serialization.MapCodec;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.SignalGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;

/**
 * 传输门: the front face is the main input, the back face is the main output, and the other four faces are the
 * side inputs; the main input is let through only while exactly one side input carries a signal.
 *
 * <p>{@link #FACING} is the front - the face a placed block turns towards whoever put it down - so the output is
 * {@code FACING.getOpposite()} and {@link #getSignal} answers on the back face only. That is 逻辑整合器's
 * convention with the two faces swapped, so the two machines look alike and mean opposite things by the same
 * property.
 *
 * <p>{@link #POWER} and {@link #LIT} are written together and only when they moved, because a machine that
 * notified six neighbours on every neighbour change would let two of them facing each other ring for ever. {@link
 * #shouldCheckWeakPower} is false: a full cube that accepts the strong power around it becomes a conductor and
 * then re-emits a charge it never made.
 *
 * <p>Inside a moving machine the block is a real block at its plot coordinate, so nothing reaches it from
 * outside; {@link #refreshCarried} re-derives the cached answer from the carrier walk.
 */
public class TransmissionGateBlock extends Block {

    public static final MapCodec<TransmissionGateBlock> CODEC = simpleCodec(TransmissionGateBlock::new);

    /** <b>The front - the main input face.</b> The output is its opposite; the other four faces are the side inputs. */
    public static final DirectionProperty FACING = BlockStateProperties.FACING;

    /** What the back face is giving: the main input's strength while exactly one side input signals, else 0. */
    public static final IntegerProperty POWER = IntegerProperty.create("power", 0, TransmissionRule.MAX);

    /** {@code POWER > 0}, as a property of its own so a pack can swap six faces' textures with twelve variants. */
    public static final BooleanProperty LIT = BlockStateProperties.LIT;

    public TransmissionGateBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(POWER, 0)
                .setValue(LIT, false));
    }

    @Override
    protected MapCodec<TransmissionGateBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, POWER, LIT);
    }

    /** The front points back at whoever placed it - so does the main input, and the output ends up facing away. */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getNearestLookingDirection().getOpposite());
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.setValue(FACING, mirror.mirror(state.getValue(FACING)));
    }

    /** A gate is a source: consumers have to ask it, and {@link #getSignal} is where they ask. */
    @Override
    protected boolean isSignalSource(BlockState state) {
        return true;
    }

    /**
     * The answer, on the <b>back face only</b>: the main output, and the one face this gate speaks out of.
     *
     * <p>{@code side} is vanilla's backwards parameter - it names the way the query travelled, <em>from the asker
     * towards this block</em> - so the face answering is {@code side.getOpposite()}. Getting it wrong is invisible
     * in a test that asks the block about its own facing: it would make the gate answer out of its front, i.e. into
     * its own main input.
     */
    @Override
    protected int getSignal(BlockState state, net.minecraft.world.level.BlockGetter level, BlockPos pos,
                            Direction side) {
        return side.getOpposite() == state.getValue(FACING).getOpposite() ? state.getValue(POWER) : 0;
    }

    /** A gate is not a block of stone: it answers with what it emits and carries no power that is not its own. */
    @Override
    public boolean shouldCheckWeakPower(BlockState state, SignalGetter level, BlockPos pos, Direction side) {
        return false;
    }

    /** A neighbour moved: an input may have moved with it, so the answer is derived again. */
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
        // One line by default, the how-to behind [Shift], like Create's wrench (RcTooltips).
        RcTooltips.append(tooltip, flag, "block.redstonecircuit.transmission_gate.tip",
                RcTooltips.detail("block.redstonecircuit.transmission_gate.tip.detail"));
    }

    // ------------------------------------------------------------------- reading --

    /** The five input faces in one pass: the main input's strength, and how many side inputs carry a signal. */
    public record Sight(int mainStrength, int poweredSecondaries) {
    }

    /**
     * Reads the five input faces of a block whose <b>front</b> is {@code front}.
     *
     * <p>The face that is <b>not</b> read is the front's opposite - the main output - because a gate does not listen to
     * what it is itself saying. Every other face is read in both frames (the coordinate inside the structure's own
     * storage when this block is carried, and the world position that face is drawn at), which is the whole of
     * {@link #readingAt}.
     */
    public static Sight sight(Level level, BlockPos pos, Direction front) {
        ProductFrame frame = frameOf(level, pos);
        Direction output = front.getOpposite();
        int main = 0;
        int secondaries = 0;
        for (Direction side : Direction.values()) {
            if (side == output) {
                continue;
            }
            int reading = readingAt(level, pos, side, frame);
            if (side == front) {
                main = reading;
            } else if (reading > 0) {
                secondaries++;
            }
        }
        return new Sight(main, secondaries);
    }

    /** The answer this block's state and surroundings add up to. */
    public static int answerOf(BlockState state, Sight sight) {
        return TransmissionRule.answer(sight.mainStrength(), sight.poweredSecondaries());
    }

    /** The frame this block is read in: where it is drawn, or where it stands. */
    private static ProductFrame frameOf(Level level, BlockPos pos) {
        return level instanceof ServerLevel serverLevel ? ProductFrame.carriedAt(serverLevel, pos)
                : ProductFrame.inPlace();
    }

    /**
     * One face's reading, in both frames.
     *
     * <p>{@link ProductFrame#signalInto} is the mod's one answer to "what is this neighbour giving this block": it asks
     * the world position the neighbour is drawn at <em>and</em>, for a carrier whose storage is the level, the local
     * coordinate itself, and takes the larger. A block on the ground degenerates to the ordinary read - weak power and
     * strong power - so the two frames cannot drift apart.
     */
    private static int readingAt(Level level, BlockPos pos, Direction side, ProductFrame frame) {
        BlockPos neighbour = pos.relative(side);
        if (level instanceof ServerLevel serverLevel) {
            return TransmissionRule.clamp(frame.signalInto(serverLevel, neighbour, side));
        }
        return TransmissionRule.clamp(Math.max(level.getSignal(neighbour, side),
                level.getDirectSignal(neighbour, side)));
    }

    // ------------------------------------------------------------------ answering --

    /**
     * Derives the answer again and publishes it if it moved.
     *
     * <p>Server side only, and the comparison is why {@link #POWER} and {@link #LIT} are block state at all: a gate
     * whose answer did not move has nothing to say to anybody, and this is called from every neighbour change.
     */
    public static void announce(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof TransmissionGateBlock)) {
            return;
        }
        int wanted = answerOf(state, sight(level, pos, state.getValue(FACING)));
        boolean lit = wanted > 0;
        if (state.getValue(POWER) == wanted && state.getValue(LIT) == lit) {
            return;
        }
        BlockState announced = state.setValue(POWER, wanted).setValue(LIT, lit);
        level.setBlock(pos, announced, Block.UPDATE_CLIENTS);
        notifyOutput(level, pos, announced);
    }

    /**
     * Wakes whatever is behind the output face - locally <b>and</b> where the block is drawn.
     *
     * <p>Vanilla's diode shape, because this block drives one face: {@code neighborChanged} for the block itself
     * (dust, a repeater, a piston) plus {@code updateNeighborsAtExceptFromFacing} for its neighbours, which is also
     * the event the mod's own solver listens to. The drawn position is woken as well: for a block riding a structure
     * the coordinate behind the face is inside the carrier's storage and the position the player can see is somewhere
     * else entirely, so a gate that changed while the machine stands still would leave the lamp beside it dark until
     * something unrelated poked it.
     */
    private static void notifyOutput(ServerLevel level, BlockPos pos, BlockState state) {
        Direction output = state.getValue(FACING).getOpposite();
        BlockPos target = pos.relative(output);
        level.neighborChanged(target, state.getBlock(), pos);
        level.updateNeighborsAtExceptFromFacing(target, state.getBlock(), output.getOpposite());

        ProductFrame frame = ProductFrame.carriedAt(level, pos);
        if (!frame.carried()) {
            return;
        }
        // The machine's OWN drawn position has to be woken, not only its neighbour's: "the block at P changed" is a
        // message to P's six neighbours, so announcing where the output points tells the neighbours of the wire
        // standing there and leaves the wire itself - a neighbour of the machine - as stale as it was. Placing a
        // block beside it pokes the world, which is the notification this line is.
        BlockPos drawnSelf = frame.worldPosOf(pos);
        if (!drawnSelf.equals(pos)) {
            level.updateNeighborsAt(drawnSelf, state.getBlock());
            InnerRedstoneNetwork.notifyOutputChanged(level, drawnSelf);
            InnerRedstoneNetwork.notifyOutputChangedNow(level, drawnSelf);
            if (RCConfig.debugLog()) {
                RCConfig.LOGGER.info("[redstonecircuit] transmission gate: woke the world at {} for the answer {} "
                        + "(local {}, output face {})", drawnSelf.toShortString(), state.getValue(POWER),
                        pos.toShortString(), output);
            }
        }
        BlockPos drawn = frame.worldPosOf(target);
        if (!drawn.equals(target)) {
            level.updateNeighborsAt(drawn, state.getBlock());
            InnerRedstoneNetwork.notifyOutputChanged(level, drawn);
            InnerRedstoneNetwork.notifyOutputChangedNow(level, drawn);
        }
    }

    /**
     * The carried half's poll: a physicalised gate, asked again by the walk that already visits it.
     *
     * <p>Called from the one walk that already visits every signal source a carrier draws
     * ({@code SableProductDriver#tellDrawnSources}: bounded, cursored, and covering a whole structure within a
     * second or two), which is why there is no ledger and no timer here. {@link #announce} writes nothing at all
     * when the answer has not moved, so a parked machine with nothing changing costs five neighbour reads per visit.
     * The state is handed in rather than looked up because the walk has just read it.
     */
    public static void refreshCarried(ServerLevel level, BlockPos localPos, BlockState state) {
        if (!(state.getBlock() instanceof TransmissionGateBlock)) {
            return;
        }
        announce(level, localPos);
    }
}
