package com.jiangyuefengyu.redstonecircuit;

import java.util.Set;

import com.jiangyuefengyu.redstonecircuit.block.StructureBlockEntity;
import com.jiangyuefengyu.redstonecircuit.structure.IoMode;
import com.jiangyuefengyu.redstonecircuit.structure.PayloadLimits;
import com.jiangyuefengyu.redstonecircuit.structure.StructureCapture;
import com.jiangyuefengyu.redstonecircuit.structure.StructurePayload;
import com.jiangyuefengyu.redstonecircuit.structure.StructurePreview;
import com.jiangyuefengyu.redstonecircuit.structure.StructureProductData;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The preview, as far as a server can be asked about it: is it a picture of the structure that was captured,
 * does a placed block carry it to a client, and is "no picture" a state of its own.
 *
 * <p>A GameTest has no client and there is no headless frame, so everything that can be wrong before a frame
 * is drawn is pinned here - the preview's cells against the captured blocks, the coarsening arithmetic, and
 * the byte budget. Whether the picture looks right is for a player to say.
 */
@GameTestHolder(RedstoneCircuit.MODID)
@PrefixGameTestTemplate(false)
public final class StructurePreviewGameTest {

    private static BlockPos from(GameTestHelper helper) {
        return helper.absolutePos(new BlockPos(1, 2, 1));
    }

    /** Three cells: stone, lamp, air - a shape whose every cell can be recognised in the preview. */
    private static void buildShape(ServerLevel level, BlockPos from) {
        level.setBlock(from, Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(from.offset(1, 0, 0), Blocks.REDSTONE_LAMP.defaultBlockState(), 3);
        level.setBlock(from.offset(2, 0, 0), Blocks.AIR.defaultBlockState(), 3);
    }

    private static StructurePayload capture(GameTestHelper helper, ServerLevel level) {
        StructureCapture.Result captured = StructureCapture.capture(level,
                new StructureCapture.Request(from(helper), from(helper).offset(2, 0, 0),
                        IoMode.ACTIVATION_FACES, "preview test", false, false, null),
                new StructureCapture.Limits(PayloadLimits.DEFAULT, Set.of(), 1));
        if (!captured.allowed()) {
            helper.fail("the capture this test needs was refused: " + captured.refusal());
            return null;
        }
        return captured.captured().payload();
    }

    /** The block a preview cell shows, resolved the way a client resolves it. */
    private static BlockState stateAt(ServerLevel level, StructurePreview preview, int x, int y, int z) {
        int index = preview.cells().get(preview.index(x, y, z));
        return NbtUtils.readBlockState(level.holderLookup(Registries.BLOCK), preview.palette().get(index));
    }

    @GameTest(template = "empty")
    public void aPreviewShowsTheBlocksTheStructureWasCapturedFrom(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        buildShape(level, from(helper));

        StructurePayload payload = capture(helper, level);
        if (payload == null) {
            return;
        }
        StructurePreview preview = StructureProductData.of(payload, 65536).preview();
        if (preview == null) {
            helper.fail("a captured structure must come with a preview - it is what a client draws");
            return;
        }

        helper.assertTrue(preview.scale() == 1, "three blocks fit at full detail, got scale " + preview.scale());
        helper.assertTrue(preview.size().equals(new Vec3i(3, 1, 1)),
                "the preview must describe the selection, got " + preview.size());
        // Not "the preview has cells" but "this cell shows the block standing at that place in the structure".
        helper.assertTrue(stateAt(level, preview, 0, 0, 0).is(Blocks.STONE),
                "the first cell must be the stone that was captured, got " + stateAt(level, preview, 0, 0, 0));
        helper.assertTrue(stateAt(level, preview, 1, 0, 0).is(Blocks.REDSTONE_LAMP),
                "the second cell must be the lamp that was captured, got "
                        + stateAt(level, preview, 1, 0, 0));
        helper.assertTrue(stateAt(level, preview, 2, 0, 0).isAir(),
                "and the third must be the air, got " + stateAt(level, preview, 2, 0, 0));
        helper.assertTrue(preview.filledCells() == 2,
                "two of the three cells are filled, got " + preview.filledCells());
        helper.assertTrue(preview.wireBytes() <= 65536,
                "the preview must fit its budget, got " + preview.describe());
        helper.succeed();
    }

    @GameTest(template = "empty")
    public void aPlacedStructureSendsItsPreviewToTheClient(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        buildShape(level, from(helper));
        StructurePayload payload = capture(helper, level);
        if (payload == null) {
            return;
        }
        StructureProductData data = StructureProductData.of(payload, 65536);

        BlockPos productPos = helper.absolutePos(new BlockPos(6, 2, 1));
        level.setBlock(productPos, RCRegistry.STRUCTURE.get().defaultBlockState(), 3);
        if (!(level.getBlockEntity(productPos) instanceof StructureBlockEntity product)) {
            helper.fail("the placed structure must have a block entity");
            return;
        }
        product.setStructure(data);

        helper.assertTrue(data.preview() != null && data.preview().equals(product.preview()),
                "the block must hold the same picture its description carried");

        // The update tag is the packet a client reads a block entity from: this is "the client can draw it".
        CompoundTag update = product.getUpdateTag(level.registryAccess());
        helper.assertTrue(update.contains("preview"),
                "the preview has to travel in the block entity's update tag, or a client has nothing to draw");
        StructurePreview sent = StructurePreview.CODEC.parse(NbtOps.INSTANCE, update.get("preview"))
                .result().orElse(null);
        helper.assertTrue(product.preview().equals(sent),
                "and the preview a client receives must be the preview the server holds; got " + sent);

        // A restart is the other road: the block reloads from its own save, so the preview has to be in it.
        CompoundTag saved = product.saveWithFullMetadata(level.registryAccess());
        StructureBlockEntity reloaded = new StructureBlockEntity(productPos.above(), product.getBlockState());
        reloaded.loadWithComponents(saved, level.registryAccess());
        helper.assertTrue(product.preview().equals(reloaded.preview()),
                "a restart must not leave a structure that draws nothing, got " + reloaded.preview());
        helper.succeed();
    }

    @GameTest(template = "empty")
    public void aDescriptionWithNoPayloadHasNoPreview(GameTestHelper helper) {
        // A description standing for a structure whose file is not in this save must not pretend to have a picture.
        StructureProductData data = StructureProductData.of(
                com.jiangyuefengyu.redstonecircuit.structure.PayloadHeader.fresh(java.util.UUID.randomUUID(),
                        "no payload", new Vec3i(4, 1, 5), BlockPos.ZERO, IoMode.ACTIVATION_FACES, false));

        helper.assertTrue(data.preview() == null,
                "a description made from a header alone has no picture, got " + data.preview());
        helper.assertTrue(data.size().equals(new Vec3i(4, 1, 5)),
                "and it still describes the size it stands for, which is the placeholder's whole content");
        helper.succeed();
    }
}
