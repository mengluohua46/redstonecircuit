package com.jiangyuefengyu.redstonecircuit;

import com.jiangyuefengyu.redstonecircuit.block.WeatherDetectorBlock;
import com.jiangyuefengyu.redstonecircuit.logic.WeatherDetectorWearer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 彩蛋：天气探测器戴在头上，天气说了算；外加它现在哪一面都贴得住。
 *
 * <p>属性全用真属性值问（{@code getAttributeValue}），不看我们自己的记账 —— 一条修饰符挂错了会立刻对不上。
 *
 * <p><b>天气不进这场测试</b>：{@code GameTest} 的关卡里 {@code setWeatherParameters} 改不动 {@code isRaining()}，而且天气
 * 是关卡级的、会被同一批的用例串味（原有那条天气探测器用例的注释里已经记着这件事）。所以"哪个天给哪条加成"走
 * {@link WeatherDetectorWearer#apply}——天气已经翻译成 {@code Mode} 之后的那一步；"天气 → Mode"由
 * {@code WeatherDetectorModeTest} 的单测管，两条路合起来盖住整件事。
 */
@GameTestHolder(RedstoneCircuit.MODID)
@PrefixGameTestTemplate(false)
public final class WeatherDetectorWearGameTest {

    private static final double EPSILON = 1.0E-4;

    private static ItemStack detector() {
        return new ItemStack(RCRegistry.WEATHER_DETECTOR_ITEM.get());
    }

    private static void close(double actual, double wanted, String what, GameTestHelper helper) {
        helper.assertTrue(Math.abs(actual - wanted) < EPSILON,
                what + "：期望 " + wanted + "，实际 " + actual);
    }

    /** 晴/雨/雷/摘下来，四种情况下两条属性各该是多少。 */
    @GameTest(template = "empty")
    public void theHelmetAnswersToTheSky(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        double attack = player.getAttributeValue(Attributes.ATTACK_DAMAGE);
        double health = player.getAttributeValue(Attributes.MAX_HEALTH);
        player.setItemSlot(EquipmentSlot.HEAD, detector());

        WeatherDetectorWearer.apply(player, WeatherDetectorBlock.Mode.CLEAR);
        close(player.getAttributeValue(Attributes.ATTACK_DAMAGE), attack + WeatherDetectorWearer.BONUS,
                "晴天该 +4 攻击", helper);
        close(player.getAttributeValue(Attributes.MAX_HEALTH), health, "晴天不该动生命上限", helper);

        WeatherDetectorWearer.apply(player, WeatherDetectorBlock.Mode.RAIN);
        close(player.getAttributeValue(Attributes.ATTACK_DAMAGE), attack, "雨天不该留攻击加成", helper);
        close(player.getAttributeValue(Attributes.MAX_HEALTH), health + WeatherDetectorWearer.BONUS,
                "雨天该 +4 生命上限", helper);
        close(player.getMaxHealth(), health + WeatherDetectorWearer.BONUS, "生命上限该真的变高", helper);

        WeatherDetectorWearer.apply(player, WeatherDetectorBlock.Mode.THUNDER);
        close(player.getAttributeValue(Attributes.ATTACK_DAMAGE), attack,
                "雷暴只给引雷，不给攻击（三档互斥）", helper);
        close(player.getAttributeValue(Attributes.MAX_HEALTH), health,
                "雷暴只给引雷，不给生命上限（三档互斥）", helper);

        WeatherDetectorWearer.apply(player, WeatherDetectorBlock.Mode.RAIN);
        close(player.getAttributeValue(Attributes.MAX_HEALTH), health + WeatherDetectorWearer.BONUS,
                "先回到雨天，血才可能超上限", helper);
        player.setHealth(player.getMaxHealth());
        player.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);
        WeatherDetectorWearer.refresh(player);
        close(player.getAttributeValue(Attributes.ATTACK_DAMAGE), attack, "摘下来该还给原样（攻击）", helper);
        close(player.getAttributeValue(Attributes.MAX_HEALTH), health, "摘下来该还给原样（生命上限）", helper);
        close(player.getHealth(), health, "生命上限掉下来时，血不能留在上限之上", helper);
        helper.succeed();
    }

    /**
     * 戴上只能靠手动放进头盔槽：右键（对手里的物品调 {@code use}）什么都不该发生，而槽位那边要收它。
     *
     * <p>作者：「能不能做到只通过手动放置到头盔位，而让对着空气右键不放上去」。所以这里两头都钉住 ——
     * 右手不动手，槽位得认。
     */
    @GameTest(template = "empty")
    public void thePlateOnlyGoesOnByHand(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        ItemStack helmet = new ItemStack(RCRegistry.REDSTONE_GOGGLES.get());
        player.setItemSlot(EquipmentSlot.HEAD, helmet);
        player.setItemInHand(InteractionHand.MAIN_HAND, detector());

        var result = RCRegistry.WEATHER_DETECTOR_ITEM.get().use(level, player, InteractionHand.MAIN_HAND);
        helper.assertTrue(!result.getResult().consumesAction(), "右键不该消耗这次点击");
        helper.assertTrue(player.getItemBySlot(EquipmentSlot.HEAD).is(RCRegistry.REDSTONE_GOGGLES.get()),
                "对着空气右键绝不能把它戴上去");
        helper.assertTrue(player.getMainHandItem().is(RCRegistry.WEATHER_DETECTOR_ITEM.get()),
                "手里的东西该还在手里");

        ItemStack stack = detector();
        helper.assertTrue(stack.canEquip(EquipmentSlot.HEAD, player),
                "头盔槽的 mayPlace 问的就是这个：它必须认领头槽");
        helper.assertTrue(!stack.canEquip(EquipmentSlot.CHEST, player),
                "只能戴头上，不能塞胸甲槽");

        player.setItemSlot(EquipmentSlot.HEAD, detector());
        helper.assertTrue(WeatherDetectorWearer.wearing(player),
                "手动放进槽里之后，效果系统该认得它（戴上这条路只有这一条）");
        helper.succeed();
    }

    /** 雷暴里打中一个生物，它头上落雷；不打雷的时候一道也不该有。 */
    @GameTest(template = "empty")
    public void theStormHitCallsLightning(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.setItemSlot(EquipmentSlot.HEAD, detector());

        Cow first = helper.spawn(EntityType.COW, new BlockPos(3, 1, 3));
        helper.assertTrue(!WeatherDetectorWearer.onHit(player, first, false),
                "不打雷的时候不该落雷");
        helper.assertEntityNotPresent(EntityType.LIGHTNING_BOLT, 3, 1, 3);

        Cow second = helper.spawn(EntityType.COW, new BlockPos(5, 1, 5));
        helper.assertTrue(WeatherDetectorWearer.onHit(player, second, true),
                "打雷 + 戴着头戴 = 打中就落雷");
        helper.assertEntityPresent(EntityType.LIGHTNING_BOLT, new BlockPos(5, 1, 5), 6.0D);
        helper.succeed();
    }

    /** 贴西墙的一片：晴天的设定在晴天就该答 15（从东边取），把墙拆了它跟着掉。 */
    @GameTest(template = "empty")
    public void aWallPlateAnswersAndFallsWithItsWall(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.setBlock(new BlockPos(2, 1, 2), Blocks.STONE.defaultBlockState());
        helper.setBlock(new BlockPos(3, 1, 2), RCRegistry.WEATHER_DETECTOR.get().defaultBlockState()
                .setValue(WeatherDetectorBlock.MOUNT, Direction.WEST)
                .setValue(WeatherDetectorBlock.WEATHER, WeatherDetectorBlock.Mode.CLEAR));
        BlockPos pos = helper.absolutePos(new BlockPos(3, 1, 2));

        BlockState state = level.getBlockState(pos);
        helper.assertTrue(state.is(RCRegistry.WEATHER_DETECTOR.get()),
                "贴西墙的一片该放得下（西边是石头），实际 " + state);
        helper.assertTrue(state.getValue(WeatherDetectorBlock.MOUNT) == Direction.WEST,
                "挂载该是西");
        helper.assertTrue(state.getValue(WeatherDetectorBlock.ACTIVE),
                "关卡里永远是晴天，设成晴天的这一片一放下就该亮");
        helper.assertTrue(level.getSignal(pos, Direction.EAST) == 15,
                "亮着就该从东边答 15，实际 " + level.getSignal(pos, Direction.EAST));
        helper.assertTrue(!net.minecraft.world.level.block.Block.isShapeFullBlock(state.getShape(level, pos)),
                "贴片不是整块方块（不然会把邻居的面剔掉）");

        helper.startSequence()
                .thenExecute(() -> helper.setBlock(new BlockPos(2, 1, 2), Blocks.AIR.defaultBlockState()))
                .thenExecuteAfter(2, () -> helper.assertTrue(level.getBlockState(pos).isAir(),
                        "墙没了，贴在上面的那片该跟着掉，实际 " + level.getBlockState(pos)))
                .thenSucceed();
    }
}
