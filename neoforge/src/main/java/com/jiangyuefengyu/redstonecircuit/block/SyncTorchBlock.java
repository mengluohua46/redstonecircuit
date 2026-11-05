package com.jiangyuefengyu.redstonecircuit.block;

import java.util.List;

import com.jiangyuefengyu.redstonecircuit.RcTooltips;
import com.jiangyuefengyu.redstonecircuit.carrier.CarriedSyncTorches;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierProjection;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierStore;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore;
import com.jiangyuefengyu.redstonecircuit.logic.InnerRedstoneNetwork;
import com.jiangyuefengyu.redstonecircuit.logic.PowerSolver;
import com.jiangyuefengyu.redstonecircuit.logic.SyncTorchRule;
import com.jiangyuefengyu.redstonecircuit.network.HostSync;
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
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 同步火把: 形状与红石火把一样, 亮灭由整个频道决定.
 *
 * <p>它只有两格状态, 因为它的全部答案就是这两个: {@link #FACING} 是<b>吸附面</b> - 它粘着的那个方块, 也是它唯一读的
 * 那一面; {@link #LIT} 是这一刻的答案, 由 {@code SyncGroups#tick} 写。设置不存在这里, 也不存在别处: 频道号在 store 的
 * {@code syncTorch} 表上, 颜色由频道号派生 ({@code SyncTorchRule#colour}), 所以这个方块自己的存档就是原版那一套。
 *
 * <p>方向是红石火把那一套, 只是极性反过来: 吸附面不输出 (它读那里), 其余五个方向答 15 级<b>弱</b>信号。弱是必须的 -
 * 强充能会让它把自己埋着的方块点亮, 而那正是这个 mod 里每一件非二极管器件都守的那条线。
 *
 * <p>没有 {@code BlockEntity}: 答案只有一格布尔, 它就写在方块状态里; 频道是"场地的事"而不是"这一刻的事", 归 store。
 */
public class SyncTorchBlock extends Block {

    public static final MapCodec<SyncTorchBlock> CODEC = simpleCodec(SyncTorchBlock::new);

    /**
     * 吸附面 - 它粘着的那个方块, 也就是它唯一读的那一面.
     *
     * <p>六个方向而不是四个, 因为一支火把既可以立在地上 (吸附面 = 下) 也可以挂在墙上或者天花板下; 与红石火把同一套规则。
     */
    public static final DirectionProperty FACING = BlockStateProperties.FACING;

    /** 这一刻的答案: 亮着就答 15, 灭着就答 0。写它的只有组 pass。 */
    public static final BooleanProperty LIT = BlockStateProperties.LIT;

    /** 立在地上: 与红石火把一样的 1/4 方块柱。 */
    private static final VoxelShape SHAPE_DOWN = Block.box(6.0, 0.0, 6.0, 10.0, 10.0, 10.0);

    /** 挂在天花板下面。 */
    private static final VoxelShape SHAPE_UP = Block.box(6.0, 6.0, 6.0, 10.0, 16.0, 10.0);

    /** 贴在四个墙面上, 贴着吸附面那一侧的那一半。 */
    private static final VoxelShape SHAPE_NORTH = Block.box(6.0, 3.0, 0.0, 10.0, 13.0, 6.0);
    private static final VoxelShape SHAPE_SOUTH = Block.box(6.0, 3.0, 10.0, 10.0, 13.0, 16.0);
    private static final VoxelShape SHAPE_WEST = Block.box(0.0, 3.0, 6.0, 6.0, 13.0, 10.0);
    private static final VoxelShape SHAPE_EAST = Block.box(10.0, 3.0, 6.0, 16.0, 13.0, 10.0);

    public SyncTorchBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
                .setValue(FACING, Direction.DOWN)
                .setValue(LIT, false));
    }

    @Override
    protected MapCodec<SyncTorchBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, LIT);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return switch (state.getValue(FACING)) {
            case UP -> SHAPE_UP;
            case NORTH -> SHAPE_NORTH;
            case SOUTH -> SHAPE_SOUTH;
            case WEST -> SHAPE_WEST;
            case EAST -> SHAPE_EAST;
            default -> SHAPE_DOWN;
        };
    }

    /** 它是一件信号源: 消费者来问它, {@link #getSignal} 是回答的地方。 */
    @Override
    protected boolean isSignalSource(BlockState state) {
        return true;
    }

    /**
     * 除吸附面以外五个方向答 15, 灭着就都答 0.
     *
     * <p>{@code side} 是"从提问者指向这个方块"的方向 - vanilla 自己的问法就是这一条
     * ({@code SignalGetter#hasNeighborSignal} 里写的是 {@code getSignal(pos.below(), DOWN)}), 于是提问者在
     * {@code pos.relative(side.getOpposite())}。吸附面那块来问时它的 {@code side} 正是 {@code FACING} 的反面, 所以
     * "不给自己挂着的那块充能"在这里是 {@code side == FACING.getOpposite()}, 其余五个方向照常作答。
     *
     * <p>原版墙上的红石火把说的是同一句话: 它的 FACING 指向<b>离墙</b>的方向, 而它答 0 的那一侧正好是
     * {@code side == FACING}({@code RedstoneWallTorchBlock#getSignal})。这里的 FACING 取的是相反的一套 - 从火把指向
     * 吸附面 - 于是同一件事写成了取反的一侧; 忘记取反等于给吸附面充电、对着空气的那一侧反而闭口, 而且这一支会被自己
     * 充起来的支撑方块一直喂着亮下去。
     */
    @Override
    protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction side) {
        if (!state.getValue(LIT)) {
            return 0;
        }
        InnerRedstoneStore store = InnerRedstoneStore.get(level);
        byte inputs = store == null ? 0 : store.syncTorchInputsAt(pos);
        // side 是"从提问者指向这个方块", 所以提问者在我们 side.getOpposite() 那一面 - 也就是发射方向。
        return SyncTorchRule.emitsOn(inputs, state.getValue(FACING), side.getOpposite())
                ? PowerSolver.MAX_POWER
                : 0;
    }

    /** 一件器件不搬运别人的功率: 弱信号只给碰着它的那一格, 不给它埋着的方块充能。 */
    @Override
    public boolean shouldCheckWeakPower(BlockState state, SignalGetter level, BlockPos pos, Direction side) {
        return false;
    }

    /**
     * 点击的那一面的反面就是吸附面: 点顶面 = 立在地上 (吸附面朝下), 点侧面 = 贴在墙上, 点底面 = 吊在下面。
     *
     * <p>一个式子覆盖六个方向, 因为"我点的是那个方块的哪一面"和"我粘在它的哪一面"本来就是同一件事的两端。
     */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction facing = context.getClickedFace().getOpposite();
        BlockState state = defaultBlockState().setValue(FACING, facing).setValue(LIT, false);
        return state.canSurvive(context.getLevel(), context.getClickedPos()) ? state : null;
    }

    /** 只有吸附面那里真有个能贴住的面才站得住 - 与红石火把同一条规则。 */
    @Override
    public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        Direction facing = state.getValue(FACING);
        BlockPos support = pos.relative(facing);
        return level.getBlockState(support).isFaceSturdy(level, support, facing.getOpposite());
    }

    /** 吸附的那个方块没了, 火把也掉下来 (于是它自己的钩子会把频道那一行清掉)。 */
    @Override
    public BlockState updateShape(BlockState state, Direction direction, BlockState neighbourState,
                                  LevelAccessor level, BlockPos pos, BlockPos neighbourPos) {
        if (direction == state.getValue(FACING) && !state.canSurvive(level, pos)) {
            return Blocks.AIR.defaultBlockState();
        }
        return state;
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        Direction facing = state.getValue(FACING);
        // 上/下两个方向在水平旋转里没有对应物, 所以原样留着: 立在地上的火把转一下还是立在地上。
        return facing.getAxis().isVertical() ? state : state.setValue(FACING, rotation.rotate(facing));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    /**
     * 一支刚放下的火把是<b>未绑定</b>的, 而它照样能用.
     *
     * <p>不在这里发号码: 两种形态必须对称 (内嵌那个也以未绑定起步), 而"一支没绑频道的火把"并不等于"一块废铁" - 组 pass
     * 把未绑定的成员当成自成一组的那个 ({@code SyncGroups#tick}), 于是它跟着自己的吸附面亮灭, 只是不跟别人联动。号码是
     * 玩家用调频的东西绑上去的。
     *
     * <p>要写的只是"这个位置有一支火把"这一行: 组 pass 靠频道表找到 level 里的火把, 没有行的那一支谁也看不见。被活塞推
     * 过来的那一支已经带着自己的行 (PistonHostTransfer), 所以 {@code movedByPiston} 时什么都不做 - 在这里补一行就等于
     * 把它推离原来的组。
     */
    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (level instanceof ServerLevel serverLevel && !movedByPiston) {
            InnerRedstoneStore store = InnerRedstoneStore.get(serverLevel);
            if (!store.hasSyncTorch(pos)) {
                store.putSyncTorch(pos, 0);
            }
        }
    }

    /**
     * 方块<b>真的</b>走了, 那一行也走.
     *
     * <p>频道表是按位置存的, 留下的行会被下一个站到这个坐标上的火把读到 - 于是"新放的一支"会莫名加入一个旧组。收尾清理
     * 写在方块自己的钩子里, 因为只有它知道自己是<b>被挖掉</b>了 (而不是世界还没加载)。
     *
     * <p>活塞推走不算"走了": 那一行是跟着元件走的 ({@code PistonHostTransfer} 把 from 的行搬到 to), 在这里删掉它就等于
     * 让被推过的那一支退组。所以 {@code movedByPiston} 时一行都不动。
     *
     * <p><b>被一台机器搬走</b>是第三种情况, 而它在方块自己这一层分辨不出来: 认领发生在方块已经消失之后, 于是"这次离开
     * 是不是搬运"当下没有答案。所以那一行照旧删 (留下的行会让下一个站到这个坐标上的火把读到别人的号码), 删之前把号码与
     * 掩码交给载体层 ({@link CarriedSyncTorches#stash}): 认领一台机器的那一趟会取走它, 落地时写回新坐标。
     * 没有物理后端时这一句不存在, 世界与之前逐字节相同。
     */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        super.onRemove(state, level, pos, newState, movedByPiston);
        if (!(level instanceof ServerLevel serverLevel) || movedByPiston || state.is(newState.getBlock())) {
            return;
        }
        InnerRedstoneStore store = InnerRedstoneStore.get(serverLevel);
        if (CarrierStore.isActive()) {
            CarriedSyncTorches.stash(serverLevel, pos, store.syncTorchChannelAt(pos),
                    store.syncTorchInputsAt(pos));
        }
        store.removeSyncTorch(pos);
        // 组里少了一个成员, 这是哪个槽位都装不下的事实: 客户端那一份只能整份换掉。
        HostSync.broadcastSyncGroups(serverLevel);
    }

    /** 一条说明: 它和红石火把同形、极性相反, 而一组是一起亮的。细节写在 Shift 里, 与其余器件同一个形状。 */
    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip,
                                TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        RcTooltips.append(tooltip, flag, "block.redstonecircuit.sync_torch.tip",
                RcTooltips.detail("block.redstonecircuit.sync_torch.tip.detail"));
    }

    /**
     * 把这一支的答案写成 {@code lit}, 并让世界听见.
     *
     * <p>这是组 pass 写"世界里那一形态"的唯一入口: 它只在答案真的变了的时候动手, 所以一个稳定的组每 tick 花在这里的
     * 只是两次读; 变了就通知全部邻居, 于是同 tick 里下游的线、以及贴着它的内嵌元件都跟着动。
     */
    public static boolean applyLit(ServerLevel level, BlockPos pos, boolean lit) {
        return applyLit(level, pos, lit, true);
    }

    /** The same write, with the neighbour notifications left to the caller when {@code announce} is false. */
    private static boolean applyLit(ServerLevel level, BlockPos pos, boolean lit, boolean announce) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof SyncTorchBlock) || state.getValue(LIT) == lit) {
            return false;
        }
        level.setBlock(pos, state.setValue(LIT, lit), Block.UPDATE_CLIENTS);
        if (announce) {
            level.updateNeighborsAt(pos, state.getBlock());
        }
        return true;
    }

    /**
     * <b>被一台机器搬着的那一支: 把组 pass 定下的答案写到机器里这一格上, 并让世界在它"看起来在哪"的那一格听到.</b>
     *
     * <p>机器里那一格自己的 tick 永远不会到来 (它所在的 plot 不是 level 正在 tick 的区块), 所以让答案落地的是那一趟走
     * 遍每个格的遍历 —— 与时钟源 {@code ClockBlock#refreshCarried} 同一条路。区别只有一处, 而这一处正是本器件的样子:
     * 这里写的<b>不是自己算出来的答案</b>, 全组 OR 只有组 pass 那一处, 这里只是把它的决定应用到方块上 (方块可能在被写下
     * 答案之后才被放回这一格, 那时组 pass 那一次写就落空了)。
     *
     * <p>亮灭一变, 世界那边也得听见: 我们往哪几面输出是"看起来在哪"那里的邻居的事, 而机器里这一格的邻居属于 plot。
     */
    public static void refreshCarried(ServerLevel level, BlockPos localPos, BlockState state) {
        if (!(state.getBlock() instanceof SyncTorchBlock) || !CarrierStore.isActive()) {
            return;
        }
        CarriedSyncTorches.Row row = CarriedSyncTorches.at(localPos);
        if (row == null || state.getValue(LIT) == row.lit()) {
            return;
        }
        applyLit(level, localPos, row.lit(), false);
        BlockPos drawn = CarrierProjection.drawnBlockOf(CarrierStore.poseOf(level, row.carrier()), localPos);
        if (drawn == null) {
            return;
        }
        level.updateNeighborsAt(drawn, level.getBlockState(drawn).getBlock());
        InnerRedstoneNetwork.notifyOutputChanged(level, drawn);
        InnerRedstoneNetwork.notifyOutputChangedNow(level, drawn);
    }
}
