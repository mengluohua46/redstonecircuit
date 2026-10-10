package com.jiangyuefengyu.redstonecircuit;

import java.util.List;
import java.util.UUID;

import com.jiangyuefengyu.redstonecircuit.block.RedstoneStructureBlockEntity;
import com.jiangyuefengyu.redstonecircuit.block.StructureBlockEntity;
import com.jiangyuefengyu.redstonecircuit.structure.IoMode;
import com.jiangyuefengyu.redstonecircuit.structure.PayloadHeader;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The two blocks of the feature: 红石结构方块 (the controller) and 结构 (the product).
 *
 * <p>Their identity and their stored state - the mode, the tier, the selection, and for the product the id of
 * the structure it stands for. So the questions worth asking here are the ones everything else depends on:
 * does a controller keep the selection it was given (a preview has to survive a chunk unload), does a product
 * remember which structure it is, and does mining either of them give the block back.
 */
public final class StructureBlockGameTest implements FabricGameTest {

    private static final BlockPos CONTROLLER = new BlockPos(1, 2, 1);
    private static final BlockPos PRODUCT = new BlockPos(4, 2, 1);

    @GameTest(template = "redstonecircuit:empty")
    public void aControllerKeepsItsSelectionAcrossASaveAndLoad(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.setBlock(CONTROLLER, RCRegistry.REDSTONE_STRUCTURE_BLOCK);

        RedstoneStructureBlockEntity controller = helper.getBlockEntity(CONTROLLER);
        helper.assertTrue(!controller.isPreviewing(), "a fresh controller is previewing nothing");
        helper.assertTrue(controller.mode() == RedstoneStructureBlockEntity.Mode.CAPTURE,
                "and it starts in capture mode, which is the first thing a player does with it");
        helper.assertTrue(controller.tier() == RedstoneStructureBlockEntity.Tier.CUT,
                "with the cutting tier: the copying tier is creative-only");
        helper.assertTrue(controller.structureName().equals(PayloadHeader.DEFAULT_NAME),
                "and the default name, got " + controller.structureName());

        BlockPos a = helper.absolutePos(CONTROLLER);
        controller.setMode(RedstoneStructureBlockEntity.Mode.RELEASE);
        controller.setTier(RedstoneStructureBlockEntity.Tier.COPY);
        controller.setIoMode(IoMode.MANUAL);
        controller.setName("my machine");
        controller.setCorner(false, a);
        controller.setCorner(true, a.offset(2, 1, 3));
        helper.assertTrue(controller.isPreviewing(), "two corners are a preview");
        Vec3i size = controller.regionSize();
        helper.assertTrue(size.getX() == 3 && size.getY() == 2 && size.getZ() == 4,
                "and the selection is 3x2x4, got " + size.getX() + "x" + size.getY() + "x" + size.getZ());

        // A chunk unload must not lose the preview, so the state has to survive being written and read.
        CompoundTag saved = controller.saveWithFullMetadata(level.registryAccess());
        BlockState state = level.getBlockState(a);
        BlockEntity reloaded = BlockEntity.loadStatic(a, state, saved, level.registryAccess());
        if (!(reloaded instanceof RedstoneStructureBlockEntity back)) {
            helper.fail("the saved id must bring the same kind of block entity back, got " + reloaded);
            return;
        }
        helper.assertTrue(back.mode() == RedstoneStructureBlockEntity.Mode.RELEASE
                        && back.tier() == RedstoneStructureBlockEntity.Tier.COPY
                        && back.ioMode() == IoMode.MANUAL,
                "mode, tier and io mode have to survive a save");
        helper.assertTrue(back.structureName().equals("my machine"),
                "so does the name a player typed, got " + back.structureName());
        helper.assertTrue(back.isPreviewing(), "and the preview is still there after a reload");

        // The sync tag is the packet a client gets, and it carries a preview and never a structure.
        CompoundTag updateTag = controller.getUpdateTag(level.registryAccess());
        helper.assertTrue(updateTag.size() <= 8,
                "the block entity update tag must stay small - it carries a preview, never structure "
                        + "data; it had " + updateTag.size() + " keys");
        helper.succeed();
    }

    @GameTest(template = "redstonecircuit:empty")
    public void bothBlocksDropThemselves(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.setBlock(CONTROLLER, RCRegistry.REDSTONE_STRUCTURE_BLOCK);
        helper.setBlock(PRODUCT, RCRegistry.STRUCTURE);

        BlockPos controllerPos = helper.absolutePos(CONTROLLER);
        BlockPos productPos = helper.absolutePos(PRODUCT);

        List<ItemStack> controllerDrops = Block.getDrops(level.getBlockState(controllerPos), level,
                controllerPos, level.getBlockEntity(controllerPos));
        helper.assertTrue(controllerDrops.size() == 1
                        && controllerDrops.get(0).is(RCRegistry.REDSTONE_STRUCTURE_BLOCK_ITEM),
                "mining the controller gives the controller back, got " + controllerDrops);

        List<ItemStack> productDrops = Block.getDrops(level.getBlockState(productPos), level, productPos,
                level.getBlockEntity(productPos));
        helper.assertTrue(productDrops.size() == 1
                        && productDrops.get(0).is(RCRegistry.STRUCTURE_ITEM),
                "and mining a structure gives the structure back - it drops itself, and carries its data "
                        + "once capture can make one; got " + productDrops);
        helper.succeed();
    }

    @GameTest(template = "redstonecircuit:empty")
    public void aProductKnowsWhichStructureItIs(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.setBlock(PRODUCT, RCRegistry.STRUCTURE);

        StructureBlockEntity product = helper.getBlockEntity(PRODUCT);
        helper.assertTrue(product.isMissing(),
                "a freshly placed structure has no id yet, which is the missing-placeholder case");
        helper.assertTrue(product.ioMode() == IoMode.ACTIVATION_FACES,
                "and it still has a defined io mode rather than null");

        UUID id = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        BlockPos pos = helper.absolutePos(PRODUCT);
        product.setStructure(id, "test structure", new Vec3i(5, 3, 7), IoMode.MANUAL, 2);
        helper.assertTrue(!product.isMissing() && id.equals(product.structureId()),
                "it must remember the id it was given");

        CompoundTag saved = product.saveWithFullMetadata(level.registryAccess());
        BlockEntity reloaded = BlockEntity.loadStatic(pos, level.getBlockState(pos), saved,
                level.registryAccess());
        if (!(reloaded instanceof StructureBlockEntity back)) {
            helper.fail("the saved id must bring the same kind of block entity back, got " + reloaded);
            return;
        }
        helper.assertTrue(id.equals(back.structureId()),
                "the id has to survive a save, or a placed structure forgets what it is");
        helper.assertTrue(back.structureName().equals("test structure")
                        && back.size().getX() == 5 && back.size().getZ() == 7
                        && back.ioMode() == IoMode.MANUAL
                        && back.nestingDepth() == 2,
                "along with its name, size, io mode and nesting depth - the depth is what stops a "
                        + "structure from being nested deeper than capture allowed");
        helper.succeed();
    }
}
