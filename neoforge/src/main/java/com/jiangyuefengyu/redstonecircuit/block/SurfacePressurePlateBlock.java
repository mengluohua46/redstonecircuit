package com.jiangyuefengyu.redstonecircuit.block;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SupportType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 压力板（贴面）- vanilla's pressure plates on the faces vanilla has no plate for. A click on a top face is vanilla's
 * own placement and is never touched (作者 2026-11-14).
 *
 * <p><b>One block per kind</b>, not one block with a kind property: the four plates then carry their own name, their
 * own drop and their own model file, exactly like vanilla's four, and nothing has to be decided at display time.
 *
 * <p>Rules, per kind, copied from vanilla:
 *
 * <ul>
 *   <li><b>stone / oak</b>: 15 while something stands in the cell, released {@link #RELEASE} ticks after the last one
 *       leaves - {@code BasePressurePlateBlock}'s delay;</li>
 *   <li><b>light weighted</b>: {@code ceil(count / 15 * 15)}, one step per entity;</li>
 *   <li><b>heavy weighted</b>: {@code ceil(count / 150 * 15)}, one step per ten entities.</li>
 * </ul>
 *
 * <p>The signal is the same on all six sides ({@code getSignalForState}), and the panel itself is vanilla's slab:
 * 1 to 15 inside the cell, half a pixel thinner while pressed.
 */
public class SurfacePressurePlateBlock extends Block {

    /** Which of vanilla's four plates this is. */
    public enum Kind {
        STONE("stone", 0, Items.STONE_PRESSURE_PLATE),
        OAK("oak", 0, Items.OAK_PRESSURE_PLATE),
        LIGHT("light", 15, Items.LIGHT_WEIGHTED_PRESSURE_PLATE),
        HEAVY("heavy", 150, Items.HEAVY_WEIGHTED_PRESSURE_PLATE);

        final String name;
        /** 0 for the plates that only know "pressed"; the entity count that counts as full for the weighted ones. */
        final int maxWeight;
        final net.minecraft.world.item.Item item;

        Kind(String name, int maxWeight, net.minecraft.world.item.Item item) {
            this.name = name;
            this.maxWeight = maxWeight;
            this.item = item;
        }

        boolean weighted() {
            return maxWeight > 0;
        }
    }

    /** 贴面 - towards the block the plate lies against. */
    public static final DirectionProperty MOUNT = DirectionProperty.create("mount", Direction.values());

    public static final IntegerProperty POWER = BlockStateProperties.POWER;

    /** Vanilla's press delay for the two unweighted plates, in ticks. */
    private static final int RELEASE = 20;

    private static final Map<String, VoxelShape> SHAPES = new HashMap<>();

    static {
        for (Direction mount : Direction.values()) {
            for (boolean pressed : new boolean[] {false, true}) {
                double depth = pressed ? 0.5 : 1.0;
                SHAPES.put(mount.getSerializedName() + "/" + pressed, switch (mount) {
                    case DOWN -> Block.box(1.0, 0.0, 1.0, 15.0, depth, 15.0);
                    case UP -> Block.box(1.0, 16.0 - depth, 1.0, 15.0, 16.0, 15.0);
                    case NORTH -> Block.box(1.0, 1.0, 0.0, 15.0, 15.0, depth);
                    case SOUTH -> Block.box(1.0, 1.0, 16.0 - depth, 15.0, 15.0, 16.0);
                    case WEST -> Block.box(0.0, 1.0, 1.0, depth, 15.0, 15.0);
                    case EAST -> Block.box(16.0 - depth, 1.0, 1.0, 16.0, 15.0, 15.0);
                });
            }
        }
    }

    private final Kind kind;

    public SurfacePressurePlateBlock(Properties properties, Kind kind) {
        super(properties);
        this.kind = kind;
        registerDefaultState(stateDefinition.any().setValue(MOUNT, Direction.UP).setValue(POWER, 0));
    }

    public Kind kind() {
        return kind;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(MOUNT, POWER);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES.getOrDefault(state.getValue(MOUNT).getSerializedName() + "/" + (state.getValue(POWER) > 0),
                Block.box(1.0, 0.0, 1.0, 15.0, 1.0, 15.0));
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        return canStay(state, level, pos);
    }

    public boolean canStay(BlockState state, LevelReader level, BlockPos pos) {
        Direction mount = state.getValue(MOUNT);
        return com.jiangyuefengyu.redstonecircuit.carrier.CarriedSupport.sturdy(
                level, pos.relative(mount), mount.getOpposite(), SupportType.CENTER);
    }

    /**
     * <b>Live entities pressing the plate - counted where the plate is drawn.</b> Dropped items do not press it.
     *
     * <p>A plate riding a machine keeps its block state at a coordinate of the carrier's own storage, while the player
     * stands where the machine is drawn: the two are different cells, and the store's one has nobody in it (作者
     * 2026-11-16：物理方块上的贴面压力板无法被激活). Both frames are therefore counted and the larger answer wins, the
     * same rule every other carried read in this mod follows; a plate standing in the world degenerates to one cell.
     */
    private static int entityCount(Level level, BlockPos pos) {
        int here = countEntities(level, pos);
        if (!(level instanceof ServerLevel server)) {
            return here;
        }
        try {
            com.jiangyuefengyu.redstonecircuit.carrier.ProductFrame frame =
                    com.jiangyuefengyu.redstonecircuit.carrier.ProductFrame.carriedAt(server, pos);
            if (!frame.carried()) {
                return here;
            }
            BlockPos drawn = frame.worldPosOf(pos);
            return drawn.equals(pos) ? here : Math.max(here, countEntities(level, drawn));
        } catch (RuntimeException | LinkageError failed) {
            // A jar without the physics half behaves like a level with no machines in it.
            return here;
        }
    }

    /** How many entities stand inside one cell. */
    private static int countEntities(Level level, BlockPos cell) {
        List<Entity> found = level.getEntities((Entity) null, new AABB(cell),
                (entity) -> entity.isAlive() && !(entity instanceof ItemEntity));
        return found.size();
    }

    /** What the plate should read right now, in vanilla's own terms. */
    private int targetPower(Level level, BlockPos pos) {
        int count = entityCount(level, pos);
        if (!kind.weighted()) {
            return count > 0 ? 15 : 0;
        }
        int capped = Math.min(count, kind.maxWeight);
        return capped > 0 ? Mth.ceil((float) capped / (float) kind.maxWeight * 15.0F) : 0;
    }

    private static void applyPower(Level level, BlockPos pos, BlockState state, int power) {
        if (state.getValue(POWER) == power) {
            return;
        }
        level.setBlock(pos, state.setValue(POWER, power), 3);
        boolean pressed = power > 0;
        level.playSound(null, pos, pressed ? SoundEvents.STONE_PRESSURE_PLATE_CLICK_ON
                : SoundEvents.STONE_PRESSURE_PLATE_CLICK_OFF, SoundSource.BLOCKS, 0.3F, pressed ? 0.6F : 0.5F);
        level.updateNeighborsAt(pos, state.getBlock());
    }

    @Override
    protected void entityInside(BlockState state, Level level, BlockPos pos, Entity entity) {
        if (level.isClientSide) {
            return;
        }
        int target = targetPower(level, pos);
        if (kind.weighted() || (target > 0 && state.getValue(POWER) == 0)) {
            applyPower(level, pos, state, target);
        }
        if (!level.getBlockTicks().hasScheduledTick(pos, this)) {
            level.scheduleTick(pos, this, kind.weighted() ? 1 : RELEASE);
        }
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        int target = targetPower(level, pos);
        applyPower(level, pos, state, target);
        if (target > 0) {
            level.scheduleTick(pos, this, kind.weighted() ? 1 : RELEASE);
        }
    }

    /**
     * <b>The plate's recompute entry, for a plate a machine is carrying.</b>
     *
     * <p>A carried plate never hears the thing it is measuring: {@code entityInside} is driven by an entity walking into
     * a block the level has at that position, and the plot cell holds nobody - the feet are on the drawn block. So the
     * one caller is the per-tick walk over a physicalised structure's own signal sources ({@code SableProductDriver}),
     * which asks every device to derive its answer again; while carried, that walk is also the plate's <b>clock</b>, so
     * nothing here schedules a tick - a plot cell's scheduled tick is not the level's, and would never come.
     */
    public static void refreshCarried(ServerLevel level, BlockPos cell, BlockState state) {
        if (!(state.getBlock() instanceof SurfacePressurePlateBlock plate)) {
            return;
        }
        applyPower(level, cell, state, plate.targetPower(level, cell));
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
        }
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbourState,
                                     LevelAccessor level, BlockPos pos, BlockPos neighbourPos) {
        if (direction == state.getValue(MOUNT) && !canStay(state, level, pos)) {
            return Blocks.AIR.defaultBlockState();
        }
        return state;
    }

    @Override
    protected boolean isSignalSource(BlockState state) {
        return true;
    }

    @Override
    protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction side) {
        return state.getValue(POWER);
    }

    @Override
    public List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        return List.of(new ItemStack(kind.item));
    }

    @Override
    public ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state) {
        return new ItemStack(kind.item);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction clicked = context.getClickedFace();
        if (clicked == Direction.UP) {
            return null;   // 地面上的压力板是原版的
        }
        BlockState state = defaultBlockState().setValue(MOUNT, clicked.getOpposite());
        return canStay(state, context.getLevel(), context.getClickedPos()) ? state : null;
    }
}
