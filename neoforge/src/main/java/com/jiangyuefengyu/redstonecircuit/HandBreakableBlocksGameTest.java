package com.jiangyuefengyu.redstonecircuit;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The small blocks are picked up by a bare hand: the three repeaters and the weather detector.
 *
 * <p>None of them ever asked for a tool, so what matters is the other half of "mineable": <b>a block with no loot table
 * drops nothing at all</b>, whatever a player mines it with. Those four were exactly that - the wire and the two
 * structure blocks had tables - so in survival they vanished instead of coming back. The tables are the fix; this test
 * asks for the drop itself, which is the thing a player sees.
 *
 * <p>Hence both halves for every block: no tool requirement (so a bare hand may take it at all) and a real drop. The
 * wire is the control - it already had a table, so a change that broke drops everywhere would show up here too.
 */
@GameTestHolder(RedstoneCircuit.MODID)
@PrefixGameTestTemplate(false)
public final class HandBreakableBlocksGameTest {

    @GameTest(template = "empty")
    public void theHandBreakableBlocksDropThemselves(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        check(helper, level, "维持中继器", RCRegistry.RETAINING_REPEATER.get(),
                RCRegistry.RETAINING_REPEATER_ITEM.get());
        check(helper, level, "双倍红石中继器", RCRegistry.DOUBLE_REPEATER.get(),
                RCRegistry.DOUBLE_REPEATER_ITEM.get());
        check(helper, level, "双倍维持中继器", RCRegistry.DOUBLE_RETAINING_REPEATER.get(),
                RCRegistry.DOUBLE_RETAINING_REPEATER_ITEM.get());
        check(helper, level, "天气探测器", RCRegistry.WEATHER_DETECTOR.get(),
                RCRegistry.WEATHER_DETECTOR_ITEM.get());
        check(helper, level, "超导红石（对照）", RCRegistry.SUPERCONDUCTING_WIRE.get(),
                RCRegistry.SUPERCONDUCTING_REDSTONE.get());
        check(helper, level, "控制器", RCRegistry.CONTROLLER.get(), RCRegistry.CONTROLLER_ITEM.get());
        check(helper, level, "红石变压器", RCRegistry.TRANSFORMER.get(), RCRegistry.TRANSFORMER_ITEM.get());
        check(helper, level, "时钟源", RCRegistry.CLOCK.get(), RCRegistry.CLOCK_ITEM.get());
        check(helper, level, "信号延长器", RCRegistry.EXTENDER.get(), RCRegistry.EXTENDER_ITEM.get());
        helper.succeed();
    }

    /**
     * Both halves of "a bare hand can take it", asked of the game rather than read out of the files.
     *
     * <p>{@code requiresCorrectToolForDrops} false is what lets a hand break the block and keep it - vanilla only calls
     * the drop path when {@code canHarvestBlock} agrees, and that method is true for everything when this is false. And
     * {@code Block#getDrops} with no tool at all is the drop path with the worst case a player can bring: if this list
     * is empty, mining by hand destroys the block for nothing.
     */
    private static void check(GameTestHelper helper, ServerLevel level, String name, Block block, Item item) {
        BlockState state = block.defaultBlockState();
        helper.assertTrue(!state.requiresCorrectToolForDrops(),
                name + " must not ask for a tool: a bare hand has to be able to take it");
        List<ItemStack> drops = Block.getDrops(state, level, BlockPos.ZERO, null);
        helper.assertTrue(drops.stream().anyMatch(stack -> stack.is(item)),
                name + " must drop itself when it is mined by hand, got " + drops
                        + " - a block with no loot table drops nothing at all");
    }
}
