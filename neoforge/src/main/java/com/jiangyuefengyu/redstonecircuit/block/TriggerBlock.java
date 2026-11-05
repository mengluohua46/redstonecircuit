package com.jiangyuefengyu.redstonecircuit.block;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.jiangyuefengyu.redstonecircuit.RcTooltips;
import com.jiangyuefengyu.redstonecircuit.logic.InnerRedstoneNetwork;
import com.jiangyuefengyu.redstonecircuit.logic.TriggerRule;
import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.SignalGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 红石触发器: 一条上升沿切换一次开关 - 正面读一次电, 背面答 15 或 0.
 *
 * <p>It is a 红石元件 in the same two shapes as 同步火把: a right-click on the ground puts this block in the world, a
 * shift-right-click on a host block stores it inside as {@code ComponentType.TRIGGER} ({@code HostRules}). Both forms
 * step the <b>same</b> rule - {@link TriggerRule} - so they cannot answer differently.
 *
 * <p>方向与 拉杆 同一套, 只是含义挪了一位: {@link #FACING} 是<b>正面</b>, 也就是<b>它读的那一面</b>, 输出走
 * {@code FACING.getOpposite()}. 拉杆 站着的时候读自己整圈邻居 (它靠 {@code hasNeighborSignal}), 这一件只读正面那一面:
 * "这一面进, 对面出" 是它作为一个器件的全部方向约定, 也是内嵌形态 ({@code Slot#facing} = 它读的那一面) 逐字相同的那一条.
 *
 * <p>状态里有一格<b>不是答案</b>的布尔: {@link #INPUT}. 开关 ({@link #POWERED}) 判不出"这一次是上升沿还是保持通电" -
 * 一个开着的触发器和一个刚刚被上升沿打开、之后一直通着电的触发器在"现在有电"上一模一样 - 所以"上一次读到的输入"必须是
 * 方块自己的一个事实, 而且必须跟着存档走: 丢掉它, 重进世界的第一趟会把一次<b>保持</b>当成一次<b>上升沿</b>, 于是开着的
 * 触发器自己就翻回去了. 这就是这一格存在的全部理由.
 *
 * <p>贴法只有两种: 贴地 (点顶面) 与贴天花板 (点底面). 对着墙点<b>拒绝</b> ({@link #faceFor} 给 {@code null}), 而不是
 * 降级成贴地: 降级会把玩家手里的朝向悄悄挪到另一个面上, 而"放不下"是玩家看得懂、也拿得回东西的那一种结果.
 *
 * <p>它<b>没有</b>右键动作: 这一件不继承 {@code LeverBlock#use}, 开关只听红石的 ({@link #evaluate}). 一件没有界面的
 * 器件不该把玩家的右键用掉.
 */
public class TriggerBlock extends Block {

    public static final MapCodec<TriggerBlock> CODEC = simpleCodec(TriggerBlock::new);

    /**
     * 贴法: 贴地 或 贴天花板, 没有贴墙 (see {@link #faceFor}).
     *
     * <p>键名仍是 {@code face}, 取值仍是 floor / ceiling - 与 拉杆 同名, 资源那边按熟悉的名字写 32 个 variant. 但属性是
     * <b>本件自己的两值属性</b>, 不是原版 {@code BlockStateProperties.ATTACH_FACE}: 那个有三个值 (floor / wall / ceiling),
     * 直接拿来用会给状态空间凭空多出 4 × 2 × 2 = 16 个 {@code face=wall} 的状态 - 它们永远到不了 ({@link #faceFor} 拒绝
     * 四个水平面), 也没有任何一个模型, 于是客户端画到它们时只能报 missing model. "这一件不做贴墙"于是由<b>状态空间</b>表达,
     * 而不是只靠放置那一刻的拒绝: 一个不存在的状态不需要谁来守.
     */
    public static final EnumProperty<AttachFace> FACE =
            EnumProperty.create("face", AttachFace.class, AttachFace.FLOOR, AttachFace.CEILING);

    /**
     * 正面 - the face it <b>reads</b>; the answer leaves on {@code FACING.getOpposite()}. Horizontal, because the block
     * only ever lies on the floor or hangs from the ceiling.
     */
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    /** 开关: the answer itself, 15 while true. Written by {@link #evaluate} and by nothing else. */
    public static final BooleanProperty POWERED = BlockStateProperties.POWERED;

    /**
     * 上一次读到的输入 - the edge memory, and the one property here that is not the answer (see the class comment).
     *
     * <p>Written as well as read by {@link #evaluate}: a reading that is not stored is a reading the next notification
     * cannot compare against, and then every notification looks like a rising edge.
     */
    public static final BooleanProperty INPUT = BooleanProperty.create("input");

    /** 贴地: 一小段柱子立在格子底部, 与红石火把同一个高度. */
    private static final VoxelShape SHAPE_FLOOR = Block.box(5.0, 0.0, 5.0, 11.0, 10.0, 11.0);

    /** 贴天花板: 同一根柱子挂在格子顶部. */
    private static final VoxelShape SHAPE_CEILING = Block.box(5.0, 6.0, 5.0, 11.0, 16.0, 11.0);

    public TriggerBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
                .setValue(FACE, AttachFace.FLOOR)
                .setValue(FACING, Direction.NORTH)
                .setValue(POWERED, false)
                // "I have never seen my 正面 powered": what a fresh trigger has to start from, because the very first
                // reading is then a rising edge and the device answers it. The inner form starts from exactly the same
                // false ({@code Slot#triggerInput}), so a trigger placed against a live wire starts switched on in both.
                .setValue(INPUT, false));
    }

    @Override
    protected MapCodec<TriggerBlock> codec() {
        return CODEC;
    }

    /** 贴法 2 × 朝向 4 × 开关 2 × 输入 2 = 32 states. */
    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACE, FACING, POWERED, INPUT);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return state.getValue(FACE) == AttachFace.CEILING ? SHAPE_CEILING : SHAPE_FLOOR;
    }

    // ------------------------------------------------------------------ 方向 --

    /** 背面 - where the answer leaves, given the state. */
    public static Direction outputFace(BlockState state) {
        return state.getValue(FACING).getOpposite();
    }

    /**
     * 点击的那一面 → 贴法, 或者 {@code null} 表示这一面<b>不接受</b>.
     *
     * <p>顶面贴地, 底面贴天花板, 四个侧面一律拒绝: 一边贴墙的触发器读的是墙上那一面, 而这一件只做"立在方块上"与
     * "吊在方块下"两种姿势 (内嵌形态才可以竖着 - 它的 {@code facing} 是上/下也照样成立). 拒绝比降级诚实: 降级会把这个
     * 朝向约定悄悄改成另一个面, 而玩家手里那件东西在他没预料到的位置上出现, 比留在手里更难解释.
     */
    @Nullable
    public static AttachFace faceFor(Direction clickedFace) {
        if (clickedFace.getAxis().isHorizontal()) {
            return null;
        }
        return clickedFace == Direction.UP ? AttachFace.FLOOR : AttachFace.CEILING;
    }

    /**
     * 正面 points back at whoever placed it - 控制器 / 红石变压器 / 时钟源 / 信号延长器 的同一条规则
     * ({@code getHorizontalDirection().getOpposite()}), so the player is standing at the input face holding the wire that
     * will feed it.
     */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        AttachFace face = faceFor(context.getClickedFace());
        if (face == null) {
            // 对着墙点: 放不下. Vanilla's own "this item does not go here": nothing is placed and nothing is consumed.
            return null;
        }
        BlockState state = defaultBlockState()
                .setValue(FACE, face)
                .setValue(FACING, context.getHorizontalDirection().getOpposite());
        return state.canSurvive(context.getLevel(), context.getClickedPos()) ? state : null;
    }

    /** 贴着的那个方块得真有一个可以贴的面 - 贴地要脚下是实心, 贴天花板要头顶是. */
    @Override
    public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        Direction support = state.getValue(FACE) == AttachFace.CEILING ? Direction.UP : Direction.DOWN;
        BlockPos supportPos = pos.relative(support);
        return canAttachTo(level, support, supportPos);
    }

    /** Vanilla's own rule for a 贴面 device ({@code FaceAttachedHorizontalDirectionalBlock#canAttachTo}). */
    private static boolean canAttachTo(BlockGetter level, Direction side, BlockPos pos) {
        BlockState support = level.getBlockState(pos);
        return support.isFaceSturdy(level, pos, side.getOpposite());
    }

    /** 贴着的方块没了, 触发器也掉下来. */
    @Override
    public BlockState updateShape(BlockState state, Direction direction, BlockState neighbourState,
                                  LevelAccessor level, BlockPos pos, BlockPos neighbourPos) {
        Direction support = state.getValue(FACE) == AttachFace.CEILING ? Direction.UP : Direction.DOWN;
        if (direction == support && !state.canSurvive(level, pos)) {
            return Blocks.AIR.defaultBlockState();
        }
        return state;
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    // --------------------------------------------------------------- answering --

    /** 它是一件信号源: 消费者来问它, {@link #getSignal} 是回答的地方. */
    @Override
    protected boolean isSignalSource(BlockState state) {
        return true;
    }

    /**
     * 背面 only, exactly as 控制器 / 红石变压器 / 时钟源 / 信号延长器 have it: 正面 and the two sides answer 0.
     *
     * <p>{@code side} 是"从提问者指向这个方块"的方向 - vanilla 自己的口径 ({@code SignalGetter#hasNeighborSignal} 里写的是
     * {@code getSignal(pos.below(), DOWN)}), 于是 {@code side.getOpposite()} 是信号要走的那一面. 让这个式子与那四件
     * 器件逐字相同, 是为了让两个形态的"哪一面有答案"不可能写成两套.
     */
    @Override
    protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction side) {
        if (side.getOpposite() != outputFace(state)) {
            return 0;
        }
        return state.getValue(POWERED) ? TriggerRule.ON : TriggerRule.OFF;
    }

    /** 一件器件不搬运别人的功率: 弱信号只给碰着它的那一格, 不给它埋着的方块充能. */
    @Override
    public boolean shouldCheckWeakPower(BlockState state, SignalGetter level, BlockPos pos, Direction side) {
        return false;
    }

    /**
     * 正面那一面此刻的电 - 只有正面, 别的面根本不问.
     *
     * <p>用的是 {@link InnerRedstoneNetwork#readSignal}, 也就是这个 mod 里"邻方向我推了什么"的唯一实现 (vanilla 的
     * {@code Level#getSignal(neighbour, side)}) - 邻居<b>自己发出</b>的那个数, 外加一条原版红石线照常扣一跳. 复用它有
     * 两个理由: 它就是内嵌形态在正面问的那个数 ({@code PowerSolver#inputFrom} 的"世界那一半"), 于是两个形态从同一格上
     * 读到同一个数; 而 {@code getDirectSignal} 故意<b>不</b>加进来 - 一块只是<b>搬运</b>别处强充能的实心方块不是信号源,
     * 这也正是 vanilla 自己那个单面听者 ({@code DiodeBlock#getInputSignal}) 的读法, 而且是"宿主方块不会给自己的输入
     * 充电"的那条线.
     */
    public static int readingAt(BlockGetter level, BlockState state, BlockPos pos) {
        Direction face = state.getValue(FACING);
        return InnerRedstoneNetwork.readSignal(level, pos.relative(face), face);
    }

    /** A neighbour moved: the input may have moved with it, so the edge is stepped - in the same tick. */
    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighbour,
                                   BlockPos fromPos, boolean movedByPiston) {
        super.neighborChanged(state, level, pos, neighbour, fromPos, movedByPiston);
        if (level instanceof ServerLevel serverLevel) {
            evaluate(serverLevel, pos);
        }
    }

    /**
     * Placed - by a player, by a piston, by a structure: the first reading is stepped too, so a trigger put against a
     * face that is already powered starts switched on. That is not an extra rule: it is {@link TriggerRule} stepped from
     * the default {@code INPUT=false}, exactly as the stored component is stepped on its first tick.
     */
    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (level instanceof ServerLevel serverLevel) {
            evaluate(serverLevel, pos);
        }
    }

    /**
     * 重算一次: 读正面, 交给 {@link TriggerRule}, 只把结果<b>动过</b>的那一半写回去.
     *
     * <p>Called from {@link #neighborChanged} and {@link #onPlace}; the input only ever changes when a neighbour does, so a
     * notification <em>is</em> the edge. Two notifications in one tick cannot flip it twice: the first one stores the
     * reading it saw in {@link #INPUT}, so the second one steps the same rule from an input equal to its own and changes
     * nothing ({@link TriggerRule#step} is idempotent). Nothing here counts ticks.
     *
     * <p>Only a moved <b>answer</b> wakes the neighbours: 背面 and everything beside it has to hear about a switch that
     * turned over, while the reading moving on its own is nobody else's business.
     */
    public static void evaluate(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof TriggerBlock)) {
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

    /** 一条说明: 上升沿切换一次, 正面进、背面出. 细节写在 Shift 里, 与其余器件同一个形状. */
    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip,
                                TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        RcTooltips.append(tooltip, flag, "block.redstonecircuit.trigger.tip",
                RcTooltips.detail("block.redstonecircuit.trigger.tip.detail"));
    }
}
