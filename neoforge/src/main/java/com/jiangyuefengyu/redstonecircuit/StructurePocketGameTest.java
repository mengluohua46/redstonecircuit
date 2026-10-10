package com.jiangyuefengyu.redstonecircuit;

import java.util.Set;

import com.jiangyuefengyu.redstonecircuit.structure.IoMode;
import com.jiangyuefengyu.redstonecircuit.structure.PayloadLimits;
import com.jiangyuefengyu.redstonecircuit.structure.StructureCapture;
import com.jiangyuefengyu.redstonecircuit.structure.StructurePayload;
import com.jiangyuefengyu.redstonecircuit.structure.StructureProductData;
import com.jiangyuefengyu.redstonecircuit.structure.pocket.PocketDimension;
import com.jiangyuefengyu.redstonecircuit.structure.pocket.PocketMaterializer;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The pocket half of a placed product, as far as this harness can see it.
 *
 * <p>"A captured circuit runs in its pocket" is the feature's headline claim, and it cannot be a game test:
 * {@code GameTestServer} builds its world from the flat preset against an empty level-stem registry, so a datapack
 * dimension does not exist in this harness at all. That claim is verified against a real dedicated server instead,
 * {@code /rc pocket archtest}, whose verdict lands in the server log.
 *
 * <p>What <em>can</em> be tested here is what happens when there is no pocket: the feature has to degrade into "a
 * block that shows nothing" rather than into a crash or a half-written structure, and every caller has to be able to
 * tell that from success.
 */
@GameTestHolder(RedstoneCircuit.MODID)
@PrefixGameTestTemplate(false)
public final class StructurePocketGameTest {

    private static final BlockPos FROM = new BlockPos(1, 2, 1);
    private static final BlockPos TO = new BlockPos(4, 3, 1);

    @GameTest(template = "empty")
    public void materializingWithoutAPocketIsRefusedAndSaysWhy(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        if (PocketDimension.of(level.getServer()) != null) {
            // The harness has grown a pocket dimension (it cannot, but a future harness might): then this
            // test's premise is gone and it must not pretend otherwise.
            helper.fail("this harness now has a pocket dimension, so the no-pocket path can no longer be "
                    + "tested here - move these assertions to the arch test on a real server");
            return;
        }

        // A payload that asks for a pocket, made the way capture makes one.
        helper.setBlock(FROM, Blocks.STONE);
        helper.setBlock(FROM.offset(1, 0, 0), Blocks.REDSTONE_LAMP);
        StructureCapture.Result captured = StructureCapture.capture(level,
                new StructureCapture.Request(helper.absolutePos(FROM), helper.absolutePos(TO),
                        IoMode.ACTIVATION_FACES, "no pocket here", false, false, null),
                new StructureCapture.Limits(PayloadLimits.DEFAULT, Set.of(), 1));
        helper.assertTrue(captured.allowed(), captured.refusal());
        StructurePayload payload = captured.captured().payload();

        PocketMaterializer.Result refused = PocketMaterializer.materialize(level, payload, false,
                level.dimension(), helper.absolutePos(FROM));
        helper.assertTrue(!refused.materialized(),
                "with no pocket dimension there is nowhere to put the blocks, and the caller must be told");
        helper.assertTrue(refused.refusal().contains("structure_space"),
                "the reason has to name the dimension that is missing, got: " + refused.refusal());
        helper.assertTrue(!PocketMaterializer.release(level, 0),
                "and giving a plot back must report that there was nothing to give back");

        // A product whose pocket could not be made is still a normal block: its entity keeps the id (so the
        // file can be found later) and no plot is claimed.
        helper.setBlock(new BlockPos(6, 2, 1), RCRegistry.STRUCTURE.get());
        BlockPos productPos = helper.absolutePos(new BlockPos(6, 2, 1));
        if (!(level.getBlockEntity(productPos)
                instanceof com.jiangyuefengyu.redstonecircuit.block.StructureBlockEntity product)) {
            helper.fail("the placed structure must have a block entity");
            return;
        }
        StructureProductData data = StructureProductData.of(payload.header());
        product.setStructure(data);
        helper.assertTrue(product.plotIndex() < 0,
                "a product with no pocket must claim no plot, got " + product.plotIndex());
        helper.assertTrue(data.id().equals(product.structureId()),
                "and it must still know which structure it stands for");

        // Removing it must be harmless: there is no plot to give back.
        level.destroyBlock(productPos, false);
        helper.assertTrue(level.getBlockState(productPos).isAir(), "and the block must go away cleanly");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public void reclaimingAProductThatNeverMaterializedIsHarmless(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.setBlock(FROM, RCRegistry.STRUCTURE.get());
        BlockPos productPos = helper.absolutePos(FROM);
        BlockState state = level.getBlockState(productPos);
        if (!(level.getBlockEntity(productPos)
                instanceof com.jiangyuefengyu.redstonecircuit.block.StructureBlockEntity product)) {
            helper.fail("the placed structure must have a block entity");
            return;
        }

        // The player path, with a block that holds no structure at all: it must drop nothing (the design's
        // missing placeholder is not an item) and must not touch a plot it never had. The player is null
        // because this is the block's own hook and the statement under test is that it needs neither.
        state.getBlock().playerDestroy(level, null, productPos, state, product, ItemStack.EMPTY);
        helper.assertTrue(level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                        new net.minecraft.world.phys.AABB(productPos).inflate(3)).isEmpty(),
                "an empty structure block must not drop a structure item");
        helper.assertTrue(product.plotIndex() < 0, "and must not have claimed a plot");
        helper.succeed();
    }
}
