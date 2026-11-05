package com.jiangyuefengyu.redstonecircuit;

import java.util.UUID;

import com.jiangyuefengyu.redstonecircuit.block.SyncTorchBlock;
import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore;
import com.jiangyuefengyu.redstonecircuit.data.Slot;
import com.jiangyuefengyu.redstonecircuit.item.FrequencyTunerItem;
import com.jiangyuefengyu.redstonecircuit.logic.InnerRedstoneNetwork;
import com.jiangyuefengyu.redstonecircuit.logic.SyncGroups;
import com.jiangyuefengyu.redstonecircuit.logic.SyncTorchRule;
import com.jiangyuefengyu.redstonecircuit.logic.TunerRule;
import com.mojang.authlib.GameProfile;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * <b>同步火把 + 调频器 in a real level: 放下即灭、吸附面读、五个方向答、一个频道同 tick 一起变.</b>
 *
 * <p>规则层由 {@code SyncTorchRuleTest} / {@code SyncGroupPassTest} 钉住, 这里钉的是<b>机制</b>: 世界里那一支和方块里
 * 内嵌的那一个都由 level 那一趟组 pass 写答案 ({@code InnerRedstoneTickHandler#onLevelTick} → {@code SyncGroups#tick}),
 * 所以每个"变了没有"的断言都等它的 tick, 而不是在同一 tick 里假设它已经跑过。
 *
 * <p>供电有两种, 都用的是别人已经跑绿的手法:
 * <ul>
 *   <li>世界形态: 吸附面那一格换成<b>红石块</b>就是 15, 换回<b>石头</b>就是 0 - 与
 *       {@code PistonHostTransferGameTest} 给活塞通电同一个写法。火把自己问的是
 *       {@code ProductFrame#signalInto} → {@code getSignal}/{@code getDirectSignal}, 而红石块两者都答满。
 *   <li>内嵌形态: 吸附面那一格塞一份<b>注入强度的内部粉尘</b> ({@code Slot#injectedPower}), 也就是
 *       {@code ExtenderGameTest} 给正面喂 7 的那个写法。这里不用红石块, 因为石头宿主是导体
 *       ({@code shouldCheckWeakPower} 为真), 一块挨着它的红石块会让宿主自己答 15, 于是"宿主替元件回答"这件事就再也
 *       测不出来 - 而粉尘不答强信号, 只答它自己那 15。
 * </ul>
 *
 * <p>模板 {@code empty} 是 5×3×5 的全空气空间, 所以坐标一律落在 x 0..4 / y 0..2 / z 0..4 之内, 每个用例自己摆地板。
 */
@GameTestHolder(RedstoneCircuit.MODID)
@PrefixGameTestTemplate(false)
public final class SyncTorchGameTest {

    /** 满强度 - 这个 mod 里每一件信号源的答案. */
    private static final int FULL = 15;

    /** 等一趟 level tick 的余量: 组 pass 在 tick 末尾写答案, 测试是从外面看的. */
    private static final int SETTLE = 2;

    /** 活塞的两 tick 行程, 加上方块作为它自己落地的几 tick - 与 {@code PistonHostTransferGameTest} 同一个数. */
    private static final int PUSH = 8;

    private static BlockPos at(GameTestHelper helper, int x, int y, int z) {
        return helper.absolutePos(new BlockPos(x, y, z));
    }

    /** 一块石头: 既是火把的吸附面, 也是"这个世界里没有信号"的那一格。 */
    private static void stone(GameTestHelper helper, int x, int y, int z) {
        helper.setBlock(new BlockPos(x, y, z), Blocks.STONE.defaultBlockState());
    }

    /** 一支立着的同步火把 (吸附面朝下, 粘在下面那块方块上). 放下时是灭的 - 那正是 {@code LIT} 的默认值. */
    private static BlockPos torch(GameTestHelper helper, int x, int y, int z) {
        helper.setBlock(new BlockPos(x, y, z), RCRegistry.SYNC_TORCH.get().defaultBlockState()
                .setValue(SyncTorchBlock.FACING, Direction.DOWN).setValue(SyncTorchBlock.LIT, false));
        return at(helper, x, y, z);
    }

    /**
     * 给吸附面那一格加/撤信号: 红石块答 15, 石头答 0.
     *
     * <p>吸附面那一格是"它唯一读的那一面", 而 {@code SyncTorchBlock#canSurvive} 要的就是那里有个能贴住的面 - 红石块
     * 与石头都是满方块, 所以换过去换回来火把都不会掉。
     */
    private static void supply(GameTestHelper helper, int x, int y, int z, boolean on) {
        helper.setBlock(new BlockPos(x, y, z), (on ? Blocks.REDSTONE_BLOCK : Blocks.STONE).defaultBlockState());
    }

    /**
     * 往一个位置塞一份内部粉尘, 强度 {@code power} - 与 {@code ExtenderGameTest#feedFront} 同一个写法.
     *
     * <p>{@code injectedPower} 是求解器的<b>输入</b>: 这一份粉尘周围是什么都答不低于它, 所以"给它 15"是一个稳定的
     * 信号源; 而粉尘不答强信号 ({@code PowerSolver#directSignalToward}), 于是挨着它的那个宿主方块不会被它充能 - 这正是
     * 第 7 例要的干净输入。
     */
    private static void inject(GameTestHelper helper, BlockPos pos, int power) {
        ServerLevel level = helper.getLevel();
        InnerRedstoneStore store = InnerRedstoneStore.get(level);
        Slot slot = new Slot(ComponentType.DUST);
        slot.power = power;
        if (power > 0) {
            slot.injectedPower = power;
        }
        store.getOrCreate(pos).setSlot(slot);
        store.markDirty();
        InnerRedstoneNetwork.markDirtyWithNeighbours(level, pos);
        InnerRedstoneInteraction.notifyNeighbours(level, pos);
    }

    /** 把那一格的东西全部拿走: 没有元件的位置对谁都答 0. */
    private static void clear(GameTestHelper helper, BlockPos pos) {
        ServerLevel level = helper.getLevel();
        InnerRedstoneStore.get(level).forgetAll(pos);
        InnerRedstoneNetwork.markDirtyWithNeighbours(level, pos);
        InnerRedstoneInteraction.notifyNeighbours(level, pos);
    }

    private static boolean lit(GameTestHelper helper, BlockPos pos) {
        return helper.getLevel().getBlockState(pos).getValue(SyncTorchBlock.LIT);
    }

    /**
     * 世界里那一支的六个方向: 亮着就是五个 15 加一个 0, 灭着就是六个 0.
     *
     * <p><b>指名</b>而不只数个数: "五个 15 一个 0" 对两种口径都成立, 只有指名才钉得住方向。{@code side} 是"从提问者
     * 指向这个方块", 所以提问者在 {@code pos.relative(side.getOpposite())}; 吸附面那块来问时它的 {@code side} 是
     * {@code FACING.getOpposite()}, 那一个必须答 0 - 否则这一支会给自己挂着的那块充能, 再被它喂着一直亮下去。
     */
    private static void assertTorchAnswers(GameTestHelper helper, BlockPos pos, boolean lit) {
        ServerLevel level = helper.getLevel();
        BlockState state = level.getBlockState(pos);
        byte inputs = InnerRedstoneStore.get(level).syncTorchInputsAt(pos);
        Direction facing = state.getValue(SyncTorchBlock.FACING);
        for (Direction side : Direction.values()) {
            // side 是"从提问者指向这个方块", 所以发射方向是它的反面; 有输入的那一面与吸附面都不输出。
            Direction emitting = side.getOpposite();
            int expected = lit && SyncTorchRule.emitsOn(inputs, facing, emitting) ? FULL : 0;
            int answer = state.getSignal(level, pos, side);
            helper.assertTrue(answer == expected, "方向 " + side + " 应当答 " + expected + ", 实际 " + answer
                    + " (有输入的面=" + inputs + ", 吸附面=" + facing + ")");
        }
    }

    /** 一次查看里两支的答案是同一个 - 也就是"同一 tick 一起变"这句话能被看见的样子。 */
    private static void assertTogether(GameTestHelper helper, BlockPos first, BlockPos second, boolean expected) {
        boolean firstLit = lit(helper, first);
        boolean secondLit = lit(helper, second);
        helper.assertTrue(firstLit == expected && secondLit == expected,
                "同一频道的两支应当同为 " + expected + ", 实际 " + firstLit + " / " + secondLit);
    }

    /** 一个没有连线的服务端玩家: 调频器要 {@link ServerPlayer}, 而 GameTest 的 mock 玩家是客户端 {@code Player}. */
    private static ServerPlayer tunerPlayer(ServerLevel level) {
        return new ServerPlayer(level.getServer(), level,
                new GameProfile(UUID.randomUUID(), "test-tuner"), ClientInformation.createDefault()) {
            @Override
            public void displayClientMessage(Component message, boolean actionBar) {
                // 没连过线的玩家没有说话的地方: 调频器那一句反馈丢在这里, 它改的世界一个字节都不少。
            }
        };
    }

    // ------------------------------------------------------------------ 用例 --

    /** 1) 世界形态放下即灭, 而且对相邻位置一个方向都不答. */
    @GameTest(template = "empty")
    public void aFreshTorchIsOutAndAnswersNowhere(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        InnerRedstoneStore store = InnerRedstoneStore.get(level);
        stone(helper, 2, 0, 2);
        BlockPos pos = torch(helper, 2, 1, 2);

        BlockState state = level.getBlockState(pos);
        helper.assertTrue(state.getBlock() instanceof SyncTorchBlock, "放下的必须是一支同步火把");
        helper.assertTrue(state.getValue(SyncTorchBlock.FACING) == Direction.DOWN, "立在地上: 吸附面朝下");
        helper.assertFalse(state.getValue(SyncTorchBlock.LIT), "放下时是灭的 - 这是它的默认值, 不是组的答案");
        helper.assertTrue(store.hasSyncTorch(pos), "放下就有一行: 组 pass 靠这张表找到这一支");
        helper.assertTrue(store.syncTorchChannelAt(pos) == 0, "而那一行是'有火把, 没频道'");
        for (Direction side : Direction.values()) {
            helper.assertTrue(level.getSignal(pos, side) == 0,
                    "灭着的火把对 " + side + " 的问句必须答 0, 实际 " + level.getSignal(pos, side));
        }
        helper.succeed();
    }

    /** 2) 一根未绑定的火把自己工作: 吸附面有信号就亮、五个方向答 15, 撤掉就灭. */
    @GameTest(template = "empty")
    public void oneUnboundTorchFollowsItsOwnInput(GameTestHelper helper) {
        stone(helper, 2, 0, 2);
        BlockPos pos = torch(helper, 2, 1, 2);
        helper.assertFalse(lit(helper, pos), "先灭着");
        helper.assertTrue(InnerRedstoneStore.get(helper.getLevel()).syncTorchChannelAt(pos) == 0,
                "新放的这一支是没绑频道的: 它自成一个组, 只看自己的输入");

        helper.startSequence()
                .thenExecuteAfter(SETTLE, () -> supply(helper, 2, 0, 2, true))
                .thenExecuteAfter(SETTLE, () -> {
                    helper.assertTrue(lit(helper, pos), "吸附面有信号 (红石块 15) 就要亮");
                    assertTorchAnswers(helper, pos, true);
                })
                .thenExecuteAfter(SETTLE, () -> supply(helper, 2, 0, 2, false))
                .thenExecuteAfter(SETTLE, () -> {
                    helper.assertFalse(lit(helper, pos), "信号撤掉就要灭");
                    assertTorchAnswers(helper, pos, false);
                })
                .thenSucceed();
    }

    /** 3) 两根同频道: 只给第一根的吸附面信号, 两支一起亮; 撤掉一起灭. */
    @GameTest(template = "empty")
    public void twoTorchesOnOneChannelChangeTogether(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        InnerRedstoneStore store = InnerRedstoneStore.get(level);
        stone(helper, 1, 0, 2);
        stone(helper, 3, 0, 2);
        BlockPos first = torch(helper, 1, 1, 2);
        BlockPos second = torch(helper, 3, 1, 2);

        int channel = SyncGroups.newChannel(level);
        helper.assertTrue(SyncTorchRule.isChannel(channel), "发下来的必须是一个真频道号, 实际 " + channel);
        SyncGroups.join(level, first, channel);
        SyncGroups.join(level, second, channel);
        helper.assertTrue(store.syncTorchChannelAt(first) == channel && store.syncTorchChannelAt(second) == channel,
                "两根都要落在这个频道上, 实际 " + store.syncTorchChannelAt(first) + " / "
                        + store.syncTorchChannelAt(second));

        helper.startSequence()
                .thenExecuteAfter(SETTLE, () -> supply(helper, 1, 0, 2, true))
                .thenExecuteAfter(SETTLE, () -> assertTogether(helper, first, second, true))
                .thenExecuteAfter(1, () -> assertTogether(helper, first, second, true))
                .thenExecuteAfter(SETTLE, () -> supply(helper, 1, 0, 2, false))
                .thenExecuteAfter(SETTLE, () -> assertTogether(helper, first, second, false))
                .thenExecuteAfter(1, () -> assertTogether(helper, first, second, false))
                .thenSucceed();
    }

    /** 4) 四根同频道, 信号给的是第二根: 四根全亮, 而且都答满五个方向. */
    @GameTest(template = "empty")
    public void fourTorchesOnOneChannelAllAnswer(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        InnerRedstoneStore store = InnerRedstoneStore.get(level);
        stone(helper, 1, 0, 1);
        stone(helper, 3, 0, 1);
        stone(helper, 1, 0, 3);
        stone(helper, 3, 0, 3);
        BlockPos[] torches = {
                torch(helper, 1, 1, 1), torch(helper, 3, 1, 1), torch(helper, 1, 1, 3), torch(helper, 3, 1, 3)
        };
        int channel = SyncGroups.newChannel(level);
        for (BlockPos pos : torches) {
            SyncGroups.join(level, pos, channel);
        }

        helper.startSequence()
                // 信号给的是第二根, 不是第一根: 组内是 OR, 谁收到都算全组收到。
                .thenExecuteAfter(SETTLE, () -> supply(helper, 3, 0, 1, true))
                .thenExecuteAfter(SETTLE, () -> {
                    for (int i = 0; i < torches.length; i++) {
                        helper.assertTrue(lit(helper, torches[i]),
                                "第 " + (i + 1) + " 根必须跟着亮 (信号给的是第二根), 实际灭着");
                        assertTorchAnswers(helper, torches[i], true);
                    }
                    helper.assertTrue(store.syncTorchChannelAt(torches[3]) == channel, "四根在同一个频道上");
                })
                .thenExecuteAfter(SETTLE, () -> supply(helper, 3, 0, 1, false))
                .thenExecuteAfter(SETTLE, () -> {
                    for (BlockPos pos : torches) {
                        helper.assertFalse(lit(helper, pos), "信号撤掉, 四根一起灭");
                    }
                })
                .thenSucceed();
    }

    /** 5) 两根都没绑频道: 各看各的输入, 不会互相点亮. */
    @GameTest(template = "empty")
    public void twoUnboundTorchesDoNotLink(GameTestHelper helper) {
        InnerRedstoneStore store = InnerRedstoneStore.get(helper.getLevel());
        stone(helper, 1, 0, 2);
        stone(helper, 3, 0, 2);
        BlockPos first = torch(helper, 1, 1, 2);
        BlockPos second = torch(helper, 3, 1, 2);
        helper.assertTrue(store.syncTorchChannelAt(first) == 0 && store.syncTorchChannelAt(second) == 0,
                "两支都不绑: 都是 0, 也就是'有火把但没频道'");

        helper.startSequence()
                .thenExecuteAfter(SETTLE, () -> supply(helper, 1, 0, 2, true))
                .thenExecuteAfter(SETTLE, () -> {
                    helper.assertTrue(lit(helper, first), "收到信号的那一支亮");
                    helper.assertFalse(lit(helper, second), "没收到的那一支必须还是灭的 - 未绑定不是'一组'");
                    assertTorchAnswers(helper, second, false);
                })
                .thenSucceed();
    }

    /**
     * 6) 活塞推走一个带着同步火把的方块, 频道跟着元件走: 推完之后给参照那一支信号, 被推走的那一支也亮.
     *
     * <p>推的是<b>内嵌形态</b>所在的宿主方块, 不是世界形态那一支: {@code RCRegistry#SYNC_TORCH} 的 {@code pushReaction}
     * 是 {@code DESTROY} (与红石火把一样), 活塞不会推它, 只会把它打掉 - 所以"推完之后还在原来那组里"这件事, 能被真
     * 活塞证明的那一半是内嵌形态: 频道在 {@code Slot#syncGroup} 上, 是随方块走的那一份。
     */
    @GameTest(template = "empty")
    public void aPushedHostKeepsItsChannelAndStaysInTheGroup(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        InnerRedstoneStore store = InnerRedstoneStore.get(level);

        helper.setBlock(new BlockPos(0, 1, 1), Blocks.PISTON.defaultBlockState()
                .setValue(BlockStateProperties.FACING, Direction.EAST));
        stone(helper, 1, 1, 1);
        BlockPos host = at(helper, 1, 1, 1);
        BlockPos landed = at(helper, 2, 1, 1);

        Slot slot = new Slot(ComponentType.SYNC_TORCH);
        slot.facing = Direction.DOWN;
        store.getOrCreate(host).setSlot(slot);
        store.markDirty();

        // 参照: 世界里立着的一支, 同一个频道 - 推完之后由它来给整组一个输入。
        stone(helper, 4, 0, 3);
        BlockPos reference = torch(helper, 4, 1, 3);

        int channel = SyncGroups.newChannel(level);
        SyncGroups.join(level, host, channel);
        SyncGroups.join(level, reference, channel);
        Slot before = store.slotAt(host);
        helper.assertTrue(before != null && before.type == ComponentType.SYNC_TORCH
                        && before.syncGroup == channel,
                "内嵌那一支先要真在这个频道上, 实际 "
                        + (before == null ? "没有元件" : Integer.toString(before.syncGroup)));

        helper.startSequence()
                // 电源来自活塞旁边一格, 与 PistonHostTransferGameTest 同一个摆法; 推是这一例自己要考的事。
                .thenExecuteAfter(SETTLE, () -> helper.setBlock(new BlockPos(0, 1, 2),
                        Blocks.REDSTONE_BLOCK.defaultBlockState()))
                .thenExecuteAfter(PUSH, () -> {
                    helper.assertTrue(level.getBlockState(landed).is(Blocks.STONE),
                            "先说清楚: 这一推本身要成立, 落点上现在是 " + level.getBlockState(landed).getBlock());
                    helper.assertTrue(store.slotAt(host) == null,
                            "元件必须跟着方块离开旧格 " + host.toShortString());
                    Slot moved = store.slotAt(landed);
                    helper.assertTrue(moved != null && moved.type == ComponentType.SYNC_TORCH,
                            "落点上要有同一个元件, 实际 " + (moved == null ? "空的" : moved.type.name()));
                    helper.assertTrue(moved.syncGroup == channel,
                            "频道要原样带过去, 期望 " + channel + ", 实际 " + moved.syncGroup);
                })
                .thenExecuteAfter(SETTLE, () -> supply(helper, 4, 0, 3, true))
                .thenExecuteAfter(SETTLE, () -> {
                    helper.assertTrue(lit(helper, reference), "参照那一支收到信号就亮");
                    Slot moved = store.slotAt(landed);
                    helper.assertTrue(moved != null && moved.power == FULL,
                            "被推走的那一支也要亮起来 (slot.power == 15), 实际 "
                                    + (moved == null ? "没有元件" : Integer.toString(moved.power)));
                })
                .thenSucceed();
    }

    /** 7) 内嵌形态: 塞进石头里的那一支跟着吸附面亮灭, 而且宿主方块替它把答案说给世界. */
    @GameTest(template = "empty")
    public void theStoredTorchLightsAndItsHostSaysSo(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        InnerRedstoneStore store = InnerRedstoneStore.get(level);
        stone(helper, 2, 0, 2);
        stone(helper, 2, 1, 2);
        BlockPos host = at(helper, 2, 1, 2);
        BlockPos support = at(helper, 2, 0, 2);

        Slot slot = new Slot(ComponentType.SYNC_TORCH);
        // 吸附面朝下: 它读下面那一格, 也就是 (2,0,2)。
        slot.facing = Direction.DOWN;
        store.getOrCreate(host).setSlot(slot);
        store.markDirty();

        helper.startSequence()
                .thenExecuteAfter(SETTLE, () -> inject(helper, support, FULL))
                .thenExecuteAfter(SETTLE, () -> {
                    Slot after = store.slotAt(host);
                    helper.assertTrue(after != null && after.type == ComponentType.SYNC_TORCH, "元件还在那里");
                    helper.assertTrue(after.power == FULL,
                            "方块里那一支也要亮 (slot.power == 15), 实际 " + after.power);
                    // 宿主替它回答: 五个方向 15、吸附面那一个 0。按"数"断言, 因为世界问句与元件自己的方向之间还隔着一次
                    // 翻转 (BlockStateSignalMixin 把问句方向取反后交给 BlockSignalSources), 而这条规则能钉住的正是
                    // "五个答、一个不答"。
                    // 与世界里那一形态同一条口径: 吸附面朝下、东面有输入, 于是这两侧答 0、其余答满。两种形态答的是
                    // 同一件事, 不然同一份设计在两个形态里会有两种行为。
                    for (Direction side : Direction.values()) {
                        Direction emitting = side.getOpposite();
                        int expected = SyncTorchRule.emitsOn(slot.syncInputs, slot.facing, emitting) ? FULL : 0;
                        int answer = level.getSignal(host, side);
                        helper.assertTrue(answer == expected, "宿主对方向 " + side + " 应当答 " + expected
                                + ", 实际 " + answer + " (掩码 " + slot.syncInputs + ")");
                    }
                })
                .thenExecuteAfter(SETTLE, () -> clear(helper, support))
                .thenExecuteAfter(SETTLE, () -> {
                    Slot after = store.slotAt(host);
                    helper.assertTrue(after != null && after.power == 0,
                            "输入没了就是 0, 实际 " + (after == null ? "没有元件" : after.power));
                    for (Direction side : Direction.values()) {
                        helper.assertTrue(level.getSignal(host, side) == 0,
                                "灭着的内嵌火把不答任何方向, " + side + " 答了 " + level.getSignal(host, side));
                    }
                })
                .thenSucceed();
    }

    /**
     * 8) 调频器: 拿着它右击两支火把, 两支被绑进同一个频道, 然后跟着同一份输入一起亮.
     *
     * <p>走的是产品那一条路 ({@code FrequencyTunerItem#use}, 也就是 {@code InnerRedstoneInteraction} 在右键事件里叫的
     * 那一个), 所以频道号真的由它发下来、写进物品那一个 component、再落进 store。没有覆盖的是 NeoForge 的事件分发本身
     * (手上是不是调频器、事件要不要 cancel), 那一段在 {@code InnerRedstoneInteraction} 里。
     */
    @GameTest(template = "empty")
    public void theTunerBindsTwoTorchesIntoOneChannel(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        InnerRedstoneStore store = InnerRedstoneStore.get(level);
        stone(helper, 1, 0, 1);
        stone(helper, 3, 0, 1);
        BlockPos first = torch(helper, 1, 1, 1);
        BlockPos second = torch(helper, 3, 1, 1);

        ServerPlayer player = tunerPlayer(level);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(RCRegistry.FREQUENCY_TUNER_ITEM.get()));

        helper.assertTrue(FrequencyTunerItem.use(level, player, InteractionHand.MAIN_HAND, first),
                "对着一支同步火把右键, 这一下必须是调频器的事");
        int channel = store.syncTorchChannelAt(first);
        helper.assertTrue(SyncTorchRule.isChannel(channel), "它要被绑进一个真频道, 实际 " + channel);
        TunerRule.Channel held = FrequencyTunerItem.channelOf(player.getItemInHand(InteractionHand.MAIN_HAND));
        helper.assertTrue(held != null && held.number() == channel,
                "手里那个号码就是它被绑上的那一个, 实际 " + (held == null ? "没有频道" : held.number()));

        helper.assertTrue(FrequencyTunerItem.use(level, player, InteractionHand.MAIN_HAND, second),
                "第二支也一样");
        helper.assertTrue(store.syncTorchChannelAt(second) == channel,
                "两次右键要绑进同一个频道, 实际 " + store.syncTorchChannelAt(second) + " vs " + channel);

        helper.assertFalse(FrequencyTunerItem.use(level, player, InteractionHand.MAIN_HAND, at(helper, 4, 2, 4)),
                "不是同步火把的位置不能被这一下吞掉 - 否则它就会挡住放置和开箱子");

        // 清空手里的频道: 潜行右击<b>任意</b>位置即可, 不必先找到一支火把。
        player.setShiftKeyDown(true);
        helper.assertTrue(FrequencyTunerItem.use(level, player, InteractionHand.MAIN_HAND, at(helper, 4, 2, 4)),
                "潜行右击一个不是火把的位置, 这一下是调频器的事 - 清空手里的频道");
        helper.assertTrue(FrequencyTunerItem.channelOf(player.getItemInHand(InteractionHand.MAIN_HAND)) == null,
                "清空之后手里就没有频道了");
        helper.assertFalse(FrequencyTunerItem.clear(level, player, InteractionHand.MAIN_HAND),
                "手里没有频道时, 这一次点击不能被吞掉");
        player.setShiftKeyDown(false);

        // 手里没频道时对着"已经有频道"的火把右键 = <b>选中它那一个</b>, 既不新开一个, 也不改动它。
        int bound = store.syncTorchChannelAt(second);
        helper.assertTrue(FrequencyTunerItem.use(level, player, InteractionHand.MAIN_HAND, second),
                "对着已经有频道的火把右键, 这一下是调频器的事");
        TunerRule.Channel adopted = FrequencyTunerItem.channelOf(player.getItemInHand(InteractionHand.MAIN_HAND));
        helper.assertTrue(adopted != null && adopted.number() == bound,
                "手里选中的必须是它原来那一个频道, 实际 "
                        + (adopted == null ? "没有" : String.valueOf(adopted.number())) + " vs " + bound);
        helper.assertTrue(store.syncTorchChannelAt(second) == bound, "而且在它身上不该有任何改动");

        // Shift+右键那支火把 = 把它自己的频道清掉(退回"没有频道"), 手里与另一支都不受影响。
        player.setShiftKeyDown(true);
        helper.assertTrue(FrequencyTunerItem.use(level, player, InteractionHand.MAIN_HAND, second),
                "Shift+右键对着有频道的火把 = 清掉它的频道");
        helper.assertTrue(store.syncTorchChannelAt(second) == 0,
                "它应当退回没有频道, 实际 " + store.syncTorchChannelAt(second));
        helper.assertTrue(store.syncTorchChannelAt(first) == channel, "另一支不许被牵连");
        helper.assertTrue(FrequencyTunerItem.channelOf(player.getItemInHand(InteractionHand.MAIN_HAND)) != null,
                "清掉的是那支火把的频道, 手里的频道不动");

        // 再对它潜行右键: 它已经没有频道了, 于是这一下什么也不做 —— 手里的频道更不该被动到。
        helper.assertTrue(FrequencyTunerItem.use(level, player, InteractionHand.MAIN_HAND, second),
                "对着没有频道的火把潜行右键, 这次点击仍然是调频器的 (否则它会去放置手里的东西)");
        helper.assertTrue(FrequencyTunerItem.channelOf(player.getItemInHand(InteractionHand.MAIN_HAND)) != null,
                "它没有频道可清, 手里的频道必须原样留着");

        // 把它绑回去, 下面"通电一起亮"那一段仍然成立。
        player.setShiftKeyDown(false);
        helper.assertTrue(FrequencyTunerItem.use(level, player, InteractionHand.MAIN_HAND, second),
                "再右键一次就把它绑回手里的频道");
        helper.assertTrue(store.syncTorchChannelAt(second) == channel,
                "回到原来那一组, 实际 " + store.syncTorchChannelAt(second));

        helper.startSequence()
                .thenExecuteAfter(SETTLE, () -> supply(helper, 1, 0, 1, true))
                .thenExecuteAfter(SETTLE, () -> assertTogether(helper, first, second, true))
                .thenSucceed();
    }

    /**
     * 15) 准星指着一支同步火把时, 潜行右键<b>不许</b>动到手里的频道.
     *
     * <p>这一条是硬判据而不只是语义: 同一次点击在方块与物品两条路上都可能各走一遍 (谁先到不确定), 所以"清空手里"必须
     * 自己会看准星 —— 指着火把就一律不认。测试直接把玩家摆到能看见火把的位置与角度上, 然后调那一句本身。
     */
    @GameTest(template = "empty")
    public void aimingAtATorchNeverClearsTheChannelInHand(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        stone(helper, 2, 0, 2);
        BlockPos pos = torch(helper, 2, 1, 2);
        ServerPlayer player = tunerPlayer(level);
        ItemStack tuner = new ItemStack(RCRegistry.FREQUENCY_TUNER_ITEM.get());
        player.setItemInHand(InteractionHand.MAIN_HAND, tuner);
        FrequencyTunerItem.setChannel(tuner, new TunerRule.Channel(7, "minecraft:overworld"));
        player.setShiftKeyDown(true);

        // 站在火把南边两格, 俯角 35° 正对着它。
        player.setPos(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 2.5D);
        player.setYRot(180.0F);
        player.setXRot(35.0F);
        helper.assertFalse(FrequencyTunerItem.clear(level, player, InteractionHand.MAIN_HAND),
                "准星指着火把: 这一下属于那支火把, 不能拿来清手里的频道");
        helper.assertTrue(FrequencyTunerItem.channelOf(tuner) != null, "手里的频道要原样留着");

        // 对照: 抬头看天 (准星什么都没指着), 同一个手势就该清空手里。
        player.setXRot(-90.0F);
        helper.assertTrue(FrequencyTunerItem.clear(level, player, InteractionHand.MAIN_HAND),
                "准星指着空气时, 潜行右键仍然是清空手里的频道");
        helper.assertTrue(FrequencyTunerItem.channelOf(tuner) == null, "这一次才该被清掉");
        helper.succeed();
    }

    /** 9) 任意一面: 红石块摆在它<b>旁边</b>(不是吸附面) 也要点亮 - 这就是要求"给任意一面". */
    @GameTest(template = "empty")
    public void aRedstoneBlockBesideItLightsIt(GameTestHelper helper) {
        stone(helper, 2, 0, 2);
        BlockPos pos = torch(helper, 2, 1, 2);
        helper.setBlock(new BlockPos(2, 1, 3), Blocks.STONE.defaultBlockState());

        helper.startSequence()
                .thenExecuteAfter(SETTLE, () -> helper.assertFalse(lit(helper, pos), "先灭着"))
                .thenExecuteAfter(SETTLE, () -> helper.setBlock(new BlockPos(2, 1, 3),
                        Blocks.REDSTONE_BLOCK.defaultBlockState()))
                .thenExecuteAfter(SETTLE, () -> {
                    // 这条规则靠两条 vanilla 事实: 红石块是红石导体, 且它对着任何一侧都答 15。以后哪天真读不出来,
                    // 失败信息里会直接写着是哪一半不成立。
                    ServerLevel world = helper.getLevel();
                    BlockState redstone = world.getBlockState(at(helper, 2, 1, 3));
                    helper.assertTrue(!redstone.isRedstoneConductor(world, at(helper, 2, 1, 3)),
                            "红石块<b>不是</b>红石导体 (vanilla: 它不传导强信号) - 所以它必须靠纯源那一路才听得见");
                    helper.assertTrue(world.getSignal(at(helper, 2, 1, 3), Direction.SOUTH) == FULL,
                            "红石块对 SOUTH 应当答 15, 实际 "
                                    + world.getSignal(at(helper, 2, 1, 3), Direction.SOUTH));
                    helper.assertTrue(lit(helper, pos), "红石块在侧面也算输入: 任意一面都能点亮");
                    assertTorchAnswers(helper, pos, true);
                })
                // 撤掉之后必须灭, 而且是<b>自己灭</b>: 如果读的是自己打出去的弱信号, 这一支会永远亮着。
                .thenExecuteAfter(SETTLE, () -> helper.setBlock(new BlockPos(2, 1, 3),
                        Blocks.STONE.defaultBlockState()))
                .thenExecuteAfter(SETTLE, () -> {
                    helper.assertFalse(lit(helper, pos), "红石块拿走就该灭 - 不然就是把自家输出读回来了");
                    assertTorchAnswers(helper, pos, false);
                })
                .thenSucceed();
    }

    /** 10) 同一组两支紧挨着不会互相点亮(自锁): 谁也没给信号时它们必须都是灭的. */
    @GameTest(template = "empty")
    public void twoAdjacentGroupMembersDoNotLatchOn(GameTestHelper helper) {
        stone(helper, 1, 0, 1);
        stone(helper, 2, 0, 1);
        BlockPos first = torch(helper, 1, 1, 1);
        BlockPos second = torch(helper, 2, 1, 1);
        int channel = SyncGroups.newChannel(helper.getLevel());
        SyncGroups.join(helper.getLevel(), first, channel);
        SyncGroups.join(helper.getLevel(), second, channel);

        helper.startSequence()
                .thenExecuteAfter(SETTLE, () -> assertTogether(helper, first, second, false))
                .thenExecuteAfter(SETTLE, () -> assertTogether(helper, first, second, false))
                .thenExecuteAfter(SETTLE, () -> helper.assertFalse(lit(helper, second),
                        "紧挨着的同组火把不许把彼此点亮 - 那会亮一次就再也熄不掉"))
                .thenSucceed();
    }

    /**
     * 12) 贴着<b>吸附面那块方块</b>的红石粉: 通电亮, 撤电灭, <b>不许自锁</b>.
     *
     * <p>这是玩家真正会做的接法, 也是唯一不会自锁的接法: 红石粉的 direct signal 把那块方块强充能, 而方块是导体,
     * 火把读的是导体发出来的那一份 (见 {@code SyncTorchRule#neighbourInput})。粉本身不算输入 —— 它的读数里可能正
     * 装着我们自己打过去的那一份。
     */
    @GameTest(template = "empty")
    public void dustBesideTheSupportLightsItAndDoesNotLatch(GameTestHelper helper) {
        stone(helper, 2, 1, 2);
        stone(helper, 3, 0, 2);
        helper.setBlock(new BlockPos(3, 1, 2), Blocks.REDSTONE_WIRE.defaultBlockState());
        stone(helper, 4, 0, 2);
        helper.setBlock(new BlockPos(4, 1, 2), Blocks.STONE.defaultBlockState());
        BlockPos pos = torch(helper, 2, 2, 2);

        helper.startSequence()
                .thenExecuteAfter(SETTLE, () -> helper.assertFalse(lit(helper, pos), "先灭着"))
                .thenExecuteAfter(SETTLE, () -> helper.setBlock(new BlockPos(4, 1, 2),
                        Blocks.REDSTONE_BLOCK.defaultBlockState()))
                .thenExecuteAfter(SETTLE, () -> {
                    helper.assertTrue(lit(helper, pos), "粉把电送进吸附面那块方块, 这一支要亮");
                    assertTorchAnswers(helper, pos, true);
                })
                .thenExecuteAfter(SETTLE, () -> helper.setBlock(new BlockPos(4, 1, 2),
                        Blocks.STONE.defaultBlockState()))
                .thenExecuteAfter(SETTLE, () -> {
                    helper.assertFalse(lit(helper, pos),
                            "粉没电了就该灭 - 还亮着就是把粉里的'我们自己那一份'读回来了(自锁)");
                    assertTorchAnswers(helper, pos, false);
                })
                .thenSucceed();
    }

    /**
     * 13) 一根<b>对着火把本身</b>的红石粉: 通电亮, 撤电灭, <b>不自锁</b>
     *
     * <p>机制在掩码上: 那一面读到信号之后就被记成输入, 于是我们从那一面<b>不再输出</b>; 外面的 15 因此不会被我们的输出
     * 续上, 撤掉外面的信号它自然归零, 火把灭, 灭着又什么都不输出 —— 环在这里断掉。
     */
    @GameTest(template = "empty")
    public void dustAimingAtTheTorchLightsItAndDoesNotLatch(GameTestHelper helper) {
        stone(helper, 2, 0, 2);
        stone(helper, 3, 0, 2);
        BlockPos pos = torch(helper, 2, 1, 2);
        helper.setBlock(new BlockPos(3, 1, 2), Blocks.REDSTONE_WIRE.defaultBlockState());
        stone(helper, 4, 0, 2);
        helper.setBlock(new BlockPos(4, 1, 2), Blocks.STONE.defaultBlockState());

        helper.startSequence()
                .thenExecuteAfter(SETTLE, () -> helper.assertFalse(lit(helper, pos), "先灭着"))
                .thenExecuteAfter(SETTLE, () -> helper.setBlock(new BlockPos(4, 1, 2),
                        Blocks.REDSTONE_BLOCK.defaultBlockState()))
                .thenExecuteAfter(SETTLE, () -> {
                    helper.assertTrue(lit(helper, pos), "对着火把本身的粉也是输入");
                    assertTorchAnswers(helper, pos, true);
                })
                .thenExecuteAfter(SETTLE, () -> helper.setBlock(new BlockPos(4, 1, 2),
                        Blocks.STONE.defaultBlockState()))
                .thenExecuteAfter(SETTLE, () -> {
                    helper.assertFalse(lit(helper, pos),
                            "粉没电了就该灭 - 还亮着说明我们把自家输出读回来了(自锁)");
                    assertTorchAnswers(helper, pos, false);
                })
                .thenSucceed();
    }

    /**
     * 14) 那个摆法: 火把立在地上, 旁边一根红石粉, 给<b>脚下那块</b>通电.
     *
     * <p>三件事一起断言: 火把亮; <b>旁边的粉也要跟着亮</b>(它原本不亮, 要打掉重放才亮 —— 那说明我们亮起时没把邻居通知
     * 到); 撤掉脚下的电之后<b>两支一起灭</b>(原本是两支都锁在亮的状态上 —— 那说明粉的读数被我们当成输入了)。
     */
    @GameTest(template = "empty")
    public void theDustBesideTheTorchLightsUpAndBothGoDark(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        stone(helper, 2, 0, 2);
        stone(helper, 3, 0, 2);
        BlockPos pos = torch(helper, 2, 1, 2);
        BlockPos dust = at(helper, 3, 1, 2);
        helper.setBlock(new BlockPos(3, 1, 2), Blocks.REDSTONE_WIRE.defaultBlockState());

        helper.startSequence()
                .thenExecuteAfter(SETTLE, () -> {
                    helper.assertFalse(lit(helper, pos), "先灭着");
                    helper.assertTrue(dustPower(helper, dust) == 0, "粉也没电");
                })
                .thenExecuteAfter(SETTLE, () -> supply(helper, 2, 0, 2, true))
                .thenExecuteAfter(SETTLE, () -> {
                    helper.assertTrue(lit(helper, pos), "脚下那块通电, 火把要亮");
                    helper.assertTrue(dustPower(helper, dust) > 0,
                            "旁边那根粉也要跟着亮 (实际 " + dustPower(helper, dust) + ") - 不亮就是亮起时没通知到邻居");
                })
                .thenExecuteAfter(SETTLE, () -> supply(helper, 2, 0, 2, false))
                .thenExecuteAfter(SETTLE, () -> {
                    helper.assertFalse(lit(helper, pos),
                            "脚下的电撤掉, 火把要灭 - 还亮着就是把粉的读数当成输入了(自锁)");
                    helper.assertTrue(dustPower(helper, dust) == 0,
                            "粉也要跟着灭, 实际 " + dustPower(helper, dust));
                })
                .thenSucceed();
    }

    /** 一根红石粉此刻的强度; 不是粉就是 0. */
    private static int dustPower(GameTestHelper helper, BlockPos pos) {
        BlockState state = helper.getLevel().getBlockState(pos);
        return state.getBlock() instanceof RedStoneWireBlock ? state.getValue(RedStoneWireBlock.POWER) : 0;
    }

    /** 11) 频道号会被回收: 一组人走光之后, 下一次发号拿回的就是那个空出来的最小号码. */
    @GameTest(template = "empty")
    public void aFreedChannelNumberIsHandedOutAgain(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        stone(helper, 1, 0, 1);
        stone(helper, 3, 0, 1);
        BlockPos first = torch(helper, 1, 1, 1);
        BlockPos second = torch(helper, 3, 1, 1);

        int channel = SyncGroups.newChannel(level);
        SyncGroups.join(level, first, channel);
        SyncGroups.join(level, second, channel);
        helper.assertTrue(SyncGroups.newChannel(level) != channel,
                "还有人在用的号码不能再发出去一次");

        SyncGroups.leave(level, first);
        SyncGroups.leave(level, second);
        helper.assertTrue(SyncGroups.newChannel(level) == channel,
                "一组人走光之后, 那个号码应当被下一次发号收回来 - 否则号码只会越堆越大, 实际 " + channel);
        helper.succeed();
    }
}
