package com.jiangyuefengyu.redstonecircuit.block;

import com.jiangyuefengyu.redstonecircuit.RCRegistry;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.SupportType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 红石粉（贴面）: vanilla's redstone wire, laid on any face of one cell - and on more than one face of the same cell.
 *
 * <p>The state is <b>which faces of this cell carry a sheet</b> ({@link #FACE}, six booleans) plus {@link #POWER}.
 * Everything a sheet connects to is derived from the world when it is asked, never stored: the four in-plane
 * neighbours of a face are {@link #ring}, whether a neighbour is joined is {@link #joined}, and the half of a line
 * that runs towards a 元件 is {@link #drawsTowards}. Storing any of that is what produced the two failures this
 * rewrite removes - a 绕棱 half that had to be re-checked every four ticks (author's whole-world freeze: vanilla's own
 * {@code Too many chained neighbor updates}) and a bend that could not be derived.
 *
 * <p><b>One cell, several faces.</b> Two sheets in one cell are one wire: the power is shared ({@link #POWER} is per
 * cell) and every face draws its own strips. That is what makes a corner work without bending dust around an edge -
 * the corner cell simply carries a sheet on the floor face and a sheet on the wall face.
 *
 * <p>Joins are per plane, and only per plane. Two sheets are joined when they lie on the <b>same</b> face of two
 * neighbouring cells; anything out of plane is ignored, by decision (作者 2026-11-12「平面外情况，就不管了」).
 * Vanilla's own dust always lies on a floor, so it is joined to a sheet of the {@code DOWN} face only.
 *
 * <p><b>State count is a load-time budget.</b> {@code 2^6 × 16} = 1,024 states. The first bent-half design carried
 * 393,216 and hung the client in the loading screen while the model loader walked every one of them.
 */
public class SurfaceWireBlock extends Block implements EntityBlock {

    /** Which faces of this cell carry a sheet. */
    public static final BooleanProperty DOWN = BooleanProperty.create("down");
    public static final BooleanProperty UP = BooleanProperty.create("up");
    public static final BooleanProperty NORTH = BooleanProperty.create("north");
    public static final BooleanProperty SOUTH = BooleanProperty.create("south");
    public static final BooleanProperty WEST = BooleanProperty.create("west");
    public static final BooleanProperty EAST = BooleanProperty.create("east");

    public static final List<BooleanProperty> FACE_PROPERTIES = List.of(DOWN, UP, NORTH, SOUTH, WEST, EAST);

    private static final Map<Direction, BooleanProperty> FACE = new EnumMap<>(Direction.class);

    static {
        for (Direction direction : Direction.values()) {
            FACE.put(direction, FACE_PROPERTIES.get(direction.ordinal()));
        }
    }

    public static final IntegerProperty POWER = BlockStateProperties.POWER;

    /** One pixel of dust, on the face the mounting names. */
    private static final Map<Direction, VoxelShape> SHAPES = Map.of(
            Direction.DOWN, Block.box(0.0, 0.0, 0.0, 16.0, 1.0, 16.0),
            Direction.UP, Block.box(0.0, 15.0, 0.0, 16.0, 16.0, 16.0),
            Direction.NORTH, Block.box(0.0, 0.0, 0.0, 16.0, 16.0, 1.0),
            Direction.SOUTH, Block.box(0.0, 0.0, 15.0, 16.0, 16.0, 16.0),
            Direction.WEST, Block.box(0.0, 0.0, 0.0, 1.0, 16.0, 16.0),
            Direction.EAST, Block.box(15.0, 0.0, 0.0, 16.0, 16.0, 16.0));

    /** The shape of every face mask, indexed by {@link #mask}. Built once: there are only 64 of them. */
    private static final VoxelShape[] SHAPE_CACHE = new VoxelShape[64];

    static {
        SHAPE_CACHE[0] = Shapes.empty();
        for (int bits = 1; bits < SHAPE_CACHE.length; bits++) {
            VoxelShape shape = Shapes.empty();
            for (Direction direction : Direction.values()) {
                if ((bits & bit(direction)) != 0) {
                    shape = Shapes.or(shape, SHAPES.get(direction));
                }
            }
            SHAPE_CACHE[bits] = shape;
        }
    }

    /**
     * How many answers this block must stay silent for - vanilla's {@code shouldSignal} flag, made a counter.
     *
     * <p>Vanilla turns it off while a wire calculates its own strength, so that the neighbouring wires it reads are
     * not also read as full-strength <b>sources</b> in the same breath; without it a run of dust would hold every
     * value at fifteen. Vanilla's flag is an instance field and the block is a singleton, which is why one counter
     * covers every sheet on the level.
     */
    private static int quiet;

    /**
     * How deep the current silence window is. Non-zero outside a recompute means a window was left open, and while it
     * is non-zero <b>every</b> sheet answers nothing at all - the state the watchdog clears (see
     * {@code InnerRedstoneNetwork#watchdog}).
     */
    public static int quietDepth() {
        return quiet;
    }

    /** Ends any silence window that was left open. Only the watchdog calls this. */
    public static void resetQuiet() {
        quiet = 0;
    }

    public SurfaceWireBlock(Properties properties) {
        super(properties);
        BlockState state = stateDefinition.any().setValue(POWER, 0);
        for (BooleanProperty property : FACE_PROPERTIES) {
            state = state.setValue(property, false);
        }
        registerDefaultState(state);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(POWER);
        builder.add(FACE_PROPERTIES.toArray(new BooleanProperty[0]));
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SurfaceWireEntity(pos, state);
    }

    // ---------------------------------------------------------------- the faces --

    private static int bit(Direction direction) {
        return 1 << direction.ordinal();
    }

    /** The six faces as one number, bit per {@link Direction#ordinal()}. */
    public static int mask(BlockState state) {
        int mask = 0;
        for (Direction direction : Direction.values()) {
            if (state.getValue(FACE.get(direction))) {
                mask |= bit(direction);
            }
        }
        return mask;
    }

    public static boolean hasFace(BlockState state, Direction face) {
        return state.getValue(FACE.get(face));
    }

    /** The faces that carry a sheet, in {@link Direction} order. */
    public static List<Direction> faces(BlockState state) {
        List<Direction> held = new ArrayList<>(6);
        for (Direction direction : Direction.values()) {
            if (state.getValue(FACE.get(direction))) {
                held.add(direction);
            }
        }
        return held;
    }

    public static BlockState withFace(BlockState state, Direction face) {
        return state.setValue(FACE.get(face), true);
    }

    public static BlockState withoutFace(BlockState state, Direction face) {
        return state.setValue(FACE.get(face), false);
    }

    /** True for every dust this sheet counts as its own kind of neighbour: ours and vanilla's. */
    public static boolean isWire(BlockState state) {
        return state.getBlock() instanceof SurfaceWireBlock || state.getBlock() instanceof RedStoneWireBlock;
    }

    /**
     * What one sheet of this dust gives back when it is taken off - the item the dust is made of.
     *
     * <p>Asked of the block rather than held as a constant so that a sheet of another dust answers for itself: the
     * superconducting sheet is the same geometry and a different material.
     */
    protected ItemStack sheetItem() {
        return new ItemStack(Items.REDSTONE);
    }

    /** The same item, asked of any sheet's state - breaking and the one-layer click both hold only the state. */
    public static ItemStack sheetItemOf(BlockState state) {
        return state.getBlock() instanceof SurfaceWireBlock sheet ? sheet.sheetItem() : new ItemStack(Items.REDSTONE);
    }

    /**
     * The model path of this dust's strips, up to face and slot: {@code block/surface_dust_down_flat_0}.
     *
     * <p>Public because the strip models are registered from the client setup, which is another package and holds the
     * blocks rather than a state.
     */
    public String stripPrefix() {
        return "block/surface_dust_";
    }

    /** The same prefix, asked of a state: the renderer has a state and needs the block's own art. */
    public static String stripPrefixOf(BlockState state) {
        return state.getBlock() instanceof SurfaceWireBlock sheet ? sheet.stripPrefix() : "block/surface_dust_";
    }

    /** The strength a neighbouring wire holds, or {@code -1} when that cell holds no wire at all. */
    private static int wirePower(BlockState state) {
        if (state.getBlock() instanceof SurfaceWireBlock) {
            return state.getValue(POWER);
        }
        if (state.getBlock() instanceof RedStoneWireBlock) {
            return state.getValue(RedStoneWireBlock.POWER);
        }
        return -1;
    }

    /** Whether a wire on some other cell also carries a sheet on this face - the only join there is. */
    private static boolean holdsPlane(BlockState neighbour, Direction face) {
        if (neighbour.getBlock() instanceof SurfaceWireBlock) {
            return neighbour.getValue(FACE.get(face));
        }
        // Vanilla's wire only ever lies on a floor.
        return neighbour.getBlock() instanceof RedStoneWireBlock && face == Direction.DOWN;
    }

    /** Whether the sheet on {@code face} runs on into the neighbouring cell in direction {@code towards}. */
    public static boolean joined(BlockGetter level, BlockPos pos, Direction face, Direction towards) {
        BlockState neighbour = com.jiangyuefengyu.redstonecircuit.carrier.CarriedBlocks.neighbourOf(level, pos, towards);
        return isWire(neighbour) && holdsPlane(neighbour, face);
    }

    /** Whether the neighbouring cell holds a wire at all. */
    public static boolean adjacentWire(BlockGetter level, BlockPos pos, Direction towards) {
        return isWire(com.jiangyuefengyu.redstonecircuit.carrier.CarriedBlocks.neighbourOf(level, pos, towards));
    }

    /**
     * Vanilla's climb, moved into the plane: a solid in-plane neighbour whose own out-of-plane face is "connectable"
     * counts as joined, so the sheet answers it full power.
     *
     * <p>This is the rule behind vanilla's most surprising reading - dust on a floor lights a repeater that stands
     * beside a <b>stone</b>, even though dust never powers a merely adjacent conductor. Vanilla reaches it through
     * {@code RedstoneSide.UP}: the wire climbs onto the block beside it when the cell above that block accepts a
     * connection. The general form asks the same question on the sheet's own open side, which is what the author's
     * 地面 / 墙面 对拍 needed (作者 2026-11-11「需要对比排查原版机制」).
     */
    private static boolean climbsTowards(BlockGetter level, BlockPos cell, Direction face, Direction towards) {
        if (!com.jiangyuefengyu.redstonecircuit.carrier.CarriedSupport
                .sturdy(level, cell, face.getOpposite(), SupportType.FULL)) {
            return false;
        }
        BlockPos front = cell.relative(face.getOpposite());
        if (front.equals(cell)) {
            return false;
        }
        return com.jiangyuefengyu.redstonecircuit.carrier.CarriedBlocks
                .neighbourOf(level, cell, face.getOpposite()).canRedstoneConnectTo(level, front, null);
    }

    /**
     * Whether the sheet draws a half towards the in-plane neighbour in direction {@code towards} because that
     * neighbour is a 元件 rather than another wire.
     *
     * <p>Answered from the world every time it is asked, never stored: see {@code client.SurfaceWireRenderer} for the
     * junction that made a stored answer impossible.
     */
    public static boolean drawsTowards(BlockGetter level, BlockPos pos, Direction towards) {
        BlockPos cell = pos.relative(towards);
        return !isWire(com.jiangyuefengyu.redstonecircuit.carrier.CarriedBlocks.neighbourOf(level, pos, towards))
                && powersCell(level, pos, cell, towards);
    }

    /** The four in-plane neighbours of a face, in {@link SurfaceRing} order. */
    public static List<Direction> ring(Direction mount) {
        return SurfaceRing.of(mount);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE_CACHE[mask(state)];
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos,
                                           CollisionContext context) {
        return SHAPE_CACHE[mask(state)];
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        return canStay(state, level, pos);
    }

    /** The same question, public: the placement path asks it before it writes anything. */
    public boolean canStay(BlockState state, LevelReader level, BlockPos pos) {
        for (Direction face : faces(state)) {
            if (supported(level, pos, face)) {
                return true;
            }
        }
        return false;
    }

    /** Whether one face still has its support block - read where it is drawn and where the carrier stores it. */
    private static boolean supported(LevelReader level, BlockPos pos, Direction face) {
        return com.jiangyuefengyu.redstonecircuit.carrier.CarriedSupport.sturdy(
                level, pos.relative(face), face.getOpposite(), SupportType.RIGID);
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbourState,
                                     LevelAccessor level, BlockPos pos, BlockPos neighbourPos) {
        if (!hasFace(state, direction) || supported(level, pos, direction)) {
            return state;
        }
        // The one support that went falls off by itself; the sheets that still have theirs stay. This is the single
        // place a sheet can disappear without a player asking, and it writes once - it never asks anyone to look again.
        BlockState left = withoutFace(state, direction);
        return faces(left).isEmpty() ? Blocks.AIR.defaultBlockState() : left;
    }

    @Override
    protected BlockState rotate(BlockState state, net.minecraft.world.level.block.Rotation rotation) {
        BlockState out = state;
        for (Direction direction : Direction.values()) {
            out = out.setValue(FACE.get(rotation.rotate(direction)), state.getValue(FACE.get(direction)));
        }
        return out;
    }

    @Override
    protected BlockState mirror(BlockState state, net.minecraft.world.level.block.Mirror mirror) {
        BlockState out = state;
        for (Direction direction : Direction.values()) {
            out = out.setValue(FACE.get(mirror.mirror(direction)), state.getValue(FACE.get(direction)));
        }
        return out;
    }

    // ---------------------------------------------------------------- the wire --

    @Override
    protected boolean isSignalSource(BlockState state) {
        return quiet == 0;
    }

    @Override
    protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction side) {
        if (quiet > 0) {
            return 0;
        }
        int power = state.getValue(POWER);
        if (power == 0) {
            return 0;
        }
        // side points from the asker to us, so side.getOpposite() is the direction towards the asker.
        Direction towards = side.getOpposite();
        BlockPos asker = pos.relative(towards);
        BlockState neighbour = com.jiangyuefengyu.redstonecircuit.carrier.CarriedBlocks.neighbourOf(level, pos, towards);
        // THE HOP IS CHARGED ONCE, AND IT IS CHARGED ON EVERY ROAD. A hop is a fact about the pair, not about which
        // branch answered: the asker that holds one of our wires reads this sheet as a wire, so this sheet reads it
        // as one too - on the branch that says "the asker is my own support block" as much as on the one that says
        // "we are connected". Before this, the support-block road answered full strength while the asker's own read
        // paid a hop, and a host holding dust held the pair at fifteen for ever after the source was gone (作者
        // 2026-11-16). Vanilla needs no such rule because its wire cannot hang on a host.
        int answer = holdsWire(level, asker) ? Math.max(0, power - 1) : power;
        for (Direction face : faces(state)) {
            if (towards == face) {
                // The asker is the block this face hangs on, which vanilla's wire powers too (dust on a lamp lights it).
                return answer;
            }
            if (ring(face).contains(towards) && (isWire(neighbour) || climbsTowards(level, asker, face, towards))) {
                return answer;
            }
        }
        // 原版判「连上了没有」看的是**传进来的 side 这一侧**，不是朝向提问者的那一侧。作者 2026-11-12 的对拍：
        // 火把在西、方块在东，粉照样给东边那块方块 15（活塞与中继器都被激活）。这条只在提问者是导体、
        // 要吃弱充能时按原版走，两格之间的线另有判据。
        if (neighbour.isRedstoneConductor(level, asker)) {
            // 原版那道判据用的是「side 这一侧连上了没有」：连上 ⇒ 答满强度。连接就是那一格能红石相连。
            BlockPos sideCell = pos.relative(side);
            BlockState sideState = level.getBlockState(sideCell);
            boolean connected = isWire(sideState)
                    || sideState.canRedstoneConnectTo(level, sideCell, side.getOpposite());
            if (connected) {
                for (Direction face : faces(state)) {
                    if (ring(face).contains(side)) {
                        return answer;
                    }
                }
            }
        }
        if (holdsWire(level, asker)) {
            // 问的人若是个「里面装着我们线」的方块，也要按线收一跳：它读我们会满强度（它把我们当世界里的线），
            // 我们读它也收一跳，两边都收，这个环就没有增益 1 了——作者报的自锁就是增益 1 的环。
            return answer;
        }
        if (hasFace(state, side)) {
            // The asker lies out of the plane of a sheet we hold: vanilla refuses the block above its wire.
            return 0;
        }
        return powersCell(level, pos, asker, towards) ? answer : 0;
    }

    @Override
    protected int getDirectSignal(BlockState state, BlockGetter level, BlockPos pos, Direction side) {
        // Vanilla's wire answers both questions the same way, and so does this one.
        return state.getSignal(level, pos, side);
    }

    /**
     * <b>Whether this cell holds one of our wires, in whichever frame it is being read.</b> A host standing in the
     * world keeps its component in the world store; a host a machine is carrying keeps it in a plot and is only
     * visible through the projection ({@code CarrierStore#projectedHostAt}, and its own coordinate through
     * {@code structureHostAt}) - the same two lookups {@code BlockStateSignalMixin} answers a world {@code getSignal}
     * with. The question is asked only to decide a hop, so it may be answered from either frame and must never be
     * answered "no" for a host that the query one line above could read: a full-strength answer across a seam that
     * pays nothing on either side is the pair at gain one that never comes back down (作者 2026-11-16：
     * 贴着宿主的粉撤源之后自锁).
     */
    private static boolean holdsWire(BlockGetter level, BlockPos cell) {
        if (!(level instanceof ServerLevel server)) {
            return false;
        }
        try {
            com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore store =
                    com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore.get(server);
            com.jiangyuefengyu.redstonecircuit.data.Slot mine = store == null ? null : store.slotAt(cell);
            if (mine != null) {
                return mine.type.isWire();
            }
            com.jiangyuefengyu.redstonecircuit.carrier.CarrierProjection.ProjectedHost drawn =
                    com.jiangyuefengyu.redstonecircuit.carrier.CarrierStore.projectedHostAt(server, cell);
            if (drawn != null && drawn.slot() != null) {
                return drawn.slot().type.isWire();
            }
            com.jiangyuefengyu.redstonecircuit.carrier.CarrierProjection.ProjectedHost inside =
                    com.jiangyuefengyu.redstonecircuit.carrier.CarrierStore.structureHostAt(server, cell);
            return inside != null && inside.slot() != null && inside.slot().type.isWire();
        } catch (RuntimeException | LinkageError failed) {
            // A jar without the physics half must behave like a world with no machines in it.
            return false;
        }
    }

    /**
     * Whether a cell is something this sheet drives even though no line is drawn to it.
     *
     * <p>Vanilla's wire asks {@code canRedstoneConnectTo}, which is true for any signal source; a host block is a
     * signal source only through the mod's own mixin, so it is asked directly.
     */
    private static boolean powersCell(BlockGetter level, BlockPos pos, BlockPos cell, Direction towards) {
        BlockState neighbour = com.jiangyuefengyu.redstonecircuit.carrier.CarriedBlocks.neighbourOf(level, pos, towards);
        if (neighbour.canRedstoneConnectTo(level, cell, towards)) {
            return true;
        }
        if (neighbour.isSignalSource()) {
            return true;
        }
        return level instanceof ServerLevel server
                && com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore.get(server).slotAt(cell) != null;
    }

    /**
     * Vanilla's scan over the six neighbours, minus the wires - those are read by {@link #wireStrength} one hop at a
     * time.
     *
     * <p>A neighbouring host block (红石 inside a block) is read twice: {@code getSignal} answers it only while the
     * mod's own solve window is closed, and a sheet's strength is recomputed from inside that window whenever the
     * solver wakes it. The store read is the one that still answers there.
     */
    private static int sourceStrength(BlockGetter level, BlockPos pos) {
        int strongest = 0;
        boolean server = level instanceof ServerLevel;
        for (Direction direction : Direction.values()) {
            BlockPos cell = pos.relative(direction);
            if (isWire(level.getBlockState(cell))) {
                continue;
            }
            int signal = signalInto(level, cell, direction);
            if (server) {
                signal = Math.max(signal, com.jiangyuefengyu.redstonecircuit.logic.InnerRedstoneNetwork
                        .worldStoreSignalAt((ServerLevel) level, cell, direction));
            }
            // 方块里的**线**按线付一跳，而且是对**整个读数**付：宿主方块有三条路会答（mixin 的 getSignal、
            // getDirectSignal、存量直读），只给其中一路扣跳的话，另外两路仍是满强度，取大之后就白扣了。
            if (signal > 0 && holdsWire(level, cell)) {
                signal = Math.max(0, signal - 1);
            }
            strongest = Math.max(strongest, signal);
        }
        return strongest;
    }

    /**
     * Vanilla's {@code Level.getSignal}: a block's own answer, and - when it is a redstone conductor - the strongest
     * direct signal poured into it from any side.
     *
     * <p>A conductor never answers {@code getDirectSignal} for itself: the power is not stored in the block, it is the
     * neighbour's answer that is collected on demand. Asking the block directly therefore always read zero.
     */
    private static int signalInto(BlockGetter level, BlockPos cell, Direction side) {
        BlockState state = level.getBlockState(cell);
        int signal = state.getSignal(level, cell, side);
        return state.isRedstoneConductor(level, cell) ? Math.max(signal, directSignalTo(level, cell)) : signal;
    }

    /**
     * Vanilla's {@code SignalGetter.getDirectSignalTo}: the strongest direct signal the six neighbours pour in, with
     * one difference - a <b>wire's</b> power arriving this way pays the hop it would have paid directly.
     *
     * <p>Vanilla never needs that subtraction because its wire cannot leave the face it lies on, so two wires can never
     * reach each other through one block. Ours can: a block with vanilla dust on top and a sheet on its side are
     * diagonal to each other and would hold each other at fifteen - a loop with gain 1, which left both lit after the
     * source was gone (作者 2026-11-11：去掉信号源自锁). Charging the hop makes the loop lose one per round.
     */
    private static int directSignalTo(BlockGetter level, BlockPos cell) {
        int best = 0;
        for (Direction direction : Direction.values()) {
            BlockPos from = cell.relative(direction);
            BlockState contributor = com.jiangyuefengyu.redstonecircuit.carrier.CarriedBlocks.neighbourOf(level, cell, direction);
            int signal = contributor.getDirectSignal(level, from, direction);
            if (signal > 0 && isWire(contributor)) {
                signal--;
            }
            best = Math.max(best, signal);
            if (best >= 15) {
                return best;
            }
        }
        return best;
    }

    /** The strongest wire joined to this cell, already charged the hop a sheet pays for it. */
    private static int wireStrength(BlockGetter level, BlockPos pos, BlockState state) {
        int best = 0;
        for (Direction face : faces(state)) {
            for (Direction direction : ring(face)) {
                BlockState neighbour = com.jiangyuefengyu.redstonecircuit.carrier.CarriedBlocks.neighbourOf(level, pos, direction);
                int power = wirePower(neighbour);
                if (power < 0) {
                    continue;
                }
                // The superconductor hands its whole run over for nothing; every other wire costs the one hop a sheet
                // pays its neighbour, exactly vanilla's own subtraction.
                boolean lossless = com.jiangyuefengyu.redstonecircuit.block.SuperconductingWireBlock
                        .isSuperconducting(neighbour);
                best = Math.max(best, lossless ? power : power - 1);
            }
        }
        return best;
    }

    /** Vanilla's {@code calculateTargetStrength}: a source anywhere, or the best neighbouring wire one hop down. */
    private static int targetStrength(Level level, BlockPos pos, BlockState state) {
        int source;
        quiet++;
        try {
            source = sourceStrength(level, pos);
        } finally {
            quiet--;
        }
        return Math.max(source, Math.max(0, wireStrength(level, pos, state)));
    }

    /**
     * Brings this sheet's strength up to date. A dust whose strength is decided somewhere else - the superconductor
     * has one network for all of its wire, floor and sheets together - answers this with that network instead.
     */
    protected void updateStrength(Level level, BlockPos pos, BlockState state) {
        updatePowerStrength(level, pos, state);
    }

    /**
     * <b>The sheet's recompute entry, for a sheet a machine is carrying.</b>
     *
     * <p>A sheet inside a structure lives at a coordinate of that structure, so nothing that changes in the world next to
     * where it is <b>drawn</b> ever notifies it - a plot cell's neighbours are the plot's. The per-tick walk over a
     * physicalised structure's own signal sources is the only caller that can turn one over while it rides, which is also
     * why a junction between two structures stayed dark (作者 2026-11-17：贴面红石无法跨结构激活). The strength itself is
     * derived from {@link com.jiangyuefengyu.redstonecircuit.carrier.CarriedBlocks#neighbourOf}, which reads both frames,
     * so this only has to ask again.
     */
    /**
     * <b>The sheets that ride a machine, by level and position.</b> The per-tick walk over a physicalised structure
     * covers the plot <b>box</b> a slice at a time, so a sheet's turn comes once per pass - and a pass over a big machine
     * is long. A sheet is cheap to recompute and there are few of them, so the ones that are known to be carried are
     * kept here and refreshed <b>every</b> tick instead of waiting for the cursor (作者 2026-11-17：跨结构点不亮，
     * 而且那张粉连一行自己的诊断都没有 —— 它根本没人重算过).
     */
    private static final Map<net.minecraft.resources.ResourceKey<Level>, it.unimi.dsi.fastutil.longs.LongSet> CARRIED =
            new java.util.HashMap<>();

    /** Records a sheet that lives in a structure's own storage, so the walk asks it every tick. */
    public static void noteCarried(ServerLevel level, BlockPos pos, BlockState state) {
        if (!(state.getBlock() instanceof SurfaceWireBlock)) {
            return;
        }
        // NO OWNERSHIP GATE. It was one, and it answered "no structure here" for a cell the diagnostic had just printed
        // as a plot coordinate ({@code carrier=none}) - so a sheet placed inside a machine was never enrolled and nothing
        // ever looked at it. A sheet is cheap to recompute and the table only holds our own blocks, so every one of them
        // is kept and re-derived each tick; the entries that stop being ours are dropped as they are met.
        
        CARRIED.computeIfAbsent(level.dimension(), key -> new it.unimi.dsi.fastutil.longs.LongOpenHashSet())
                .add(pos.asLong());
    }

    /**
     * Recomputes every known carried sheet of this level, once. Cells that are no longer one of our sheets - broken,
     * turned into something else, or no longer inside a structure - are dropped as they are met.
     */
    public static void refreshCarriedSheets(ServerLevel level) {
        it.unimi.dsi.fastutil.longs.LongSet cells = CARRIED.get(level.dimension());
        if (cells == null || cells.isEmpty()) {
            return;
        }
        it.unimi.dsi.fastutil.longs.LongIterator it = cells.iterator();
        java.util.List<Long> gone = new java.util.ArrayList<>();
        while (it.hasNext()) {
            long packed = it.nextLong();
            BlockPos cell = BlockPos.of(packed);
            BlockState state = level.getBlockState(cell);
            if (!(state.getBlock() instanceof SurfaceWireBlock)) {
                gone.add(packed);
                continue;
            }
            refreshCarried(level, cell, state);
        }
        for (Long packed : gone) {
            cells.remove(packed.longValue());
        }
    }

    public static void refreshCarried(ServerLevel level, BlockPos cell, BlockState state) {
        if (state.getBlock() instanceof SurfaceWireBlock wire) {
            // Being asked at all is the registration: a sheet placed by an older build was never in the table, so the
            // first time anything reaches it - the box walk, a world poke, a neighbour change - it is enrolled.
            noteCarried(level, cell, state);
            wire.updateStrength(level, cell, state);
        }
    }

    /** Vanilla's {@code updatePowerStrength}: write the new strength, then wake the cell and everything around it. */
    private static void updatePowerStrength(Level level, BlockPos pos, BlockState state) {
        int target = targetStrength(level, pos, state);
        if (state.getValue(POWER) == target) {
            return;
        }
        BlockState updated = state.setValue(POWER, target);
        if (level.getBlockState(pos) == state) {
            level.setBlock(pos, updated, 2);
        }
        Set<BlockPos> cells = new java.util.HashSet<>();
        cells.add(pos);
        for (Direction direction : Direction.values()) {
            cells.add(pos.relative(direction));
        }
        for (BlockPos cell : cells) {
            level.updateNeighborsAt(cell, updated.getBlock());
        }
        // AND WHERE THE STRENGTH IS DRAWN. A sheet riding a machine stands in a plot; the wire beside it that the player
        // built stands in the world at the pose, and a plot cell's neighbour update never reaches it. Same two-frame wake
        // every plate device already does (RcPlateBlock#wakeOutput).
        if (level instanceof ServerLevel server) {
            com.jiangyuefengyu.redstonecircuit.carrier.ProductFrame carried =
                    com.jiangyuefengyu.redstonecircuit.carrier.ProductFrame.carriedAt(server, pos);
            BlockPos drawn = carried.carried() ? carried.worldPosOf(pos) : null;
            if (drawn != null && !drawn.equals(pos)) {
                level.updateNeighborsAt(drawn, updated.getBlock());
                com.jiangyuefengyu.redstonecircuit.logic.InnerRedstoneNetwork.notifyOutputChanged(server, drawn);
                com.jiangyuefengyu.redstonecircuit.logic.InnerRedstoneNetwork.notifyOutputChangedNow(server, drawn);
            }
        }
    }

    /**
     * <b>One line per recompute of a sheet that rides a machine:</b> what it holds, and what each neighbour of its own
     * plane answers in both frames. A sheet that stays dark while the player expects it lit is either not being
     * recomputed, reading air where the other wire is, or reading a block that is not a wire - and only the neighbour
     * answers can tell those apart (作者 2026-11-17：跨结构还是点不亮).
     */
    private static void probe(Level level, BlockPos pos, BlockState state, int target) {
        if (!(level instanceof ServerLevel server) || !com.jiangyuefengyu.redstonecircuit.RCConfig.debugLog()) {
            return;
        }
        com.jiangyuefengyu.redstonecircuit.carrier.ProductFrame frame =
                com.jiangyuefengyu.redstonecircuit.carrier.ProductFrame.carriedAt(server, pos);
        // <b>Both sides of a junction report</b>: the sheet inside a machine and the sheet standing in the world beside
        // it read each other, and only one of the two is "carried" - the world one is the side that never reported before,
        // which is why the first probe answered nothing at all (作者 2026-11-17 的日志：wire 行 0 条).
        StringBuilder line = new StringBuilder();
        line.append("wire ").append(pos.toShortString())
                .append(frame.carried() ? " drawn at " + frame.worldPosOf(pos).toShortString() : " in the world")
                .append(" power ").append(state.getValue(POWER)).append("->").append(target);
        for (Direction face : faces(state)) {
            line.append(" [").append(face).append("]");
            for (Direction towards : ring(face)) {
                BlockState seen = com.jiangyuefengyu.redstonecircuit.carrier.CarriedBlocks
                        .neighbourOf(level, pos, towards);
                line.append(' ').append(towards).append('=')
                        .append(seen.getBlock() instanceof SurfaceWireBlock ? "wire" : seen.getBlock());
            }
        }
        com.jiangyuefengyu.redstonecircuit.carrier.CarriedBlocks.noteWire(level, line.toString());
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean isMoving) {
        if (oldState.is(state.getBlock()) || level.isClientSide) {
            return;
        }
        updateStrength(level, pos, level.getBlockState(pos));
        for (Direction direction : Direction.values()) {
            level.updateNeighborsAt(pos.relative(direction), this);
        }
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (isMoving || state.is(newState.getBlock())) {
            return;
        }
        super.onRemove(state, level, pos, newState, isMoving);
        if (level.isClientSide) {
            return;
        }
        for (Direction direction : Direction.values()) {
            level.updateNeighborsAt(pos.relative(direction), this);
        }
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block block, BlockPos fromPos,
                                   boolean isMoving) {
        if (level.isClientSide) {
            return;
        }
        if (!canStay(state, level, pos)) {
            dropResources(state, level, pos);
            level.removeBlock(pos, false);
            return;
        }
        updateStrength(level, pos, state);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state = withFace(defaultBlockState(), context.getClickedFace().getOpposite());
        return canStay(state, context.getLevel(), context.getClickedPos()) ? state : null;
    }

    /** One face of a multi-face sheet, taken off by hand: the click names the layer, not the whole cell. */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hit) {
        Direction face = hit.getDirection().getOpposite();
        if (!removeFace(level, pos, state, face)) {
            return InteractionResult.PASS;
        }
        level.playSound(player, pos, state.getSoundType().getBreakSound(), SoundSource.BLOCKS, 0.7F, 1.0F);
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /**
     * Takes one face off a sheet and hands one redstone back, or does nothing when that face is all there is.
     *
     * <p>A cell with a single face is removed by breaking it, as any block is: the click only means "this layer" when
     * there is more than one layer to name.
     */
    public static boolean removeFace(Level level, BlockPos pos, BlockState state, Direction face) {
        if (faces(state).size() < 2 || !hasFace(state, face)) {
            return false;
        }
        level.setBlock(pos, withoutFace(state, face), 3);
        if (!level.isClientSide) {
            Block.popResource(level, pos, sheetItemOf(state));
        }
        return true;
    }

    @Override
    public List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        List<ItemStack> drops = new ArrayList<>(6);
        for (int i = 0; i < faces(state).size(); i++) {
            drops.add(sheetItemOf(state));
        }
        return drops;
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (state.getValue(POWER) == 0) {
            return;
        }
        for (Direction face : faces(state)) {
            Direction out = face.getOpposite();
            double x = pos.getX() + 0.5 + (random.nextDouble() - 0.5) * 0.6 + out.getStepX() * 0.45;
            double y = pos.getY() + 0.5 + (random.nextDouble() - 0.5) * 0.6 + out.getStepY() * 0.45;
            double z = pos.getZ() + 0.5 + (random.nextDouble() - 0.5) * 0.6 + out.getStepZ() * 0.45;
            level.addParticle(DustParticleOptions.REDSTONE, x, y, z, 0.0, 0.0, 0.0);
        }
    }

    @Override
    public ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state) {
        return sheetItemOf(state);
    }
}
