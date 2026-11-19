package com.jiangyuefengyu.redstonecircuit.block;

import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.SignalGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SupportType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.ticks.TickPriority;

/**
 * A diode that hangs on any face of its cell and reads any direction inside that face - the shared half of
 * 中继器（贴面）and 比较器（贴面）.
 *
 * <p>Vanilla's diodes cannot be this, for one structural reason: {@code FACING} is a <b>horizontal</b> property, so
 * "read upwards" is not a state they can hold. This base therefore carries the diode itself - {@link #FACING} has six
 * values - and every rule in it is vanilla's, line for line, with two additions:
 *
 * <ul>
 *   <li>{@link #MOUNT} - the face of the cell it hangs on. It moves and shapes the plate, and it decides which four
 *       directions {@link #FACING} can take (the plate's own plane).</li>
 *   <li>the side input used for locking - two sides for a horizontal diode as in vanilla, the four horizontals for a
 *       vertical one, the same choice {@code PowerSolver#lockSides} makes for a component inside a block.</li>
 * </ul>
 *
 * <p>Subclasses answer the three questions that make a repeater a repeater or a comparator a comparator: how long the
 * delay is, what the back answers, and when it counts as switched on.
 */
public abstract class SurfaceDiodeBlock extends Block {

    /** 朝向 - the face this diode reads; it answers on {@code FACING.getOpposite()}. Six values, which is the point. */
    public static final DirectionProperty FACING = DirectionProperty.create("facing", Direction.values());

    /** 贴面 - towards the block it hangs on. Separate question from {@link #FACING}, and never the same axis. */
    public static final DirectionProperty MOUNT = DirectionProperty.create("mount", Direction.values());

    public static final BooleanProperty POWERED = BlockStateProperties.POWERED;

    /** The plate's 2 px, on the face each mount names. */
    private static final Map<Direction, VoxelShape> SHAPES = Map.of(
            Direction.DOWN, Block.box(0.0, 0.0, 0.0, 16.0, 2.0, 16.0),
            Direction.UP, Block.box(0.0, 14.0, 0.0, 16.0, 16.0, 16.0),
            Direction.NORTH, Block.box(0.0, 0.0, 0.0, 16.0, 16.0, 2.0),
            Direction.SOUTH, Block.box(0.0, 0.0, 14.0, 16.0, 16.0, 16.0),
            Direction.WEST, Block.box(0.0, 0.0, 0.0, 2.0, 16.0, 16.0),
            Direction.EAST, Block.box(14.0, 0.0, 0.0, 16.0, 16.0, 16.0));

    protected SurfaceDiodeBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    /** Registers the default state; called by a subclass once its own properties exist. */
    protected void registerDefaults(BlockState state) {
        this.registerDefaultState(state);
    }

    /** One delay setting, in ticks. */
    protected abstract int getDelay(BlockState state);

    /** What the back answers while the diode is on. */
    protected abstract int getOutputSignal(BlockGetter level, BlockPos pos, BlockState state);

    /** Whether the diode counts as switched on right now. */
    protected abstract boolean shouldTurnOn(Level level, BlockPos pos, BlockState state);

    /** The right-click gesture: cycle the delay, flip the mode, ... */
    protected abstract BlockState cycleOnUse(BlockState state);

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, MOUNT, POWERED);
    }

    // ------------------------------------------------------------------ the plate --

    /** The shape of one mounting, for the collision box, the outline and anything that has to report it. */
    public static VoxelShape shapeFor(Direction mount) {
        return SHAPES.get(mount);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES.get(state.getValue(MOUNT));
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos,
                                           CollisionContext context) {
        return SHAPES.get(state.getValue(MOUNT));
    }

    /** A 朝向 has to lie in the plate's own plane; anything along the mount axis would come out of the plate. */
    public static boolean facingFitsMount(Direction mount, Direction facing) {
        return facing.getAxis() != mount.getAxis();
    }

    /** The four 朝向 a mounting can honour, clockwise seen from outside the plate. */
    public static List<Direction> facingRing(Direction mount) {
        return SurfaceRing.of(mount);
    }

    /** The next 朝向 round that ring. */
    public static Direction nextFacing(Direction mount, Direction facing) {
        List<Direction> ring = facingRing(mount);
        int at = ring.indexOf(facing);
        return ring.get(at < 0 ? 0 : (at + 1) % ring.size());
    }

    /** The same 朝向, or the nearest one this mounting can honour when the player aimed into the wall. */
    public static Direction snapFacing(Direction mount, Direction facing) {
        if (facingFitsMount(mount, facing)) {
            return facing;
        }
        return mount.getAxis().isVertical() ? Direction.NORTH : mount.getClockWise();
    }

    /**
     * The 朝向 a click should produce: <b>where the player is looking</b>, turned away from them, so that a plate put
     * on a wall while looking up reads upwards without the rod.
     */
    public static Direction placementFacing(Direction mount, Direction whereThePlayerLooks,
                                            Direction whereThePlayerFacesHorizontally) {
        if (facingFitsMount(mount, whereThePlayerLooks)) {
            return whereThePlayerLooks;
        }
        return snapFacing(mount, whereThePlayerFacesHorizontally);
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        return canStay(state, level, pos);
    }

    /** The same question, public: the placement path asks it before it writes anything. */
    public boolean canStay(BlockState state, LevelReader level, BlockPos pos) {
        Direction mount = state.getValue(MOUNT);
        return com.jiangyuefengyu.redstonecircuit.carrier.CarriedSupport.sturdy(
                level, pos.relative(mount), mount.getOpposite(), SupportType.RIGID);
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbourState,
                                     LevelAccessor level, BlockPos pos, BlockPos neighbourPos) {
        if (direction == state.getValue(MOUNT)) {
            return canStay(state, level, pos) ? state : Blocks.AIR.defaultBlockState();
        }
        return state;
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)))
                .setValue(MOUNT, rotation.rotate(state.getValue(MOUNT)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.setValue(FACING, mirror.mirror(state.getValue(FACING)))
                .setValue(MOUNT, mirror.mirror(state.getValue(MOUNT)));
    }

    // ------------------------------------------------------------------ the diode --

    @Override
    protected boolean isSignalSource(BlockState state) {
        return true;
    }

    @Override
    protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction side) {
        if (!state.getValue(POWERED)) {
            return 0;
        }
        return state.getValue(FACING) == side ? getOutputSignal(level, pos, state) : 0;
    }

    @Override
    protected int getDirectSignal(BlockState state, BlockGetter level, BlockPos pos, Direction side) {
        return state.getSignal(level, pos, side);
    }

    /** The strength arriving at the reading face - vanilla's read, including a wire lying against it. */
    protected int getInputSignal(Level level, BlockPos pos, BlockState state) {
        Direction facing = state.getValue(FACING);
        BlockPos front = pos.relative(facing);
        // 方块内的红石：宿主方块的信号要走模组自己的读法（世界里 / 结构里两个帧），不能只问原版 getSignal。
        int signal = neighbourSignal(level, pos, facing);
        if (signal >= 15) {
            return signal;
        }
        // Vanilla reads a wire lying against the back face straight from its state, and ours has to join that line for
        // a second reason: a sheet answers nothing while one of its own kind is being solved, and a 维持 plate works its
        // output out live - so a plate whose input is a sheet read 0 at exactly the moment the sheet in front of it
        // asked its question, and the two could never agree (作者 2026-11-15: 维持踩不出贴面粉，普通粉可以).
        BlockState wire = level.getBlockState(front);
        int wirePower = 0;
        if (wire.is(Blocks.REDSTONE_WIRE)) {
            wirePower = wire.getValue(RedStoneWireBlock.POWER);
        } else if (wire.getBlock() instanceof SurfaceWireBlock) {
            wirePower = wire.getValue(SurfaceWireBlock.POWER);
        }
        return Math.max(signal, wirePower);
    }

    /**
     * Everything a neighbouring cell can be giving a plate on its {@code side}.
     *
     * <p>Three sources, because "redstone stored in a block" is the mod's own data and reaches the world by three
     * different roads: the plate device read ({@code readingInto}, both frames), the inner network's published answer
     * for that cell ({@code readSignal} - the one a driven component like dust answers with), and finally vanilla's two
     * queries for anything that is not a host at all.
     */
    public static int neighbourSignal(Level level, BlockPos pos, Direction side) {
        BlockPos cell = pos.relative(side);
        int strongest = Math.max(level.getSignal(cell, side), level.getDirectSignal(cell, side));
        strongest = Math.max(strongest, RcPlateBlock.readingInto(level, pos, side));
        if (level instanceof ServerLevel serverLevel) {
            // 存量那一路要单独问：getSignal 的一半在模组自己的求解窗口里是被故意关掉的（见 worldStoreSignalAt），
            // 而这块板子不是那次求解的一部分——它问的是宿主此刻的答案。
            strongest = Math.max(strongest,
                    com.jiangyuefengyu.redstonecircuit.logic.InnerRedstoneNetwork.worldStoreSignalAt(
                            serverLevel, cell, side));
            strongest = Math.max(strongest,
                    com.jiangyuefengyu.redstonecircuit.logic.InnerRedstoneNetwork.readSignal(serverLevel, cell, side));
        }
        return com.jiangyuefengyu.redstonecircuit.logic.PowerSolver.clamp(strongest);
    }

    /** A {@code SignalGetter} is not a {@code Level}; the neighbour reads need the level, and callers always have one. */
    private static Level level1(SignalGetter level) {
        return (Level) level;
    }

    /** The sides that can feed a diode's side input: two for a horizontal one, four horizontals for a vertical one. */
    protected static List<Direction> lockSides(Direction facing) {
        // Every face of the plate but the two the signal runs along: those are the sides a plate can be held by.
        // On the floor and the ceiling that is the two horizontals *plus* up and down - which vanilla never needs
        // because a vanilla diode cannot stand above one pointing down, but this plate can be that neighbour.
        List<Direction> sides = new java.util.ArrayList<>(4);
        for (Direction direction : Direction.values()) {
            if (direction != facing && direction != facing.getOpposite()) {
                sides.add(direction);
            }
        }
        return sides;
    }

    /**
     * The strongest side input.
     *
     * <p>Vanilla's version has an option to count only diodes; this twin is not a {@code DiodeBlock}, so the test is
     * made here instead: a side counts when a switched-on diode stands there - vanilla's, or another plate like this.
     */
    protected int getAlternateSignal(SignalGetter level, BlockPos pos, BlockState state) {
        int strongest = 0;
        for (Direction side : lockSides(state.getValue(FACING))) {
            BlockPos neighbour = pos.relative(side);
            BlockState other = level.getBlockState(neighbour);
            boolean diode = other.getBlock() instanceof DiodeBlock
                    || other.getBlock() instanceof SurfaceDiodeBlock;
            if (diode && other.getValue(POWERED)) {
                strongest = Math.max(strongest, neighbourSignal(level1(level), pos, side));
            }
        }
        return strongest;
    }

    /** Whether a neighbour is holding this diode still; comparators are never locked. */
    public boolean isLocked(LevelReader level, BlockPos pos, BlockState state) {
        return false;
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (isLocked(level, pos, state)) {
            return;
        }
        boolean wasOn = state.getValue(POWERED);
        boolean shouldBeOn = shouldTurnOn(level, pos, state);
        if (wasOn && !shouldBeOn) {
            level.setBlock(pos, state.setValue(POWERED, false), 2);
        } else if (!wasOn) {
            level.setBlock(pos, state.setValue(POWERED, true), 2);
            if (!shouldBeOn) {
                level.scheduleTick(pos, this, getDelay(state), TickPriority.VERY_HIGH);
            }
        }
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block block, BlockPos fromPos,
                                   boolean isMoving) {
        if (!state.canSurvive(level, pos)) {
            BlockEntity blockEntity = state.hasBlockEntity() ? level.getBlockEntity(pos) : null;
            dropResources(state, level, pos, blockEntity);
            level.removeBlock(pos, false);
            for (Direction direction : Direction.values()) {
                level.updateNeighborsAt(pos.relative(direction), this);
            }
            return;
        }
        if (state.getValue(POWERED)) {
            // A comparator's answer is a strength, not just on/off: an input fading from seven to three never changes
            // the on/off answer, so the back has to be told again by hand.
            updateNeighborsInFront(level, pos, state);
        }
        checkTickOnNeighbor(level, pos, state);
    }

    /** Vanilla's: schedule a tick when the answer should change, with the priority vanilla gives a diode. */
    protected void checkTickOnNeighbor(Level level, BlockPos pos, BlockState state) {
        if (isLocked(level, pos, state)) {
            return;
        }
        boolean wasOn = state.getValue(POWERED);
        boolean shouldBeOn = shouldTurnOn(level, pos, state);
        if (wasOn != shouldBeOn && !level.getBlockTicks().willTickThisTick(pos, this)) {
            TickPriority priority = TickPriority.HIGH;
            if (shouldPrioritize(level, pos, state)) {
                priority = TickPriority.EXTREMELY_HIGH;
            } else if (wasOn) {
                priority = TickPriority.VERY_HIGH;
            }
            level.scheduleTick(pos, this, getDelay(state), priority);
        }
    }

    /** Vanilla's: a diode in front that is not facing straight back at us makes this one urgent. */
    public boolean shouldPrioritize(BlockGetter level, BlockPos pos, BlockState state) {
        Direction output = state.getValue(FACING).getOpposite();
        Direction frontFacing = facingOf(level.getBlockState(pos.relative(output)));
        return frontFacing != null && frontFacing != output;
    }

    /** The 朝向 of a diode standing in a cell - vanilla's or ours - or {@code null} when it is not one. */
    @Nullable
    public static Direction facingOf(BlockState state) {
        if (state.getBlock() instanceof DiodeBlock) {
            return state.getValue(BlockStateProperties.HORIZONTAL_FACING);
        }
        if (state.getBlock() instanceof SurfaceDiodeBlock) {
            return state.getValue(FACING);
        }
        return null;
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean isMoving) {
        updateNeighborsInFront(level, pos, state);
        // Vanilla puts this one line in setPlacedBy, which only the vanilla item reaches. Our twins are placed by the
        // mixin on the vanilla item's own path, so setPlacedBy never runs: a diode put beside a wire that is **already**
        // lit then waits for a signal change that never comes (作者 2026-11-11：已经被点亮的墙面上放一个墙面中继器
        // 不会被激活，断开信号重放才行). Scheduling here covers every placement path, and a diode that is already off
        // costs nothing.
        if (!level.isClientSide && state == level.getBlockState(pos) && shouldTurnOn(level, pos, state)) {
            level.scheduleTick(pos, this, 1);
        }
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!isMoving && !state.is(newState.getBlock())) {
            super.onRemove(state, level, pos, newState, isMoving);
            updateNeighborsInFront(level, pos, state);
        }
    }

    /** Vanilla's wake-up of the cell in front, and the notification the mod rebuilds for that cell. */
    protected void updateNeighborsInFront(Level level, BlockPos pos, BlockState state) {
        Direction facing = state.getValue(FACING);
        BlockPos output = pos.relative(facing.getOpposite());
        // This twin is not a DiodeBlock, so the mixin that rebuilds NeighborNotifyEvent never sees it: the one side
        // NeoForge would put in the event is announced here instead.
        if (level instanceof ServerLevel serverLevel) {
            com.jiangyuefengyu.redstonecircuit.logic.InnerRedstoneTickHandler.onNeighborNotify(serverLevel, pos,
                    com.jiangyuefengyu.redstonecircuit.logic.InnerRedstoneTickHandler.side(facing.getOpposite()),
                    "a surface diode's front");
        }
        level.neighborChanged(output, this, pos);
        level.updateNeighborsAtExceptFromFacing(output, this, facing);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hit) {
        if (!player.getAbilities().mayBuild) {
            return InteractionResult.PASS;
        }
        BlockState next = cycleOnUse(state);
        level.setBlock(pos, next, 3);
        afterCycle(level, pos, player, next);
        // A flipped mode or delay changes the answer, so the diode has to look again through the same path an ordinary
        // neighbour change takes - otherwise it keeps answering last time's question until something else happens.
        level.neighborChanged(pos, next.getBlock(), pos);
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /** Anything the gesture should also do - vanilla's comparator clicks, a repeater is silent. */
    protected void afterCycle(Level level, BlockPos pos, Player player, BlockState next) {
    }

    /** 转向杆: one click, one step round the face's own ring. */
    public static Direction turnClockwise(ServerLevel level, BlockPos pos, BlockState state) {
        Direction next = nextFacing(state.getValue(MOUNT), state.getValue(FACING));
        BlockState turned = state.setValue(FACING, next);
        level.setBlock(pos, turned, Block.UPDATE_ALL);
        level.neighborChanged(pos, turned.getBlock(), pos);
        return next;
    }

    /** The redstone dust a working diode gives off, placed against the plate rather than above a floor slab. */
    protected void dustParticle(BlockState state, Level level, BlockPos pos, RandomSource random, float slide) {
        Direction mount = state.getValue(MOUNT);
        Direction facing = state.getValue(FACING);
        double x = pos.getX() + 0.5 + (random.nextDouble() - 0.5) * 0.2 - mount.getStepX() * 0.4;
        double y = pos.getY() + 0.5 + (random.nextDouble() - 0.5) * 0.2 - mount.getStepY() * 0.4;
        double z = pos.getZ() + 0.5 + (random.nextDouble() - 0.5) * 0.2 - mount.getStepZ() * 0.4;
        double offset = slide / 16.0;
        level.addParticle(DustParticleOptions.REDSTONE,
                x + offset * facing.getStepX(), y + offset * facing.getStepY(), z + offset * facing.getStepZ(),
                0.0, 0.0, 0.0);
    }

    /** What the delay-dependent slide of that particle is, vanilla's numbers: -5, or the delay twice minus one. */
    protected static float particleSlide(BlockState state, RandomSource random, int delay) {
        float slide = -5.0F;
        if (random.nextBoolean()) {
            slide = delay * 2 - 1;
        }
        return slide;
    }

    /** Breaking one gives the vanilla item back: these blocks have no items of their own. */
    public abstract ItemStack cloneAs();
}