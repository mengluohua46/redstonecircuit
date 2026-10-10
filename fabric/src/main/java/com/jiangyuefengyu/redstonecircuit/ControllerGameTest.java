package com.jiangyuefengyu.redstonecircuit;

import com.jiangyuefengyu.redstonecircuit.block.ControllerBlock;
import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore;
import com.jiangyuefengyu.redstonecircuit.data.Slot;
import com.jiangyuefengyu.redstonecircuit.logic.ControllerRule;
import com.jiangyuefengyu.redstonecircuit.logic.InnerRedstoneNetwork;
import com.jiangyuefengyu.redstonecircuit.logic.PowerSolver;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 控制器 in a real level: the four settings, the face that answers, and the plate's own shape.
 *
 * <p>The inputs are the mod's own components (redstone placed <em>inside</em> the blocks around the plate, through
 * {@code InnerRedstoneStore}): a vanilla wire would recompute whatever was written into it on the next tick, while an
 * inner dust keeps its injected strength and still answers the level's ordinary signal question - so 7 can be 7.
 *
 * <p>The face question is pinned the hard way, by asking the block from both sides ({@code state.getSignal}): a plate
 * whose answer came out of its front would pass a test that only read {@code POWER} and would drive the wire the player
 * built on the wrong side of it.
 */
public final class ControllerGameTest implements FabricGameTest {

    private static BlockPos at(GameTestHelper helper, int x, int y, int z) {
        return helper.absolutePos(new BlockPos(x, y, z));
    }

    /** Places inner dust at an absolute position with a chosen strength, the way {@code /rc place} injects one. */
    private static void placeDust(GameTestHelper helper, BlockPos pos, int power) {
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

    /** A plate at (2,1,2) with these four settings, and its 正面  pointing {@code facing}. */
    private static BlockPos placeController(GameTestHelper helper, Direction facing, ControllerRule.Main main,
                                            ControllerRule.Sub sub, int x, int y) {
        helper.setBlock(new BlockPos(2, 1, 2), RCRegistry.CONTROLLER.defaultBlockState()
                .setValue(ControllerBlock.FACING, facing)
                .setValue(ControllerBlock.MAIN, main)
                .setValue(ControllerBlock.SUB, sub)
                .setValue(ControllerBlock.X, x)
                .setValue(ControllerBlock.Y, y));
        return at(helper, 2, 1, 2);
    }

    /** Feeds the front face (one cell north of the plate when 正面 = north) with a chosen strength. */
    private static void feedFront(GameTestHelper helper, int power) {
        ServerLevel level = helper.getLevel();
        BlockPos front = at(helper, 2, 1, 1);
        level.setBlock(front, Blocks.STONE.defaultBlockState(), 3);
        placeDust(helper, front, power);
    }

    /**
     * The example, in a level: 主档位大于 + 副档位等于 + 档位7 + 输出15 ⇒ "输入≥7 就输出15".
     *
     * <p>And it is the <b>back</b> that answers: asked from behind (the 背面) the plate gives 15, asked from its own front
     * it gives nothing.
     */
    @GameTest(template = "redstonecircuit:empty")
    public void theAuthorsExampleWorksInTheWorld(GameTestHelper helper) {
        BlockPos plate = placeController(helper, Direction.NORTH, ControllerRule.Main.GREATER,
                ControllerRule.Sub.EQUAL, 7, 15);
        feedFront(helper, 7);

        ControllerBlock.announce(helper.getLevel(), plate);
        BlockState state = helper.getLevel().getBlockState(plate);

        helper.assertTrue(ControllerBlock.answerAt(helper.getLevel(), plate, state) == 15,
                "输入 7、档位 7：要输出 15，但方块说 " + ControllerBlock.answerAt(helper.getLevel(), plate, state));
        helper.assertTrue(state.getValue(ControllerBlock.LIT), "and the top display is lit while it answers");
        helper.assertTrue(state.getSignal(helper.getLevel(), plate, Direction.NORTH) == 15,
                "asked from behind (the 背面), the plate answers 15");
        helper.assertTrue(state.getSignal(helper.getLevel(), plate, Direction.SOUTH) == 0,
                "asked from in front (its own input), it answers nothing - a device does not feed its own input");
        helper.assertTrue(state.getSignal(helper.getLevel(), plate, Direction.EAST) == 0,
                "and a side hears nothing back either");
        helper.succeed();
    }

    /** One step below the level is silence, and the display goes dark with it. */
    @GameTest(template = "redstonecircuit:empty")
    public void oneBelowTheLevelIsSilence(GameTestHelper helper) {
        BlockPos plate = placeController(helper, Direction.NORTH, ControllerRule.Main.GREATER,
                ControllerRule.Sub.EQUAL, 7, 15);
        feedFront(helper, 6);

        ControllerBlock.announce(helper.getLevel(), plate);
        BlockState state = helper.getLevel().getBlockState(plate);

        helper.assertTrue(ControllerBlock.answerAt(helper.getLevel(), plate, state) == 0,
                "输入 6 对档位 7：不输出，但方块说 " + ControllerBlock.answerAt(helper.getLevel(), plate, state));
        helper.assertFalse(state.getValue(ControllerBlock.LIT), "and the display is not lit");
        helper.succeed();
    }

    /**
     * 只等于 does not look at 主档位 at all.
     *
     * <p>Two plates, same 副档位 (只等于) and the same 档位 7, one 主档位 大于 and one 小于, and both have to answer the same
     * way: 15 at exactly 7 and nothing at 9.
     */
    @GameTest(template = "redstonecircuit:empty")
    public void onlyEqualIgnoresTheMainLevel(GameTestHelper helper) {
        BlockPos exact = placeController(helper, Direction.NORTH, ControllerRule.Main.LESS,
                ControllerRule.Sub.ONLY_EQUAL, 7, 15);
        feedFront(helper, 7);
        ControllerBlock.announce(helper.getLevel(), exact);
        helper.assertTrue(ControllerBlock.answerAt(helper.getLevel(), exact,
                        helper.getLevel().getBlockState(exact)) == 15,
                "只等于：输入正好 7 时要输出，即使主档位是「小于」");

        // The same plate, fed 9 instead: 只等于 has nothing to say about it.
        feedFront(helper, 9);
        ControllerBlock.announce(helper.getLevel(), exact);
        helper.assertTrue(ControllerBlock.answerAt(helper.getLevel(), exact,
                        helper.getLevel().getBlockState(exact)) == 0,
                "只等于：输入 9 时不该输出，主档位不参与这句话");

        // And 只不等于 is its mirror image.
        BlockPos mirrored = placeController(helper, Direction.NORTH, ControllerRule.Main.GREATER,
                ControllerRule.Sub.ONLY_NOT_EQUAL, 7, 15);
        feedFront(helper, 9);
        ControllerBlock.announce(helper.getLevel(), mirrored);
        helper.assertTrue(ControllerBlock.answerAt(helper.getLevel(), mirrored,
                        helper.getLevel().getBlockState(mirrored)) == 15,
                "只不等于：输入 9（≠7）时输出");
        helper.succeed();
    }

    /**
     * The plate itself: one eighth of a block thick, not a full cube (so a component can never be stored inside it - the
     * host rule read the other way round), and the four settings it starts with.
     */
    @GameTest(template = "redstonecircuit:empty")
    public void thePlateIsAnEighthOfABlockAndNeverAHost(GameTestHelper helper) {
        BlockState fresh = RCRegistry.CONTROLLER.defaultBlockState();
        helper.assertTrue(fresh.getValue(ControllerBlock.MAIN) == ControllerRule.Main.GREATER
                        && fresh.getValue(ControllerBlock.SUB) == ControllerRule.Sub.EQUAL
                        && fresh.getValue(ControllerBlock.X) == 1
                        && fresh.getValue(ControllerBlock.Y) == ControllerRule.MAX,
                "放下就能用的默认值：大于 + 等于 + 档位 1 + 输出 15（任何真信号都输出 15）");

        BlockPos pos = at(helper, 2, 1, 2);
        helper.setBlock(new BlockPos(2, 1, 2), fresh);
        BlockState placed = helper.getLevel().getBlockState(pos);

        double top = placed.getShape(helper.getLevel(), pos).max(Direction.Axis.Y);
        helper.assertTrue(Math.abs(top - 0.125) < 1e-6,
                "薄板高 1/8 格（0.125），量到的是 " + top);
        helper.assertFalse(placed.isCollisionShapeFullBlock(helper.getLevel(), pos),
                "and it is not a full cube, which is what the host rule reads");
        helper.assertFalse(HostRules.isValidHost(placed),
                "所以红石元件塞不进它里面 - 它自己六面已有归属");
        helper.succeed();
    }

    /**
     * The same four sentences, for a controller stored inside a stone block.
     *
     * <p>The component form reads the face it was mounted on ({@code Slot#facing}, which {@code HostRules#slotFor} takes
     * from the click) and answers on the opposite one, exactly like the plate - so the two forms are one rule
     * ({@code ControllerRule.Setting}) asked at two addresses. What is asserted here is the whole chain: the setting on
     * the slot, the solver's answer, the one-way direction, and the host block's own reply to the world.
     */
    @GameTest(template = "redstonecircuit:empty")
    public void theControllerAlsoWorksInsideAHostBlock(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos host = at(helper, 2, 1, 2);
        helper.setBlock(new BlockPos(2, 1, 2), Blocks.STONE.defaultBlockState());

        Slot slot = new Slot(ComponentType.CONTROLLER);
        // 正面 = east (the face it was placed on), so it reads east and answers on 背面 = west.
        slot.facing = Direction.EAST;
        slot.controller = new ControllerRule.Setting(ControllerRule.Main.GREATER, ControllerRule.Sub.EQUAL, 7, 15);
        InnerRedstoneStore store = InnerRedstoneStore.get(level);
        store.getOrCreate(host).setSlot(slot);
        store.markDirty();

        // The front face carries 7 (the same inner dust the plate's tests feed it with).
        helper.setBlock(new BlockPos(3, 1, 2), Blocks.STONE.defaultBlockState());
        placeDust(helper, at(helper, 3, 1, 2), 7);
        InnerRedstoneNetwork.markDirtyWithNeighbours(level, host);
        InnerRedstoneInteraction.notifyNeighbours(level, host);
        // The solve itself, asked for by name: in the world it arrives with the level tick (markDirtyWithNeighbours is
        // what queues it), and a test that read the slot without it would be reading it before anybody had looked.
        InnerRedstoneNetwork.recompute(level, store, host);

        Slot after = InnerRedstoneStore.get(level).slotAt(host);
        helper.assertTrue(after != null && after.power == 15,
                "输入 7、档位 7 ⇒ 输出 15，实际是 " + (after == null ? "没有元件" : Integer.toString(after.power)));
        helper.assertTrue(after != null && PowerSolver.emitsToward(after, Direction.WEST),
                "答案从 背面（西）出去");
        helper.assertTrue(after != null && !PowerSolver.emitsToward(after, Direction.EAST),
                "不往正面回灌 - 它是单向的，和世界里的薄板一样");
        // The host block's own reply, and the direction is vanilla's backwards one: a signal query names the way the
        // query travelled (from the asker towards this block), so "the block standing west of it asks" is EAST here -
        // `emitted` is the opposite of that.
        helper.assertTrue(level.getSignal(host, Direction.EAST) == 15,
                "宿主方块替它回答西边（问句 travelled east）：15，实际 " + level.getSignal(host, Direction.EAST));
        helper.assertTrue(level.getSignal(host, Direction.WEST) == 0,
                "而正面那一边听不到自己的答案");
        helper.succeed();
    }

    /**
     * What the host screen's write has to reach.
     *
     * <p>The click and the packet are not testable here, but the write they end in is: the same four settings, on the
     * stored component, and then the answer the solver derives from them. A controller inside a block that never answered
     * anything is the failure this pins.
     */
    @GameTest(template = "redstonecircuit:empty")
    public void theHostScreensWriteReachesTheStoredComponent(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos host = at(helper, 2, 1, 2);
        helper.setBlock(new BlockPos(2, 1, 2), Blocks.STONE.defaultBlockState());

        InnerRedstoneStore store = InnerRedstoneStore.get(level);
        Slot slot = new Slot(ComponentType.CONTROLLER);
        slot.facing = Direction.EAST;
        store.getOrCreate(host).setSlot(slot);
        store.markDirty();

        // The screen's write: 只等于 + 档位 9 + 输出 4.
        helper.assertTrue(ControllerBlock.applyHostSettings(level, host, store.slotAt(host),
                        new ControllerRule.Setting(ControllerRule.Main.GREATER, ControllerRule.Sub.ONLY_EQUAL, 9, 4)),
                "界面的写入必须落到方块里那个元件上");
        Slot written = store.slotAt(host);
        helper.assertTrue(written != null && written.controller.threshold() == 9
                        && written.controller.output() == 4
                        && written.controller.sub() == ControllerRule.Sub.ONLY_EQUAL,
                "四个设置要照着写进去，实际 " + (written == null ? "没有元件" : written.controller));

        // 9 in, and 只等于 answers with 4; 8 in, and it answers nothing.
        helper.setBlock(new BlockPos(3, 1, 2), Blocks.STONE.defaultBlockState());
        placeDust(helper, at(helper, 3, 1, 2), 9);
        InnerRedstoneNetwork.recompute(level, store, host);
        helper.assertTrue(store.slotAt(host).power == 4,
                "输入 9 = 档位 9 ⇒ 输出 4，实际 " + store.slotAt(host).power);

        placeDust(helper, at(helper, 3, 1, 2), 8);
        InnerRedstoneNetwork.recompute(level, store, host);
        helper.assertTrue(store.slotAt(host).power == 0,
                "输入 8 ≠ 档位 9 ⇒ 不输出，实际 " + store.slotAt(host).power);
        helper.succeed();
    }

    /**
     * Why the block state space of these machines has to stay small.
     *
     * <p>A block state property is a <em>dimension</em>, not a field: 逻辑整合器's {@code FACING(6) × MODE(7) × X(17) ×
     * Y(17) × POWER(16)} is 194,208 states and 控制器's {@code FACING(4) × MAIN(2) × SUB(4) × X(16) × Y(15) × POWER(16) ×
     * LIT(2)} is another 196,608 - every one of them a real {@code BlockState} object, built and kept for the life of the
     * game on both sides, whether or not one of those blocks is ever placed.
     *
     * <p>So the <b>settings</b> belong in a block entity rather than in the state: the answer ({@code POWER}) and the
     * facing have to stay in the state, the numbers a player types do not. The bound below is a log line and a tripwire,
     * not a tight limit.
     */
    @GameTest(template = "redstonecircuit:empty")
    public void theBlockStatesAreCounted(GameTestHelper helper) {
        int controller = RCRegistry.CONTROLLER.getStateDefinition().getPossibleStates().size();
        int transformer = RCRegistry.TRANSFORMER.getStateDefinition().getPossibleStates().size();
        int clock = RCRegistry.CLOCK.getStateDefinition().getPossibleStates().size();
        int integrator = RCRegistry.LOGIC_INTEGRATOR.getStateDefinition().getPossibleStates().size();
        int gate = RCRegistry.TRANSMISSION_GATE.getStateDefinition().getPossibleStates().size();
        RCConfig.LOGGER.info("[redstonecircuit] block state space: 控制器 {} + 红石变压器 {} + 时钟源 {} + 逻辑整合器 {}"
                        + " + 传输门 {} = {} states built at startup", controller, transformer, clock, integrator, gate,
                controller + transformer + clock + integrator + gate);
        helper.assertTrue(controller + transformer + clock + integrator + gate < 30_000,
                "这几个方块的状态空间必须只有几千个：答案搬出了状态（BlockAnswerCache），设置留着"
                        + "（活塞要推着走）。实际：控制器 " + controller + "，红石变压器 " + transformer + "，时钟源 "
                        + clock + "，逻辑整合器 " + integrator + "，传输门 " + gate);
        helper.assertTrue(clock == 8,
                "时钟源是唯一一件连设置都不在状态里的：秒 0-3600 放进状态就是 430 万个，"
                        + "所以它的状态只剩 朝向 4 × 亮 2 = 8，实际 " + clock);
        helper.succeed();
    }
}
