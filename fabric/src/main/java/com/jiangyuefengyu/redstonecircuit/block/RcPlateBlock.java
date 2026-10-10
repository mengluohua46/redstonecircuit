package com.jiangyuefengyu.redstonecircuit.block;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.InnerRedstoneInteraction;
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
import com.jiangyuefengyu.redstonecircuit.logic.InnerRedstoneNetwork;
import com.jiangyuefengyu.redstonecircuit.logic.PowerSolver;
import com.jiangyuefengyu.redstonecircuit.network.HostSync;
import com.jiangyuefengyu.redstonecircuit.network.RCNetwork;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The four 带界面的红石元件 are 薄板: one eighth of a block thick, lying at the bottom of their cell.
 *
 * <p>Each set has whole 16×16 squares for its two big faces and a 16×2 strip for its edges; the strip sits on the
 * bottom two rows, which is what vanilla's auto-UV samples for a two-pixel-tall element, so the plate is one element
 * with no rotation. {@link #FACING} is 正面, the <b>input</b> face, and every device answers on
 * {@code FACING.getOpposite()}; the facing is horizontal, so 正面 is the face towards whoever placed it. The class
 * owns the geometry, placement, rotations, the click that opens the screen, the edit distance and the throttle.
 */
public abstract class RcPlateBlock extends Block {

    /**
     * 正面 - the input face; the output is always {@code FACING.getOpposite()}. Horizontal, because the plate lies flat.
     */
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    /** 1/8 of a block, at the bottom of the cell. */
    private static final VoxelShape SHAPE = Block.box(0.0, 0.0, 0.0, 16.0, 2.0, 16.0);

    /** How far a player may be from the block (measured where it is drawn) and still edit it. */
    protected static final double RANGE = 8.0;

    /** The fewest ticks between two answers to the same player's screen; see {@link #mayAnswer}. */
    private static final int ANSWER_INTERVAL_TICKS = 2;

    /** How many players' last-answered ticks are remembered before the oldest are dropped. */
    private static final int ANSWER_MEMORY = 64;

    /** The tick each player's screen was last answered on, so a refresh button is not a packet flood. */
    private static final Map<UUID, Long> LAST_ANSWER = new HashMap<>();

    protected RcPlateBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos,
                                           CollisionContext context) {
        return SHAPE;
    }

    /**
     * 正面 points back at whoever placed it - a repeater's own rule ({@code getHorizontalDirection().getOpposite()}), so
     * the player is standing at the input face holding the wire that will feed it.
     */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    /**
     * 点击进入控制界面: the server opens the screen, because the server is the side that has to agree the click
     * happened. The payload names the coordinate the click arrived at, which for a physicalised device is its plot
     * coordinate and not where it is drawn.
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

    /** Sends one player this device's settings and its current reading; {@code open} also asks for the screen. */
    public abstract void sendView(ServerPlayer player, ServerLevel level, BlockPos pos, boolean open);

    /** The player's edit, and the server's answer to it - whatever the verb was, the answer is the next view. */
    public abstract void applyEdit(ServerPlayer player, RCNetwork.DeviceEditPayload payload);

    /**
     * Whether this player's screen may be answered again yet: two ticks apart, because a screen refreshes itself on a
     * timer and a client is not trusted to send them at that rate. The ledger holds each player's own last tick,
     * dropped once nobody could still be refreshing.
     */
    protected static boolean mayAnswer(ServerLevel level, ServerPlayer player) {
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

    /** 背面 - the face every one of the four answers on, given its 正面. */
    public static Direction outputFace(BlockState state) {
        return state.getValue(FACING).getOpposite();
    }

    // ------------------------------------------------- shared device machinery --
    //
    // Shared because these are the parts a copy gets wrong: the front reading is read in two frames, the output wake
    // has to reach the place a physicalised device is DRAWN as well as the place it stands, and the in-block lookup
    // has to ask three stores before it may say "no such component".

    /**
     * The strength arriving at one face, read in both frames. {@link ProductFrame#signalInto} is the mod's one answer
     * to what a neighbour is giving this block: it asks the world position the neighbour is drawn at and, for a
     * carrier whose storage is the level, the local coordinate as well, and takes the larger. A plate on the ground
     * degenerates to the ordinary read, so the two frames cannot drift apart.
     */
    protected static int readingInto(Level level, BlockPos pos, Direction side) {
        BlockPos neighbour = pos.relative(side);
        if (level instanceof ServerLevel serverLevel) {
            return PowerSolver.clamp(ProductFrame.carriedAt(serverLevel, pos).signalInto(serverLevel, neighbour, side));
        }
        return PowerSolver.clamp(Math.max(level.getSignal(neighbour, side), level.getDirectSignal(neighbour, side)));
    }

    /**
     * Wakes whatever stands at 背面, both where the device stands and where it is drawn.
     *
     * <p>Vanilla's diode shape, because a diode drives one face: {@code neighborChanged} for the block itself plus
     * {@code updateNeighborsAtExceptFromFacing} for its neighbours, which is also the event the mod's own solver
     * listens to, so a component inside a host block at the back hears about it with no extra call. A plate riding a
     * structure stands in a plot and is drawn somewhere else, where its neighbours heard nothing: both halves are
     * woken there, with vanilla's notification and with the mod's own.
     */
    protected static void wakeOutput(ServerLevel level, BlockPos pos, BlockState state, int input, int power,
                                     String device) {
        Direction output = outputFace(state);
        BlockPos target = pos.relative(output);
        level.neighborChanged(target, state.getBlock(), pos);
        level.updateNeighborsAtExceptFromFacing(target, state.getBlock(), output.getOpposite());

        ProductFrame frame = ProductFrame.carriedAt(level, pos);
        if (!frame.carried()) {
            return;
        }
        BlockPos drawnSelf = frame.worldPosOf(pos);
        BlockPos drawnTarget = frame.worldPosOf(target);
        if (!drawnSelf.equals(pos)) {
            level.updateNeighborsAt(drawnSelf, state.getBlock());
            InnerRedstoneNetwork.notifyOutputChanged(level, drawnSelf);
            InnerRedstoneNetwork.notifyOutputChangedNow(level, drawnSelf);
            if (RCConfig.debugLog()) {
                RCConfig.LOGGER.info("[redstonecircuit] {}: woke the world at {} for the answer {} (input {},"
                        + " output face {})", device, drawnSelf, power, input, output);
            }
        }
        if (!drawnTarget.equals(target) && !drawnTarget.equals(drawnSelf)) {
            level.updateNeighborsAt(drawnTarget, state.getBlock());
            InnerRedstoneNetwork.notifyOutputChanged(level, drawnTarget);
            InnerRedstoneNetwork.notifyOutputChangedNow(level, drawnTarget);
        }
    }

    /**
     * The component of one type stored inside the block at {@code host}, or {@code null} when there is not one.
     *
     * <p>Three stores: the level's own store for a host standing in the world, the carrier's index for one riding a
     * structure, and the structure's own frame, which the click's own resolver names ({@code CarrierStore#hitAt}
     * scans the structure's local coordinates). A record's world key is where it answered when it was captured, so a
     * lookup through the drawn position misses the moment the machine has flown.
     */
    @Nullable
    public static Slot hostSlot(ServerLevel level, BlockPos host, ComponentType type) {
        Slot slot = InnerRedstoneStore.get(level).slotAt(host);
        if (slot == null) {
            slot = CarrierStore.slotAt(level, host);
        }
        if (slot == null) {
            CarriedHostIndex index = CarrierStore.indexOf(level);
            CarriedBlockRemoval.Hit hit = CarrierStore.hitAt(level, host);
            CarriedHost carried = hit == null || index == null ? null : index.at(hit.carrier(), hit.localPos());
            slot = carried == null ? null : carried.slot();
        }
        return slot != null && slot.type == type ? slot : null;
    }

    /**
     * What is arriving at a stored component's 正面 right now: the number the last solve read, not a fresh query.
     *
     * <p>A component inside a machine is read through the structure's own view, so the coordinate the screen is
     * addressed at is not where the input is. The solver records the number where it reads it ({@code Slot#lastInput})
     * and this hands it back; the level query stays as the fallback for a component that has not been solved yet, which
     * is what {@code lastInput < 0} means. For a host on the ground the two paths agree.
     */
    public static int hostReading(ServerLevel level, BlockPos host, Slot slot) {
        if (slot.lastInput >= 0) {
            return slot.lastInput;
        }
        BlockPos neighbour = host.relative(slot.facing);
        return level.isLoaded(neighbour) ? InnerRedstoneNetwork.readSignal(level, neighbour, slot.facing) : 0;
    }

    /**
     * A stored component's settings changed: the writes and notifications that make that true everywhere it has to be.
     *
     * <p>One sequence for every device, because every part of it is a thing a copy gets wrong. In order: the store the
     * component actually lives in (a host in the world keeps it in the level's store, one riding a structure in the
     * carrier's index), the broadcast that goes with either - the client draws a <b>copy</b> of the component, so a
     * change that is not broadcast is one the player cannot see - then the pair of writes a carried host needs, at the
     * structure's own coordinate and at the world position the machine is drawn at, and finally the two notifications
     * that make the answer move in the same tick. The caller writes its own field first; this does the rest.
     */
    protected static void announceHostSettings(ServerLevel level, BlockPos host, Slot slot) {
        InnerRedstoneStore store = InnerRedstoneStore.get(level);
        if (store.slotAt(host) == slot) {
            store.markDirty();
            HostSync.broadcast(level, host, slot);
        } else {
            BlockPos drawn = ProductFrame.carriedAt(level, host).worldPosOf(host);
            CarriedHostIndex index = CarrierStore.indexOf(level);
            CarrierId carrier = index == null ? null : index.carrierHolding(drawn);
            if (carrier != null) {
                index.updateSlot(carrier, host, slot);
            }
            CarriedHosts.get(level).setDirty();
            if (carrier != null) {
                CarrierStore.broadcastCarrierNow(level, carrier);
            }
            // The world-side entry at the drawn position is cleared, not written: that position stops being the
            // machine's the moment it moves, and an entry left there is a component drawn in mid-air for ever.
            HostSync.publishWorldEntry(level, drawn);
        }
        InnerRedstoneNetwork.markDirtyWithNeighbours(level, host);
        InnerRedstoneInteraction.notifyNeighbours(level, host);
    }
}
