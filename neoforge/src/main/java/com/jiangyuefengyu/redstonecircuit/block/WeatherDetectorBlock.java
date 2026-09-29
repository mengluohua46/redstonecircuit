package com.jiangyuefengyu.redstonecircuit.block;

import java.util.Locale;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 天气探测器 - a block that keeps a redstone signal on while the weather matches its setting.
 *
 * <h2>What it is</h2>
 * The shape and the way it is used follow vanilla's daylight detector: a plate that answers with a
 * signal, and a right-click that walks the setting on to the next weather. Three settings, because the
 * sky has three states a player builds against: <b>晴天</b> (clear), <b>雨天</b> (raining, no thunder)
 * and <b>雷暴</b> (thundering). They are mutually exclusive - thunder only ever happens while it is
 * raining, so thunder is asked first and a plain shower never lights the 雷暴 setting.
 *
 * <p>Unlike the daylight detector its answer does not depend on where the sun is, and it does not need
 * to see the sky: only the level's weather is asked, so it works inside a base as well as on a roof.
 * The strength is a plain fifteen - there is nothing to grade.
 *
 * <h2>How the weather reaches the world</h2>
 * Consumers (lamps, dust, our own inner components) only re-read a block when something tells them to,
 * so the block has to notice the weather itself. It cannot do that from {@code getSignal}: nothing calls
 * it when the rain starts. Vanilla solves the same problem for the daylight detector with a ticker, and
 * this does the same with a scheduled tick: once a second it compares the weather with what it last
 * announced and, when they differ, writes the new answer into the block state - which notifies the
 * neighbours on its own, exactly like a lever being thrown.
 *
 * <p>The two properties are therefore about different things: {@link #WEATHER} is the setting the player
 * chose and is saved with the world, while {@link #ACTIVE} is what the block is announcing right now.
 * Keeping both is what makes "did anything change?" a single comparison.
 */
public class WeatherDetectorBlock extends Block {

    public static final MapCodec<WeatherDetectorBlock> CODEC = simpleCodec(WeatherDetectorBlock::new);

    /**
     * The three weathers a detector can watch, in the order a right-click walks them.
     *
     * <p>Declared in that order on purpose: an {@link EnumProperty} hands its values back in declaration
     * order, and {@code BlockState#cycle} is what the plate's right-click uses, so this list <em>is</em>
     * the cycle the player sees - 晴 → 雨 → 雷 → 晴.
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

    /** How often the weather is looked at again, in ticks. One second is far finer than weather moves. */
    private static final int POLL_INTERVAL = 20;

    /** The plate is six pixels tall, as the daylight detector's is; the post above it does not collide. */
    private static final VoxelShape SHAPE = Block.box(0.0, 0.0, 0.0, 16.0, 6.0, 16.0);

    public WeatherDetectorBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(WEATHER, Mode.CLEAR).setValue(ACTIVE, false));
    }

    @Override
    protected MapCodec<WeatherDetectorBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(WEATHER, ACTIVE);
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
        return SHAPE;
    }

    /** Light is only blocked by the plate, not by the whole cube, so the post is not pitch dark. */
    @Override
    protected boolean useShapeForLightOcclusion(BlockState state) {
        return true;
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
}
