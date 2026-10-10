package com.jiangyuefengyu.redstonecircuit;

import java.util.Set;

import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneNode;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore;
import com.jiangyuefengyu.redstonecircuit.structure.IoMode;
import com.jiangyuefengyu.redstonecircuit.structure.PayloadLimits;
import com.jiangyuefengyu.redstonecircuit.structure.StructureCapture;
import com.jiangyuefengyu.redstonecircuit.structure.StructurePayload;
import com.jiangyuefengyu.redstonecircuit.structure.StructureRelease;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Release: putting a captured structure back.
 *
 * <p>往返要求逐方块一致，所以目标位置与源位置不同 - 在原件上释放会因为什么都没做而通过；控制器槽里的
 * 产物要被消耗。每个测试先清空目标区域，GameTest 世界里本来就有它自己的方块。
 */
@GameTestHolder(RedstoneCircuit.MODID)
@PrefixGameTestTemplate(false)
public final class StructureReleaseGameTest {

    private static final BlockPos FROM = new BlockPos(1, 2, 1);
    private static final BlockPos TO = new BlockPos(3, 3, 3);
    /**
     * Somewhere else entirely, and a <b>different</b> spot per test: absolute and far away, because a
     * GameTest batch lays its structures a handful of blocks apart in one shared level, so a large
     * <em>relative</em> offset would land inside the next test's structure.
     */
    private static final BlockPos TARGET_A = new BlockPos(120000, 100, 120000);
    private static final BlockPos TARGET_B = new BlockPos(120000, 100, 120200);
    private static final BlockPos TARGET_C = new BlockPos(120000, 100, 120400);
    private static final Vec3i SIZE = new Vec3i(3, 2, 3);

    private static void buildRegion(GameTestHelper helper, ServerLevel level, BlockPos relativeFrom) {
        helper.setBlock(relativeFrom, Blocks.STONE);
        helper.setBlock(relativeFrom.offset(1, 0, 0), Blocks.CHEST);
        helper.setBlock(relativeFrom.offset(2, 0, 0), Blocks.REDSTONE_WIRE);
        helper.setBlock(relativeFrom.offset(0, 1, 0), Blocks.STONE);
        helper.setBlock(relativeFrom.offset(1, 1, 0), RCRegistry.RETAINING_REPEATER.get());
        helper.setBlock(relativeFrom.offset(2, 1, 0), Blocks.STONE);
        // Just outside the selection: the context shell, which a release must NOT put back.
        helper.setBlock(relativeFrom.offset(-1, 0, 0), Blocks.OBSIDIAN);

        if (level.getBlockEntity(helper.absolutePos(relativeFrom.offset(1, 0, 0)))
                instanceof ChestBlockEntity chest) {
            chest.setItem(0, new ItemStack(Items.DIAMOND, 3));
        }
        InnerRedstoneStore.get(level).getOrCreate(helper.absolutePos(relativeFrom))
                .setSlot(InnerRedstoneNode.of(ComponentType.DUST).slot());
    }

    /** Empties a target region, so the test is about the release and not about the template. */
    private static void clearTarget(ServerLevel level, BlockPos target) {
        for (int y = 0; y < SIZE.getY(); y++) {
            for (int z = 0; z < SIZE.getZ(); z++) {
                for (int x = 0; x < SIZE.getX(); x++) {
                    level.setBlock(target.offset(x, y, z), Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
    }

    private static StructurePayload capture(GameTestHelper helper, ServerLevel level, BlockPos from,
                                            BlockPos to) {
        StructureCapture.Result result = StructureCapture.capture(level,
                new StructureCapture.Request(from, to, IoMode.ACTIVATION_FACES, "round trip", true, true, null),
                new StructureCapture.Limits(PayloadLimits.DEFAULT, Set.of(), 1));
        helper.assertTrue(result.allowed(), "the round trip needs a capture first: " + result.refusal());
        return result.captured().payload();
    }

    /** What the target region currently holds, for a failure message that says what actually happened. */
    private static String dump(ServerLevel level, BlockPos target) {
        StringBuilder out = new StringBuilder();
        for (int y = 0; y < SIZE.getY(); y++) {
            for (int z = 0; z < SIZE.getZ(); z++) {
                for (int x = 0; x < SIZE.getX(); x++) {
                    out.append('(').append(x).append(',').append(y).append(',').append(z).append(')')
                            .append(level.getBlockState(target.offset(x, y, z)).getBlock().getName()
                                    .getString())
                            .append(' ');
                }
            }
        }
        return out.toString();
    }

    private static void assertMatches(GameTestHelper helper, StructurePayload payload, ServerLevel level,
                                      BlockPos target, BlockState[] before, StructureCapture.Box box) {
        for (int y = 0; y < box.size().getY(); y++) {
            for (int z = 0; z < box.size().getZ(); z++) {
                for (int x = 0; x < box.size().getX(); x++) {
                    BlockState expected = before[box.index(x, y, z)];
                    BlockState actual = StructureRelease.stateAt(payload, level, x, y, z);
                    helper.assertTrue(actual != null && actual.equals(expected),
                            "the payload must still describe " + expected + " at " + x + "," + y + "," + z
                                    + ", got " + actual);
                    BlockState world = level.getBlockState(target.offset(x, y, z));
                    helper.assertTrue(world.equals(expected),
                            "the world at " + x + "," + y + "," + z + " must be " + expected + " after the "
                                    + "release, got " + world + ". Region: " + dump(level, target));
                }
            }
        }
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public void captureThenReleaseRestoresTheWorldBlockForBlock(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        buildRegion(helper, level, FROM);
        clearTarget(level, TARGET_A);
        BlockPos from = helper.absolutePos(FROM);
        BlockPos to = helper.absolutePos(TO);
        BlockPos target = TARGET_A;
        StructureCapture.Box box = StructureCapture.Box.of(from, to);

        BlockState[] before = new BlockState[box.volume()];
        for (int y = 0; y < box.size().getY(); y++) {
            for (int z = 0; z < box.size().getZ(); z++) {
                for (int x = 0; x < box.size().getX(); x++) {
                    before[box.index(x, y, z)] = level.getBlockState(from.offset(x, y, z));
                }
            }
        }

        StructurePayload payload = capture(helper, level, from, to);
        helper.assertTrue(level.getBlockState(from).isAir(), "the cut took the source region");

        helper.startSequence()
                .thenExecute(() -> {
                    StructureRelease.Result result = StructureRelease.start(level, payload, target, true,
                            new StructureRelease.Options(4096, true));
                    helper.assertTrue(result.started(), "releasing into an empty target must start: "
                            + result.refusal());
                })
                // The job writes its budget on level ticks, so the answer is a few ticks away.
                .thenIdle(3)
                .thenExecute(() -> {
                    assertMatches(helper, payload, level, target, before, box);

                    // What the blocks carried, at the new place.
                    if (level.getBlockEntity(target.offset(1, 0, 0)) instanceof ChestBlockEntity chest) {
                        helper.assertTrue(chest.getItem(0).is(Items.DIAMOND)
                                        && chest.getItem(0).getCount() == 3,
                                "the chest's contents must come back with it, got " + chest.getItem(0));
                    } else {
                        helper.fail("the released chest must be a chest with a block entity, got "
                                + level.getBlockEntity(target.offset(1, 0, 0)));
                    }
                    InnerRedstoneNode restored = InnerRedstoneStore.get(level).get(target);
                    helper.assertTrue(restored != null && !restored.isEmpty(),
                            "our own component inside the host block must come back too - it lives in the "
                                    + "level's store, not in the block");
                    InnerRedstoneNode source = InnerRedstoneStore.get(level).get(from);
                    helper.assertTrue(source == null || source.isEmpty(),
                            "and it must not still be sitting where it was captured from");

                    // The context shell was a copy of the world's edge, never part of the capture.
                    helper.assertTrue(level.getBlockState(target.offset(-1, 0, 0)).isAir(),
                            "the context shell must not be released: it was never part of the capture");
                })
                .thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public void releaseAsksBeforeOverwritingAndSaysHowMuch(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        buildRegion(helper, level, FROM);
        clearTarget(level, TARGET_B);
        BlockPos from = helper.absolutePos(FROM);
        BlockPos to = helper.absolutePos(TO);
        BlockPos target = TARGET_B;
        StructurePayload payload = capture(helper, level, from, to);

        // Two blocks in the way, where the structure brings a block of its own (the source's middle is air).
        level.setBlock(TARGET_B.offset(0, 1, 0), Blocks.GOLD_BLOCK.defaultBlockState(), 3);
        level.setBlock(TARGET_B.offset(2, 1, 0), Blocks.DIAMOND_BLOCK.defaultBlockState(), 3);

        StructureRelease.Options guarded = new StructureRelease.Options(4096, true);
        StructureRelease.Result refused = StructureRelease.start(level, payload, target, false, guarded);
        helper.assertTrue(!refused.started(), "with blocks in the way the release must ask first");
        helper.assertTrue(refused.refusal().contains("2 block(s)"),
                "and say how many it would destroy, got: " + refused.refusal());
        helper.assertTrue(refused.refusal().contains("structureReleaseConflictGuard"),
                "and name the setting that decides it, got: " + refused.refusal());
        helper.assertTrue(level.getBlockState(target.offset(0, 1, 0)).is(Blocks.GOLD_BLOCK),
                "and nothing may have been written yet");

        // The negative control: with the guard off the same release goes ahead, so the refusal was the guard.
        StructureRelease.Result ungated = StructureRelease.start(level, payload, target, false,
                new StructureRelease.Options(4096, false));
        helper.assertTrue(ungated.started(),
                "with the guard off the same target is released into: " + ungated.refusal());

        helper.startSequence()
                .thenIdle(3)
                .thenExecute(() -> {
                    // The source had stone in both places, so both obstacles are gone.
                    helper.assertTrue(level.getBlockState(target.offset(0, 1, 0))
                                    .equals(StructureRelease.stateAt(payload, level, 0, 1, 0)),
                            "the gold block must have been overwritten by the structure's own block, as "
                                    + "vanilla's structure block would; region: " + dump(level, target));
                    helper.assertTrue(level.getBlockState(target.offset(2, 1, 0))
                                    .equals(StructureRelease.stateAt(payload, level, 2, 1, 0)),
                            "and so must the diamond block; region: " + dump(level, target));
                    helper.assertTrue(level.getBlockState(target.offset(0, 1, 0)).is(Blocks.STONE),
                            "which for this structure is stone");
                })
                .thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public void aReleaseSpreadsOverTicksAndReportsProgress(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        buildRegion(helper, level, FROM);
        clearTarget(level, TARGET_C);
        BlockPos from = helper.absolutePos(FROM);
        BlockPos to = helper.absolutePos(TO);
        BlockPos target = TARGET_C;
        StructurePayload payload = capture(helper, level, from, to);

        // One block per tick: a 48³ structure is 110,592 blocks, writing them all at once freezes the server.
        StructureRelease.Result started = StructureRelease.start(level, payload, target, true,
                new StructureRelease.Options(1, true));
        helper.assertTrue(started.started(), started.refusal());
        StructureRelease.Job job = started.job();

        helper.startSequence()
                .thenIdle(2)
                .thenExecute(() -> {
                    helper.assertTrue(job.placed() >= 1 && job.placed() < job.total(),
                            "after two ticks a one-block-per-tick release must be part way through, at "
                                    + job.placed() + "/" + job.total());
                    helper.assertTrue(level.getBlockState(target.offset(2, 1, 2)).isAir(),
                            "and it must not have written the whole thing at once; region: "
                                    + dump(level, target));
                })
                .thenIdle(25)
                .thenExecute(() -> {
                    helper.assertTrue(job.done(), "after 25 more ticks it must be finished, at "
                            + job.placed() + "/" + job.total());
                    helper.assertTrue(StructureRelease.jobs().isEmpty(), "and no longer be a running job");
                    helper.assertTrue(level.getBlockState(target)
                                    .equals(StructureRelease.stateAt(payload, level, 0, 0, 0)),
                            "with the structure actually in the world; region: " + dump(level, target));
                })
                .thenSucceed();
    }
}
