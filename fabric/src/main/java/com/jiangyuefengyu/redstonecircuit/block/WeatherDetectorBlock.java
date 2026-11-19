package com.jiangyuefengyu.redstonecircuit.block;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.jiangyuefengyu.redstonecircuit.RcTooltips;
import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SupportType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 天气探测器 - a block that keeps a redstone signal on while the weather matches its setting.
 *
 * <p>The shape and the way it is used follow vanilla's daylight detector: a plate that answers with a signal, and a
 * right-click that walks the setting on. Three settings, because the sky has three states a player builds against -
 * <b>晴天</b> (clear), <b>雨天</b> (raining, no thunder) and <b>雷暴</b> (thundering) - and they are mutually exclusive,
 * since thunder only ever happens while it is raining; thunder is therefore asked first. The answer does not depend on
 * where the sun is and needs no view of the sky, only the level's weather, so it works inside a base as well as on a
 * roof, and the strength is a plain fifteen.
 *
 * <p>Consumers only re-read a block when something tells them to, and nothing calls {@code getSignal} when the rain
 * starts, so the block notices the weather itself with a scheduled tick: once a second it compares the weather with what
 * it last announced and writes the new answer into the block state, which notifies the neighbours on its own, exactly
 * like a lever being thrown. {@link #WEATHER} is the setting the player chose and is saved with the world, while
 * {@link #ACTIVE} is what the block is announcing right now - keeping both is what makes "did anything change?" a
 * single comparison.
 *
 * <p><b>哪一面都贴得住。</b>{@link #MOUNT} is the face it is glued to - six values, the same word and the same
 * convention the 贴面 families use - because the plate wants exactly one thing from its cell: a sturdy face behind it.
 * The plate then lies <em>in</em> that face's plane, which the blockstate's {@code x}/{@code y} reaches without any baked
 * geometry: this device has no 朝向 inside the plane (a right-click walks the weather, not a direction), so one pair of
 * angles per mount is the whole pose.
 */
public class WeatherDetectorBlock extends Block {

    public static final MapCodec<WeatherDetectorBlock> CODEC = simpleCodec(WeatherDetectorBlock::new);

    /**
     * The three weathers a detector can watch, in the order a right-click walks them. An {@link EnumProperty} hands its
     * values back in declaration order and {@code BlockState#cycle} is what the plate's right-click uses, so this list
     * <em>is</em> the cycle the player sees - 晴 → 雨 → 雷 → 晴.
     */
    public enum Mode implements StringRepresentable {
        /** Clear: no precipitation at all. */
        CLEAR,
        /** Raining, and not thundering - the two are distinct settings, so a storm is not "rain". */
        RAIN,
        /** Thundering. Thunder implies rain, so this one is asked first. */
        THUNDER;

        @Override
        public String getSerializedName() {
            return this.name().toLowerCase(Locale.ROOT);
        }

        /** The key suffix both the chat message and the Jade line are built from. */
        public String key() {
            return getSerializedName();
        }
    }

    /** The setting: which weather this plate is watching for. */
    public static final EnumProperty<Mode> WEATHER = EnumProperty.create("weather", Mode.class);
    /** What the block is outputting now: true while the weather matches the setting. */
    public static final BooleanProperty ACTIVE = BooleanProperty.create("active");

    /** 挂载: the face it is glued to, the same six values the 贴面 families carry. */
    public static final DirectionProperty MOUNT = DirectionProperty.create("mount", Direction.values());

    /** How often the weather is looked at again, in ticks. One second is far finer than weather moves. */
    private static final int POLL_INTERVAL = 20;

    /**
     * The plate, six pixels thick as the daylight detector's is - lying in whichever face it is glued to.
     *
     * <p>The post and the cap above it do not collide, and the plate is the whole shape: a block whose shape is a full
     * cube would cull the touching faces of its neighbours, and the plate does not fill the cell.
     */
    private static final Map<Direction, VoxelShape> SHAPES = Map.of(
            Direction.DOWN, Block.box(0.0, 0.0, 0.0, 16.0, 6.0, 16.0),
            Direction.UP, Block.box(0.0, 10.0, 0.0, 16.0, 16.0, 16.0),
            Direction.NORTH, Block.box(0.0, 0.0, 0.0, 16.0, 16.0, 6.0),
            Direction.SOUTH, Block.box(0.0, 0.0, 10.0, 16.0, 16.0, 16.0),
            Direction.WEST, Block.box(0.0, 0.0, 0.0, 6.0, 16.0, 16.0),
            Direction.EAST, Block.box(10.0, 0.0, 0.0, 16.0, 16.0, 16.0));

    public WeatherDetectorBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(WEATHER, Mode.CLEAR).setValue(ACTIVE, false)
                .setValue(MOUNT, Direction.DOWN));
    }

    @Override
    protected MapCodec<WeatherDetectorBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(WEATHER, ACTIVE, MOUNT);
    }

    /** A weak source on every side, like the daylight detector: it lights and drives, it does not charge. */
    @Override
    protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction side) {
        return state.getValue(ACTIVE) ? 15 : 0;
    }

    @Override
    protected boolean isSignalSource(BlockState state) {
        return true;
    }

    /**
     * The plate's own shape, six pixels tall - and this is about far more than collisions.
     *
     * <p>A block whose shape is a full cube makes the game cull the touching faces of its neighbours.
     * The plate does not fill the block, so those faces have to stay: without this the block next to
     * it, and the one above it, would be drawn with a hole in them and the world behind would show
     * through. Vanilla's daylight detector answers the same question the same way.
     */
    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos,
                                  CollisionContext context) {
        return SHAPES.get(state.getValue(MOUNT));
    }

    /** Light is only blocked by the plate, not by the whole cube, so the post is not pitch dark. */
    @Override
    protected boolean useShapeForLightOcclusion(BlockState state) {
        return true;
    }

    // ------------------------------------------------------------------ which face --

    /** A plate goes onto the face the player clicked - glued to it, so that face is the mount. */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction mount = context.getClickedFace().getOpposite();
        BlockState state = defaultBlockState().setValue(MOUNT, mount);
        return state.canSurvive(context.getLevel(), context.getClickedPos()) ? state : null;
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        Direction mount = state.getValue(MOUNT);
        return com.jiangyuefengyu.redstonecircuit.carrier.CarriedSupport.sturdy(
                level, pos.relative(mount), mount.getOpposite(), SupportType.RIGID);
    }

    /** Knock the wall away and the plate goes with it, exactly as it does for the 贴面 families. */
    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbourState,
                                     LevelAccessor level, BlockPos pos, BlockPos neighbourPos) {
        if (direction == state.getValue(MOUNT)) {
            return canSurvive(state, level, pos) ? state : Blocks.AIR.defaultBlockState();
        }
        return state;
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(MOUNT, rotation.rotate(state.getValue(MOUNT)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.setValue(MOUNT, mirror.mirror(state.getValue(MOUNT)));
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState,
                           boolean isMoving) {
        super.onPlace(state, level, pos, oldState, isMoving);
        if (!level.isClientSide) {
            refresh(level, pos, state);
            level.scheduleTick(pos, this, POLL_INTERVAL);
        }
    }

    /** Right-click walks to the next weather, then answers immediately rather than a second later. */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hitResult) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        BlockState stepped = state.cycle(WEATHER);
        level.setBlock(pos, stepped, 3);
        refresh(level, pos, stepped);
        // Told only to the player who clicked: a detector's setting is theirs to change, but everyone
        // around it can see the signal itself.
        player.displayClientMessage(Component.translatable(
                "message.redstonecircuit.weather." + stepped.getValue(WEATHER).key()), true);
        return InteractionResult.CONSUME;
    }

    /** The poll: if the weather no longer matches what we announced, announce the new answer. */
    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        refresh(level, pos, state);
        level.scheduleTick(pos, this, POLL_INTERVAL);
    }

    /**
     * Writes the current weather's answer into the state if it differs from what is stored.
     *
     * <p>A {@code setBlock} with flag 3 is what makes the world notice: it re-renders, tells the
     * clients and notifies the neighbours, which is precisely the "something changed here" a lamp or a
     * wire needs - and it is why no notification is sent by hand.
     */
    private static void refresh(Level level, BlockPos pos, BlockState state) {
        boolean wanted = matches(state, level);
        if (state.getValue(ACTIVE) != wanted) {
            level.setBlock(pos, state.setValue(ACTIVE, wanted), 3);
        }
    }

    /** Whether the weather right now is the one this detector is set to watch. */
    private static boolean matches(BlockState state, Level level) {
        return state.getValue(WEATHER) == weatherOf(level.isRaining(), level.isThundering());
    }

    /**
     * The sky right now, as one of the three settings.
     *
     * <p>The whole rule, in one place and as a pure function, because it is the only part of this block
     * that is a decision rather than plumbing: thunder is asked first because it implies rain, so the
     * three answers are mutually exclusive and a plain shower can never light the 雷暴 setting.
     */
    public static Mode weatherOf(boolean raining, boolean thundering) {
        if (thundering) {
            return Mode.THUNDER;
        }
        return raining ? Mode.RAIN : Mode.CLEAR;
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip,
                                TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        RcTooltips.append(tooltip, flag, "block.redstonecircuit.weather_detector.tip",
                RcTooltips.detail("block.redstonecircuit.weather_detector.tip.detail"));
    }
}
