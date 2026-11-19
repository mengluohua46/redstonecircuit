package com.jiangyuefengyu.redstonecircuit.block;

import java.util.Map;

import org.jetbrains.annotations.Nullable;

import com.jiangyuefengyu.redstonecircuit.logic.InnerRedstoneNetwork;
import com.jiangyuefengyu.redstonecircuit.logic.TriggerRule;
import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.SignalGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SupportType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 红石触发器（贴面）: the trigger on any of the six faces of its cell (作者 2026-11-15).
 *
 * <p>Same device as {@link TriggerBlock} - one rising edge toggles the switch, 正面 reads, 背面 answers - and the same
 * rule object ({@link TriggerRule}), so the two forms cannot disagree about what an edge is. What changes is the state:
 * the floor version has to know floor / ceiling and nothing else, while this one hangs anywhere, so it carries the
 * <b>mount</b> (which face it is glued to) and a <b>facing</b> that is free to point anywhere inside that face, up and
 * down included.
 *
 * <p><b>外观把朝向画出来。</b>The model is the floor version's, turned onto the mount with the blockstate's
 * {@code x} / {@code y} - the same pair the flat form already uses, so the floor and the ceiling keep the flat form's own
 * table. A wall mount still needs a rotation <em>inside</em> its own plane for 朝向, and that is not a pair of world-axis
 * turns: the three turned poses are baked geometry ({@code trigger_turn1..3}, {@code trigger_on_turn1..3}) spun about the
 * block centre from the two flat models, so the lever really points at the face it reads.
 *
 * <p>The wall pose and the turn follow the vanilla convention exactly: {@code BlockModelRotation} builds
 * {@code Ry(-y)·Rx(-x)}, and a model element's {@code rotation.angle} is a right-handed turn about its axis. The check that
 * pins this down lives in {@code SurfaceTriggerWiringTest}: the base has to land on {@code MOUNT} and the lever on
 * {@code FACING} for every in-plane state.
 */
public class SurfaceTriggerBlock extends Block {

    public static final MapCodec<SurfaceTriggerBlock> CODEC = simpleCodec(SurfaceTriggerBlock::new);

    /** 挂载: the face it is glued to. Six values, the same word the three 贴面 diodes use. */
    public static final DirectionProperty MOUNT = DirectionProperty.create("mount", Direction.values());

    /** 正面: the face it reads; the answer leaves on {@link #outputFace}. Inside the mount's plane, up and down too. */
    public static final DirectionProperty FACING = DirectionProperty.create("facing", Direction.values());

    public static final BooleanProperty POWERED = BlockStateProperties.POWERED;

    /** 上一次读到的输入 - the edge memory, exactly as the floor version has it. */
    public static final BooleanProperty INPUT = TriggerBlock.INPUT;

    /** The pillar, standing on whichever face it is glued to: same 6 × 10 × 6 as the floor version. */
    private static final Map<Direction, VoxelShape> SHAPES = Map.of(
            Direction.DOWN, Block.box(5.0, 0.0, 5.0, 11.0, 10.0, 11.0),
            Direction.UP, Block.box(5.0, 6.0, 5.0, 11.0, 16.0, 11.0),
            Direction.WEST, Block.box(0.0, 5.0, 5.0, 10.0, 11.0, 11.0),
            Direction.EAST, Block.box(6.0, 5.0, 5.0, 16.0, 11.0, 11.0),
            Direction.NORTH, Block.box(5.0, 5.0, 0.0, 11.0, 11.0, 10.0),
            Direction.SOUTH, Block.box(5.0, 5.0, 6.0, 11.0, 11.0, 16.0));

    public SurfaceTriggerBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
                .setValue(MOUNT, Direction.DOWN)
                .setValue(FACING, Direction.NORTH)
                .setValue(POWERED, false)
                .setValue(INPUT, false));
    }

    @Override
    protected MapCodec<SurfaceTriggerBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(MOUNT, FACING, POWERED, INPUT);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES.get(state.getValue(MOUNT));
    }

    /** 背面 - where the answer leaves. */
    public static Direction outputFace(BlockState state) {
        return state.getValue(FACING).getOpposite();
    }

    /**
     * 转向杆: one click, one step round the ring the mount can honour - up and down included on a wall.
     *
     * <p>The new 正面 is a different cell, so the edge has to be stepped again straight away: that is what the
     * neighbour notification is for, and it is the same pair of lines the three 贴面 diodes turn with. Nothing here
     * schedules a tick, so turning with no input cannot make it pulse.
     */
    public static Direction turnClockwise(ServerLevel level, BlockPos pos, BlockState state) {
        Direction next = SurfaceDiodeBlock.nextFacing(state.getValue(MOUNT), state.getValue(FACING));
        BlockState turned = state.setValue(FACING, next);
        level.setBlock(pos, turned, Block.UPDATE_ALL);
        level.neighborChanged(pos, turned.getBlock(), pos);
        return next;
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction mount = context.getClickedFace().getOpposite();
        BlockState state = defaultBlockState()
                .setValue(MOUNT, mount)
                .setValue(FACING, SurfaceDiodeBlock.placementFacing(mount,
                        directionToward(context), context.getHorizontalDirection().getOpposite()));
        return state.canSurvive(context.getLevel(), context.getClickedPos()) ? state : null;
    }

    /** Where the plate's front should point: back at the player, read in three dimensions. */
    private static Direction directionToward(BlockPlaceContext context) {
        if (context.getPlayer() == null) {
            return context.getHorizontalDirection().getOpposite();
        }
        net.minecraft.world.phys.Vec3 look = context.getPlayer().getLookAngle();
        return Direction.getNearest(-look.x, -look.y, -look.z);
    }

    @Override
    public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        Direction mount = state.getValue(MOUNT);
        BlockPos support = pos.relative(mount);
        return canAttachTo(level, mount, support);
    }

    private static boolean canAttachTo(BlockGetter level, Direction side, BlockPos pos) {
        return com.jiangyuefengyu.redstonecircuit.carrier.CarriedSupport.sturdy(
                level, pos, side.getOpposite(), SupportType.FULL);
    }

    @Override
    public BlockState updateShape(BlockState state, Direction direction, BlockState neighbourState,
                                  LevelAccessor level, BlockPos pos, BlockPos neighbourPos) {
        if (direction == state.getValue(MOUNT) && !state.canSurvive(level, pos)) {
            return Blocks.AIR.defaultBlockState();
        }
        return state;
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(MOUNT, rotation.rotate(state.getValue(MOUNT)))
                .setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.setValue(MOUNT, mirror.mirror(state.getValue(MOUNT)))
                .setValue(FACING, mirror.mirror(state.getValue(FACING)));
    }

    // --------------------------------------------------------------- answering --

    @Override
    protected boolean isSignalSource(BlockState state) {
        return true;
    }

    @Override
    protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction side) {
        if (side.getOpposite() != outputFace(state)) {
            return 0;
        }
        return state.getValue(POWERED) ? TriggerRule.ON : TriggerRule.OFF;
    }

    @Override
    public boolean shouldCheckWeakPower(BlockState state, SignalGetter level, BlockPos pos, Direction side) {
        return false;
    }

    /** 正面那一面此刻的电: the same read the floor version makes, from its own FACING. */
    public static int readingAt(BlockGetter level, BlockState state, BlockPos pos) {
        Direction face = state.getValue(FACING);
        return InnerRedstoneNetwork.readSignal(level, pos.relative(face), face);
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighbour,
                                   BlockPos fromPos, boolean movedByPiston) {
        super.neighborChanged(state, level, pos, neighbour, fromPos, movedByPiston);
        if (level instanceof ServerLevel serverLevel) {
            evaluate(serverLevel, pos);
        }
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (level instanceof ServerLevel serverLevel) {
            evaluate(serverLevel, pos);
        }
    }

    /** The floor version's {@code evaluate}, for this block's own state and properties. */
    public static void evaluate(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof SurfaceTriggerBlock)) {
            return;
        }
        boolean input = TriggerRule.live(readingAt(level, state, pos));
        boolean powered = state.getValue(POWERED);
        TriggerRule.Step step = TriggerRule.step(state.getValue(INPUT), input, powered);
        if (step.powered() == powered && step.input() == state.getValue(INPUT)) {
            return;
        }
        level.setBlock(pos, state.setValue(POWERED, step.powered()).setValue(INPUT, step.input()),
                Block.UPDATE_CLIENTS);
        if (step.powered() != powered) {
            level.updateNeighborsAt(pos, state.getBlock());
        }
    }
}
